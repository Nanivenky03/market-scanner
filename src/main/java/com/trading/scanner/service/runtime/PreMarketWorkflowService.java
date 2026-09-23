package com.trading.scanner.service.runtime;

import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.config.RuntimeAutomationProperties;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.config.provider.AngelOneProperties;
import com.trading.scanner.model.InstrumentMaster;
import com.trading.scanner.model.StockUniverse;
import com.trading.scanner.model.WorkflowStatus;
import com.trading.scanner.repository.InstrumentMasterRepository;
import com.trading.scanner.repository.StockUniverseRepository;
import com.trading.scanner.service.data.EodDataEntryService;
import com.trading.scanner.service.engine.VolumeBaselineService;
import com.trading.scanner.service.instrument.AngelOneInstrumentCatalogSyncService;
import com.trading.scanner.service.instrument.InstrumentMasterSyncService;
import com.trading.scanner.service.instrument.InstrumentTokenSyncService;
import com.trading.scanner.service.provider.angelone.AngelOneSessionService;
import com.trading.scanner.service.workflow.WorkflowStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PreMarketWorkflowService {

    private final MarketCalendarService marketCalendarService;
    private final TradingCalendar tradingCalendar;
    private final RuntimeHousekeepingService runtimeHousekeepingService;
    private final AngelOneInstrumentCatalogSyncService catalogSyncService;
    private final InstrumentMasterSyncService instrumentMasterSyncService;
    private final AngelOneSessionService angelOneSessionService;
    private final InstrumentTokenSyncService instrumentTokenSyncService;
    private final StockUniverseRepository stockUniverseRepository;
    private final InstrumentMasterRepository instrumentMasterRepository;
    private final EodDataEntryService eodDataEntryService;
    private final VolumeBaselineService volumeBaselineService;
    private final RuntimeSettingService runtimeSettingService;
    private final WorkflowStatusService workflowStatusService;
    private final RuntimeAutomationService runtimeAutomationService;
    private final RuntimeAutomationProperties runtimeAutomationProperties;
    private final AngelOneProperties angelOneProperties;
    private final TimeProvider timeProvider;

    private final Object workflowLock = new Object();

        /**
     * 07:00 AM Morning Maintenance Chain:
     * 1. Startup Bootstrap Verification (Fail-closed if runtime-bootstrap-complete is not SUCCESS)
     * 2. trading-day-init (Checks holiday calendar, updates runtime.process.date; skips if weekend/holiday)
     * 3. premarket-housekeeping (Purges candles, signals, snapshots beyond retention days & clears memory)
     */
    public WorkflowResult runMorningMaintenance() {
        synchronized (workflowLock) {
            LocalDate today = timeProvider.today();

            // Prerequisite: Verify one-time startup bootstrap is complete
            if (!workflowStatusService.isSuccessfulActiveStartupWorkflow(WorkflowStatusService.RUNTIME_BOOTSTRAP_COMPLETE)) {
                throw new IllegalStateException("Morning maintenance blocked: Startup bootstrap sequence is not complete");
            }

            // Refresh official NSE holiday calendar
            try {
                marketCalendarService.refreshFromOfficialSourceIfDue(timeProvider.nowDateTime());
            } catch (Exception ex) {
                log.warn("Official NSE holiday refresh failed during morning maintenance: {}", ex.getMessage());
            }

            if (!tradingCalendar.isTradingDay(today)) {
                log.info("Today ({}) is not a trading day (Weekend / NSE Holiday). Skipping daily pre-market workflows.", today);
                return skipped(WorkflowStatusService.TRADING_DAY_INIT, today, "Non-trading day. Daily workflows skipped.");
            }

            // Step 1: trading-day-init
            WorkflowStatus dayInit = executeStage(WorkflowStatusService.TRADING_DAY_INIT, today, null, () -> {
                runtimeSettingService.setProcessDate(today);
                return "Trading day initialized successfully for date: " + today;
            });

            // Step 2: premarket-housekeeping (Chained to trading-day-init)
            executeStage(WorkflowStatusService.PREMARKET_HOUSEKEEPING, today, dayInit.getWorkflowId(), () -> {
                RuntimeHousekeepingService.HousekeepingResult result = runtimeHousekeepingService.runHousekeeping();
                return "Housekeeping completed. deletedCandles=" + (result.deletedOneMinuteCandles() + result.deletedFiveMinuteCandles() + result.deletedFifteenMinuteCandles())
                        + ", deletedSignals=" + result.deletedLiveSignals();
            });

            return complete(WorkflowStatusService.PREMARKET_HOUSEKEEPING, today, "Morning maintenance chain completed successfully");
        }
    }

    /**
     * 08:00 AM Pre-Market Data Preparation Chain:
     * 3. premarket-catalog-sync (Catalog download, NIFTY check, session login, token sync)
     * 4. premarket-universe-sync (Reconcile stock_universe with instrument_master + prior day EOD completeness)
     * 5. premarket-morning-reference (Compute CPR, ADR, resistance, volume baselines)
     */
    public WorkflowResult runPreMarketDataPipeline() {
        synchronized (workflowLock) {
            LocalDate today = timeProvider.today();

            if (!tradingCalendar.isTradingDay(today)) {
                return skipped(WorkflowStatusService.PREMARKET_CATALOG_SYNC, today, "Non-trading day. Data pipeline skipped.");
            }

            // Prerequisite: Verify both morning maintenance steps succeeded for today
            if (!workflowStatusService.isSuccessfulActiveDailyWorkflow(WorkflowStatusService.TRADING_DAY_INIT, WorkflowStatusService.PREMARKET_GROUP, today.toString())) {
                throw new IllegalStateException("Pre-market data pipeline blocked: trading-day-init is not SUCCESS for today (" + today + ")");
            }

            if (!workflowStatusService.isSuccessfulActiveDailyWorkflow(WorkflowStatusService.PREMARKET_HOUSEKEEPING, WorkflowStatusService.PREMARKET_GROUP, today.toString())) {
                throw new IllegalStateException("Pre-market data pipeline blocked: premarket-housekeeping is not SUCCESS for today (" + today + ")");
            }

            // Step 3: premarket-catalog-sync
            WorkflowStatus catalogStep = executeStage(WorkflowStatusService.PREMARKET_CATALOG_SYNC, today, null, () -> {
                if (!angelOneProperties.enabled()) {
                    throw new IllegalStateException("Angel One provider is disabled");
                }

                AngelOneInstrumentCatalogSyncService.CatalogSyncResult catalogResult = catalogSyncService.syncNseCatalog();
                if (catalogResult == null || catalogResult.importedRecords() == 0 || !catalogResult.niftyImported()) {
                    throw new IllegalStateException("Instrument catalog synchronization failed or missing NIFTY");
                }

                int requiredChanges = instrumentMasterSyncService.ensureRequiredMarketReferences();

                // Authenticate broker session early
                angelOneSessionService.createSessionTokens();

                // Sync active instruments tokens
                InstrumentTokenSyncService.TokenSyncResult tokenResult = instrumentTokenSyncService.syncActiveInstruments();
                if (tokenResult == null || tokenResult.failed() > 0) {
                    throw new IllegalStateException("Token synchronization failed. failed=" + (tokenResult != null ? tokenResult.failed() : "null"));
                }

                return "Catalog synced (imported=" + catalogResult.importedRecords() + "), market references verified (" + requiredChanges + "), tokens mapped (" + tokenResult.mapped() + ")";
            });

            // Step 4: premarket-universe-sync (Chained to premarket-catalog-sync)
            WorkflowStatus universeStep = executeStage(WorkflowStatusService.PREMARKET_UNIVERSE_SYNC, today, catalogStep.getWorkflowId(), () -> {
                List<StockUniverse> universe = stockUniverseRepository.findAll();
                LocalDate prevDay = tradingCalendar.previousTradingDay(today);
                List<String> untradableSymbols = new ArrayList<>();
                int activeCount = 0;
                int tradableCount = 0;

                for (StockUniverse stock : universe) {
                    String exchange = stock.getExchange() != null ? stock.getExchange().name() : "NSE";
                    InstrumentMaster inst = instrumentMasterRepository.findBySymbolAndExchange(stock.getSymbol(), exchange).orElse(null);

                    // Reconcile with instrument_master
                    if (inst == null || !Boolean.TRUE.equals(inst.getIsActive())) {
                        stock.setIsActive(false);
                        stock.setIsTradable(false);
                        stockUniverseRepository.save(stock);
                        continue;
                    }

                    if (Boolean.TRUE.equals(stock.getIsActive())) {
                        activeCount++;

                        // EOD Completeness Check for previous trading day
                        boolean eodComplete = eodDataEntryService.isSuccessful(stock.getSymbol(), exchange, prevDay);
                        if (!eodComplete) {
                            if (Boolean.TRUE.equals(stock.getIsTradable())) {
                                stock.setIsTradable(false);
                                stockUniverseRepository.save(stock);
                                untradableSymbols.add(stock.getSymbol());
                            }
                        } else if (Boolean.TRUE.equals(stock.getIsTradable())) {
                            tradableCount++;
                        }
                    }
                }

                if (!untradableSymbols.isEmpty()) {
                    log.warn("Demoted {} symbols to isTradable=false due to incomplete EOD data for {}: {}",
                            untradableSymbols.size(), prevDay, String.join(", ", untradableSymbols));
                }

                return "Universe reconciled. active=" + activeCount + ", tradable=" + tradableCount + ", eodDemoted=" + untradableSymbols.size();
            });

            // Step 5: premarket-morning-reference (Chained to premarket-universe-sync)
            executeStage(WorkflowStatusService.PREMARKET_MORNING_REFERENCE, today, universeStep.getWorkflowId(), () -> {
                LocalDate prevDay = tradingCalendar.previousTradingDay(today);
                VolumeBaselineService.PreCalculationResult baselineResult = volumeBaselineService.preCalculateForCompletedTradingDay(prevDay);
                return "Morning baselines pre-calculated for " + prevDay + ". processedSymbols=" + baselineResult.processedSymbols();
            });

            return complete(WorkflowStatusService.PREMARKET_MORNING_REFERENCE, today, "Pre-market data preparation pipeline completed successfully");
        }
    }

    /**
     * 08:55 AM Final Pre-Market Live Start Gate:
     * 6. premarket-live-start (Verifies all 5 prior steps SUCCESS, connects WebSocket & subscribes)
     */
    public WorkflowResult startLiveRuntime() {
        synchronized (workflowLock) {
            LocalDate today = timeProvider.today();

            if (!tradingCalendar.isTradingDay(today)) {
                return skipped(WorkflowStatusService.PREMARKET_LIVE_START, today, "Non-trading day. Live start skipped.");
            }

            if (!runtimeAutomationProperties.getLive().isAutoRun()) {
                return skipped(WorkflowStatusService.PREMARKET_LIVE_START, today, "Live runtime auto-run is disabled");
            }

            // Verify all previous 5 steps for today are SUCCESS
            String dateStr = today.toString();
            List<String> requiredStages = List.of(
                    WorkflowStatusService.TRADING_DAY_INIT,
                    WorkflowStatusService.PREMARKET_HOUSEKEEPING,
                    WorkflowStatusService.PREMARKET_CATALOG_SYNC,
                    WorkflowStatusService.PREMARKET_UNIVERSE_SYNC,
                    WorkflowStatusService.PREMARKET_MORNING_REFERENCE);

            for (String stage : requiredStages) {
                if (!workflowStatusService.isSuccessfulActiveDailyWorkflow(stage, WorkflowStatusService.PREMARKET_GROUP, dateStr)) {
                    throw new IllegalStateException("Live runtime start blocked: prerequisite stage [" + stage + "] is not SUCCESS for today (" + dateStr + ")");
                }
            }

            // Step 6: premarket-live-start
            executeStage(WorkflowStatusService.PREMARKET_LIVE_START, today, null, () -> {
                RuntimeAutomationService.RuntimeActionResult result = runtimeAutomationService.connectAndSubscribe();
                if (!result.websocketConnected()) {
                    throw new IllegalStateException("WebSocket connection failed: " + result.message());
                }
                return "Live WebSocket connected and active universe subscribed successfully: " + result.message();
            });

            return complete(WorkflowStatusService.PREMARKET_LIVE_START, today, "Live runtime started successfully");
        }
    }

    private WorkflowStatus executeStage(String stageName, LocalDate date, UUID dependsOnId, StageAction action) {
        String dateStr = date.toString();
        WorkflowStatus workflow = workflowStatusService.prepareForDaily(
                stageName,
                WorkflowStatusService.PREMARKET_GROUP,
                dateStr,
                dependsOnId);

        if (workflow.getStatus() == WorkflowStatus.Status.SUCCESS) {
            log.info("Daily stage [{}] already SUCCESS for date={}", stageName, dateStr);
            return workflow;
        }

        WorkflowStatus running = workflowStatusService.markRunning(workflow.getWorkflowId());
        try {
            String message = action.execute();
            WorkflowStatus success = workflowStatusService.markSuccess(running.getWorkflowId(), message);
            log.info("Daily stage [{}] completed successfully for date={}: {}", stageName, dateStr, message);
            return success;
        } catch (Exception ex) {
            workflowStatusService.markFailed(running.getWorkflowId(), "Daily stage failed: " + stageName, ex);
            log.error("Daily stage [{}] failed for date={}: {}", stageName, dateStr, ex.getMessage(), ex);
            throw new IllegalStateException("Daily stage [" + stageName + "] failed for date=" + dateStr, ex);
        }
    }

    private WorkflowResult complete(String stage, LocalDate date, String message) {
        return new WorkflowResult(stage, date, "COMPLETE", message);
    }

    private WorkflowResult skipped(String stage, LocalDate date, String message) {
        return new WorkflowResult(stage, date, "SKIPPED", message);
    }

    public record WorkflowResult(
            String stage,
            LocalDate workflowDate,
            String status,
            String message) {
    }

    @FunctionalInterface
    private interface StageAction {
        String execute() throws Exception;
    }
}