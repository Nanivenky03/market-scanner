package com.trading.scanner.service.engine;

import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.CandleProcessingStatus;
import com.trading.scanner.model.CandleQualityStatus;
import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.DailyStockContext;
import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.model.StockPrice;
import com.trading.scanner.model.VolumeDailyBaseline;
import com.trading.scanner.model.VolumeTimeWindowBaseline;
import com.trading.scanner.repository.DailyStockContextRepository;
import com.trading.scanner.repository.MarketCandleRepository;
import com.trading.scanner.repository.StockPriceRepository;
import com.trading.scanner.repository.VolumeDailyBaselineRepository;
import com.trading.scanner.repository.VolumeTimeWindowBaselineRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class VolumeBaselineService {

        private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);

        private static final int MARKET_SESSION_MINUTES = 375;

        private static final int LOOKBACK_DAYS = 20;

        private final DailyStockContextRepository dailyStockContextRepository;

        private final StockPriceRepository stockPriceRepository;

        private final MarketCandleRepository marketCandleRepository;

        private final VolumeDailyBaselineRepository volumeDailyBaselineRepository;

        private final VolumeTimeWindowBaselineRepository volumeTimeWindowBaselineRepository;

        private final TradingCalendar tradingCalendar;
        private final TimeProvider timeProvider;

        @Value("${runtime.volume.precalc.auto-run:true}")
        private boolean autoRun;

        @Value("${runtime.volume.precalc.hour:16}")
        private int scheduledHour;

        @Value("${runtime.volume.precalc.minute:5}")
        private int scheduledMinute;

        @Transactional
        public PreCalculationResult preCalculateForCompletedTradingDay(
                        LocalDate completedTradingDate) {

                if (completedTradingDate == null) {
                        return new PreCalculationResult(
                                        null,
                                        null,
                                        0,
                                        0,
                                        0,
                                        0,
                                        "Skipped volume baseline pre-calculation because completedTradingDate was null");
                }

                if (!tradingCalendar.isTradingDay(
                                completedTradingDate)) {
                        return new PreCalculationResult(
                                        completedTradingDate,
                                        null,
                                        0,
                                        0,
                                        0,
                                        0,
                                        "Skipped volume baseline pre-calculation because completedTradingDate was not a trading day");
                }

                LocalDate effectiveTradingDate = tradingCalendar.nextTradingDay(
                                completedTradingDate);

                LocalDateTime computedAt = timeProvider.nowDateTime();

                List<DailyStockContext> symbolContexts = dailyStockContextRepository
                                .findByTradingDateOrderBySymbolAsc(
                                                completedTradingDate);

                int processedSymbols = 0;
                int upsertedDailyBaselines = 0;
                int upsertedTimeWindowBaselines = 0;
                int skippedSymbols = 0;

                for (DailyStockContext context : symbolContexts) {

                        if (context == null
                                        || context.getSymbol() == null
                                        || context.getExchange() == null) {
                                skippedSymbols++;
                                continue;
                        }

                        DailyBaselineWindow dailyWindow = computeDailyBaselineWindow(
                                        context.getSymbol(),
                                        completedTradingDate);

                        if (dailyWindow.sampleDays() <= 0) {
                                skippedSymbols++;
                                continue;
                        }

                        upsertDailyBaseline(
                                        context.getSymbol(),
                                        context.getExchange(),
                                        effectiveTradingDate,
                                        dailyWindow.averageVolume(),
                                        dailyWindow.sampleDays(),
                                        computedAt);

                        upsertedDailyBaselines++;

                        upsertedTimeWindowBaselines += upsertTimeWindowBaselines(
                                        context.getSymbol(),
                                        context.getExchange(),
                                        completedTradingDate,
                                        effectiveTradingDate,
                                        dailyWindow.lookbackDates(),
                                        computedAt);

                        processedSymbols++;
                }

                PreCalculationResult result = new PreCalculationResult(
                                completedTradingDate,
                                effectiveTradingDate,
                                processedSymbols,
                                upsertedDailyBaselines,
                                upsertedTimeWindowBaselines,
                                skippedSymbols,
                                "Volume baseline pre-calculation completed");

                log.info(
                                "Volume baseline pre-calculation completed: {}",
                                result);

                return result;
        }

        @Transactional(readOnly = true)
        public VolumeMetrics calculateCurrentVolumeMetrics(
                        String symbol,
                        String exchange,
                        LocalDate tradingDate,
                        LocalDateTime asOf,
                        Long cumulativeVolumeToday) {

                if (symbol == null
                                || exchange == null
                                || tradingDate == null
                                || asOf == null
                                || !tradingDate.equals(
                                                asOf.toLocalDate())
                                || cumulativeVolumeToday == null
                                || cumulativeVolumeToday < 0L) {

                        return new VolumeMetrics(
                                        cumulativeVolumeToday,
                                        null,
                                        null,
                                        null);
                }

                Integer currentSessionMinute = sessionMinute(asOf);

                if (currentSessionMinute == null) {
                        return new VolumeMetrics(
                                        cumulativeVolumeToday,
                                        null,
                                        null,
                                        null);
                }

                Optional<VolumeTimeWindowBaseline> baseline = volumeTimeWindowBaselineRepository
                                .findBySymbolAndExchangeAndTradingDateAndSessionMinute(
                                                symbol,
                                                exchange,
                                                tradingDate,
                                                currentSessionMinute);

                if (baseline.isEmpty()
                                || baseline.get()
                                                .getAvgCumulativeVolume20() == null
                                || baseline.get()
                                                .getAvgCumulativeVolume20() <= 0L) {

                        return new VolumeMetrics(
                                        cumulativeVolumeToday,
                                        currentSessionMinute,
                                        null,
                                        null);
                }

                long averageCumulativeVolume = baseline.get()
                                .getAvgCumulativeVolume20();

                double volX = cumulativeVolumeToday
                                / (double) averageCumulativeVolume;

                return new VolumeMetrics(
                                cumulativeVolumeToday,
                                currentSessionMinute,
                                averageCumulativeVolume,
                                volX);
        }

        public Integer sessionMinute(
                        LocalDateTime dateTime) {

                if (dateTime == null) {
                        return null;
                }

                long elapsed = Duration.between(
                                MARKET_OPEN,
                                dateTime.toLocalTime())
                                .toMinutes();

                if (elapsed < 0
                                || elapsed >= MARKET_SESSION_MINUTES) {
                        return null;
                }

                return Math.toIntExact(elapsed);
        }

        /*
         * Kept for compatibility with existing callers.
         * Baselines now exist for every session minute, not only
         * 30-minute endpoints.
         */
        public Integer windowEndpointSessionMinute(
                        LocalDateTime dateTime) {

                return sessionMinute(dateTime);
        }

        public void scheduledPreCalculateBaselines() {
                if (!autoRun) {
                        return;
                }

                LocalDateTime now = timeProvider.nowDateTime();

                if (!tradingCalendar.isTradingDay(
                                now.toLocalDate())) {
                        return;
                }

                if (now.getHour() != scheduledHour
                                || now.getMinute() != scheduledMinute) {
                        return;
                }

                try {
                        preCalculateForCompletedTradingDay(
                                        tradingCalendar.previousTradingDay(
                                                        now.toLocalDate()));

                } catch (Exception ex) {
                        log.warn(
                                        "Scheduled volume baseline pre-calculation failed: {}",
                                        ex.getMessage(),
                                        ex);
                }
        }

        private DailyBaselineWindow computeDailyBaselineWindow(
                        String symbol,
                        LocalDate completedTradingDate) {

                List<StockPrice> prices = stockPriceRepository
                                .findBySymbolAndDateLessThanEqualOrderByDateAsc(
                                                symbol,
                                                completedTradingDate);

                if (prices == null) {
                        prices = List.of();
                }

                List<StockPrice> usable = prices.stream()
                                .filter(price -> price != null
                                                && price.getDate() != null)
                                .filter(price -> !price.getDate()
                                                .isAfter(completedTradingDate))
                                .filter(price -> price.getVolume() != null
                                                && price.getVolume() > 0)
                                .toList();

                if (usable.isEmpty()) {
                        return new DailyBaselineWindow(
                                        0L,
                                        0,
                                        List.of());
                }

                int fromIndex = Math.max(
                                0,
                                usable.size() - LOOKBACK_DAYS);

                List<StockPrice> window = usable.subList(
                                fromIndex,
                                usable.size());

                long averageVolume = Math.round(
                                window.stream()
                                                .map(StockPrice::getVolume)
                                                .mapToLong(Integer::longValue)
                                                .average()
                                                .orElse(0.0));

                List<LocalDate> lookbackDates = window.stream()
                                .map(StockPrice::getDate)
                                .toList();

                return new DailyBaselineWindow(
                                averageVolume,
                                window.size(),
                                lookbackDates);
        }

        private void upsertDailyBaseline(
                        String symbol,
                        String exchange,
                        LocalDate effectiveTradingDate,
                        long averageVolume,
                        int sampleDays,
                        LocalDateTime computedAt) {

                VolumeDailyBaseline row = volumeDailyBaselineRepository
                                .findBySymbolAndExchangeAndTradingDate(
                                                symbol,
                                                exchange,
                                                effectiveTradingDate)
                                .orElseGet(() -> VolumeDailyBaseline.builder()
                                                .symbol(symbol)
                                                .exchange(exchange)
                                                .tradingDate(
                                                                effectiveTradingDate)
                                                .build());

                row.setAvgDailyVolume20(
                                averageVolume);

                row.setSampleDays(sampleDays);
                row.setComputedAt(computedAt);

                volumeDailyBaselineRepository.save(row);
        }

        private int upsertTimeWindowBaselines(
                        String symbol,
                        String exchange,
                        LocalDate completedTradingDate,
                        LocalDate effectiveTradingDate,
                        List<LocalDate> lookbackDates,
                        LocalDateTime computedAt) {

                if (lookbackDates == null
                                || lookbackDates.isEmpty()) {
                        return 0;
                }

                Set<LocalDate> eligibleDates = new LinkedHashSet<>(lookbackDates);

                LocalDate earliestDate = lookbackDates.get(0);

                List<MarketCandle> candles = marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                symbol,
                                                exchange,
                                                CandleTimeframe.ONE_MINUTE,
                                                earliestDate.atTime(
                                                                MARKET_OPEN),
                                                completedTradingDate
                                                                .plusDays(1)
                                                                .atStartOfDay()
                                                                .minusNanos(1));

                Map<LocalDate, Long> cumulativeByDate = new HashMap<>();

                Map<Integer, VolumeAggregate> aggregateBySessionMinute = new HashMap<>();

                for (MarketCandle candle : candles) {
                        if (!isUsableCandle(candle)
                                        || candle.getVolume() == null
                                        || candle.getVolume() < 0L) {
                                continue;
                        }

                        LocalDate candleDate = candle.getCandleTime()
                                        .toLocalDate();

                        if (!eligibleDates.contains(candleDate)) {
                                continue;
                        }

                        Integer sessionMinute = sessionMinute(
                                        candle.getCandleTime());

                        if (sessionMinute == null) {
                                continue;
                        }

                        long cumulative = cumulativeByDate.merge(
                                        candleDate,
                                        candle.getVolume(),
                                        Long::sum);

                        aggregateBySessionMinute
                                        .computeIfAbsent(
                                                        sessionMinute,
                                                        ignored -> new VolumeAggregate())
                                        .add(cumulative);
                }

                int upsertedRows = 0;

                for (int minute = 0; minute < MARKET_SESSION_MINUTES; minute++) {

                        VolumeAggregate aggregate = aggregateBySessionMinute.get(minute);

                        if (aggregate == null
                                        || aggregate.count <= 0) {
                                continue;
                        }

                        final int baselineSessionMinute = minute;

                        long averageCumulativeVolume = Math.round(
                                        (double) aggregate.total
                                                        / aggregate.count);

                        VolumeTimeWindowBaseline row = volumeTimeWindowBaselineRepository
                                        .findBySymbolAndExchangeAndTradingDateAndSessionMinute(
                                                        symbol,
                                                        exchange,
                                                        effectiveTradingDate,
                                                        baselineSessionMinute)
                                        .orElseGet(() -> VolumeTimeWindowBaseline.builder()
                                                        .symbol(symbol)
                                                        .exchange(exchange)
                                                        .tradingDate(
                                                                        effectiveTradingDate)
                                                        .sessionMinute(
                                                                        baselineSessionMinute)
                                                        .build());

                        row.setAvgCumulativeVolume20(
                                        averageCumulativeVolume);

                        row.setSampleDays(
                                        aggregate.count);

                        row.setComputedAt(computedAt);

                        volumeTimeWindowBaselineRepository.save(row);
                        upsertedRows++;
                }

                return upsertedRows;
        }

        private boolean isUsableCandle(
                        MarketCandle candle) {

                return candle != null
                                && candle.getCandleTime() != null
                                && Boolean.TRUE.equals(
                                                candle.getIsFinalized())
                                && candle.getQualityStatus() != CandleQualityStatus.SUSPECT
                                && (candle.getProcessingStatus() == null
                                                || candle.getProcessingStatus() == CandleProcessingStatus.RELEASED);
        }

        private record DailyBaselineWindow(
                        long averageVolume,
                        int sampleDays,
                        List<LocalDate> lookbackDates) {
        }

        private static final class VolumeAggregate {
                private long total;
                private int count;

                private void add(long value) {
                        total += value;
                        count++;
                }
        }

        public record VolumeMetrics(
                        Long cumulativeVolumeToday,
                        Integer sessionMinute,
                        Long averageCumulativeVolumeAtCurrentTime,
                        Double volX) {
        }

        public record PreCalculationResult(
                        LocalDate completedTradingDate,
                        LocalDate effectiveTradingDate,
                        int processedSymbols,
                        int upsertedDailyBaselines,
                        int upsertedTimeWindowBaselines,
                        int skippedSymbols,
                        String message) {
        }
}
