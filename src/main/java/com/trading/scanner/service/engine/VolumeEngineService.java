package com.trading.scanner.service.engine;

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
    private static final int OPENING_RANGE_END_SESSION_MINUTE = 14;
    private static final int RECENT_DIRECTION_CANDLES = 3;

    private final MarketMinuteSnapshotRepository marketMinuteSnapshotRepository;
    private final VolumeTimeWindowBaselineRepository volumeTimeWindowBaselineRepository;
    private final VolumeDailyBaselineRepository volumeDailyBaselineRepository;
    private final DailyStockContextRepository dailyStockContextRepository;
    private final MarketCandleRepository marketCandleRepository;

    @Transactional(readOnly = true)
    public VolumeState currentVolumeState(String symbol, String exchange, LocalDate tradingDate) {
        String normalizedSymbol = normalize(symbol);
        String normalizedExchange = normalize(exchange);

        if (normalizedSymbol == null || normalizedExchange == null || tradingDate == null) {
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
        int sessionMinute = sessionMinute(snapshot.getMinuteTime().toLocalTime());

        VolumeTimeWindowBaseline currentBaseline = volumeTimeWindowBaselineRepository
                .findBySymbolAndExchangeAndTradingDateAndSessionMinute(
                        normalizedSymbol,
                        normalizedExchange,
                        tradingDate,
                        sessionMinute)
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
        Long baselineCumulativeVolume = currentBaseline != null ? currentBaseline.getAvgCumulativeVolume20() : null;
        Double volX = ratio(currentCumulativeVolume, baselineCumulativeVolume);

        Long openingRangeVolume = dailyContext != null ? dailyContext.getOpeningRangeVolume() : null;
        Long openingRangeBaselineVolume = openingRangeBaseline != null
                ? openingRangeBaseline.getAvgCumulativeVolume20()
                : null;
        Double openingRangeParticipationRatio = ratio(openingRangeVolume, openingRangeBaselineVolume);

        String recentVolumeDirection = resolveRecentVolumeDirection(
                normalizedSymbol,
                normalizedExchange,
                snapshot.getMinuteTime());

        return new VolumeState(
                normalizedSymbol,
                normalizedExchange,
                tradingDate,
                snapshot.getMinuteTime(),
                sessionMinute,
                currentCumulativeVolume,
                baselineCumulativeVolume,
                volX,
                dailyBaseline != null ? dailyBaseline.getAvgDailyVolume20() : null,
                openingRangeVolume,
                openingRangeBaselineVolume,
                openingRangeParticipationRatio,
                recentVolumeDirection);
    }

    private String resolveRecentVolumeDirection(String symbol, String exchange, java.time.LocalDateTime candleTime) {
        List<MarketCandle> recentDesc = marketCandleRepository
                .findTop100BySymbolAndExchangeAndTimeframeAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
                        symbol,
                        exchange,
                        CandleTimeframe.ONE_MINUTE,
                        candleTime);

        if (recentDesc.size() < RECENT_DIRECTION_CANDLES) {
            return "INSUFFICIENT_DATA";
        }

        List<MarketCandle> recent = new ArrayList<>(recentDesc.subList(0, RECENT_DIRECTION_CANDLES));
        Collections.reverse(recent);

        long first = volume(recent.get(0));
        long second = volume(recent.get(1));
        long third = volume(recent.get(2));

        if (first < second && second < third) {
            return "UP";
        }

        if (first > second && second > third) {
            return "DOWN";
        }

        return "FLAT";
    }

    private int sessionMinute(LocalTime candleTime) {
        if (candleTime == null || candleTime.isBefore(MARKET_OPEN)) {
            return 0;
        }
        return (int) Duration.between(MARKET_OPEN, candleTime).toMinutes();
    }

    private long volume(MarketCandle candle) {
        return candle.getVolume() != null ? candle.getVolume() : 0L;
    }

    private Double ratio(Long numerator, Long denominator) {
        if (numerator == null || denominator == null || denominator <= 0L) {
            return null;
        }
        return numerator / (double) denominator;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    public record VolumeState(
            String symbol,
            String exchange,
            LocalDate tradingDate,
            java.time.LocalDateTime snapshotMinuteTime,
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
