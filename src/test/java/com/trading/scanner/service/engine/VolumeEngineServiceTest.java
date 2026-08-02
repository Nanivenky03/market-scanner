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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;

class VolumeEngineServiceTest {

    private MarketMinuteSnapshotRepository marketMinuteSnapshotRepository;
    private VolumeTimeWindowBaselineRepository volumeTimeWindowBaselineRepository;
    private VolumeDailyBaselineRepository volumeDailyBaselineRepository;
    private DailyStockContextRepository dailyStockContextRepository;
    private MarketCandleRepository marketCandleRepository;
    private VolumeEngineService service;

    @BeforeEach
    void setUp() {
        marketMinuteSnapshotRepository = mock(MarketMinuteSnapshotRepository.class);
        volumeTimeWindowBaselineRepository = mock(VolumeTimeWindowBaselineRepository.class);
        volumeDailyBaselineRepository = mock(VolumeDailyBaselineRepository.class);
        dailyStockContextRepository = mock(DailyStockContextRepository.class);
        marketCandleRepository = mock(MarketCandleRepository.class);

        service = new VolumeEngineService(
                marketMinuteSnapshotRepository,
                volumeTimeWindowBaselineRepository,
                volumeDailyBaselineRepository,
                dailyStockContextRepository,
                marketCandleRepository);
    }

    @Test
    void currentVolumeState_shouldReturnNullWhenSnapshotMissing() {
        LocalDate tradingDate = LocalDate.of(2026, 7, 15);

        when(marketMinuteSnapshotRepository.findTopBySymbolAndExchangeAndTradingDateOrderByLatestTickTimeDesc(
                "ONGC", "NSE", tradingDate)).thenReturn(Optional.empty());

        assertNull(service.currentVolumeState("ONGC", "NSE", tradingDate));
    }

    @Test
    void currentVolumeState_shouldComputeVolXAndOpeningRangeParticipationAndDirection() {
        LocalDate tradingDate = LocalDate.of(2026, 7, 15);
        LocalDateTime minuteTime = LocalDateTime.of(2026, 7, 15, 9, 45);

        when(marketMinuteSnapshotRepository.findTopBySymbolAndExchangeAndTradingDateOrderByLatestTickTimeDesc(
                "ONGC", "NSE", tradingDate))
                .thenReturn(Optional.of(MarketMinuteSnapshot.builder()
                        .symbol("ONGC")
                        .exchange("NSE")
                        .tradingDate(tradingDate)
                        .minuteTime(minuteTime)
                        .latestTickTime(LocalDateTime.of(2026, 7, 15, 9, 45, 58))
                        .volumeTradedForDay(150000L)
                        .build()));

        when(volumeTimeWindowBaselineRepository.findBySymbolAndExchangeAndTradingDateAndSessionMinute(
                "ONGC", "NSE", tradingDate, 30))
                .thenReturn(Optional.of(VolumeTimeWindowBaseline.builder()
                        .symbol("ONGC")
                        .exchange("NSE")
                        .tradingDate(tradingDate)
                        .sessionMinute(30)
                        .avgCumulativeVolume20(100000L)
                        .sampleDays(20)
                        .build()));

        when(volumeDailyBaselineRepository.findBySymbolAndExchangeAndTradingDate(
                "ONGC", "NSE", tradingDate))
                .thenReturn(Optional.of(VolumeDailyBaseline.builder()
                        .symbol("ONGC")
                        .exchange("NSE")
                        .tradingDate(tradingDate)
                        .avgDailyVolume20(500000L)
                        .sampleDays(20)
                        .build()));

        when(dailyStockContextRepository.findBySymbolAndExchangeAndTradingDate(
                "ONGC", "NSE", tradingDate))
                .thenReturn(Optional.of(DailyStockContext.builder()
                        .symbol("ONGC")
                        .exchange("NSE")
                        .tradingDate(tradingDate)
                        .openingRangeVolume(45000L)
                        .build()));

        when(volumeTimeWindowBaselineRepository.findBySymbolAndExchangeAndTradingDateAndSessionMinute(
                "ONGC", "NSE", tradingDate, 14))
                .thenReturn(Optional.of(VolumeTimeWindowBaseline.builder()
                        .symbol("ONGC")
                        .exchange("NSE")
                        .tradingDate(tradingDate)
                        .sessionMinute(14)
                        .avgCumulativeVolume20(60000L)
                        .sampleDays(20)
                        .build()));

        when(marketCandleRepository
                .findTop100BySymbolAndExchangeAndTimeframeAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
                        "ONGC", "NSE", CandleTimeframe.ONE_MINUTE, minuteTime))
                .thenReturn(List.of(
                        oneMinute(minuteTime, 120L),
                        oneMinute(minuteTime.minusMinutes(1), 100L),
                        oneMinute(minuteTime.minusMinutes(2), 80L)));

        VolumeEngineService.VolumeState state = service.currentVolumeState("ONGC", "NSE", tradingDate);

        assertEquals("ONGC", state.symbol());
        assertEquals("NSE", state.exchange());
        assertEquals(30, state.sessionMinute());
        assertEquals(150000L, state.currentCumulativeVolume());
        assertEquals(100000L, state.baselineCumulativeVolume());
        assertEquals(1.5, state.volX(), 0.000001);
        assertEquals(500000L, state.avgDailyVolume20());
        assertEquals(45000L, state.openingRangeVolume());
        assertEquals(60000L, state.openingRangeBaselineVolume());
        assertEquals(0.75, state.openingRangeParticipationRatio(), 0.000001);
        assertEquals("UP", state.recentVolumeDirection());
    }

    @Test
    void currentVolumeState_shouldReturnFlatWhenRecentVolumesAreMixed() {
        LocalDate tradingDate = LocalDate.of(2026, 7, 15);
        LocalDateTime minuteTime = LocalDateTime.of(2026, 7, 15, 10, 0);

        when(marketMinuteSnapshotRepository.findTopBySymbolAndExchangeAndTradingDateOrderByLatestTickTimeDesc(
                "ONGC", "NSE", tradingDate))
                .thenReturn(Optional.of(MarketMinuteSnapshot.builder()
                        .symbol("ONGC")
                        .exchange("NSE")
                        .tradingDate(tradingDate)
                        .minuteTime(minuteTime)
                        .latestTickTime(LocalDateTime.of(2026, 7, 15, 10, 0, 58))
                        .volumeTradedForDay(200000L)
                        .build()));

        when(volumeTimeWindowBaselineRepository.findBySymbolAndExchangeAndTradingDateAndSessionMinute(
                anyString(), anyString(), any(LocalDate.class), anyInt()))
                .thenReturn(Optional.empty());
        when(volumeDailyBaselineRepository.findBySymbolAndExchangeAndTradingDate(
                anyString(), anyString(), any(LocalDate.class)))
                .thenReturn(Optional.empty());
        when(dailyStockContextRepository.findBySymbolAndExchangeAndTradingDate(
                anyString(), anyString(), any(LocalDate.class)))
                .thenReturn(Optional.empty());

        when(marketCandleRepository
                .findTop100BySymbolAndExchangeAndTimeframeAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
                        "ONGC", "NSE", CandleTimeframe.ONE_MINUTE, minuteTime))
                .thenReturn(List.of(
                        oneMinute(minuteTime, 100L),
                        oneMinute(minuteTime.minusMinutes(1), 120L),
                        oneMinute(minuteTime.minusMinutes(2), 80L)));

        VolumeEngineService.VolumeState state = service.currentVolumeState("ONGC", "NSE", tradingDate);

        assertEquals("FLAT", state.recentVolumeDirection());
        assertNull(state.volX());
        assertNull(state.openingRangeParticipationRatio());
    }

    private MarketCandle oneMinute(LocalDateTime candleTime, long volume) {
        return MarketCandle.builder()
                .symbol("ONGC")
                .exchange("NSE")
                .timeframe(CandleTimeframe.ONE_MINUTE)
                .candleTime(candleTime)
                .openPrice(100.0)
                .highPrice(101.0)
                .lowPrice(99.0)
                .closePrice(100.5)
                .volume(volume)
                .isFinalized(true)
                .build();
    }
}
