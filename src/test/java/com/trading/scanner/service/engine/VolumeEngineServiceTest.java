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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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
                LocalDate date = LocalDate.of(2026, 7, 15);

                when(marketMinuteSnapshotRepository
                                .findTopBySymbolAndExchangeAndTradingDateOrderByLatestTickTimeDesc(
                                                "ONGC",
                                                "NSE",
                                                date))
                                .thenReturn(Optional.empty());

                assertNull(
                                service.currentVolumeState(
                                                "ONGC",
                                                "NSE",
                                                date));
        }

        @Test
        void currentVolumeState_shouldUseMinute14Baseline() {
                LocalDate date = LocalDate.of(2026, 7, 15);

                LocalDateTime minuteTime = date.atTime(9, 29);

                when(marketMinuteSnapshotRepository
                                .findTopBySymbolAndExchangeAndTradingDateOrderByLatestTickTimeDesc(
                                                "ONGC",
                                                "NSE",
                                                date))
                                .thenReturn(Optional.of(
                                                MarketMinuteSnapshot.builder()
                                                                .symbol("ONGC")
                                                                .exchange("NSE")
                                                                .tradingDate(date)
                                                                .minuteTime(minuteTime)
                                                                .latestTickTime(
                                                                                date.atTime(
                                                                                                9,
                                                                                                29,
                                                                                                58))
                                                                .volumeTradedForDay(50_000L)
                                                                .build()));

                when(volumeTimeWindowBaselineRepository
                                .findBySymbolAndExchangeAndTradingDateAndSessionMinute(
                                                "ONGC",
                                                "NSE",
                                                date,
                                                14))
                                .thenReturn(Optional.of(
                                                VolumeTimeWindowBaseline.builder()
                                                                .symbol("ONGC")
                                                                .exchange("NSE")
                                                                .tradingDate(date)
                                                                .sessionMinute(14)
                                                                .avgCumulativeVolume20(40_000L)
                                                                .sampleDays(20)
                                                                .build()));

                when(volumeDailyBaselineRepository
                                .findBySymbolAndExchangeAndTradingDate(
                                                "ONGC",
                                                "NSE",
                                                date))
                                .thenReturn(Optional.of(
                                                VolumeDailyBaseline.builder()
                                                                .symbol("ONGC")
                                                                .exchange("NSE")
                                                                .tradingDate(date)
                                                                .avgDailyVolume20(500_000L)
                                                                .sampleDays(20)
                                                                .build()));

                when(dailyStockContextRepository
                                .findBySymbolAndExchangeAndTradingDate(
                                                "ONGC",
                                                "NSE",
                                                date))
                                .thenReturn(Optional.of(
                                                DailyStockContext.builder()
                                                                .symbol("ONGC")
                                                                .exchange("NSE")
                                                                .tradingDate(date)
                                                                .openingRangeVolume(45_000L)
                                                                .build()));

                when(marketCandleRepository
                                .findTop100BySymbolAndExchangeAndTimeframeAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
                                                "ONGC",
                                                "NSE",
                                                CandleTimeframe.ONE_MINUTE,
                                                minuteTime))
                                .thenReturn(List.of(
                                                oneMinute(
                                                                minuteTime,
                                                                120L),
                                                oneMinute(
                                                                minuteTime.minusMinutes(1),
                                                                100L),
                                                oneMinute(
                                                                minuteTime.minusMinutes(2),
                                                                80L)));

                VolumeEngineService.VolumeState state = service.currentVolumeState(
                                "ONGC",
                                "NSE",
                                date);

                assertEquals(
                                14,
                                state.sessionMinute());

                assertEquals(
                                50_000L,
                                state.currentCumulativeVolume());

                assertEquals(
                                40_000L,
                                state.baselineCumulativeVolume());

                assertEquals(
                                1.25,
                                state.volX(),
                                0.000001);

                assertEquals(
                                45_000L,
                                state.openingRangeVolume());

                assertEquals(
                                40_000L,
                                state.openingRangeBaselineVolume());

                assertEquals(
                                1.125,
                                state.openingRangeParticipationRatio(),
                                0.000001);

                assertEquals(
                                "UP",
                                state.recentVolumeDirection());
        }

        @Test
        void currentVolumeState_shouldUseExactCurrentMinuteBaseline() {
                LocalDate date = LocalDate.of(2026, 7, 15);

                LocalDateTime minuteTime = date.atTime(9, 35);

                when(marketMinuteSnapshotRepository
                                .findTopBySymbolAndExchangeAndTradingDateOrderByLatestTickTimeDesc(
                                                "ONGC",
                                                "NSE",
                                                date))
                                .thenReturn(Optional.of(
                                                MarketMinuteSnapshot.builder()
                                                                .symbol("ONGC")
                                                                .exchange("NSE")
                                                                .tradingDate(date)
                                                                .minuteTime(minuteTime)
                                                                .volumeTradedForDay(30_000L)
                                                                .build()));

                when(volumeTimeWindowBaselineRepository
                                .findBySymbolAndExchangeAndTradingDateAndSessionMinute(
                                                "ONGC",
                                                "NSE",
                                                date,
                                                20))
                                .thenReturn(Optional.of(
                                                VolumeTimeWindowBaseline.builder()
                                                                .avgCumulativeVolume20(20_000L)
                                                                .build()));

                when(volumeDailyBaselineRepository
                                .findBySymbolAndExchangeAndTradingDate(
                                                anyString(),
                                                anyString(),
                                                any(LocalDate.class)))
                                .thenReturn(Optional.empty());

                when(dailyStockContextRepository
                                .findBySymbolAndExchangeAndTradingDate(
                                                anyString(),
                                                anyString(),
                                                any(LocalDate.class)))
                                .thenReturn(Optional.empty());

                when(marketCandleRepository
                                .findTop100BySymbolAndExchangeAndTimeframeAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
                                                anyString(),
                                                anyString(),
                                                eq(CandleTimeframe.ONE_MINUTE),
                                                any(LocalDateTime.class)))
                                .thenReturn(List.of());

                VolumeEngineService.VolumeState state = service.currentVolumeState(
                                "ONGC",
                                "NSE",
                                date);

                assertEquals(
                                20,
                                state.sessionMinute());

                assertEquals(
                                1.5,
                                state.volX(),
                                0.000001);

                assertEquals(
                                "INSUFFICIENT_DATA",
                                state.recentVolumeDirection());
        }

        @Test
        void currentVolumeState_shouldReturnFlatForMixedVolumes() {
                LocalDate date = LocalDate.of(2026, 7, 15);

                LocalDateTime minuteTime = date.atTime(10, 0);

                when(marketMinuteSnapshotRepository
                                .findTopBySymbolAndExchangeAndTradingDateOrderByLatestTickTimeDesc(
                                                "ONGC",
                                                "NSE",
                                                date))
                                .thenReturn(Optional.of(
                                                MarketMinuteSnapshot.builder()
                                                                .symbol("ONGC")
                                                                .exchange("NSE")
                                                                .tradingDate(date)
                                                                .minuteTime(minuteTime)
                                                                .volumeTradedForDay(200_000L)
                                                                .build()));

                when(volumeTimeWindowBaselineRepository
                                .findBySymbolAndExchangeAndTradingDateAndSessionMinute(
                                                anyString(),
                                                anyString(),
                                                any(LocalDate.class),
                                                anyInt()))
                                .thenReturn(Optional.empty());

                when(volumeDailyBaselineRepository
                                .findBySymbolAndExchangeAndTradingDate(
                                                anyString(),
                                                anyString(),
                                                any(LocalDate.class)))
                                .thenReturn(Optional.empty());

                when(dailyStockContextRepository
                                .findBySymbolAndExchangeAndTradingDate(
                                                anyString(),
                                                anyString(),
                                                any(LocalDate.class)))
                                .thenReturn(Optional.empty());

                when(marketCandleRepository
                                .findTop100BySymbolAndExchangeAndTimeframeAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
                                                "ONGC",
                                                "NSE",
                                                CandleTimeframe.ONE_MINUTE,
                                                minuteTime))
                                .thenReturn(List.of(
                                                oneMinute(
                                                                minuteTime,
                                                                100L),
                                                oneMinute(
                                                                minuteTime.minusMinutes(1),
                                                                120L),
                                                oneMinute(
                                                                minuteTime.minusMinutes(2),
                                                                80L)));

                VolumeEngineService.VolumeState state = service.currentVolumeState(
                                "ONGC",
                                "NSE",
                                date);

                assertEquals(
                                "FLAT",
                                state.recentVolumeDirection());

                assertNull(state.volX());
                assertNull(
                                state.openingRangeParticipationRatio());
        }

        private MarketCandle oneMinute(
                        LocalDateTime candleTime,
                        long volume) {

                return MarketCandle.builder()
                                .symbol("ONGC")
                                .exchange("NSE")
                                .timeframe(
                                                CandleTimeframe.ONE_MINUTE)
                                .candleTime(candleTime)
                                .openPrice(100.0)
                                .highPrice(101.0)
                                .lowPrice(99.0)
                                .closePrice(100.5)
                                .volume(volume)
                                .isFinalized(true)
                                .qualityStatus(
                                                CandleQualityStatus.LIVE)
                                .processingStatus(
                                                CandleProcessingStatus.RELEASED)
                                .build();
        }
}
