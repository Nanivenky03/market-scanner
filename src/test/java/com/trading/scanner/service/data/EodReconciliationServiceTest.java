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
import com.trading.scanner.model.StockPrice;
import com.trading.scanner.repository.LiveMinuteResolutionRepository;
import com.trading.scanner.repository.MarketCandleRepository;
import com.trading.scanner.repository.StockPriceRepository;
import com.trading.scanner.repository.StockUniverseRepository;
import com.trading.scanner.service.engine.DailyDataStatusService;
import com.trading.scanner.service.provider.angelone.AngelOneMarketDataProvider;
import com.trading.scanner.service.provider.angelone.AngelOneWebSocketService;
import com.trading.scanner.service.provider.angelone.dto.AngelOneMarketDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        private StockPriceRepository stockPriceRepository;
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
                stockPriceRepository = mock(StockPriceRepository.class);

                service = new EodReconciliationService(
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
                                null,
                                null,
                                null,
                                stockPriceRepository);
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
                                contains("EOD reconciliation verified"));

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
                assertEquals(3, result.unresolvedMinutes());

                verify(dailyDataStatusService).markStatus(
                                eq("ONGC"),
                                eq("NSE"),
                                eq(date),
                                eq(DataStatus.PARTIAL),
                                eq(3),
                                eq(0),
                                eq(3),
                                contains("EOD candle integrity check failed"));
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
                                "NSE", "ONGC-EQ", "3045", 100.0, 101.0, 99.0, 100.5, 100.5, 100.0, 200L, 50000.0,
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
                                contains("EOD reconciliation verified"));
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
                                eq("ONGC"),
                                eq("NSE"),
                                eq(date),
                                eq(DataStatus.RECONCILED),
                                eq(3),
                                eq(3),
                                eq(0),
                                contains("EOD reconciliation verified"));
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

        @Test
        void reconcileTradingDay_shouldOnlyFetchQuotesAndHistoricalCandlesForPendingSymbols() {
                LocalDate date = LocalDate.of(2026, 8, 28);
                when(timeProvider.today()).thenReturn(date);
                when(timeProvider.nowDateTime()).thenReturn(date.atTime(17, 0));
                when(tradingCalendar.isTradingDay(date)).thenReturn(true);
                when(tradingCalendar.expectedOneMinuteCandleCount(date)).thenReturn(3);

                StockUniverse ongc = StockUniverse.builder().symbol("ONGC")
                                .exchange(com.trading.scanner.model.Exchange.NSE).isActive(true).build();
                StockUniverse reliance = StockUniverse.builder().symbol("RELIANCE")
                                .exchange(com.trading.scanner.model.Exchange.NSE).isActive(true).build();
                when(stockUniverseRepository.findByIsActiveTrueOrderBySymbolAsc()).thenReturn(List.of(ongc, reliance));

                // NIFTY and ONGC are already SUCCESSFUL; RELIANCE is NOT.
                when(eodDataEntryService.isSuccessful("NIFTY", "NSE", date)).thenReturn(true);
                when(eodDataEntryService.isSuccessful("ONGC", "NSE", date)).thenReturn(true);
                when(eodDataEntryService.isSuccessful("RELIANCE", "NSE", date)).thenReturn(false);

                when(marketDataProvider.fetchMarketQuotes(List.of("RELIANCE"), "NSE"))
                                .thenReturn(java.util.Map.of("RELIANCE",
                                                new com.trading.scanner.service.provider.angelone.dto.AngelOneMarketDtos.AngelOneMarketQuoteItem(
                                                                "NSE", "RELIANCE-EQ", "2885", 2500.0, 2550.0, 2480.0,
                                                                2520.0, 2520.0, 2500.0, 1000L, 100000.0, 1000.0)));

                when(marketDataProvider.fetchHistoricalOneMinuteCandles(eq("RELIANCE"), eq(date), eq(date)))
                                .thenReturn(List.of(
                                                candle(date.atTime(9, 15), 2500.0, 2510.0, 2490.0, 2505.0, 300L,
                                                                CandleQualityStatus.LIVE,
                                                                CandleProcessingStatus.RELEASED),
                                                candle(date.atTime(9, 16), 2505.0, 2550.0, 2480.0, 2510.0, 400L,
                                                                CandleQualityStatus.LIVE,
                                                                CandleProcessingStatus.RELEASED),
                                                candle(date.atTime(9, 17), 2510.0, 2520.0, 2500.0, 2520.0, 300L,
                                                                CandleQualityStatus.LIVE,
                                                                CandleProcessingStatus.RELEASED)));

                when(dailyDataStatusService.checkCompleteness("RELIANCE", "NSE", date))
                                .thenReturn(new DailyDataStatusService.CompletenessResult("RELIANCE", "NSE", date, 3, 3,
                                                0, false, false, true, DataStatus.LIVE, "complete"));

                EodReconciliationService.ReconciliationBatchResult batchResult = service.reconcileTradingDay(date);

                assertEquals(3, batchResult.symbolsProcessed()); // NIFTY + ONGC + RELIANCE
                assertEquals(3, batchResult.reconciledSymbols() + batchResult.repairedSymbols());

                // Verify batch quote fetch was called ONLY for RELIANCE
                verify(marketDataProvider).fetchMarketQuotes(List.of("RELIANCE"), "NSE");
                verify(marketDataProvider, never()).fetchMarketQuotes(
                                argThat(list -> list.contains("ONGC") || list.contains("NIFTY")), anyString());

                // Verify historical provider candles were fetched ONLY for RELIANCE
                verify(marketDataProvider).fetchHistoricalOneMinuteCandles("RELIANCE", date, date);
                verify(marketDataProvider, never()).fetchHistoricalOneMinuteCandles("ONGC", date, date);
                verify(marketDataProvider, never()).fetchHistoricalOneMinuteCandles("NIFTY", date, date);
        }

        @Test
        void reconcile_shouldSurgicallyPatchMissingMinutesFromHistoricalDataWhenSanityCheckPasses() {
                LocalDate date = LocalDate.of(2026, 8, 24);
                LocalDateTime m0 = date.atTime(9, 15);
                LocalDateTime m1 = date.atTime(9, 16);
                LocalDateTime m2 = date.atTime(9, 17);

                stubThreeMinuteDay(date);

                MarketCandle db0 = candle(m0, 100.0, 101.0, 99.0, 100.5, 100L, CandleQualityStatus.LIVE,
                                CandleProcessingStatus.RELEASED);
                // m1 is missing in DB!
                MarketCandle db2 = candle(m2, 100.5, 102.0, 100.0, 101.5, 150L, CandleQualityStatus.LIVE,
                                CandleProcessingStatus.RELEASED);

                when(marketCandleRepository.findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                "ONGC", "NSE", CandleTimeframe.ONE_MINUTE, date.atTime(9, 15), date.atTime(15, 29)))
                                .thenReturn(List.of(db0, db2));

                MarketCandle prov0 = candle(m0, 100.0, 101.0, 99.0, 100.5, 100L, CandleQualityStatus.LIVE,
                                CandleProcessingStatus.RELEASED);
                MarketCandle prov1 = candle(m1, 100.5, 101.5, 99.5, 100.5, 200L, CandleQualityStatus.LIVE,
                                CandleProcessingStatus.RELEASED);
                MarketCandle prov2 = candle(m2, 100.5, 102.0, 100.0, 101.5, 150L, CandleQualityStatus.LIVE,
                                CandleProcessingStatus.RELEASED);

                when(marketDataProvider.fetchHistoricalOneMinuteCandles("ONGC", date, date))
                                .thenReturn(List.of(prov0, prov1, prov2));

                when(dailyDataStatusService.checkCompleteness("ONGC", "NSE", date))
                                .thenReturn(completeResult(date));

                when(timeProvider.nowDateTime()).thenReturn(date.atTime(16, 15));

                com.trading.scanner.service.provider.angelone.dto.AngelOneMarketDtos.AngelOneMarketQuoteItem quote = new com.trading.scanner.service.provider.angelone.dto.AngelOneMarketDtos.AngelOneMarketQuoteItem(
                                "NSE", "ONGC-EQ", "3045", 100.0, 102.0, 99.0, 101.5, 101.5, 100.0, 450L, 50000.0,
                                500.0);

                EodReconciliationService.ReconciliationResult result = service.reconcile("ONGC", "NSE", date, false,
                                quote);

                assertEquals(DataStatus.REPAIRED, result.status());
                assertEquals(2, result.matchedCandles()); // db0 and db2
                assertEquals(1, result.repairedCandles()); // db1 surgically inserted!
                assertEquals(0, result.unresolvedMinutes());

                // Verify that only the missing minute m1 was saved as new candle
                ArgumentCaptor<MarketCandle> candleCaptor = ArgumentCaptor.forClass(MarketCandle.class);
                verify(marketCandleRepository, atLeastOnce()).save(candleCaptor.capture());

                List<MarketCandle> savedList = candleCaptor.getAllValues();
                assertTrue(savedList.stream().anyMatch(c -> c.getCandleTime().equals(m1)
                                && c.getQualityStatus() == CandleQualityStatus.REPAIRED));
        }

        @Test
        void reconcile_shouldPassWhenDayHighOrLowOccurredDuringPreMarketAuction() {
                LocalDate date = LocalDate.of(2026, 8, 24);
                LocalDateTime m0 = date.atTime(9, 15);
                LocalDateTime m1 = date.atTime(9, 16);
                LocalDateTime m2 = date.atTime(9, 17);

                stubThreeMinuteDay(date);

                // Continuous session: open 403.65, high 403.65, low 400.0, close 401.0
                MarketCandle c0 = candle(m0, 403.65, 403.65, 402.0, 402.5, 100L, CandleQualityStatus.LIVE,
                                CandleProcessingStatus.RELEASED);
                MarketCandle c1 = candle(m1, 402.5, 403.0, 400.0, 400.5, 100L, CandleQualityStatus.LIVE,
                                CandleProcessingStatus.RELEASED);
                MarketCandle c2 = candle(m2, 400.5, 401.5, 400.5, 401.0, 100L, CandleQualityStatus.LIVE,
                                CandleProcessingStatus.RELEASED);

                when(marketCandleRepository.findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                "KOTAKBANK", "NSE", CandleTimeframe.ONE_MINUTE, date.atTime(9, 15),
                                date.atTime(15, 29)))
                                .thenReturn(List.of(c0, c1, c2));

                when(marketDataProvider.fetchHistoricalOneMinuteCandles("KOTAKBANK", date, date))
                                .thenReturn(List.of(c0, c1, c2));

                when(dailyDataStatusService.checkCompleteness("KOTAKBANK", "NSE", date))
                                .thenReturn(completeResult(date));

                when(timeProvider.nowDateTime()).thenReturn(date.atTime(16, 15));

                // Quote: Pre-market open = 403.70, High = 403.70 (occurred at 09:08), Low =
                // 400.0, LTP = 401.0
                com.trading.scanner.service.provider.angelone.dto.AngelOneMarketDtos.AngelOneMarketQuoteItem quote = new com.trading.scanner.service.provider.angelone.dto.AngelOneMarketDtos.AngelOneMarketQuoteItem(
                                "NSE", "KOTAKBANK-EQ", "1922", 403.70, 403.70, 400.0, 401.0, 401.0, 403.70, 300L,
                                50000.0,
                                500.0);

                EodReconciliationService.ReconciliationResult result = service.reconcile("KOTAKBANK", "NSE", date,
                                false,
                                quote);

                assertEquals(DataStatus.RECONCILED, result.status());
                assertEquals(3, result.matchedCandles());
                assertEquals(0, result.unresolvedMinutes());
        }

        @Test
        void reconcile_whenLocalDbIsValidAgainstQuote_shouldMarkReconciledWithoutCallingHistoricalApi() {
                LocalDate date = LocalDate.of(2026, 8, 24);
                LocalDateTime m0 = date.atTime(9, 15);
                LocalDateTime m1 = date.atTime(9, 16);
                LocalDateTime m2 = date.atTime(9, 17);

                stubThreeMinuteDay(date);

                MarketCandle c0 = candle(m0, 100.0, 101.0, 99.0, 100.5, 100L, CandleQualityStatus.LIVE,
                                CandleProcessingStatus.RELEASED);
                MarketCandle c1 = candle(m1, 100.5, 102.0, 100.0, 101.0, 100L, CandleQualityStatus.LIVE,
                                CandleProcessingStatus.RELEASED);
                MarketCandle c2 = candle(m2, 101.0, 101.5, 100.5, 101.5, 100L, CandleQualityStatus.LIVE,
                                CandleProcessingStatus.RELEASED);

                when(marketCandleRepository.findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                "TCS", "NSE", CandleTimeframe.ONE_MINUTE, date.atTime(9, 15), date.atTime(15, 29)))
                                .thenReturn(List.of(c0, c1, c2));

                when(dailyDataStatusService.checkCompleteness("TCS", "NSE", date))
                                .thenReturn(new DailyDataStatusService.CompletenessResult("TCS", "NSE", date, 3, 3,
                                                0, false, false, true, DataStatus.LIVE, "complete"));

                when(timeProvider.nowDateTime()).thenReturn(date.atTime(16, 15));

                com.trading.scanner.service.provider.angelone.dto.AngelOneMarketDtos.AngelOneMarketQuoteItem quote = new com.trading.scanner.service.provider.angelone.dto.AngelOneMarketDtos.AngelOneMarketQuoteItem(
                                "NSE", "TCS-EQ", "11536", 100.0, 102.0, 99.0, 101.5, 101.5, 100.0, 300L, 50000.0,
                                500.0);

                EodReconciliationService.ReconciliationResult result = service.reconcile("TCS", "NSE", date, false,
                                quote);

                assertEquals(DataStatus.RECONCILED, result.status());
                assertEquals(3, result.matchedCandles());
                assertEquals(0, result.repairedCandles());

                // Assert that historical API was NEVER called because local DB was complete &
                // sound!
                verify(marketDataProvider, never()).fetchHistoricalOneMinuteCandles(eq("TCS"), any(), any());

                // Assert 09:08 pre-market candle was saved with identical OHLC matching
                // quote.open
                ArgumentCaptor<MarketCandle> candleCaptor = ArgumentCaptor.forClass(MarketCandle.class);
                verify(marketCandleRepository, atLeastOnce()).save(candleCaptor.capture());

                List<MarketCandle> saved = candleCaptor.getAllValues();
                MarketCandle preMarket = saved.stream()
                                .filter(c -> c.getCandleTime().equals(date.atTime(9, 8)))
                                .findFirst()
                                .orElse(null);

                assertTrue(preMarket != null);
                assertEquals(100.0, preMarket.getOpenPrice());
                assertEquals(100.0, preMarket.getHighPrice());
                assertEquals(100.0, preMarket.getLowPrice());
                assertEquals(100.0, preMarket.getClosePrice());
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

        @Test
        void reconcile_shouldUpsertDailyStockPriceFromMarketQuote() {
                LocalDate date = LocalDate.of(2026, 8, 24);
                LocalDateTime first = date.atTime(9, 15);
                MarketCandle local = candle(first, 100.0, 101.0, 99.0, 100.5, 100L,
                                CandleQualityStatus.LIVE, CandleProcessingStatus.RELEASED);

                stubThreeMinuteDay(date);

                when(timeProvider.today()).thenReturn(date);
                when(timeProvider.nowDateTime()).thenReturn(date.atTime(17, 1));

                when(dailyDataStatusService.checkCompleteness("ONGC", "NSE", date))
                                .thenReturn(completeResult(date));

                com.trading.scanner.service.provider.angelone.dto.AngelOneMarketDtos.AngelOneMarketQuoteItem quote = new com.trading.scanner.service.provider.angelone.dto.AngelOneMarketDtos.AngelOneMarketQuoteItem(
                                "NSE", "ONGC-EQ", "3045", 100.0, 101.0, 99.0, 100.5, 100.5, 100.0, 300L, 50000.0,
                                500.0);

                when(marketDataProvider.fetchHistoricalOneMinuteCandles("ONGC", date, date))
                                .thenReturn(List.of(local, candleAt(first.plusMinutes(1)),
                                                candleAt(first.plusMinutes(2))));

                service.reconcile("ONGC", "NSE", date, false, quote);

                ArgumentCaptor<StockPrice> captor = ArgumentCaptor.forClass(StockPrice.class);
                verify(stockPriceRepository).save(captor.capture());

                StockPrice saved = captor.getValue();
                assertEquals("ONGC", saved.getSymbol());
                assertEquals(date, saved.getDate());
                assertEquals(100.0, saved.getOpenPrice());
                assertEquals(101.0, saved.getHighPrice());
                assertEquals(99.0, saved.getLowPrice());
                assertEquals(100.5, saved.getClosePrice());
                assertEquals(300, saved.getVolume());
        }
}
