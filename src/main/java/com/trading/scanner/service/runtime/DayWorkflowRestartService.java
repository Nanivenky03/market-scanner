package com.trading.scanner.service.runtime;

import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.StockUniverse;
import com.trading.scanner.position.PositionManagerService;
import com.trading.scanner.repository.*;
import com.trading.scanner.service.data.BackfillQueueService;
import com.trading.scanner.service.data.LiveMarketCandleService;
import com.trading.scanner.service.provider.angelone.AngelOneWebSocketService;
import com.trading.scanner.service.provider.angelone.LiveMarketSnapshotService;
import com.trading.scanner.service.provider.angelone.WebSocketFrameCaptureService;
import com.trading.scanner.strategy.cache.StockContextCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Service to execute the mid-day restart of today's workflow from scratch.
 * Safely purges today's partial data (with zero impact on previous days or production order tables),
 * cleans all in-memory candle sliding windows, tick accumulators, snapshots and frame captures,
 * puts recovery watchdogs on explicit HOLD, re-executes premarket stages, synchronizes 09:08 premarket quotes,
 * connects WebSocket live streaming, and enqueues prioritized historical backfills.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DayWorkflowRestartService {

    private final PreMarketWorkflowService preMarketWorkflowService;
    private final MarketQuoteSyncService marketQuoteSyncService;
    private final BackfillQueueService backfillQueueService;
    private final TradingCalendar tradingCalendar;
    private final TimeProvider timeProvider;

    private final MarketCandleRepository marketCandleRepository;
    private final DailyStockContextRepository dailyStockContextRepository;
    private final DailyDataStatusRepository dailyDataStatusRepository;
    private final BackfillJobRepository backfillJobRepository;
    private final WorkflowStatusRepository workflowStatusRepository;
    private final StockUniverseRepository stockUniverseRepository;

    private final StockContextCache stockContextCache;
    private final LiveMarketCandleService liveMarketCandleService;
    private final LiveMarketSnapshotService liveMarketSnapshotService;
    private final WebSocketFrameCaptureService webSocketFrameCaptureService;
    private final AngelOneWebSocketService angelOneWebSocketService;
    private final RuntimeAutomationService runtimeAutomationService;
    private final ObjectProvider<PositionManagerService> positionManagerServiceProvider;

    private final AtomicBoolean isRestartInProgress = new AtomicBoolean(false);

    @Value("${runtime.simulation.enabled:false}")
    private boolean simulationEnabled;

    public record DayRestartResult(
            boolean success,
            LocalDate tradingDate,
            long deletedCandles,
            long deletedWorkflowStages,
            MarketQuoteSyncService.QuoteSyncResult quoteSyncResult,
            int backfillJobsQueued,
            String message) {
    }

    /**
     * Executes mid-day workflow restart for today.
     * Strictly fails if date is not today or not a trading day, or if a restart is already in progress.
     */
    public DayRestartResult restartTodayWorkflow(LocalDate requestedDate) {
        if (!isRestartInProgress.compareAndSet(false, true)) {
            throw new IllegalStateException(
                    "A mid-day workflow restart is already in progress. Concurrent restart requests are rejected.");
        }

        try {
            LocalDate today = timeProvider.today();
            LocalDate targetDate = requestedDate != null ? requestedDate : today;

            // Safety Gate: strictly only today is allowed
            if (!targetDate.equals(today)) {
                throw new IllegalArgumentException(
                        "Workflow restart is strictly permitted only for today (" + today + "). Requested: " + targetDate);
            }

            if (!tradingCalendar.isTradingDay(targetDate)) {
                throw new IllegalStateException("Today (" + targetDate + ") is not an active trading day");
            }

            LocalDateTime now = timeProvider.nowDateTime();
            log.info("Starting mid-day workflow restart for date={} (simulationEnabled={})", targetDate, simulationEnabled);

            // 1. Put watchdog on explicit HOLD to prevent race conditions during restart
            if (runtimeAutomationService != null) {
                runtimeAutomationService.acquireWatchdogHold();
            }

            // 2. Teardown active ingestion: disconnect WebSocket and discard open candles without DB flush
            if (angelOneWebSocketService != null) {
                try {
                    angelOneWebSocketService.disconnect();
                } catch (Exception ex) {
                    log.warn("WebSocket disconnect during restart warning: {}", ex.getMessage());
                }
            }

            if (liveMarketCandleService != null) {
                try {
                    liveMarketCandleService.clearOpenCandlesWithoutPersisting();
                } catch (Exception ex) {
                    log.warn("Clear open candles warning: {}", ex.getMessage());
                }
            }

            // 3. Purge today's state cleanly and clear all in-memory caches
            long deletedCandles = purgeTodayState(targetDate);
            long deletedStages = workflowStatusRepository.deleteByProcessDate(targetDate.toString());

            // 4. Execute Pre-Market Stages 1 to 5
            log.info("Executing Stage 1-2 (Morning Maintenance)...");
            preMarketWorkflowService.runMorningMaintenance();

            log.info("Executing Stage 3-5 (Pre-Market Data Pipeline)...");
            preMarketWorkflowService.runPreMarketDataPipeline();

            // 5. Batch fetch SmartAPI Market Quotes & synthesize 09:08 pre-market candles
            log.info("Executing Market Quote synchronization for 09:08 candles...");
            MarketQuoteSyncService.QuoteSyncResult quoteResult = marketQuoteSyncService.syncMarketQuotesIfMissing(targetDate);

            // 6. Connect WebSocket immediately (Stage 6: premarket-live-start)
            log.info("Executing Stage 6 (Live WebSocket Connect & Subscribe)...");
            preMarketWorkflowService.startLiveRuntime();

            // 7. Asynchronously queue prioritized historical 1-minute backfill (09:15 to last closed minute)
            LocalTime marketOpen = LocalTime.of(9, 15);
            int queuedBackfills = 0;

            if (now.toLocalTime().isAfter(marketOpen)) {
                LocalDateTime fromTime = targetDate.atTime(marketOpen);
                // Strictly queue up to last completed minute so current running minute is not partial
                LocalDateTime toTime = now.truncatedTo(ChronoUnit.MINUTES).minusMinutes(1);

                if (!toTime.isBefore(fromTime)) {
                    queuedBackfills = queueMorningCatchup(targetDate, fromTime, toTime);
                    log.info("Queued {} prioritized historical backfill jobs from {} to {}", queuedBackfills, fromTime, toTime);
                }
            }

            return new DayRestartResult(
                    true,
                    targetDate,
                    deletedCandles,
                    deletedStages,
                    quoteResult,
                    queuedBackfills,
                    "Today's workflow restarted successfully. Live WebSocket active and prioritized backfills queued.");
        } finally {
            isRestartInProgress.set(false);
            // Always ensure watchdog hold is released
            if (runtimeAutomationService != null) {
                runtimeAutomationService.releaseWatchdogHold();
            }
        }
    }

    @Transactional
    public long purgeTodayState(LocalDate targetDate) {
        LocalDateTime dayStart = targetDate.atStartOfDay();
        LocalDateTime dayEnd = targetDate.atTime(23, 59, 59);

        // Delete today's market_candles across all timeframes
        long deletedCandles = marketCandleRepository.deleteByCandleTimeBetween(dayStart, dayEnd);

        // Clean daily status and context for today (production order/position tables are strictly preserved)
        dailyStockContextRepository.deleteByTradingDate(targetDate);
        dailyDataStatusRepository.deleteByTradingDate(targetDate);
        backfillJobRepository.deleteByTradingDate(targetDate);

        // Clear all in-memory caches and state across services
        if (stockContextCache != null) {
            stockContextCache.clear();
        }
        if (liveMarketSnapshotService != null) {
            liveMarketSnapshotService.clear();
        }
        if (webSocketFrameCaptureService != null) {
            webSocketFrameCaptureService.clear();
        }

        log.info("Purged today's state for date={}: deletedCandles={}, all in-memory caches cleared", targetDate, deletedCandles);
        return deletedCandles;
    }

    private int queueMorningCatchup(LocalDate targetDate, LocalDateTime fromTime, LocalDateTime toTime) {
        List<StockUniverse> activeUniverse = stockUniverseRepository.findByIsActiveTrue();
        if (activeUniverse == null || activeUniverse.isEmpty()) {
            return 0;
        }

        int count = 0;
        for (StockUniverse stock : activeUniverse) {
            String exchange = stock.getExchange() != null ? stock.getExchange().name() : "NSE";
            // Tradable symbols receive high priority (0) to unblock strategies rapidly; others receive priority 10
            int priority = Boolean.TRUE.equals(stock.getIsTradable()) ? 0 : 10;
            int jobs = backfillQueueService.enqueueIntradayGap(
                    stock.getSymbol(), exchange, targetDate, fromTime, toTime, priority);
            if (jobs > 0) {
                count++;
            }
        }
        return count;
    }
}
