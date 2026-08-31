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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class VolumeBaselineServiceTest {

        private DailyStockContextRepository dailyStockContextRepository;
        private StockPriceRepository stockPriceRepository;
        private MarketCandleRepository marketCandleRepository;
        private VolumeDailyBaselineRepository volumeDailyBaselineRepository;
        private VolumeTimeWindowBaselineRepository volumeTimeWindowBaselineRepository;
        private TradingCalendar tradingCalendar;
        private TimeProvider timeProvider;
        private VolumeBaselineService service;

        @BeforeEach
        void setUp() {
                dailyStockContextRepository = mock(DailyStockContextRepository.class);

                stockPriceRepository = mock(StockPriceRepository.class);

                marketCandleRepository = mock(MarketCandleRepository.class);

                volumeDailyBaselineRepository = mock(VolumeDailyBaselineRepository.class);

                volumeTimeWindowBaselineRepository = mock(VolumeTimeWindowBaselineRepository.class);

                tradingCalendar = mock(TradingCalendar.class);

                timeProvider = mock(TimeProvider.class);

                service = new VolumeBaselineService(
                                dailyStockContextRepository,
                                stockPriceRepository,
                                marketCandleRepository,
                                volumeDailyBaselineRepository,
                                volumeTimeWindowBaselineRepository,
                                tradingCalendar,
                                timeProvider);
        }

        @Test
        void preCalculate_shouldPersistEverySessionMinute() {
                LocalDate completedDate = LocalDate.of(2026, 7, 14);

                LocalDate effectiveDate = LocalDate.of(2026, 7, 15);

                LocalDateTime computedAt = completedDate.atTime(16, 5);

                List<LocalDate> historyDates = List.of(
                                LocalDate.of(2026, 7, 10),
                                LocalDate.of(2026, 7, 11),
                                completedDate);

                when(tradingCalendar.isTradingDay(
                                completedDate))
                                .thenReturn(true);

                when(tradingCalendar.nextTradingDay(
                                completedDate))
                                .thenReturn(effectiveDate);

                when(timeProvider.nowDateTime())
                                .thenReturn(computedAt);

                when(dailyStockContextRepository
                                .findByTradingDateOrderBySymbolAsc(
                                                completedDate))
                                .thenReturn(List.of(
                                                DailyStockContext.builder()
                                                                .symbol("ONGC")
                                                                .exchange("NSE")
                                                                .tradingDate(completedDate)
                                                                .build()));

                when(stockPriceRepository
                                .findBySymbolAndDateLessThanEqualOrderByDateAsc(
                                                "ONGC",
                                                completedDate))
                                .thenReturn(List.of(
                                                stockPrice(
                                                                "ONGC",
                                                                historyDates.get(0),
                                                                1000),
                                                stockPrice(
                                                                "ONGC",
                                                                historyDates.get(1),
                                                                2000),
                                                stockPrice(
                                                                "ONGC",
                                                                historyDates.get(2),
                                                                3000)));

                when(volumeDailyBaselineRepository
                                .findBySymbolAndExchangeAndTradingDate(
                                                "ONGC",
                                                "NSE",
                                                effectiveDate))
                                .thenReturn(Optional.empty());

                when(marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                "ONGC",
                                                "NSE",
                                                CandleTimeframe.ONE_MINUTE,
                                                historyDates.get(0).atTime(9, 15),
                                                completedDate
                                                                .plusDays(1)
                                                                .atStartOfDay()
                                                                .minusNanos(1)))
                                .thenReturn(fullHistoricalCandles(historyDates));

                when(volumeTimeWindowBaselineRepository
                                .findBySymbolAndExchangeAndTradingDateAndSessionMinute(
                                                eq("ONGC"),
                                                eq("NSE"),
                                                eq(effectiveDate),
                                                anyInt()))
                                .thenReturn(Optional.empty());

                VolumeBaselineService.PreCalculationResult result = service.preCalculateForCompletedTradingDay(
                                completedDate);

                assertEquals(
                                1,
                                result.processedSymbols());

                assertEquals(
                                1,
                                result.upsertedDailyBaselines());

                assertEquals(
                                375,
                                result.upsertedTimeWindowBaselines());

                ArgumentCaptor<VolumeTimeWindowBaseline> captor = ArgumentCaptor.forClass(
                                VolumeTimeWindowBaseline.class);

                verify(volumeTimeWindowBaselineRepository, times(375))
                                .save(captor.capture());

                List<VolumeTimeWindowBaseline> saved = captor.getAllValues();

                Set<Integer> minutes = saved.stream()
                                .map(VolumeTimeWindowBaseline::getSessionMinute)
                                .collect(java.util.stream.Collectors.toSet());

                assertEquals(375, minutes.size());

                for (int minute = 0; minute < 375; minute++) {
                        assertTrue(minutes.contains(minute));
                }

                VolumeTimeWindowBaseline minute14 = saved.stream()
                                .filter(row -> row.getSessionMinute() == 14)
                                .findFirst()
                                .orElseThrow();

                assertEquals(
                                3000L,
                                minute14.getAvgCumulativeVolume20());

                assertEquals(
                                3,
                                minute14.getSampleDays());

                VolumeTimeWindowBaseline minute30 = saved.stream()
                                .filter(row -> row.getSessionMinute() == 30)
                                .findFirst()
                                .orElseThrow();

                assertEquals(
                                6200L,
                                minute30.getAvgCumulativeVolume20());

                VolumeTimeWindowBaseline minute374 = saved.stream()
                                .filter(row -> row.getSessionMinute() == 374)
                                .findFirst()
                                .orElseThrow();

                assertEquals(
                                75000L,
                                minute374.getAvgCumulativeVolume20());
        }

        @Test
        void sessionMinute_shouldReturnEveryTradingMinute() {
                assertEquals(
                                0,
                                service.sessionMinute(
                                                LocalDateTime.of(
                                                                2026,
                                                                7,
                                                                15,
                                                                9,
                                                                15)));

                assertEquals(
                                14,
                                service.sessionMinute(
                                                LocalDateTime.of(
                                                                2026,
                                                                7,
                                                                15,
                                                                9,
                                                                29)));

                assertEquals(
                                20,
                                service.sessionMinute(
                                                LocalDateTime.of(
                                                                2026,
                                                                7,
                                                                15,
                                                                9,
                                                                35)));

                assertEquals(
                                29,
                                service.sessionMinute(
                                                LocalDateTime.of(
                                                                2026,
                                                                7,
                                                                15,
                                                                9,
                                                                44)));

                assertEquals(
                                30,
                                service.sessionMinute(
                                                LocalDateTime.of(
                                                                2026,
                                                                7,
                                                                15,
                                                                9,
                                                                45)));

                assertEquals(
                                374,
                                service.sessionMinute(
                                                LocalDateTime.of(
                                                                2026,
                                                                7,
                                                                15,
                                                                15,
                                                                29)));

                assertNull(
                                service.sessionMinute(
                                                LocalDateTime.of(
                                                                2026,
                                                                7,
                                                                15,
                                                                9,
                                                                14)));

                assertNull(
                                service.sessionMinute(
                                                LocalDateTime.of(
                                                                2026,
                                                                7,
                                                                15,
                                                                15,
                                                                30)));
        }

        @Test
        void windowEndpointSessionMinute_shouldReturnCurrentSessionMinute() {
                assertEquals(
                                0,
                                service.windowEndpointSessionMinute(
                                                LocalDateTime.of(
                                                                2026,
                                                                7,
                                                                15,
                                                                9,
                                                                15)));

                assertEquals(
                                14,
                                service.windowEndpointSessionMinute(
                                                LocalDateTime.of(
                                                                2026,
                                                                7,
                                                                15,
                                                                9,
                                                                29)));

                assertEquals(
                                20,
                                service.windowEndpointSessionMinute(
                                                LocalDateTime.of(
                                                                2026,
                                                                7,
                                                                15,
                                                                9,
                                                                35)));

                assertEquals(
                                31,
                                service.windowEndpointSessionMinute(
                                                LocalDateTime.of(
                                                                2026,
                                                                7,
                                                                15,
                                                                9,
                                                                46)));
        }

        @Test
        void calculateCurrentVolumeMetrics_shouldUseMinute14Baseline() {
                LocalDate date = LocalDate.of(2026, 7, 15);

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

                VolumeBaselineService.VolumeMetrics metrics = service.calculateCurrentVolumeMetrics(
                                "ONGC",
                                "NSE",
                                date,
                                date.atTime(9, 29),
                                50_000L);

                assertEquals(
                                14,
                                metrics.sessionMinute());

                assertEquals(
                                40_000L,
                                metrics.averageCumulativeVolumeAtCurrentTime());

                assertEquals(
                                1.25,
                                metrics.volX(),
                                0.000001);
        }

        @Test
        void calculateCurrentVolumeMetrics_shouldUseMinuteZeroAtMarketOpen() {
                LocalDate date = LocalDate.of(2026, 7, 15);

                when(volumeTimeWindowBaselineRepository
                                .findBySymbolAndExchangeAndTradingDateAndSessionMinute(
                                                "ONGC",
                                                "NSE",
                                                date,
                                                0))
                                .thenReturn(Optional.of(
                                                VolumeTimeWindowBaseline.builder()
                                                                .symbol("ONGC")
                                                                .exchange("NSE")
                                                                .tradingDate(date)
                                                                .sessionMinute(0)
                                                                .avgCumulativeVolume20(10_000L)
                                                                .sampleDays(20)
                                                                .build()));

                VolumeBaselineService.VolumeMetrics metrics = service.calculateCurrentVolumeMetrics(
                                "ONGC",
                                "NSE",
                                date,
                                date.atTime(9, 15),
                                12_000L);

                assertEquals(
                                0,
                                metrics.sessionMinute());

                assertEquals(
                                1.2,
                                metrics.volX(),
                                0.000001);
        }

        @Test
        void calculateCurrentVolumeMetrics_shouldReturnNullOutsideSession() {
                LocalDate date = LocalDate.of(2026, 7, 15);

                VolumeBaselineService.VolumeMetrics metrics = service.calculateCurrentVolumeMetrics(
                                "ONGC",
                                "NSE",
                                date,
                                date.atTime(9, 14),
                                50_000L);

                assertNull(metrics.sessionMinute());
                assertNull(metrics.volX());

                verifyNoInteractions(
                                volumeTimeWindowBaselineRepository);
        }

        @Test
        void calculateCurrentVolumeMetrics_shouldReturnNullWithoutBaseline() {
                LocalDate date = LocalDate.of(2026, 7, 15);

                when(volumeTimeWindowBaselineRepository
                                .findBySymbolAndExchangeAndTradingDateAndSessionMinute(
                                                "ONGC",
                                                "NSE",
                                                date,
                                                20))
                                .thenReturn(Optional.empty());

                VolumeBaselineService.VolumeMetrics metrics = service.calculateCurrentVolumeMetrics(
                                "ONGC",
                                "NSE",
                                date,
                                date.atTime(9, 35),
                                50_000L);

                assertEquals(
                                20,
                                metrics.sessionMinute());

                assertNull(
                                metrics.averageCumulativeVolumeAtCurrentTime());

                assertNull(metrics.volX());
        }

        private List<MarketCandle> fullHistoricalCandles(
                        List<LocalDate> dates) {

                List<MarketCandle> candles = new ArrayList<>();

                long[] volumes = {
                                100L,
                                200L,
                                300L
                };

                for (int dayIndex = 0; dayIndex < dates.size(); dayIndex++) {

                        for (int minute = 0; minute < 375; minute++) {

                                candles.add(
                                                candle(
                                                                dates.get(dayIndex),
                                                                minute,
                                                                volumes[dayIndex]));
                        }
                }

                return candles;
        }

        private StockPrice stockPrice(
                        String symbol,
                        LocalDate date,
                        int volume) {

                return StockPrice.builder()
                                .symbol(symbol)
                                .date(date)
                                .volume(volume)
                                .build();
        }

        private MarketCandle candle(
                        LocalDate date,
                        int sessionMinute,
                        long volume) {

                return MarketCandle.builder()
                                .symbol("ONGC")
                                .exchange("NSE")
                                .timeframe(CandleTimeframe.ONE_MINUTE)
                                .candleTime(
                                                date.atTime(9, 15)
                                                                .plusMinutes(sessionMinute))
                                .openPrice(100.0)
                                .highPrice(101.0)
                                .lowPrice(99.0)
                                .closePrice(100.0)
                                .volume(volume)
                                .source("LIVE_WEBSOCKET")
                                .isFinalized(true)
                                .qualityStatus(CandleQualityStatus.LIVE)
                                .processingStatus(
                                                CandleProcessingStatus.RELEASED)
                                .build();
        }
}
