package com.trading.scanner.service.data;

import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.CandleDirection;
import com.trading.scanner.model.CandleProcessingStatus;
import com.trading.scanner.model.CandleQualityStatus;
import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.LiveMinuteResolution;
import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.model.MinuteResolutionStatus;
import com.trading.scanner.repository.LiveMinuteResolutionRepository;
import com.trading.scanner.repository.MarketCandleRepository;
import com.trading.scanner.service.engine.DailyDataStatusService;
import com.trading.scanner.service.engine.DailyStockContextService;
import com.trading.scanner.service.engine.IntradayIndicatorService;
import com.trading.scanner.service.runtime.LiveSignalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class LiveMarketCandleServiceTest {

        private MarketCandleRepository marketCandleRepository;
        private LiveMinuteResolutionRepository liveMinuteResolutionRepository;
        private TimeProvider timeProvider;
        private LiveSignalService liveSignalService;
        private DailyStockContextService dailyStockContextService;
        private IntradayIndicatorService intradayIndicatorService;
        private DailyDataStatusService dailyDataStatusService;
        private ApplicationEventPublisher eventPublisher;
        private LiveMarketCandleService service;

        @BeforeEach
        void setUp() {
                marketCandleRepository = mock(MarketCandleRepository.class);

                liveMinuteResolutionRepository = mock(LiveMinuteResolutionRepository.class);

                timeProvider = mock(TimeProvider.class);

                liveSignalService = mock(LiveSignalService.class);

                dailyStockContextService = mock(DailyStockContextService.class);

                intradayIndicatorService = mock(IntradayIndicatorService.class);

                dailyDataStatusService = mock(DailyDataStatusService.class);

                eventPublisher = mock(ApplicationEventPublisher.class);

                service = new LiveMarketCandleService(
                                marketCandleRepository,
                                timeProvider,
                                liveSignalService,
                                dailyStockContextService,
                                intradayIndicatorService,
                                dailyDataStatusService,
                                eventPublisher,
                                liveMinuteResolutionRepository);
        }

        @Test
        void ingestTick_shouldCreateAndUpdateOpenCandleWithinSameMinute() {
                LiveMarketCandleService.IngestResult first = service.ingestTick(
                                new LiveMarketCandleService.TickInput(
                                                "ongc",
                                                "nse",
                                                LocalDateTime.of(
                                                                2026,
                                                                6,
                                                                18,
                                                                10,
                                                                15,
                                                                5),
                                                250.0,
                                                100L));

                LiveMarketCandleService.IngestResult second = service.ingestTick(
                                new LiveMarketCandleService.TickInput(
                                                "ONGC",
                                                "NSE",
                                                LocalDateTime.of(
                                                                2026,
                                                                6,
                                                                18,
                                                                10,
                                                                15,
                                                                40),
                                                252.0,
                                                150L));

                assertTrue(first.accepted());
                assertFalse(first.finalizedPreviousCandle());
                assertTrue(second.accepted());
                assertFalse(second.finalizedPreviousCandle());

                List<LiveMarketCandleService.OpenCandleView> open = service.openCandles();

                assertEquals(1, open.size());
                assertEquals("ONGC", open.get(0).symbol());
                assertEquals(250.0, open.get(0).openPrice());
                assertEquals(252.0, open.get(0).highPrice());
                assertEquals(250.0, open.get(0).lowPrice());
                assertEquals(252.0, open.get(0).closePrice());
                assertEquals(250L, open.get(0).volume());

                verify(marketCandleRepository, never())
                                .save(any(MarketCandle.class));

                verifyNoInteractions(intradayIndicatorService);
                verifyNoInteractions(dailyDataStatusService);
                verifyNoInteractions(eventPublisher);
        }

        @Test
        void ingestTick_nextMinuteShouldReleaseCandle() {
                LocalDate date = LocalDate.of(2026, 6, 18);

                LocalDateTime firstMinute = date.atTime(10, 15);

                LocalDateTime secondMinute = date.atTime(10, 16);

                when(timeProvider.nowDateTime())
                                .thenReturn(date.atTime(10, 16));

                when(marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTime(
                                                "ONGC",
                                                "NSE",
                                                CandleTimeframe.ONE_MINUTE,
                                                firstMinute))
                                .thenReturn(Optional.empty());

                /*
                 * This ascending query is used for the complete same-day
                 * ATR history. The production code now uses it separately
                 * from the capped RSI history query.
                 */
                when(marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                anyString(),
                                                anyString(),
                                                any(CandleTimeframe.class),
                                                any(LocalDateTime.class),
                                                any(LocalDateTime.class)))
                                .thenReturn(List.of(
                                                oneMinuteCandle(
                                                                firstMinute,
                                                                CandleProcessingStatus.RELEASED)));

                when(marketCandleRepository
                                .findTop100BySymbolAndExchangeAndTimeframeAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
                                                anyString(),
                                                anyString(),
                                                any(CandleTimeframe.class),
                                                any(LocalDateTime.class)))
                                .thenReturn(List.of(
                                                oneMinuteCandle(
                                                                firstMinute,
                                                                CandleProcessingStatus.RELEASED)));

                when(marketCandleRepository.save(
                                any(MarketCandle.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                when(intradayIndicatorService
                                .calculateSessionVwap(any()))
                                .thenReturn(249.75);

                when(intradayIndicatorService
                                .calculateRsi14Wilder(any()))
                                .thenReturn(null);

                when(intradayIndicatorService
                                .calculateAtr14Wilder(
                                                anyList(),
                                                any()))
                                .thenReturn(null);

                service.ingestTick(
                                new LiveMarketCandleService.TickInput(
                                                "ONGC",
                                                "NSE",
                                                firstMinute.plusSeconds(5),
                                                250.0,
                                                100L));

                LiveMarketCandleService.IngestResult result = service.ingestTick(
                                new LiveMarketCandleService.TickInput(
                                                "ONGC",
                                                "NSE",
                                                secondMinute.plusSeconds(1),
                                                251.0,
                                                50L));

                assertTrue(result.accepted());
                assertTrue(result.finalizedPreviousCandle());

                verify(dailyDataStatusService)
                                .markLive(
                                                "ONGC",
                                                "NSE",
                                                date);

                verify(dailyStockContextService)
                                .processFinalizedOneMinuteCandle(
                                                any(MarketCandle.class));

                verify(intradayIndicatorService, atLeastOnce())
                                .calculateAtr14Wilder(
                                                anyList(),
                                                any());
        }

        @Test
        void ingestTick_shouldBlockSymbolAndPublishGap() {
                LocalDateTime firstMinute = LocalDateTime.of(
                                2026,
                                8,
                                17,
                                11,
                                16);

                LocalDateTime resumedMinute = LocalDateTime.of(
                                2026,
                                8,
                                17,
                                11,
                                20);

                when(marketCandleRepository
                                .findTopBySymbolAndExchangeAndTimeframeOrderByCandleTimeDesc(
                                                "ONGC",
                                                "NSE",
                                                CandleTimeframe.ONE_MINUTE))
                                .thenReturn(Optional.empty());

                when(marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTime(
                                                "ONGC",
                                                "NSE",
                                                CandleTimeframe.ONE_MINUTE,
                                                firstMinute))
                                .thenReturn(Optional.empty());

                when(marketCandleRepository.save(
                                any(MarketCandle.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                service.ingestTick(
                                new LiveMarketCandleService.TickInput(
                                                "ONGC",
                                                "NSE",
                                                firstMinute.plusSeconds(5),
                                                250.0,
                                                100L));

                LiveMarketCandleService.IngestResult result = service.ingestTick(
                                new LiveMarketCandleService.TickInput(
                                                "ONGC",
                                                "NSE",
                                                resumedMinute.plusSeconds(5),
                                                251.0,
                                                100L));

                assertTrue(result.accepted());
                assertTrue(result.finalizedPreviousCandle());

                ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);

                verify(eventPublisher)
                                .publishEvent(captor.capture());

                assertTrue(
                                captor.getValue() instanceof IntradayGapDetectedEvent);

                IntradayGapDetectedEvent event = (IntradayGapDetectedEvent) captor.getValue();

                assertEquals("ONGC", event.symbol());
                assertEquals("NSE", event.exchange());

                assertEquals(
                                LocalDateTime.of(
                                                2026,
                                                8,
                                                17,
                                                11,
                                                17),
                                event.fromTime());

                assertEquals(
                                LocalDateTime.of(
                                                2026,
                                                8,
                                                17,
                                                11,
                                                19),
                                event.toTime());

                verify(intradayIndicatorService, never())
                                .calculateSessionVwap(any());

                verify(dailyStockContextService, never())
                                .processFinalizedOneMinuteCandle(any());

                verify(dailyDataStatusService, never())
                                .markLive(
                                                anyString(),
                                                anyString(),
                                                any(LocalDate.class));

                verify(marketCandleRepository)
                                .save(argThat(candle -> candle
                                                .getProcessingStatus() == CandleProcessingStatus.PROVISIONAL));
        }

        @Test
        void releaseRepairedRange_shouldReleasePrecedingProvisionalMinuteAndRecompute() {
                LocalDate date = LocalDate.of(2026, 8, 17);

                LocalDateTime from = date.atTime(11, 17);

                LocalDateTime to = date.atTime(11, 19);

                LocalDateTime releaseFrom = date.atTime(11, 16);

                List<MarketCandle> releaseCandles = List.of(
                                oneMinuteCandle(
                                                releaseFrom,
                                                CandleProcessingStatus.PROVISIONAL),
                                oneMinuteCandle(
                                                from,
                                                CandleProcessingStatus.PROVISIONAL),
                                oneMinuteCandle(
                                                date.atTime(11, 18),
                                                CandleProcessingStatus.PROVISIONAL),
                                oneMinuteCandle(
                                                to,
                                                CandleProcessingStatus.PROVISIONAL));

                List<MarketCandle> recomputeCandles = List.of(
                                oneMinuteCandle(
                                                releaseFrom,
                                                CandleProcessingStatus.RELEASED),
                                oneMinuteCandle(
                                                from,
                                                CandleProcessingStatus.RELEASED),
                                oneMinuteCandle(
                                                date.atTime(11, 18),
                                                CandleProcessingStatus.RELEASED),
                                oneMinuteCandle(
                                                to,
                                                CandleProcessingStatus.RELEASED));

                when(marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                anyString(),
                                                anyString(),
                                                eq(CandleTimeframe.ONE_MINUTE),
                                                any(LocalDateTime.class),
                                                any(LocalDateTime.class)))
                                .thenReturn(recomputeCandles);

                when(marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                "ONGC",
                                                "NSE",
                                                CandleTimeframe.ONE_MINUTE,
                                                releaseFrom,
                                                to))
                                .thenReturn(releaseCandles);

                when(liveMinuteResolutionRepository
                                .findBySymbolAndExchangeAndMinuteTimeBetweenOrderByMinuteTimeAsc(
                                                anyString(),
                                                anyString(),
                                                any(LocalDateTime.class),
                                                any(LocalDateTime.class)))
                                .thenReturn(List.of());

                when(marketCandleRepository
                                .findTop100BySymbolAndExchangeAndTimeframeAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
                                                anyString(),
                                                anyString(),
                                                any(CandleTimeframe.class),
                                                any(LocalDateTime.class)))
                                .thenReturn(List.of(
                                                oneMinuteCandle(
                                                                releaseFrom,
                                                                CandleProcessingStatus.RELEASED)));

                when(marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTime(
                                                anyString(),
                                                anyString(),
                                                any(CandleTimeframe.class),
                                                any(LocalDateTime.class)))
                                .thenReturn(Optional.empty());

                when(timeProvider.nowDateTime())
                                .thenReturn(date.atTime(11, 20));

                when(marketCandleRepository.save(
                                any(MarketCandle.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                when(intradayIndicatorService
                                .calculateSessionVwap(any()))
                                .thenReturn(100.0);

                when(intradayIndicatorService
                                .calculateRsi14Wilder(any()))
                                .thenReturn(50.0);

                when(intradayIndicatorService
                                .calculateAtr14Wilder(
                                                anyList(),
                                                any()))
                                .thenReturn(1.0);

                boolean released = service.releaseRepairedRange(
                                "ONGC",
                                "NSE",
                                from,
                                to);

                assertTrue(released);

                assertTrue(
                                releaseCandles.stream()
                                                .allMatch(candle -> candle
                                                                .getProcessingStatus() == CandleProcessingStatus.RELEASED));

                verify(intradayIndicatorService, atLeastOnce())
                                .calculateSessionVwap(any());

                verify(intradayIndicatorService, atLeastOnce())
                                .calculateRsi14Wilder(any());

                verify(intradayIndicatorService, atLeastOnce())
                                .calculateAtr14Wilder(
                                                anyList(),
                                                any());

                verify(liveSignalService, never())
                                .processFinalizedDerivedCandle(any());
        }

        @Test
        void releaseRepairedRange_shouldAcceptConfirmedNoTradeMinute() {
                LocalDate date = LocalDate.of(2026, 8, 17);

                LocalDateTime from = date.atTime(11, 17);

                LocalDateTime to = date.atTime(11, 19);

                LocalDateTime releaseFrom = date.atTime(11, 16);

                List<MarketCandle> releaseCandles = List.of(
                                oneMinuteCandle(
                                                releaseFrom,
                                                CandleProcessingStatus.PROVISIONAL),
                                oneMinuteCandle(
                                                from,
                                                CandleProcessingStatus.PROVISIONAL),
                                oneMinuteCandle(
                                                to,
                                                CandleProcessingStatus.PROVISIONAL));

                List<MarketCandle> bucketCandles = List.of(
                                oneMinuteCandle(
                                                date.atTime(11, 15),
                                                CandleProcessingStatus.RELEASED),
                                oneMinuteCandle(
                                                releaseFrom,
                                                CandleProcessingStatus.RELEASED),
                                oneMinuteCandle(
                                                from,
                                                CandleProcessingStatus.RELEASED),
                                oneMinuteCandle(
                                                to,
                                                CandleProcessingStatus.RELEASED));

                LiveMinuteResolution noTrade = LiveMinuteResolution.builder()
                                .symbol("ONGC")
                                .exchange("NSE")
                                .tradingDate(date)
                                .minuteTime(date.atTime(11, 18))
                                .status(
                                                MinuteResolutionStatus.NO_TRADE_CONFIRMED)
                                .build();

                when(marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                anyString(),
                                                anyString(),
                                                eq(CandleTimeframe.ONE_MINUTE),
                                                any(LocalDateTime.class),
                                                any(LocalDateTime.class)))
                                .thenReturn(bucketCandles);

                when(marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                "ONGC",
                                                "NSE",
                                                CandleTimeframe.ONE_MINUTE,
                                                releaseFrom,
                                                to))
                                .thenReturn(releaseCandles);

                when(liveMinuteResolutionRepository
                                .findBySymbolAndExchangeAndMinuteTimeBetweenOrderByMinuteTimeAsc(
                                                anyString(),
                                                anyString(),
                                                any(LocalDateTime.class),
                                                any(LocalDateTime.class)))
                                .thenReturn(List.of(noTrade));

                when(marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTime(
                                                anyString(),
                                                anyString(),
                                                any(CandleTimeframe.class),
                                                any(LocalDateTime.class)))
                                .thenReturn(Optional.empty());

                when(marketCandleRepository
                                .findTop100BySymbolAndExchangeAndTimeframeAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
                                                anyString(),
                                                anyString(),
                                                any(CandleTimeframe.class),
                                                any(LocalDateTime.class)))
                                .thenReturn(List.of(
                                                oneMinuteCandle(
                                                                releaseFrom,
                                                                CandleProcessingStatus.RELEASED)));

                when(timeProvider.nowDateTime())
                                .thenReturn(date.atTime(11, 20));

                when(marketCandleRepository.save(
                                any(MarketCandle.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                when(intradayIndicatorService
                                .calculateSessionVwap(any()))
                                .thenReturn(100.0);

                when(intradayIndicatorService
                                .calculateRsi14Wilder(any()))
                                .thenReturn(50.0);

                when(intradayIndicatorService
                                .calculateAtr14Wilder(
                                                anyList(),
                                                any()))
                                .thenReturn(1.0);

                boolean released = service.releaseRepairedRange(
                                "ONGC",
                                "NSE",
                                from,
                                to);

                assertTrue(released);

                assertTrue(
                                releaseCandles.stream()
                                                .allMatch(candle -> candle
                                                                .getProcessingStatus() == CandleProcessingStatus.RELEASED));

                verify(intradayIndicatorService, atLeastOnce())
                                .calculateSessionVwap(any());

                verify(intradayIndicatorService, atLeastOnce())
                                .calculateAtr14Wilder(
                                                anyList(),
                                                any());
        }

        @Test
        void releaseRepairedRange_shouldRejectUnresolvedMinute() {
                LocalDate date = LocalDate.of(2026, 8, 17);

                LocalDateTime from = date.atTime(11, 17);

                LocalDateTime to = date.atTime(11, 19);

                LocalDateTime releaseFrom = date.atTime(11, 16);

                when(marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                "ONGC",
                                                "NSE",
                                                CandleTimeframe.ONE_MINUTE,
                                                releaseFrom,
                                                to))
                                .thenReturn(List.of());

                when(liveMinuteResolutionRepository
                                .findBySymbolAndExchangeAndMinuteTimeBetweenOrderByMinuteTimeAsc(
                                                "ONGC",
                                                "NSE",
                                                releaseFrom,
                                                to))
                                .thenReturn(List.of());

                assertFalse(
                                service.releaseRepairedRange(
                                                "ONGC",
                                                "NSE",
                                                from,
                                                to));

                verify(marketCandleRepository, never())
                                .save(any(MarketCandle.class));
        }

        @Test
        void ingestTick_shouldIgnoreOutOfOrderTick() {
                LocalDateTime firstMinute = LocalDateTime.of(
                                2026,
                                6,
                                18,
                                10,
                                15);

                LocalDateTime olderMinute = LocalDateTime.of(
                                2026,
                                6,
                                18,
                                10,
                                14);

                service.ingestTick(
                                new LiveMarketCandleService.TickInput(
                                                "ONGC",
                                                "NSE",
                                                firstMinute.plusSeconds(5),
                                                250.0,
                                                100L));

                LiveMarketCandleService.IngestResult result = service.ingestTick(
                                new LiveMarketCandleService.TickInput(
                                                "ONGC",
                                                "NSE",
                                                olderMinute.plusSeconds(5),
                                                249.0,
                                                50L));

                assertFalse(result.accepted());
                assertFalse(result.finalizedPreviousCandle());
                assertEquals(
                                firstMinute,
                                result.candleTime());
        }

        @Test
        void recomputeDerivedData_shouldBeCallableForHistoricalRepair() {
                LocalDate date = LocalDate.of(2026, 8, 17);

                when(marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                "ONGC",
                                                "NSE",
                                                CandleTimeframe.ONE_MINUTE,
                                                date.atTime(9, 15),
                                                date.atTime(15, 29)))
                                .thenReturn(List.of());

                service.recomputeDerivedData(
                                "ongc",
                                "nse",
                                date);

                verify(marketCandleRepository)
                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                "ONGC",
                                                "NSE",
                                                CandleTimeframe.ONE_MINUTE,
                                                date.atTime(9, 15),
                                                date.atTime(15, 29));
        }

        @Test
        void rolloverCompletedMinutes_shouldFinalizeOpenCandleWhenPastMinute() {
                LocalDateTime tickTime = LocalDateTime.of(2026, 9, 23, 9, 15, 20);
                LocalDateTime rolloverClockTime = LocalDateTime.of(2026, 9, 23, 9, 16, 5);

                when(timeProvider.nowDateTime()).thenReturn(tickTime);

                service.ingestTick(new LiveMarketCandleService.TickInput(
                                "RELIANCE",
                                "NSE",
                                tickTime,
                                2500.0,
                                100L));

                assertEquals(1, service.openCandles().size());

                when(timeProvider.nowDateTime()).thenReturn(rolloverClockTime);
                when(marketCandleRepository.save(any(MarketCandle.class))).thenAnswer(inv -> inv.getArgument(0));

                LiveMarketCandleService.RolloverResult result = service.rolloverCompletedMinutes();

                assertEquals(1, result.rolledOver());
                assertEquals(0, service.openCandles().size());
                verify(marketCandleRepository, atLeastOnce())
                                .save(argThat(candle -> "RELIANCE".equals(candle.getSymbol())
                                                && LocalDateTime.of(2026, 9, 23, 9, 15, 0)
                                                                .equals(candle.getCandleTime())
                                                && Boolean.TRUE.equals(candle.getIsFinalized())));
        }

        @Test
        void taintRangeAsProvisional_shouldMarkCandlesProvisionalAndBlockSymbol() {
                LocalDateTime from = LocalDateTime.of(2026, 9, 23, 9, 15, 0);
                LocalDateTime to = LocalDateTime.of(2026, 9, 23, 9, 20, 0);
                MarketCandle candle = oneMinuteCandle(from, CandleProcessingStatus.RELEASED);

                when(timeProvider.nowDateTime()).thenReturn(LocalDateTime.of(2026, 9, 23, 9, 21, 0));
                when(marketCandleRepository.findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                eq("ONGC"), eq("NSE"), eq(CandleTimeframe.ONE_MINUTE), eq(from), eq(to)))
                                .thenReturn(List.of(candle));

                service.taintRangeAsProvisional("ONGC", "NSE", from, to);

                assertEquals(CandleProcessingStatus.PROVISIONAL, candle.getProcessingStatus());
                verify(marketCandleRepository).save(candle);
        }

        @Test
        void applyCandleStructure_shouldHandleZeroRangeAndNonPositivePrices() {
                MarketCandle zeroRange = oneMinuteCandle(LocalDateTime.of(2026, 9, 23, 9, 15),
                                CandleProcessingStatus.RELEASED);
                zeroRange.setHighPrice(100.0);
                zeroRange.setLowPrice(100.0);
                zeroRange.setOpenPrice(100.0);
                zeroRange.setClosePrice(100.0);
                LiveMarketCandleService.applyCandleStructure(zeroRange);

                assertNull(zeroRange.getBodyRatio());
                assertNull(zeroRange.getUpperWickRatio());
                assertNull(zeroRange.getLowerWickRatio());
                assertNull(zeroRange.getRangePct());
                assertFalse(zeroRange.getStrongBullish());
                assertFalse(zeroRange.getStrongBearish());

                MarketCandle nonPositiveLow = oneMinuteCandle(LocalDateTime.of(2026, 9, 23, 9, 16),
                                CandleProcessingStatus.RELEASED);
                nonPositiveLow.setHighPrice(100.0);
                nonPositiveLow.setLowPrice(0.0);
                nonPositiveLow.setOpenPrice(50.0);
                nonPositiveLow.setClosePrice(80.0);
                LiveMarketCandleService.applyCandleStructure(nonPositiveLow);

                assertNull(nonPositiveLow.getBodyRatio());
                assertFalse(nonPositiveLow.getStrongBullish());
        }

        @Test
        void applyCandleStructure_shouldClassifyStrongBullishCandle() {
                MarketCandle bullish = oneMinuteCandle(LocalDateTime.of(2026, 9, 23, 9, 17),
                                CandleProcessingStatus.RELEASED);
                bullish.setOpenPrice(100.0);
                bullish.setLowPrice(99.9);
                bullish.setHighPrice(101.0);
                bullish.setClosePrice(100.9);
                LiveMarketCandleService.applyCandleStructure(bullish);

                assertTrue(bullish.getStrongBullish());
                assertFalse(bullish.getStrongBearish());
                assertEquals(CandleDirection.BULLISH, bullish.getDirection());
        }

        @Test
        void ingestTick_shouldComputeMinuteVolumeFromCumulativeDayVolumeDelta() {
                LocalDateTime tick1Time = LocalDateTime.of(2026, 9, 23, 9, 15, 5);
                LocalDateTime tick2Time = LocalDateTime.of(2026, 9, 23, 9, 15, 30);
                LocalDateTime nextMinuteTick = LocalDateTime.of(2026, 9, 23, 9, 16, 5);

                when(marketCandleRepository.findBySymbolAndExchangeAndTimeframeAndCandleTime(anyString(), anyString(),
                                any(), any()))
                                .thenReturn(Optional.empty());
                when(marketCandleRepository.save(any(MarketCandle.class)))
                                .thenAnswer(inv -> inv.getArgument(0));

                service.ingestTick(
                                new LiveMarketCandleService.TickInput("INFY", "NSE", tick1Time, 1500.0, 10L, 50000L));
                service.ingestTick(
                                new LiveMarketCandleService.TickInput("INFY", "NSE", tick2Time, 1502.0, 20L, 51250L));

                // Finalize 09:15 candle by sending tick for 09:16
                service.ingestTick(new LiveMarketCandleService.TickInput("INFY", "NSE", nextMinuteTick, 1503.0, 5L,
                                51300L));

                ArgumentCaptor<MarketCandle> captor = ArgumentCaptor.forClass(MarketCandle.class);
                verify(marketCandleRepository, atLeastOnce()).save(captor.capture());

                MarketCandle saved0915 = captor.getAllValues().stream()
                                .filter(c -> c.getCandleTime().equals(LocalDateTime.of(2026, 9, 23, 9, 15)))
                                .findFirst()
                                .orElseThrow();

                assertEquals(1250L, saved0915.getVolume()); // 51250 - 50000 = 1250
        }

        @Test
        void ingestTick_shouldFallbackToIncrementalQuantityWhenCumulativeDayVolumeIsNull() {
                LocalDateTime tick1Time = LocalDateTime.of(2026, 9, 23, 9, 15, 5);
                LocalDateTime tick2Time = LocalDateTime.of(2026, 9, 23, 9, 15, 30);
                LocalDateTime nextMinuteTick = LocalDateTime.of(2026, 9, 23, 9, 16, 5);

                when(marketCandleRepository.findBySymbolAndExchangeAndTimeframeAndCandleTime(anyString(), anyString(),
                                any(), any()))
                                .thenReturn(Optional.empty());
                when(marketCandleRepository.save(any(MarketCandle.class)))
                                .thenAnswer(inv -> inv.getArgument(0));

                service.ingestTick(new LiveMarketCandleService.TickInput("INFY", "NSE", tick1Time, 1500.0, 15L, null));
                service.ingestTick(new LiveMarketCandleService.TickInput("INFY", "NSE", tick2Time, 1502.0, 35L, null));

                // Finalize 09:15 candle by sending tick for 09:16
                service.ingestTick(
                                new LiveMarketCandleService.TickInput("INFY", "NSE", nextMinuteTick, 1503.0, 5L, null));

                ArgumentCaptor<MarketCandle> captor = ArgumentCaptor.forClass(MarketCandle.class);
                verify(marketCandleRepository, atLeastOnce()).save(captor.capture());

                MarketCandle saved0915 = captor.getAllValues().stream()
                                .filter(c -> c.getCandleTime().equals(LocalDateTime.of(2026, 9, 23, 9, 15)))
                                .findFirst()
                                .orElseThrow();

                assertEquals(50L, saved0915.getVolume()); // 15 + 35 = 50
        }

        @Test
        void ingestTick_shouldIgnorePreMarketTicksPriorTo0908Uncrossing() {
                LocalDateTime earlyTick = LocalDateTime.of(2026, 9, 23, 8, 55, 30);
                LiveMarketCandleService.IngestResult result = service.ingestTick(
                                new LiveMarketCandleService.TickInput("INFY", "NSE", earlyTick, 1500.0, 10L, 0L));

                assertFalse(result.accepted());
                assertEquals("Ignored pre-market tick prior to 09:08 uncrossing", result.message());
                assertEquals(0, service.openCandles().size());
        }

        @Test
        void ingestTick_0908PreMarketCandleShouldHaveIdenticalOHLCEqualToUncrossedPrice() {
                when(marketCandleRepository.save(any(MarketCandle.class))).thenAnswer(inv -> inv.getArgument(0));
                when(timeProvider.nowDateTime()).thenReturn(LocalDateTime.of(2026, 9, 23, 9, 15, 0));

                LocalDateTime tick1 = LocalDateTime.of(2026, 9, 23, 9, 8, 10);
                LocalDateTime tick2 = LocalDateTime.of(2026, 9, 23, 9, 8, 45); // Uncrossed price = 1520.0
                LocalDateTime tick0915 = LocalDateTime.of(2026, 9, 23, 9, 15, 5);

                service.ingestTick(new LiveMarketCandleService.TickInput("INFY", "NSE", tick1, 1515.0, 100L, 100L));
                service.ingestTick(new LiveMarketCandleService.TickInput("INFY", "NSE", tick2, 1520.0, 200L, 300L));

                // Finalize 09:08 candle by sending 09:15 continuous session tick
                service.ingestTick(new LiveMarketCandleService.TickInput("INFY", "NSE", tick0915, 1522.0, 50L, 350L));

                ArgumentCaptor<MarketCandle> captor = ArgumentCaptor.forClass(MarketCandle.class);
                verify(marketCandleRepository, atLeastOnce()).save(captor.capture());

                MarketCandle saved0908 = captor.getAllValues().stream()
                                .filter(c -> c.getCandleTime().equals(LocalDateTime.of(2026, 9, 23, 9, 8)))
                                .findFirst()
                                .orElseThrow();

                // For 09:08 pre-market candle, all OHLC must equal the final uncrossed price
                // (1520.0)
                assertEquals(1520.0, saved0908.getOpenPrice());
                assertEquals(1520.0, saved0908.getHighPrice());
                assertEquals(1520.0, saved0908.getLowPrice());
                assertEquals(1520.0, saved0908.getClosePrice());
        }

        private MarketCandle oneMinuteCandle(
                        LocalDateTime time,
                        CandleProcessingStatus processingStatus) {

                return MarketCandle.builder()
                                .symbol("ONGC")
                                .exchange("NSE")
                                .timeframe(
                                                CandleTimeframe.ONE_MINUTE)
                                .candleTime(time)
                                .openPrice(100.0)
                                .highPrice(101.0)
                                .lowPrice(99.0)
                                .closePrice(100.5)
                                .volume(100L)
                                .source("TEST")
                                .isFinalized(true)
                                .qualityStatus(
                                                CandleQualityStatus.LIVE)
                                .processingStatus(processingStatus)
                                .build();
        }
}
