package com.trading.scanner.service.runtime;

import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.Exchange;
import com.trading.scanner.model.StockUniverse;
import com.trading.scanner.position.PositionManagerService;
import com.trading.scanner.repository.*;
import com.trading.scanner.service.data.BackfillQueueService;
import com.trading.scanner.service.data.LiveMarketCandleService;
import com.trading.scanner.service.provider.angelone.AngelOneWebSocketService;
import com.trading.scanner.strategy.cache.StockContextCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DayWorkflowRestartServiceTest {

    private PreMarketWorkflowService preMarketWorkflowService;
    private MarketQuoteSyncService marketQuoteSyncService;
    private BackfillQueueService backfillQueueService;
    private TradingCalendar tradingCalendar;
    private TimeProvider timeProvider;

    private MarketCandleRepository marketCandleRepository;
    private DailyStockContextRepository dailyStockContextRepository;
    private DailyDataStatusRepository dailyDataStatusRepository;
    private BackfillJobRepository backfillJobRepository;
    private WorkflowStatusRepository workflowStatusRepository;
    private StockUniverseRepository stockUniverseRepository;

    private StockContextCache stockContextCache;
    private LiveMarketCandleService liveMarketCandleService;
    private AngelOneWebSocketService angelOneWebSocketService;
    private RuntimeAutomationService runtimeAutomationService;
    private ObjectProvider<PositionManagerService> positionManagerServiceProvider;

    private DayWorkflowRestartService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        preMarketWorkflowService = mock(PreMarketWorkflowService.class);
        marketQuoteSyncService = mock(MarketQuoteSyncService.class);
        backfillQueueService = mock(BackfillQueueService.class);
        tradingCalendar = mock(TradingCalendar.class);
        timeProvider = mock(TimeProvider.class);

        marketCandleRepository = mock(MarketCandleRepository.class);
        dailyStockContextRepository = mock(DailyStockContextRepository.class);
        dailyDataStatusRepository = mock(DailyDataStatusRepository.class);
        backfillJobRepository = mock(BackfillJobRepository.class);
        workflowStatusRepository = mock(WorkflowStatusRepository.class);
        stockUniverseRepository = mock(StockUniverseRepository.class);

        stockContextCache = mock(StockContextCache.class);
        liveMarketCandleService = mock(LiveMarketCandleService.class);
        angelOneWebSocketService = mock(AngelOneWebSocketService.class);
        runtimeAutomationService = mock(RuntimeAutomationService.class);
        positionManagerServiceProvider = mock(ObjectProvider.class);

        service = new DayWorkflowRestartService(
                preMarketWorkflowService,
                marketQuoteSyncService,
                backfillQueueService,
                tradingCalendar,
                timeProvider,
                marketCandleRepository,
                dailyStockContextRepository,
                dailyDataStatusRepository,
                backfillJobRepository,
                workflowStatusRepository,
                stockUniverseRepository,
                stockContextCache,
                liveMarketCandleService,
                angelOneWebSocketService,
                runtimeAutomationService,
                positionManagerServiceProvider
        );
    }

    @Test
    @DisplayName("Should reject restart if requested date is not today")
    void restartTodayWorkflow_shouldRejectNonTodayDate() {
        LocalDate today = LocalDate.of(2026, 10, 12);
        LocalDate otherDate = LocalDate.of(2026, 10, 11);
        when(timeProvider.today()).thenReturn(today);

        assertThrows(IllegalArgumentException.class, () -> service.restartTodayWorkflow(otherDate));
        verifyNoInteractions(marketCandleRepository);
    }

    @Test
    @DisplayName("Should reject restart if today is not an active trading day")
    void restartTodayWorkflow_shouldRejectNonTradingDay() {
        LocalDate today = LocalDate.of(2026, 10, 12);
        when(timeProvider.today()).thenReturn(today);
        when(tradingCalendar.isTradingDay(today)).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> service.restartTodayWorkflow(today));
        verifyNoInteractions(marketCandleRepository);
    }

    @Test
    @DisplayName("Should suspend watchdog, teardown ingestion, purge DB & RAM caches, re-run stages, and prioritize backfills")
    void restartTodayWorkflow_fullLifecycleExecution() {
        LocalDate today = LocalDate.of(2026, 10, 12);
        LocalDateTime now = LocalDateTime.of(2026, 10, 12, 11, 30);
        when(timeProvider.today()).thenReturn(today);
        when(timeProvider.nowDateTime()).thenReturn(now);
        when(tradingCalendar.isTradingDay(today)).thenReturn(true);

        when(marketCandleRepository.deleteByCandleTimeBetween(any(), any())).thenReturn(1500L);
        when(workflowStatusRepository.deleteByProcessDate(today.toString())).thenReturn(5L);

        StockUniverse tradable = StockUniverse.builder()
                .symbol("RELIANCE")
                .exchange(Exchange.NSE)
                .isActive(true)
                .isTradable(true)
                .build();
        StockUniverse untradable = StockUniverse.builder()
                .symbol("IDEA")
                .exchange(Exchange.NSE)
                .isActive(true)
                .isTradable(false)
                .build();
        when(stockUniverseRepository.findByIsActiveTrue()).thenReturn(List.of(tradable, untradable));
        when(backfillQueueService.enqueueIntradayGap(anyString(), anyString(), any(), any(), any(), anyInt()))
                .thenReturn(1);

        MarketQuoteSyncService.QuoteSyncResult quoteResult =
                new MarketQuoteSyncService.QuoteSyncResult(true, 2, 2, 2, "Synced");
        when(marketQuoteSyncService.syncMarketQuotesIfMissing(today)).thenReturn(quoteResult);

        DayWorkflowRestartService.DayRestartResult result = service.restartTodayWorkflow(today);

        assertTrue(result.success());
        assertEquals(1500L, result.deletedCandles());
        assertEquals(5L, result.deletedWorkflowStages());
        assertEquals(2, result.backfillJobsQueued());

        // 1. Verify watchdog was suspended and then resumed
        verify(runtimeAutomationService).suspendWatchdogUntil(now.plusMinutes(2));
        verify(runtimeAutomationService).resumeWatchdog();

        // 2. Verify ingestion teardown without dirty flush
        verify(angelOneWebSocketService).disconnect();
        verify(liveMarketCandleService).clearOpenCandlesWithoutPersisting();

        // 3. Verify in-memory cache clearing
        verify(stockContextCache).clear();

        // 4. Verify pre-market workflow stages re-run
        verify(preMarketWorkflowService).runMorningMaintenance();
        verify(preMarketWorkflowService).runPreMarketDataPipeline();
        verify(marketQuoteSyncService).syncMarketQuotesIfMissing(today);
        verify(preMarketWorkflowService).startLiveRuntime();

        // 5. Verify prioritized backfill: tradable=priority 0, untradable=priority 10
        LocalDateTime expectedFrom = today.atTime(9, 15);
        LocalDateTime expectedTo = LocalDateTime.of(2026, 10, 12, 11, 29);
        verify(backfillQueueService).enqueueIntradayGap(
                eq("RELIANCE"), eq("NSE"), eq(today), eq(expectedFrom), eq(expectedTo), eq(0));
        verify(backfillQueueService).enqueueIntradayGap(
                eq("IDEA"), eq("NSE"), eq(today), eq(expectedFrom), eq(expectedTo), eq(10));
    }

    @Test
    @DisplayName("Purge should delete today's DB records and clear in-memory cache without impacting previous days")
    void purgeTodayState_shouldDeleteOnlyTodayAndClearCache() {
        LocalDate today = LocalDate.of(2026, 10, 12);
        when(marketCandleRepository.deleteByCandleTimeBetween(
                eq(today.atStartOfDay()), eq(today.atTime(23, 59, 59)))).thenReturn(500L);

        long deleted = service.purgeTodayState(today);

        assertEquals(500L, deleted);
        verify(dailyStockContextRepository).deleteByTradingDate(today);
        verify(dailyDataStatusRepository).deleteByTradingDate(today);
        verify(backfillJobRepository).deleteByTradingDate(today);
        verify(stockContextCache).clear();
    }
}
