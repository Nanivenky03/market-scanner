package com.trading.scanner.service.engine;

import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.CandleProcessingStatus;
import com.trading.scanner.model.CandleQualityStatus;
import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.ContextStatus;
import com.trading.scanner.model.DailyCandleSummary;
import com.trading.scanner.model.DailyDataStatus;
import com.trading.scanner.model.DataStatus;
import com.trading.scanner.model.LiveMinuteResolution;
import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.model.MinuteResolutionStatus;
import com.trading.scanner.repository.DailyCandleSummaryRepository;
import com.trading.scanner.repository.DailyDataStatusRepository;
import com.trading.scanner.repository.DailyStockContextRepository;
import com.trading.scanner.repository.LiveMinuteResolutionRepository;
import com.trading.scanner.repository.MarketCandleRepository;
import com.trading.scanner.repository.StockUniverseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DailyDataStatusService {

        private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);

        private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 29);

        private final DailyDataStatusRepository dailyDataStatusRepository;

        private final DailyStockContextRepository dailyStockContextRepository;

        private final MarketCandleRepository marketCandleRepository;

        private final TradingCalendar tradingCalendar;
        private final TimeProvider timeProvider;
        private final StockUniverseRepository stockUniverseRepository;

        private final DailyCandleSummaryRepository dailyCandleSummaryRepository;

        private final LiveMinuteResolutionRepository liveMinuteResolutionRepository;

        @Transactional
        public void markLive(
                        String symbol,
                        String exchange,
                        LocalDate tradingDate) {

                DailyDataStatus status = findOrCreate(
                                symbol,
                                exchange,
                                tradingDate);

                if (status.getStatus() == DataStatus.RECONCILED
                                || status.getStatus() == DataStatus.FAILED) {
                        return;
                }

                status.setStatus(DataStatus.LIVE);
                status.setReason("Live candle data received");
                status.setUpdatedAt(
                                timeProvider.nowDateTime());

                dailyDataStatusRepository.save(status);
        }

        @Transactional
        public void markPartial(
                        String symbol,
                        String exchange,
                        LocalDate tradingDate,
                        String reason) {

                DailyDataStatus status = findOrCreate(
                                symbol,
                                exchange,
                                tradingDate);

                if (status.getStatus() == DataStatus.RECONCILED
                                || status.getStatus() == DataStatus.FAILED) {
                        return;
                }

                status.setStatus(DataStatus.PARTIAL);
                status.setReason(reason);
                status.setUpdatedAt(
                                timeProvider.nowDateTime());

                dailyDataStatusRepository.save(status);
        }

        @Transactional
        public void markStatus(
                        String symbol,
                        String exchange,
                        LocalDate tradingDate,
                        DataStatus dataStatus,
                        Integer expectedCandleCount,
                        Integer actualCandleCount,
                        Integer missingCandleCount,
                        String reason) {

                DailyDataStatus status = findOrCreate(
                                symbol,
                                exchange,
                                tradingDate);

                status.setStatus(dataStatus);
                status.setExpectedCandleCount(
                                expectedCandleCount);
                status.setActualCandleCount(
                                actualCandleCount);
                status.setMissingCandleCount(
                                missingCandleCount);
                status.setReason(reason);
                status.setUpdatedAt(
                                timeProvider.nowDateTime());

                dailyDataStatusRepository.save(status);
        }

        @Transactional
        public CompletenessResult checkCompleteness(
                        String symbol,
                        String exchange,
                        LocalDate tradingDate) {

                int expected = tradingCalendar
                                .expectedOneMinuteCandleCount(
                                                tradingDate);

                if (expected == 0) {
                        return new CompletenessResult(
                                        symbol,
                                        exchange,
                                        tradingDate,
                                        0,
                                        0,
                                        0,
                                        false,
                                        false,
                                        false,
                                        null,
                                        "Skipped non-trading day");
                }

                LocalDateTime from = tradingDate.atTime(MARKET_OPEN);

                LocalDateTime to = tradingDate.atTime(MARKET_CLOSE);

                List<MarketCandle> candles = marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                symbol,
                                                exchange,
                                                CandleTimeframe.ONE_MINUTE,
                                                from,
                                                to);

                List<LiveMinuteResolution> resolutions = liveMinuteResolutionRepository
                                .findBySymbolAndExchangeAndMinuteTimeBetweenOrderByMinuteTimeAsc(
                                                symbol,
                                                exchange,
                                                from,
                                                to);

                Map<LocalDateTime, LiveMinuteResolution> resolutionByMinute = new HashMap<>();

                for (LiveMinuteResolution resolution : resolutions) {
                        resolutionByMinute.put(
                                        resolution.getMinuteTime(),
                                        resolution);
                }

                Set<LocalDateTime> actualTimes = new HashSet<>();

                boolean duplicate = false;
                boolean unfinalized = false;
                boolean suspect = false;

                for (MarketCandle candle : candles) {
                        if (!actualTimes.add(
                                        candle.getCandleTime())) {
                                duplicate = true;
                        }

                        if (!isReleased(candle)
                                        || !Boolean.TRUE.equals(
                                                        candle.getIsFinalized())) {
                                unfinalized = true;
                        }

                        if (candle.getQualityStatus() == CandleQualityStatus.SUSPECT) {
                                suspect = true;
                        }
                }

                int noTradeCount = 0;
                int missing = 0;

                for (LocalDateTime minute = from; !minute.isAfter(to); minute = minute.plusMinutes(1)) {

                        if (actualTimes.contains(minute)) {
                                continue;
                        }

                        LiveMinuteResolution resolution = resolutionByMinute.get(minute);

                        if (resolution != null
                                        && resolution.getStatus() == MinuteResolutionStatus.NO_TRADE_CONFIRMED) {
                                noTradeCount++;
                        } else {
                                missing++;
                        }
                }

                boolean complete = actualTimes.size()
                                + noTradeCount == expected
                                && missing == 0
                                && !duplicate
                                && !unfinalized
                                && !suspect;

                DailyDataStatus status = findOrCreate(
                                symbol,
                                exchange,
                                tradingDate);

                DataStatus currentStatus = status.getStatus();

                if (!complete
                                && currentStatus != DataStatus.RECONCILED
                                && currentStatus != DataStatus.FAILED) {

                        status.setStatus(DataStatus.PARTIAL);

                } else if (complete
                                && (currentStatus == null
                                                || currentStatus == DataStatus.PARTIAL)) {

                        status.setStatus(DataStatus.LIVE);
                }

                status.setExpectedCandleCount(expected);
                status.setActualCandleCount(candles.size());
                status.setMissingCandleCount(missing);
                status.setReason(
                                reason(
                                                complete,
                                                missing,
                                                noTradeCount,
                                                duplicate,
                                                unfinalized,
                                                suspect));
                status.setUpdatedAt(
                                timeProvider.nowDateTime());

                dailyDataStatusRepository.save(status);

                /*
                 * Upsert the summary instead of always inserting a new row.
                 * This prevents duplicate-key failures when the completeness
                 * check runs more than once for the same symbol and date.
                 */
                DailyCandleSummary summary = dailyCandleSummaryRepository
                                .findBySymbolAndExchangeAndTradingDate(
                                                symbol,
                                                exchange,
                                                tradingDate)
                                .orElseGet(() -> DailyCandleSummary.builder()
                                                .symbol(symbol)
                                                .exchange(exchange)
                                                .tradingDate(tradingDate)
                                                .build());

                summary.setExpectedCount(expected);
                summary.setActualCount(candles.size());
                summary.setMissingCount(missing);
                summary.setNoTradeCount(noTradeCount);
                summary.setDuplicateDetected(duplicate);
                summary.setUnfinalizedDetected(unfinalized);
                summary.setLiveCount(
                                count(
                                                candles,
                                                CandleQualityStatus.LIVE));
                summary.setRepairedCount(
                                count(
                                                candles,
                                                CandleQualityStatus.REPAIRED));
                summary.setReconciledCount(
                                count(
                                                candles,
                                                CandleQualityStatus.RECONCILED));
                summary.setSuspectCount(
                                count(
                                                candles,
                                                CandleQualityStatus.SUSPECT));
                summary.setDataStatus(
                                status.getStatus());
                summary.setUpdatedAt(
                                timeProvider.nowDateTime());

                dailyCandleSummaryRepository.save(summary);

                return new CompletenessResult(
                                symbol,
                                exchange,
                                tradingDate,
                                expected,
                                candles.size(),
                                missing,
                                duplicate,
                                unfinalized,
                                complete,
                                status.getStatus(),
                                status.getReason());
        }

        @Transactional
        public void scheduledCompletenessCheck() {
                LocalDate completedDate = tradingCalendar.previousTradingDay(
                                timeProvider.today());

                stockUniverseRepository
                                .findByIsActiveTrueOrderBySymbolAsc()
                                .forEach(stock -> checkCompleteness(
                                                stock.getSymbol(),
                                                stock.getExchange().name(),
                                                completedDate));
        }

        @Transactional(readOnly = true)
        public Optional<DailyDataStatus> find(
                        String symbol,
                        String exchange,
                        LocalDate tradingDate) {

                return dailyDataStatusRepository
                                .findBySymbolAndExchangeAndTradingDate(
                                                symbol,
                                                exchange,
                                                tradingDate);
        }

        @Transactional(readOnly = true)
        public boolean dataStatusIsTrusted(
                        String symbol,
                        String exchange,
                        LocalDate tradingDate) {

                return find(
                                symbol,
                                exchange,
                                tradingDate)
                                .map(status -> status.getStatus() == DataStatus.RECONCILED
                                                || status.getStatus() == DataStatus.REPAIRED)
                                .orElse(false);
        }

        @Transactional(readOnly = true)
        public boolean contextIsComplete(
                        String symbol,
                        String exchange,
                        LocalDate tradingDate) {

                return dailyStockContextRepository
                                .findBySymbolAndExchangeAndTradingDate(
                                                symbol,
                                                exchange,
                                                tradingDate)
                                .map(context -> context.getContextStatus() == ContextStatus.COMPLETE)
                                .orElse(false);
        }

        @Transactional(readOnly = true)
        public boolean isTrustedForSimulation(
                        String symbol,
                        String exchange,
                        LocalDate tradingDate) {

                return dataStatusIsTrusted(
                                symbol,
                                exchange,
                                tradingDate)
                                && contextIsComplete(
                                                symbol,
                                                exchange,
                                                tradingDate);
        }

        private boolean isReleased(
                        MarketCandle candle) {

                return candle.getProcessingStatus() == null
                                || candle.getProcessingStatus() == CandleProcessingStatus.RELEASED;
        }

        private int count(
                        List<MarketCandle> candles,
                        CandleQualityStatus quality) {

                return (int) candles.stream()
                                .filter(candle -> candle.getQualityStatus() == quality)
                                .count();
        }

        private DailyDataStatus findOrCreate(
                        String symbol,
                        String exchange,
                        LocalDate tradingDate) {

                return dailyDataStatusRepository
                                .findBySymbolAndExchangeAndTradingDate(
                                                symbol,
                                                exchange,
                                                tradingDate)
                                .orElseGet(() -> DailyDataStatus.builder()
                                                .symbol(symbol)
                                                .exchange(exchange)
                                                .tradingDate(tradingDate)
                                                .build());
        }

        private String reason(
                        boolean complete,
                        int missing,
                        int noTrade,
                        boolean duplicate,
                        boolean unfinalized,
                        boolean suspect) {

                if (complete) {
                        return "Candle completeness verified; noTrade="
                                        + noTrade
                                        + "; reconciliation pending";
                }

                return "Incomplete candle data: missing="
                                + missing
                                + ", noTrade="
                                + noTrade
                                + ", duplicate="
                                + duplicate
                                + ", unfinalized="
                                + unfinalized
                                + ", suspect="
                                + suspect;
        }

        public record CompletenessResult(
                        String symbol,
                        String exchange,
                        LocalDate tradingDate,
                        int expectedCandleCount,
                        int actualCandleCount,
                        int missingCandleCount,
                        boolean duplicateCandleDetected,
                        boolean unfinalizedCandleDetected,
                        boolean complete,
                        DataStatus resultingStatus,
                        String message) {
        }
}
