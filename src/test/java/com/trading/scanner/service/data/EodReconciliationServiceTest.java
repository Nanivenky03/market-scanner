package com.trading.scanner.service.data;

import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.CandleProcessingStatus;
import com.trading.scanner.model.CandleQualityStatus;
import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.DataStatus;
import com.trading.scanner.model.LiveMinuteResolution;
import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.model.MinuteResolutionStatus;
import com.trading.scanner.model.StockUniverse;
import com.trading.scanner.repository.LiveMinuteResolutionRepository;
import com.trading.scanner.repository.MarketCandleRepository;
import com.trading.scanner.repository.StockUniverseRepository;
import com.trading.scanner.service.engine.DailyDataStatusService;
import com.trading.scanner.service.provider.angelone.AngelOneMarketDataProvider;
import com.trading.scanner.service.provider.angelone.AngelOneWebSocketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class EodReconciliationServiceTest {

        private AngelOneMarketDataProvider marketDataProvider;
        private MarketCandleRepository marketCandleRepository;
        private LiveMinuteResolutionRepository resolutionRepository;
        private DailyDataStatusService dailyDataStatusService;
        private StockUniverseRepository stockUniverseRepository;
        private TradingCalendar tradingCalendar;
        private TimeProvider timeProvider;
        private EodDataEntryService eodDataEntryService;
        private EodReconciliationService service;

        @BeforeEach
        void setUp() {
                marketDataProvider = mock(AngelOneMarketDataProvider.class);
                marketCandleRepository = mock(MarketCandleRepository.class);
                resolutionRepository = mock(LiveMinuteResolutionRepository.class);
                dailyDataStatusService = mock(DailyDataStatusService.class);
                stockUniverseRepository = mock(StockUniverseRepository.class);
                tradingCalendar = mock(TradingCalendar.class);
                timeProvider = mock(TimeProvider.class);
                eodDataEntryService = mock(EodDataEntryService.class);

                service = new EodReconciliationService(
                                marketDataProvider,
                                marketCandleRepository,
                                resolutionRepository,
                                dailyDataStatusService,
                                stockUniverseRepository,
                                tradingCalendar,
                                timeProvider,
                                eodDataEntryService);
        }

        @Test
        void reconcile_shouldMarkMatchingCandlesReconciled() {
                LocalDate date = LocalDate.of(2026, 8, 24);
                LocalDateTime first = date.atTime(9, 15);

                MarketCandle provider = candle(
                                first,
                                100.0,
                                101.0,
                                99.0,
                                100.5,
                                100L,
                                CandleQualityStatus.LIVE,
                                CandleProcessingStatus.RELEASED);

                MarketCandle local = candle(
                                first,
                                100.0,
                                101.0,
                                99.0,
                                100.5,
                                100L,
                                CandleQualityStatus.LIVE,
                                CandleProcessingStatus.RELEASED);

                stubThreeMinuteDay(date);

                when(marketDataProvider.fetchHistoricalOneMinuteCandles(
                                "ONGC", date, date))
                                .thenReturn(List.of(
                                                provider,
                                                candleAt(first.plusMinutes(1)),
                                                candleAt(first.plusMinutes(2))));

                when(marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                "ONGC",
                                                "NSE",
                                                CandleTimeframe.ONE_MINUTE,
                                                date.atTime(9, 15),
                                                date.atTime(15, 29)))
                                .thenReturn(List.of(
                                                local,
                                                candleAt(first.plusMinutes(1)),
                                                candleAt(first.plusMinutes(2))));

                when(resolutionRepository
                                .findBySymbolAndExchangeAndMinuteTimeBetweenOrderByMinuteTimeAsc(
                                                anyString(),
                                                anyString(),
                                                any(),
                                                any()))
                                .thenReturn(List.of());

                when(dailyDataStatusService.checkCompleteness(
                                "ONGC", "NSE", date))
                                .thenReturn(completeResult(date));

                when(timeProvider.nowDateTime())
                                .thenReturn(date.atTime(16, 15));

                EodReconciliationService.ReconciliationResult result = service.reconcile("ONGC", "NSE", date);

                assertEquals(DataStatus.RECONCILED, result.status());
                assertEquals(3, result.matchedCandles());
                assertEquals(0, result.repairedCandles());

                verify(dailyDataStatusService).markStatus(
                                eq("ONGC"),
                                eq("NSE"),
                                eq(date),
                                eq(DataStatus.RECONCILED),
                                eq(3),
                                eq(3),
                                eq(0),
                                contains("EOD reconciliation verified all candles"));

                assertEquals(
                                CandleQualityStatus.RECONCILED,
                                local.getQualityStatus());
        }

        @Test
        void reconcile_shouldRepairMissingAndMismatchedCandles() {
                LocalDate date = LocalDate.of(2026, 8, 24);
                LocalDateTime first = date.atTime(9, 15);

                MarketCandle providerFirst = candle(
                                first,
                                100.0,
                                101.0,
                                99.0,
                                100.5,
                                100L,
                                CandleQualityStatus.LIVE,
                                CandleProcessingStatus.RELEASED);

                MarketCandle providerSecond = candleAt(first.plusMinutes(1));

                MarketCandle providerThird = candleAt(first.plusMinutes(2));

                MarketCandle mismatchedLocal = candle(
                                first,
                                90.0,
                                91.0,
                                89.0,
                                90.5,
                                50L,
                                CandleQualityStatus.LIVE,
                                CandleProcessingStatus.RELEASED);

                stubThreeMinuteDay(date);

                when(marketDataProvider.fetchHistoricalOneMinuteCandles(
                                "ONGC", date, date))
                                .thenReturn(List.of(
                                                providerFirst,
                                                providerSecond,
                                                providerThird));

                when(marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                "ONGC",
                                                "NSE",
                                                CandleTimeframe.ONE_MINUTE,
                                                date.atTime(9, 15),
                                                date.atTime(15, 29)))
                                .thenReturn(List.of(mismatchedLocal));

                when(resolutionRepository
                                .findBySymbolAndExchangeAndMinuteTimeBetweenOrderByMinuteTimeAsc(
                                                anyString(),
                                                anyString(),
                                                any(),
                                                any()))
                                .thenReturn(List.of());

                when(resolutionRepository
                                .findBySymbolAndExchangeAndMinuteTime(
                                                anyString(),
                                                anyString(),
                                                any()))
                                .thenReturn(Optional.empty());

                when(resolutionRepository.save(
                                any(LiveMinuteResolution.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                when(marketCandleRepository.save(any(MarketCandle.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                when(dailyDataStatusService.checkCompleteness(
                                "ONGC", "NSE", date))
                                .thenReturn(completeResult(date));

                when(timeProvider.nowDateTime())
                                .thenReturn(date.atTime(16, 15));

                EodReconciliationService.ReconciliationResult result = service.reconcile("ONGC", "NSE", date);

                assertEquals(DataStatus.REPAIRED, result.status());
                assertEquals(0, result.matchedCandles());
                assertEquals(3, result.repairedCandles());

                assertEquals(100.0, mismatchedLocal.getOpenPrice());
                assertEquals(
                                CandleQualityStatus.REPAIRED,
                                mismatchedLocal.getQualityStatus());

                verify(marketCandleRepository, atLeast(3))
                                .save(any(MarketCandle.class));

                verify(resolutionRepository, atLeast(3))
                                .save(any(LiveMinuteResolution.class));
        }

        @Test
        void reconcile_shouldRemainPartialWhenProviderIntegrityFailsDuringEarlyAttempts() {
                LocalDate date = LocalDate.of(2026, 8, 24);
                LocalDateTime first = date.atTime(10, 0); // Fails boundary check (starts at 10:00 instead of 09:15)

                stubThreeMinuteDay(date);

                when(marketDataProvider.fetchHistoricalOneMinuteCandles(
                                "ONGC", date, date))
                                .thenReturn(List.of(
                                                candleAt(first),
                                                candleAt(first.plusMinutes(1))));

                when(marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                "ONGC",
                                                "NSE",
                                                CandleTimeframe.ONE_MINUTE,
                                                date.atTime(9, 15),
                                                date.atTime(15, 29)))
                                .thenReturn(List.of());

                when(timeProvider.today()).thenReturn(date);
                when(timeProvider.nowDateTime()).thenReturn(date.atTime(17, 0));

                EodReconciliationService.ReconciliationResult result = service.reconcile("ONGC", "NSE", date);

                assertEquals(DataStatus.PARTIAL, result.status());
                assertEquals(1, result.unresolvedMinutes());

                verify(dailyDataStatusService).markStatus(
                                eq("ONGC"),
                                eq("NSE"),
                                eq(date),
                                eq(DataStatus.PARTIAL),
                                eq(3),
                                eq(2),
                                eq(1),
                                contains("EOD provider data integrity check failed"));
        }

        @Test
        void reconcile_shouldConfirmNoTradeOnEarlyAttemptWhenProviderIntegrityPasses() {
                LocalDate date = LocalDate.of(2026, 8, 24);
                LocalDateTime first = date.atTime(9, 15);

                stubThreeMinuteDay(date);

                when(marketDataProvider.fetchHistoricalOneMinuteCandles(
                                "ONGC", date, date))
                                .thenReturn(List.of(
                                                candleAt(first),
                                                candleAt(first.plusMinutes(1))));

                when(marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                "ONGC",
                                                "NSE",
                                                CandleTimeframe.ONE_MINUTE,
                                                date.atTime(9, 15),
                                                date.atTime(15, 29)))
                                .thenReturn(List.of(
                                                candleAt(first),
                                                candleAt(first.plusMinutes(1))));

                when(resolutionRepository
                                .findBySymbolAndExchangeAndMinuteTimeBetweenOrderByMinuteTimeAsc(
                                                anyString(),
                                                anyString(),
                                                any(),
                                                any()))
                                .thenReturn(List.of());

                when(dailyDataStatusService.checkCompleteness(
                                "ONGC", "NSE", date))
                                .thenReturn(completeResult(date));

                when(timeProvider.today()).thenReturn(date);
                when(timeProvider.nowDateTime()).thenReturn(date.atTime(17, 1)); // Early attempt at 17:01!

                com.trading.scanner.service.provider.angelone.dto.AngelOneMarketDtos.AngelOneMarketQuoteItem quote = new com.trading.scanner.service.provider.angelone.dto.AngelOneMarketDtos.AngelOneMarketQuoteItem(
                                "NSE", "ONGC-EQ", "3045", 100.0, 101.0, 99.0, 100.5, 100.5, 100.0, 500L, 50000.0,
                                500.0);

                EodReconciliationService.ReconciliationResult result = service.reconcile("ONGC", "NSE", date, false,
                                quote);

                assertEquals(DataStatus.RECONCILED, result.status());
                assertEquals(1, result.noTradeMinutes());
                assertEquals(0, result.unresolvedMinutes());

                verify(dailyDataStatusService).markStatus(
                                eq("ONGC"),
                                eq("NSE"),
                                eq(date),
                                eq(DataStatus.RECONCILED),
                                eq(3),
                                eq(3),
                                eq(0),
                                contains("EOD reconciliation verified all candles"));
        }

        @Test
        void reconcile_shouldConfirmNoTradeForOmittedProviderMinutesDuringFinalAttempt() {
                LocalDate date = LocalDate.of(2026, 8, 24);
                LocalDateTime first = date.atTime(9, 15);

                stubThreeMinuteDay(date);

                when(marketDataProvider.fetchHistoricalOneMinuteCandles(
                                "ONGC", date, date))
                                .thenReturn(List.of(
                                                candleAt(first),
                                                candleAt(first.plusMinutes(1))));

                when(marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                "ONGC",
                                                "NSE",
                                                CandleTimeframe.ONE_MINUTE,
                                                date.atTime(9, 15),
                                                date.atTime(15, 29)))
                                .thenReturn(List.of(
                                                candleAt(first),
                                                candleAt(first.plusMinutes(1))));

                when(resolutionRepository
                                .findBySymbolAndExchangeAndMinuteTimeBetweenOrderByMinuteTimeAsc(
                                                anyString(),
                                                anyString(),
                                                any(),
                                                any()))
                                .thenReturn(List.of());

                when(dailyDataStatusService.checkCompleteness(
                                "ONGC", "NSE", date))
                                .thenReturn(completeResult(date));

                when(timeProvider.today()).thenReturn(date);
                when(timeProvider.nowDateTime()).thenReturn(date.atTime(23, 0));

                EodReconciliationService.ReconciliationResult result = service.reconcile("ONGC", "NSE", date);

                assertEquals(DataStatus.RECONCILED, result.status());
                assertEquals(1, result.noTradeMinutes());
                assertEquals(0, result.unresolvedMinutes());

                verify(dailyDataStatusService).markStatus(
                                "ONGC",
                                "NSE",
                                date,
                                DataStatus.RECONCILED,
                                3,
                                3,
                                0,
                                "EOD reconciliation verified all candles via provider integrity check");
        }

        @Test
        void reconcileTradingDay_shouldDeferAlertOnIntermediateRetryWhenPartial() {
                LocalDate date = LocalDate.of(2026, 8, 28);
                when(timeProvider.today()).thenReturn(date);
                when(timeProvider.nowDateTime()).thenReturn(date.atTime(17, 0));
                when(tradingCalendar.isTradingDay(date)).thenReturn(true);

                StockUniverse stock = StockUniverse.builder()
                                .symbol("ONGC")
                                .exchange(com.trading.scanner.model.Exchange.NSE)
                                .isActive(true)
                                .build();
                when(stockUniverseRepository.findByIsActiveTrueOrderBySymbolAsc()).thenReturn(List.of(stock));

                when(tradingCalendar.expectedOneMinuteCandleCount(date)).thenReturn(375);
                when(marketDataProvider.fetchHistoricalOneMinuteCandles(anyString(), any(), any()))
                                .thenThrow(new RuntimeException("API error"));

                com.trading.scanner.service.runtime.RuntimeAlertService runtimeAlertService = mock(
                                com.trading.scanner.service.runtime.RuntimeAlertService.class);

                EodReconciliationService svc = new EodReconciliationService(
                                marketDataProvider,
                                marketCandleRepository,
                                resolutionRepository,
                                dailyDataStatusService,
                                stockUniverseRepository,
                                tradingCalendar,
                                timeProvider,
                                eodDataEntryService,
                                null,
                                null,
                                runtimeAlertService,
                                null,
                                null);

                EodReconciliationService.ReconciliationBatchResult result = svc.reconcileTradingDay(date);

                assertEquals(2, result.partialSymbols());
                // Alert must NOT be triggered on intermediate 17:00 run!
                verify(runtimeAlertService, never()).reportEodReconciliationFailure(
                                any(),
                                anyInt(),
                                anyString(),
                                any());
        }

        @Test
        void reconcileTradingDay_shouldTriggerHighPriorityAlertOnPartialSymbolsDuringFinalAttempt() {
                LocalDate date = LocalDate.of(2026, 8, 28);
                when(timeProvider.today()).thenReturn(date);
                when(timeProvider.nowDateTime()).thenReturn(date.atTime(23, 0));
                when(tradingCalendar.isTradingDay(date)).thenReturn(true);

                StockUniverse stock = StockUniverse.builder()
                                .symbol("ONGC")
                                .exchange(com.trading.scanner.model.Exchange.NSE)
                                .isActive(true)
                                .build();
                when(stockUniverseRepository.findByIsActiveTrueOrderBySymbolAsc()).thenReturn(List.of(stock));

                when(tradingCalendar.expectedOneMinuteCandleCount(date)).thenReturn(375);
                when(marketDataProvider.fetchHistoricalOneMinuteCandles(anyString(), any(), any()))
                                .thenThrow(new RuntimeException("API error"));

                com.trading.scanner.service.runtime.RuntimeAlertService runtimeAlertService = mock(
                                com.trading.scanner.service.runtime.RuntimeAlertService.class);

                EodReconciliationService svc = new EodReconciliationService(
                                marketDataProvider,
                                marketCandleRepository,
                                resolutionRepository,
                                dailyDataStatusService,
                                stockUniverseRepository,
                                tradingCalendar,
                                timeProvider,
                                eodDataEntryService,
                                null,
                                null,
                                runtimeAlertService,
                                null,
                                null);

                EodReconciliationService.ReconciliationBatchResult result = svc.reconcileTradingDay(date);

                assertEquals(2, result.partialSymbols());
                verify(runtimeAlertService).reportEodReconciliationFailure(
                                eq(date),
                                eq(2),
                                anyString(),
                                any());
        }

        private void stubThreeMinuteDay(LocalDate date) {
                when(tradingCalendar.expectedOneMinuteCandleCount(date))
                                .thenReturn(3);
        }

        private DailyDataStatusService.CompletenessResult completeResult(
                        LocalDate date) {

                return new DailyDataStatusService.CompletenessResult(
                                "ONGC",
                                "NSE",
                                date,
                                3,
                                3,
                                0,
                                false,
                                false,
                                true,
                                DataStatus.LIVE,
                                "complete");
        }

        private MarketCandle candleAt(LocalDateTime time) {
                return candle(
                                time,
                                100.0,
                                101.0,
                                99.0,
                                100.5,
                                100L,
                                CandleQualityStatus.LIVE,
                                CandleProcessingStatus.RELEASED);
        }

        private MarketCandle candle(
                        LocalDateTime time,
                        double open,
                        double high,
                        double low,
                        double close,
                        long volume,
                        CandleQualityStatus quality,
                        CandleProcessingStatus processingStatus) {

                return MarketCandle.builder()
                                .symbol("ONGC")
                                .exchange("NSE")
                                .timeframe(CandleTimeframe.ONE_MINUTE)
                                .candleTime(time)
                                .openPrice(open)
                                .highPrice(high)
                                .lowPrice(low)
                                .closePrice(close)
                                .volume(volume)
                                .source("TEST")
                                .isFinalized(true)
                                .qualityStatus(quality)
                                .processingStatus(processingStatus)
                                .build();
        }
}
