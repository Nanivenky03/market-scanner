package com.trading.scanner.service.runtime;

import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.StockUniverse;
import com.trading.scanner.repository.*;
import com.trading.scanner.service.data.BackfillQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Service to execute the mid-day restart of today's workflow from scratch.
 * Safely purges today's partial data (with zero impact on previous days),
 * re-executes all 6 premarket stages, synchronizes 09:08 premarket quotes,
 * connects WebSocket live streaming, and enqueues historical backfills
 * for 09:15 up to the last closed minute.
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
     * Strictly fails if date is not today or not a trading day.
     */
    public DayRestartResult restartTodayWorkflow(LocalDate requestedDate) {
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

        log.info("Starting mid-day workflow restart for date={} (simulationEnabled={})", targetDate, simulationEnabled);

        // 1. Purge today's state cleanly
        long deletedCandles = purgeTodayState(targetDate);
        long deletedStages = workflowStatusRepository.deleteByProcessDate(targetDate.toString());

        // 2. Execute Pre-Market Stages 1 to 5
        log.info("Executing Stage 1-2 (Morning Maintenance)...");
        preMarketWorkflowService.runMorningMaintenance();

        log.info("Executing Stage 3-5 (Pre-Market Data Pipeline)...");
        preMarketWorkflowService.runPreMarketDataPipeline();

        // 3. Batch fetch SmartAPI Market Quotes & synthesize 09:08 pre-market candles
        log.info("Executing Market Quote synchronization for 09:08 candles...");
        MarketQuoteSyncService.QuoteSyncResult quoteResult = marketQuoteSyncService.syncMarketQuotesIfMissing(targetDate);

        // 4. Connect WebSocket immediately (Stage 6: premarket-live-start)
        log.info("Executing Stage 6 (Live WebSocket Connect & Subscribe)...");
        preMarketWorkflowService.startLiveRuntime();

        // 5. Asynchronously queue historical 1-minute backfill from 09:15 to last closed minute
        LocalDateTime now = timeProvider.nowDateTime();
        LocalTime marketOpen = LocalTime.of(9, 15);
        int queuedBackfills = 0;

        if (now.toLocalTime().isAfter(marketOpen)) {
            LocalDateTime fromTime = targetDate.atTime(marketOpen);
            // Strictly queue up to last completed minute so current running minute is not partial
            LocalDateTime toTime = now.truncatedTo(ChronoUnit.MINUTES).minusMinutes(1);

            if (!toTime.isBefore(fromTime)) {
                queuedBackfills = queueMorningCatchup(targetDate, fromTime, toTime);
                log.info("Queued {} historical backfill jobs from {} to {}", queuedBackfills, fromTime, toTime);
            }
        }

        return new DayRestartResult(
                true,
                targetDate,
                deletedCandles,
                deletedStages,
                quoteResult,
                queuedBackfills,
                "Today's workflow restarted successfully. Live WebSocket active and backfill jobs queued.");
    }

    @Transactional
    public long purgeTodayState(LocalDate targetDate) {
        LocalDateTime dayStart = targetDate.atStartOfDay();
        LocalDateTime dayEnd = targetDate.atTime(23, 59, 59);

        // Delete today's market_candles across all timeframes
        long deletedCandles = marketCandleRepository.deleteByCandleTimeBetween(dayStart, dayEnd);

        // Clean daily status and context for today
        dailyStockContextRepository.deleteByTradingDate(targetDate);
        dailyDataStatusRepository.deleteByTradingDate(targetDate);
        backfillJobRepository.deleteByTradingDate(targetDate);

        log.info("Purged today's state for date={}: deletedCandles={}", targetDate, deletedCandles);
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
            int jobs = backfillQueueService.enqueueIntradayGap(
                    stock.getSymbol(), exchange, targetDate, fromTime, toTime);
            if (jobs > 0) {
                count++;
            }
        }
        return count;
    }
}
