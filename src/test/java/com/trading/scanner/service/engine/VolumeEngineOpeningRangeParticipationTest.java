package com.trading.scanner.service.engine;

import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.DailyStockContext;
import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.model.MarketMinuteSnapshot;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class VolumeEngineOpeningRangeParticipationTest {

    private MarketMinuteSnapshotRepository snapshotRepository;
    private VolumeTimeWindowBaselineRepository baselineRepository;
    private VolumeDailyBaselineRepository dailyBaselineRepository;
    private DailyStockContextRepository contextRepository;
    private MarketCandleRepository candleRepository;
    private VolumeEngineService service;

    @BeforeEach
    void setUp() {
        snapshotRepository = mock(MarketMinuteSnapshotRepository.class);

        baselineRepository = mock(VolumeTimeWindowBaselineRepository.class);

        dailyBaselineRepository = mock(VolumeDailyBaselineRepository.class);

        contextRepository = mock(DailyStockContextRepository.class);

        candleRepository = mock(MarketCandleRepository.class);

        service = new VolumeEngineService(
                snapshotRepository,
                baselineRepository,
                dailyBaselineRepository,
                contextRepository,
                candleRepository);
    }

    @Test
    void currentVolumeState_shouldCalculateOpeningRangeParticipation() {
        LocalDate date = LocalDate.of(2026, 8, 24);

        LocalDateTime time = date.atTime(10, 0);

        when(snapshotRepository
                .findTopBySymbolAndExchangeAndTradingDateOrderByLatestTickTimeDesc(
                        "ONGC",
                        "NSE",
                        date))
                .thenReturn(Optional.of(
                        MarketMinuteSnapshot.builder()
                                .symbol("ONGC")
                                .exchange("NSE")
                                .tradingDate(date)
                                .minuteTime(time)
                                .volumeTradedForDay(40_000L)
                                .build()));

        when(baselineRepository
                .findBySymbolAndExchangeAndTradingDateAndSessionMinute(
                        "ONGC",
                        "NSE",
                        date,
                        45))
                .thenReturn(Optional.empty());

        when(baselineRepository
                .findBySymbolAndExchangeAndTradingDateAndSessionMinute(
                        "ONGC",
                        "NSE",
                        date,
                        14))
                .thenReturn(Optional.of(
                        VolumeTimeWindowBaseline.builder()
                                .sessionMinute(14)
                                .avgCumulativeVolume20(30_000L)
                                .build()));

        when(contextRepository
                .findBySymbolAndExchangeAndTradingDate(
                        "ONGC",
                        "NSE",
                        date))
                .thenReturn(Optional.of(
                        DailyStockContext.builder()
                                .openingRangeVolume(15_000L)
                                .build()));

        when(candleRepository
                .findTop100BySymbolAndExchangeAndTimeframeAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
                        anyString(),
                        anyString(),
                        any(CandleTimeframe.class),
                        any(LocalDateTime.class)))
                .thenReturn(List.of());

        VolumeEngineService.VolumeState state = service.currentVolumeState(
                "ONGC",
                "NSE",
                date);

        assertEquals(
                0.5,
                state.openingRangeParticipationRatio(),
                0.000001);
    }

    @Test
    void currentVolumeState_shouldReturnNullForMissingOrZeroBaseline() {
        LocalDate date = LocalDate.of(2026, 8, 24);

        LocalDateTime time = date.atTime(10, 0);

        when(snapshotRepository
                .findTopBySymbolAndExchangeAndTradingDateOrderByLatestTickTimeDesc(
                        "ONGC",
                        "NSE",
                        date))
                .thenReturn(Optional.of(
                        MarketMinuteSnapshot.builder()
                                .symbol("ONGC")
                                .exchange("NSE")
                                .tradingDate(date)
                                .minuteTime(time)
                                .volumeTradedForDay(40_000L)
                                .build()));

        when(baselineRepository
                .findBySymbolAndExchangeAndTradingDateAndSessionMinute(
                        anyString(),
                        anyString(),
                        any(LocalDate.class),
                        anyInt()))
                .thenReturn(Optional.empty());

        when(contextRepository
                .findBySymbolAndExchangeAndTradingDate(
                        anyString(),
                        anyString(),
                        any(LocalDate.class)))
                .thenReturn(Optional.of(
                        DailyStockContext.builder()
                                .openingRangeVolume(15_000L)
                                .build()));

        when(candleRepository
                .findTop100BySymbolAndExchangeAndTimeframeAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
                        anyString(),
                        anyString(),
                        any(CandleTimeframe.class),
                        any(LocalDateTime.class)))
                .thenReturn(List.of());

        VolumeEngineService.VolumeState state = service.currentVolumeState(
                "ONGC",
                "NSE",
                date);

        assertNull(
                state.openingRangeParticipationRatio());
    }

    @Test
    void currentVolumeState_shouldReturnNullForZeroOpeningRangeBaseline() {
        LocalDate date = LocalDate.of(2026, 8, 24);

        LocalDateTime time = date.atTime(10, 0);

        when(snapshotRepository
                .findTopBySymbolAndExchangeAndTradingDateOrderByLatestTickTimeDesc(
                        "ONGC",
                        "NSE",
                        date))
                .thenReturn(Optional.of(
                        MarketMinuteSnapshot.builder()
                                .symbol("ONGC")
                                .exchange("NSE")
                                .tradingDate(date)
                                .minuteTime(time)
                                .volumeTradedForDay(40_000L)
                                .build()));

        when(baselineRepository
                .findBySymbolAndExchangeAndTradingDateAndSessionMinute(
                        anyString(),
                        anyString(),
                        any(LocalDate.class),
                        anyInt()))
                .thenAnswer(invocation -> {
                    Integer minute = invocation.getArgument(3);

                    if (minute == 14) {
                        return Optional.of(
                                VolumeTimeWindowBaseline.builder()
                                        .sessionMinute(14)
                                        .avgCumulativeVolume20(0L)
                                        .build());
                    }

                    return Optional.empty();
                });

        when(contextRepository
                .findBySymbolAndExchangeAndTradingDate(
                        anyString(),
                        anyString(),
                        any(LocalDate.class)))
                .thenReturn(Optional.of(
                        DailyStockContext.builder()
                                .openingRangeVolume(15_000L)
                                .build()));

        when(candleRepository
                .findTop100BySymbolAndExchangeAndTimeframeAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
                        anyString(),
                        anyString(),
                        any(CandleTimeframe.class),
                        any(LocalDateTime.class)))
                .thenReturn(List.of());

        VolumeEngineService.VolumeState state = service.currentVolumeState(
                "ONGC",
                "NSE",
                date);

        assertNull(
                state.openingRangeParticipationRatio());
    }
}
