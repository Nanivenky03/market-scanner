package com.trading.scanner.service.engine;

import com.trading.scanner.model.CandleProcessingStatus;
import com.trading.scanner.model.CandleQualityStatus;
import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.DailyStockContext;
import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.model.MarketMinuteSnapshot;
import com.trading.scanner.model.VolumeDailyBaseline;
import com.trading.scanner.model.VolumeTimeWindowBaseline;
import com.trading.scanner.repository.DailyStockContextRepository;
import com.trading.scanner.repository.MarketCandleRepository;
import com.trading.scanner.repository.MarketMinuteSnapshotRepository;
import com.trading.scanner.repository.VolumeDailyBaselineRepository;
import com.trading.scanner.repository.VolumeTimeWindowBaselineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class VolumeEngineService {

    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);

    private static final int MARKET_SESSION_MINUTES = 375;

    private static final int OPENING_RANGE_END_SESSION_MINUTE = 14;

    private static final int RECENT_DIRECTION_CANDLES = 3;

    private final MarketMinuteSnapshotRepository marketMinuteSnapshotRepository;

    private final VolumeTimeWindowBaselineRepository volumeTimeWindowBaselineRepository;

    private final VolumeDailyBaselineRepository volumeDailyBaselineRepository;

    private final DailyStockContextRepository dailyStockContextRepository;

    private final MarketCandleRepository marketCandleRepository;

    @Transactional(readOnly = true)
    public VolumeState currentVolumeState(
            String symbol,
            String exchange,
            LocalDate tradingDate) {

        String normalizedSymbol = normalize(symbol);

        String normalizedExchange = normalize(exchange);

        if (normalizedSymbol == null
                || normalizedExchange == null
                || tradingDate == null) {
            return null;
        }

        Optional<MarketMinuteSnapshot> snapshotOpt = marketMinuteSnapshotRepository
                .findTopBySymbolAndExchangeAndTradingDateOrderByLatestTickTimeDesc(
                        normalizedSymbol,
                        normalizedExchange,
                        tradingDate);

        if (snapshotOpt.isEmpty()) {
            return null;
        }

        MarketMinuteSnapshot snapshot = snapshotOpt.get();

        LocalDateTime snapshotTime = snapshot.getMinuteTime();

        if (snapshotTime == null
                || !tradingDate.equals(
                        snapshotTime.toLocalDate())) {
            return null;
        }

        Integer currentSessionMinute = sessionMinute(snapshotTime.toLocalTime());

        if (currentSessionMinute == null) {
            return null;
        }

        VolumeTimeWindowBaseline currentBaseline = volumeTimeWindowBaselineRepository
                .findBySymbolAndExchangeAndTradingDateAndSessionMinute(
                        normalizedSymbol,
                        normalizedExchange,
                        tradingDate,
                        currentSessionMinute)
                .orElse(null);

        VolumeDailyBaseline dailyBaseline = volumeDailyBaselineRepository
                .findBySymbolAndExchangeAndTradingDate(
                        normalizedSymbol,
                        normalizedExchange,
                        tradingDate)
                .orElse(null);

        DailyStockContext dailyContext = dailyStockContextRepository
                .findBySymbolAndExchangeAndTradingDate(
                        normalizedSymbol,
                        normalizedExchange,
                        tradingDate)
                .orElse(null);

        VolumeTimeWindowBaseline openingRangeBaseline = volumeTimeWindowBaselineRepository
                .findBySymbolAndExchangeAndTradingDateAndSessionMinute(
                        normalizedSymbol,
                        normalizedExchange,
                        tradingDate,
                        OPENING_RANGE_END_SESSION_MINUTE)
                .orElse(null);

        Long currentCumulativeVolume = snapshot.getVolumeTradedForDay();

        Long baselineCumulativeVolume = currentBaseline == null
                ? null
                : currentBaseline
                        .getAvgCumulativeVolume20();

        Double volX = ratio(
                currentCumulativeVolume,
                baselineCumulativeVolume);

        Long openingRangeVolume = dailyContext == null
                ? null
                : dailyContext
                        .getOpeningRangeVolume();

        Long openingRangeBaselineVolume = openingRangeBaseline == null
                ? null
                : openingRangeBaseline
                        .getAvgCumulativeVolume20();

        Double openingRangeParticipationRatio = ratio(
                openingRangeVolume,
                openingRangeBaselineVolume);

        String recentVolumeDirection = resolveRecentVolumeDirection(
                normalizedSymbol,
                normalizedExchange,
                tradingDate,
                snapshotTime);

        return new VolumeState(
                normalizedSymbol,
                normalizedExchange,
                tradingDate,
                snapshotTime,
                currentSessionMinute,
                currentCumulativeVolume,
                baselineCumulativeVolume,
                volX,
                dailyBaseline == null
                        ? null
                        : dailyBaseline
                                .getAvgDailyVolume20(),
                openingRangeVolume,
                openingRangeBaselineVolume,
                openingRangeParticipationRatio,
                recentVolumeDirection);
    }

    private String resolveRecentVolumeDirection(
            String symbol,
            String exchange,
            LocalDate tradingDate,
            LocalDateTime candleTime) {

        List<MarketCandle> recentDesc = marketCandleRepository
                .findTop100BySymbolAndExchangeAndTimeframeAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
                        symbol,
                        exchange,
                        CandleTimeframe.ONE_MINUTE,
                        candleTime);

        if (recentDesc == null) {
            return "INSUFFICIENT_DATA";
        }

        List<MarketCandle> recent = recentDesc.stream()
                .filter(this::isUsableOneMinuteCandle)
                .filter(candle -> candle.getCandleTime()
                        .toLocalDate()
                        .equals(tradingDate))
                .filter(candle -> candle.getVolume() != null
                        && candle.getVolume() >= 0L)
                .limit(RECENT_DIRECTION_CANDLES)
                .toList();

        if (recent.size() < RECENT_DIRECTION_CANDLES) {
            return "INSUFFICIENT_DATA";
        }

        List<MarketCandle> chronological = new ArrayList<>(recent);

        Collections.reverse(chronological);

        long first = chronological.get(0).getVolume();

        long second = chronological.get(1).getVolume();

        long third = chronological.get(2).getVolume();

        if (first < second
                && second < third) {
            return "UP";
        }

        if (first > second
                && second > third) {
            return "DOWN";
        }

        return "FLAT";
    }

    private Integer sessionMinute(
            LocalTime candleTime) {

        if (candleTime == null) {
            return null;
        }

        long elapsed = Duration.between(
                MARKET_OPEN,
                candleTime)
                .toMinutes();

        if (elapsed < 0
                || elapsed >= MARKET_SESSION_MINUTES) {
            return null;
        }

        return Math.toIntExact(elapsed);
    }

    private Double ratio(
            Long numerator,
            Long denominator) {

        if (numerator == null
                || denominator == null
                || numerator < 0L
                || denominator <= 0L) {
            return null;
        }

        return numerator / (double) denominator;
    }

    private boolean isUsableOneMinuteCandle(
            MarketCandle candle) {

        return candle != null
                && candle.getCandleTime() != null
                && Boolean.TRUE.equals(
                        candle.getIsFinalized())
                && candle.getQualityStatus() != CandleQualityStatus.SUSPECT
                && (candle.getProcessingStatus() == null
                        || candle.getProcessingStatus() == CandleProcessingStatus.RELEASED);
    }

    private String normalize(String value) {
        return value == null || value.isBlank()
                ? null
                : value.trim()
                        .toUpperCase(Locale.ROOT);
    }

    public record VolumeState(
            String symbol,
            String exchange,
            LocalDate tradingDate,
            LocalDateTime snapshotMinuteTime,
            int sessionMinute,
            Long currentCumulativeVolume,
            Long baselineCumulativeVolume,
            Double volX,
            Long avgDailyVolume20,
            Long openingRangeVolume,
            Long openingRangeBaselineVolume,
            Double openingRangeParticipationRatio,
            String recentVolumeDirection) {
    }
}
