package com.trading.scanner.service.engine;

import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.CandleProcessingStatus;
import com.trading.scanner.model.CandleQualityStatus;
import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.ContextStatus;
import com.trading.scanner.model.DailyCandleSummary;
import com.trading.scanner.model.DailyDataStatus;
import com.trading.scanner.model.DailyStockContext;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class DailyDataStatusServiceTest {

        private DailyDataStatusRepository dailyDataStatusRepository;
        private DailyStockContextRepository dailyStockContextRepository;
        private MarketCandleRepository marketCandleRepository;
        private TradingCalendar tradingCalendar;
        private TimeProvider timeProvider;
        private StockUniverseRepository stockUniverseRepository;
        private DailyCandleSummaryRepository dailyCandleSummaryRepository;
        private LiveMinuteResolutionRepository liveMinuteResolutionRepository;
        private DailyDataStatusService service;

        @BeforeEach
        void setUp() {
                dailyDataStatusRepository = mock(DailyDataStatusRepository.class);
                dailyStockContextRepository = mock(DailyStockContextRepository.class);
                marketCandleRepository = mock(MarketCandleRepository.class);
                tradingCalendar = mock(TradingCalendar.class);
                timeProvider = mock(TimeProvider.class);
                stockUniverseRepository = mock(StockUniverseRepository.class);
                dailyCandleSummaryRepository = mock(DailyCandleSummaryRepository.class);
                liveMinuteResolutionRepository = mock(LiveMinuteResolutionRepository.class);

                service = new DailyDataStatusService(
                                dailyDataStatusRepository,
                                dailyStockContextRepository,
                                marketCandleRepository,
                                tradingCalendar,
                                timeProvider,
                                stockUniverseRepository,
                                dailyCandleSummaryRepository,
                                liveMinuteResolutionRepository);
        }

        @Test
        void markLive_shouldCreateLiveStatus() {
                LocalDate date = LocalDate.of(2026, 8, 14);

                when(dailyDataStatusRepository
                                .findBySymbolAndExchangeAndTradingDate(
                                                "ONGC", "NSE", date))
                                .thenReturn(Optional.empty());

                when(timeProvider.nowDateTime())
                                .thenReturn(LocalDateTime.of(2026, 8, 14, 9, 16));

                service.markLive("ONGC", "NSE", date);

                verify(dailyDataStatusRepository).save(argThat(status -> status.getStatus() == DataStatus.LIVE
                                && "ONGC".equals(status.getSymbol())
                                && "NSE".equals(status.getExchange())
                                && date.equals(status.getTradingDate())));
        }

        @Test
        void checkCompleteness_shouldMarkMissingMinuteAsPartial() {
                LocalDate date = LocalDate.of(2026, 8, 14);
                LocalDateTime start = date.atTime(9, 15);

                when(tradingCalendar.expectedOneMinuteCandleCount(date))
                                .thenReturn(375);

                when(dailyDataStatusRepository
                                .findBySymbolAndExchangeAndTradingDate(
                                                "ONGC", "NSE", date))
                                .thenReturn(Optional.empty());

                when(marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                "ONGC",
                                                "NSE",
                                                CandleTimeframe.ONE_MINUTE,
                                                start,
                                                date.atTime(15, 29)))
                                .thenReturn(candlesWithout(start, 374));

                when(liveMinuteResolutionRepository
                                .findBySymbolAndExchangeAndMinuteTimeBetweenOrderByMinuteTimeAsc(
                                                "ONGC",
                                                "NSE",
                                                start,
                                                date.atTime(15, 29)))
                                .thenReturn(List.of());

                when(timeProvider.nowDateTime())
                                .thenReturn(LocalDateTime.of(2026, 8, 14, 15, 40));

                DailyDataStatusService.CompletenessResult result = service.checkCompleteness("ONGC", "NSE", date);

                assertFalse(result.complete());
                assertEquals(375, result.expectedCandleCount());
                assertEquals(374, result.actualCandleCount());
                assertEquals(1, result.missingCandleCount());
                assertFalse(result.unfinalizedCandleDetected());
                assertEquals(DataStatus.PARTIAL, result.resultingStatus());
        }

        @Test
        void checkCompleteness_shouldMarkUnfinalizedCandleAsPartial() {
                LocalDate date = LocalDate.of(2026, 8, 14);
                LocalDateTime start = date.atTime(9, 15);

                List<MarketCandle> candles = new ArrayList<>();

                for (int i = 0; i < 375; i++) {
                        boolean finalized = i != 200;
                        candles.add(candle(
                                        start.plusMinutes(i),
                                        finalized,
                                        true));
                }

                when(tradingCalendar.expectedOneMinuteCandleCount(date))
                                .thenReturn(375);

                when(dailyDataStatusRepository
                                .findBySymbolAndExchangeAndTradingDate(
                                                "ONGC", "NSE", date))
                                .thenReturn(Optional.empty());

                when(marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                "ONGC",
                                                "NSE",
                                                CandleTimeframe.ONE_MINUTE,
                                                start,
                                                date.atTime(15, 29)))
                                .thenReturn(candles);

                when(liveMinuteResolutionRepository
                                .findBySymbolAndExchangeAndMinuteTimeBetweenOrderByMinuteTimeAsc(
                                                "ONGC",
                                                "NSE",
                                                start,
                                                date.atTime(15, 29)))
                                .thenReturn(List.of());

                when(timeProvider.nowDateTime())
                                .thenReturn(LocalDateTime.of(2026, 8, 14, 15, 40));

                DailyDataStatusService.CompletenessResult result = service.checkCompleteness("ONGC", "NSE", date);

                assertFalse(result.complete());
                assertEquals(375, result.expectedCandleCount());
                assertEquals(375, result.actualCandleCount());
                assertEquals(0, result.missingCandleCount());
                assertTrue(result.unfinalizedCandleDetected());
                assertEquals(DataStatus.PARTIAL, result.resultingStatus());
        }

        @Test
        void checkCompleteness_shouldNotDowngradeReconciledStatus() {
                LocalDate date = LocalDate.of(2026, 8, 14);
                LocalDateTime start = date.atTime(9, 15);

                when(tradingCalendar.expectedOneMinuteCandleCount(date))
                                .thenReturn(375);

                when(dailyDataStatusRepository
                                .findBySymbolAndExchangeAndTradingDate(
                                                "ONGC", "NSE", date))
                                .thenReturn(Optional.of(DailyDataStatus.builder()
                                                .symbol("ONGC")
                                                .exchange("NSE")
                                                .tradingDate(date)
                                                .status(DataStatus.RECONCILED)
                                                .build()));

                when(marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                "ONGC",
                                                "NSE",
                                                CandleTimeframe.ONE_MINUTE,
                                                start,
                                                date.atTime(15, 29)))
                                .thenReturn(List.of());

                when(liveMinuteResolutionRepository
                                .findBySymbolAndExchangeAndMinuteTimeBetweenOrderByMinuteTimeAsc(
                                                "ONGC",
                                                "NSE",
                                                start,
                                                date.atTime(15, 29)))
                                .thenReturn(List.of());

                when(timeProvider.nowDateTime())
                                .thenReturn(LocalDateTime.of(2026, 8, 14, 15, 40));

                DailyDataStatusService.CompletenessResult result = service.checkCompleteness("ONGC", "NSE", date);

                assertEquals(DataStatus.RECONCILED, result.resultingStatus());

                verify(dailyDataStatusRepository).save(argThat(status -> status.getStatus() == DataStatus.RECONCILED));
        }

        @Test
        void checkCompleteness_shouldCountConfirmedNoTradeAsResolved() {
                LocalDate date = LocalDate.of(2026, 8, 14);
                LocalDateTime start = date.atTime(9, 15);
                LocalDateTime noTradeMinute = start.plusMinutes(374);

                when(tradingCalendar.expectedOneMinuteCandleCount(date))
                                .thenReturn(375);

                when(dailyDataStatusRepository
                                .findBySymbolAndExchangeAndTradingDate(
                                                "ONGC", "NSE", date))
                                .thenReturn(Optional.empty());

                when(marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                "ONGC",
                                                "NSE",
                                                CandleTimeframe.ONE_MINUTE,
                                                start,
                                                date.atTime(15, 29)))
                                .thenReturn(candlesWithout(start, 374));

                when(liveMinuteResolutionRepository
                                .findBySymbolAndExchangeAndMinuteTimeBetweenOrderByMinuteTimeAsc(
                                                "ONGC",
                                                "NSE",
                                                start,
                                                date.atTime(15, 29)))
                                .thenReturn(List.of(
                                                LiveMinuteResolution.builder()
                                                                .symbol("ONGC")
                                                                .exchange("NSE")
                                                                .tradingDate(date)
                                                                .minuteTime(noTradeMinute)
                                                                .status(MinuteResolutionStatus.NO_TRADE_CONFIRMED)
                                                                .build()));

                when(timeProvider.nowDateTime())
                                .thenReturn(LocalDateTime.of(2026, 8, 14, 15, 40));

                when(dailyDataStatusRepository.save(any(DailyDataStatus.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                when(dailyCandleSummaryRepository.save(
                                any(DailyCandleSummary.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                DailyDataStatusService.CompletenessResult result = service.checkCompleteness("ONGC", "NSE", date);

                assertTrue(result.complete());
                assertEquals(0, result.missingCandleCount());

                verify(dailyCandleSummaryRepository).save(argThat(summary -> summary.getActualCount() == 374
                                && summary.getMissingCount() == 0
                                && summary.getNoTradeCount() == 1));
        }

        @Test
        void isTrustedForSimulation_shouldRequireTrustedDataAndCompleteContext() {
                LocalDate date = LocalDate.of(2026, 8, 14);

                when(dailyDataStatusRepository
                                .findBySymbolAndExchangeAndTradingDate(
                                                "ONGC", "NSE", date))
                                .thenReturn(Optional.of(DailyDataStatus.builder()
                                                .status(DataStatus.RECONCILED)
                                                .build()));

                when(dailyStockContextRepository
                                .findBySymbolAndExchangeAndTradingDate(
                                                "ONGC", "NSE", date))
                                .thenReturn(Optional.of(DailyStockContext.builder()
                                                .contextStatus(ContextStatus.COMPLETE)
                                                .build()));

                assertTrue(service.isTrustedForSimulation(
                                "ONGC", "NSE", date));
        }

        @Test
        void isTrustedForSimulation_shouldRejectPartialData() {
                LocalDate date = LocalDate.of(2026, 8, 14);

                when(dailyDataStatusRepository
                                .findBySymbolAndExchangeAndTradingDate(
                                                "ONGC", "NSE", date))
                                .thenReturn(Optional.of(DailyDataStatus.builder()
                                                .status(DataStatus.PARTIAL)
                                                .build()));

                when(dailyStockContextRepository
                                .findBySymbolAndExchangeAndTradingDate(
                                                "ONGC", "NSE", date))
                                .thenReturn(Optional.of(DailyStockContext.builder()
                                                .contextStatus(ContextStatus.COMPLETE)
                                                .build()));

                assertFalse(service.isTrustedForSimulation(
                                "ONGC", "NSE", date));
        }

        @Test
        void isTrustedForSimulation_shouldRejectIncompleteContext() {
                LocalDate date = LocalDate.of(2026, 8, 14);

                when(dailyDataStatusRepository
                                .findBySymbolAndExchangeAndTradingDate(
                                                "ONGC", "NSE", date))
                                .thenReturn(Optional.of(DailyDataStatus.builder()
                                                .status(DataStatus.REPAIRED)
                                                .build()));

                when(dailyStockContextRepository
                                .findBySymbolAndExchangeAndTradingDate(
                                                "ONGC", "NSE", date))
                                .thenReturn(Optional.of(DailyStockContext.builder()
                                                .contextStatus(ContextStatus.PARTIAL)
                                                .build()));

                assertFalse(service.isTrustedForSimulation(
                                "ONGC", "NSE", date));
        }

        @Test
        void isTrustedForSimulation_shouldRejectMissingContext() {
                LocalDate date = LocalDate.of(2026, 8, 14);

                when(dailyDataStatusRepository
                                .findBySymbolAndExchangeAndTradingDate(
                                                "ONGC", "NSE", date))
                                .thenReturn(Optional.of(DailyDataStatus.builder()
                                                .status(DataStatus.RECONCILED)
                                                .build()));

                when(dailyStockContextRepository
                                .findBySymbolAndExchangeAndTradingDate(
                                                "ONGC", "NSE", date))
                                .thenReturn(Optional.empty());

                assertFalse(service.isTrustedForSimulation(
                                "ONGC", "NSE", date));
        }

        @Test
        void scheduledCompletenessCheck_shouldReadActiveUniverse() {
                LocalDate today = LocalDate.of(2026, 8, 17);
                LocalDate previousTradingDay = LocalDate.of(2026, 8, 14);

                when(timeProvider.today()).thenReturn(today);
                when(tradingCalendar.previousTradingDay(today))
                                .thenReturn(previousTradingDay);

                when(stockUniverseRepository
                                .findByIsActiveTrueOrderBySymbolAsc())
                                .thenReturn(List.of());

                service.scheduledCompletenessCheck();

                verify(stockUniverseRepository)
                                .findByIsActiveTrueOrderBySymbolAsc();
        }

        private List<MarketCandle> candlesWithout(
                        LocalDateTime start,
                        int count) {

                List<MarketCandle> candles = new ArrayList<>();

                for (int i = 0; i < count; i++) {
                        candles.add(candle(
                                        start.plusMinutes(i),
                                        true,
                                        true));
                }

                return candles;
        }

        private MarketCandle candle(
                        LocalDateTime time,
                        boolean finalized,
                        boolean released) {

                return MarketCandle.builder()
                                .symbol("ONGC")
                                .exchange("NSE")
                                .timeframe(CandleTimeframe.ONE_MINUTE)
                                .candleTime(time)
                                .openPrice(100.0)
                                .highPrice(101.0)
                                .lowPrice(99.0)
                                .closePrice(100.5)
                                .volume(100L)
                                .source("TEST")
                                .isFinalized(finalized)
                                .qualityStatus(CandleQualityStatus.LIVE)
                                .processingStatus(released
                                                ? CandleProcessingStatus.RELEASED
                                                : CandleProcessingStatus.PROVISIONAL)
                                .build();
        }
}
