package com.trading.scanner.service.runtime;

import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.service.data.BackfillQueueService;
import com.trading.scanner.service.data.EodReconciliationService;
import com.trading.scanner.service.provider.angelone.LiveMarketSnapshotService;
import com.trading.scanner.service.workflow.WorkflowStatusService;
import com.trading.scanner.model.WorkflowStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Service
@Slf4j
public class StartupRecoveryService {

    private final TimeProvider timeProvider;
    private final TradingCalendar tradingCalendar;
    private final BackfillQueueService backfillQueueService;
    private final LiveMarketSnapshotService liveMarketSnapshotService;
    private final PreMarketWorkflowService preMarketWorkflowService;
    private final EodReconciliationService eodReconciliationService;
    private final WorkflowStatusService workflowStatusService;
    private final org.springframework.beans.factory.ObjectProvider<com.trading.scanner.position.PositionManagerService> positionManagerServiceProvider;
    private final org.springframework.beans.factory.ObjectProvider<com.trading.scanner.repository.PositionRepository> positionRepositoryProvider;
    private final org.springframework.beans.factory.ObjectProvider<com.trading.scanner.repository.SimulatedPositionRepository> simulatedPositionRepositoryProvider;
    private final org.springframework.beans.factory.ObjectProvider<com.trading.scanner.strategy.cache.StockContextCache> stockContextCacheProvider;
    private final org.springframework.beans.factory.ObjectProvider<com.trading.scanner.repository.StockUniverseRepository> stockUniverseRepositoryProvider;
    private final org.springframework.beans.factory.ObjectProvider<com.trading.scanner.execution.adapter.SimulatedOrderAdapter> simulatedOrderAdapterProvider;
    private final org.springframework.beans.factory.ObjectProvider<com.trading.scanner.risk.RiskEngine> riskEngineProvider;
    private final org.springframework.beans.factory.ObjectProvider<com.trading.scanner.execution.SimulationManagementService> simulationManagementServiceProvider;

    @org.springframework.beans.factory.annotation.Autowired
    public StartupRecoveryService(
            TimeProvider timeProvider,
            TradingCalendar tradingCalendar,
            BackfillQueueService backfillQueueService,
            LiveMarketSnapshotService liveMarketSnapshotService,
            PreMarketWorkflowService preMarketWorkflowService,
            EodReconciliationService eodReconciliationService,
            WorkflowStatusService workflowStatusService,
            org.springframework.beans.factory.ObjectProvider<com.trading.scanner.position.PositionManagerService> positionManagerServiceProvider,
            org.springframework.beans.factory.ObjectProvider<com.trading.scanner.repository.PositionRepository> positionRepositoryProvider,
            org.springframework.beans.factory.ObjectProvider<com.trading.scanner.repository.SimulatedPositionRepository> simulatedPositionRepositoryProvider,
            org.springframework.beans.factory.ObjectProvider<com.trading.scanner.strategy.cache.StockContextCache> stockContextCacheProvider,
            org.springframework.beans.factory.ObjectProvider<com.trading.scanner.repository.StockUniverseRepository> stockUniverseRepositoryProvider,
            org.springframework.beans.factory.ObjectProvider<com.trading.scanner.execution.adapter.SimulatedOrderAdapter> simulatedOrderAdapterProvider,
            org.springframework.beans.factory.ObjectProvider<com.trading.scanner.risk.RiskEngine> riskEngineProvider,
            org.springframework.beans.factory.ObjectProvider<com.trading.scanner.execution.SimulationManagementService> simulationManagementServiceProvider) {
        this.timeProvider = timeProvider;
        this.tradingCalendar = tradingCalendar;
        this.backfillQueueService = backfillQueueService;
        this.liveMarketSnapshotService = liveMarketSnapshotService;
        this.preMarketWorkflowService = preMarketWorkflowService;
        this.eodReconciliationService = eodReconciliationService;
        this.workflowStatusService = workflowStatusService;
        this.positionManagerServiceProvider = positionManagerServiceProvider;
        this.positionRepositoryProvider = positionRepositoryProvider;
        this.simulatedPositionRepositoryProvider = simulatedPositionRepositoryProvider;
        this.stockContextCacheProvider = stockContextCacheProvider;
        this.stockUniverseRepositoryProvider = stockUniverseRepositoryProvider;
        this.simulatedOrderAdapterProvider = simulatedOrderAdapterProvider;
        this.riskEngineProvider = riskEngineProvider;
        this.simulationManagementServiceProvider = simulationManagementServiceProvider;
    }

    public StartupRecoveryService(
            TimeProvider timeProvider,
            TradingCalendar tradingCalendar,
            BackfillQueueService backfillQueueService,
            LiveMarketSnapshotService liveMarketSnapshotService,
            PreMarketWorkflowService preMarketWorkflowService,
            EodReconciliationService eodReconciliationService,
            WorkflowStatusService workflowStatusService,
            org.springframework.beans.factory.ObjectProvider<com.trading.scanner.position.PositionManagerService> positionManagerServiceProvider,
            org.springframework.beans.factory.ObjectProvider<com.trading.scanner.repository.PositionRepository> positionRepositoryProvider,
            org.springframework.beans.factory.ObjectProvider<com.trading.scanner.repository.SimulatedPositionRepository> simulatedPositionRepositoryProvider,
            org.springframework.beans.factory.ObjectProvider<com.trading.scanner.strategy.cache.StockContextCache> stockContextCacheProvider,
            org.springframework.beans.factory.ObjectProvider<com.trading.scanner.repository.StockUniverseRepository> stockUniverseRepositoryProvider) {
        this(timeProvider, tradingCalendar, backfillQueueService, liveMarketSnapshotService,
                preMarketWorkflowService, eodReconciliationService, workflowStatusService,
                positionManagerServiceProvider, positionRepositoryProvider, simulatedPositionRepositoryProvider,
                stockContextCacheProvider, stockUniverseRepositoryProvider, null, null, null);
    }

    public StartupRecoveryService(
            TimeProvider timeProvider,
            TradingCalendar tradingCalendar,
            BackfillQueueService backfillQueueService,
            LiveMarketSnapshotService liveMarketSnapshotService,
            PreMarketWorkflowService preMarketWorkflowService,
            EodReconciliationService eodReconciliationService,
            WorkflowStatusService workflowStatusService,
            org.springframework.beans.factory.ObjectProvider<com.trading.scanner.position.PositionManagerService> positionManagerServiceProvider,
            org.springframework.beans.factory.ObjectProvider<com.trading.scanner.repository.PositionRepository> positionRepositoryProvider,
            org.springframework.beans.factory.ObjectProvider<com.trading.scanner.repository.SimulatedPositionRepository> simulatedPositionRepositoryProvider) {
        this(timeProvider, tradingCalendar, backfillQueueService, liveMarketSnapshotService,
                preMarketWorkflowService, eodReconciliationService, workflowStatusService,
                positionManagerServiceProvider, positionRepositoryProvider, simulatedPositionRepositoryProvider,
                null, null, null, null, null);
    }

    public StartupRecoveryService(
            TimeProvider timeProvider,
            TradingCalendar tradingCalendar,
            BackfillQueueService backfillQueueService,
            LiveMarketSnapshotService liveMarketSnapshotService,
            PreMarketWorkflowService preMarketWorkflowService,
            EodReconciliationService eodReconciliationService,
            WorkflowStatusService workflowStatusService) {
        this(timeProvider, tradingCalendar, backfillQueueService, liveMarketSnapshotService,
                preMarketWorkflowService, eodReconciliationService, workflowStatusService,
                null, null, null, null, null, null, null, null);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverAtStartup() {
        try {
            RecoveryResult result = recoverNow();
            log.info("Startup recovery completed: {}", result);
        } catch (Exception ex) {
            log.error(
                    "Startup recovery failed; scheduled recovery remains active",
                    ex);
        }
    }

    public RecoveryResult recoverNow() {
        LocalDate today = timeProvider.today();
        LocalDateTime now = timeProvider.nowDateTime();

        int staleJobs = backfillQueueService.recoverStaleJobs();

        int detectedGaps = 0;

        if (tradingCalendar.isTradingDay(today)) {
            // 1. Catch up any missed pre-market workflow stages for today in chronological
            // sequence
            if (preMarketWorkflowService != null) {
                try {
                    preMarketWorkflowService.catchUpPreMarketWorkflowsIfDue();
                } catch (Exception ex) {
                    log.warn("Auto-catchup of pre-market workflows had issues: {}", ex.getMessage(), ex);
                }
            }

            // 2. If during market hours, detect and queue any missing minute gaps
            detectedGaps = liveMarketSnapshotService.checkForClosedMinuteGaps();

            // 3. If after market hours (post 17:00), auto-catchup EOD reconciliation if not
            // yet complete
            if (now.toLocalTime().isAfter(LocalTime.of(17, 0)) && eodReconciliationService != null) {
                try {
                    if (workflowStatusService != null) {
                        try {
                            WorkflowStatus marketHours = workflowStatusService.findOrCreateDailyWorkflow(
                                    WorkflowStatusService.MARKET_HOURS,
                                    WorkflowStatusService.MARKET_HOURS_GROUP,
                                    today.toString(),
                                    null);
                            if (marketHours != null && marketHours.getStatus() != WorkflowStatus.Status.SUCCESS) {
                                workflowStatusService.markSuccess(
                                        marketHours.getWorkflowId(),
                                        "Market hours finalized upon post-market startup recovery");
                                log.info("Market hours workflow stage marked SUCCESS upon startup recovery for date: {}", today);
                            }
                        } catch (Exception ex) {
                            log.warn("Failed to finalize market-hours stage during startup recovery: {}", ex.getMessage());
                        }
                    }
                    boolean eodSuccess = workflowStatusService.isSuccessfulActiveDailyWorkflow(
                            WorkflowStatusService.EOD_RECONCILIATION,
                            WorkflowStatusService.EOD_GROUP,
                            today.toString());
                    if (!eodSuccess) {
                        log.info("Auto-catching up EOD reconciliation for today ({}) on startup/recovery", today);
                        eodReconciliationService.reconcileTradingDay(today);
                    }
                } catch (Exception ex) {
                    log.warn("Auto-catchup EOD reconciliation for today ({}) had issues: {}", today, ex.getMessage(),
                            ex);
                }
            }

            // 4. Rehydrate active positions into RAM upon crash recovery
            if (positionManagerServiceProvider != null) {
                try {
                    com.trading.scanner.position.PositionManagerService pms = positionManagerServiceProvider.getIfAvailable();
                    if (pms != null) {
                        com.trading.scanner.repository.PositionRepository posRepo = positionRepositoryProvider != null ? positionRepositoryProvider.getIfAvailable() : null;
                        com.trading.scanner.repository.SimulatedPositionRepository simPosRepo = simulatedPositionRepositoryProvider != null ? simulatedPositionRepositoryProvider.getIfAvailable() : null;

                        java.util.List<com.trading.scanner.strategy.model.PositionSnapshot> liveSnapshots = new java.util.ArrayList<>();
                        if (posRepo != null) {
                            java.util.List<com.trading.scanner.model.Position> openPositions = posRepo.findByPositionStatus("OPEN");
                            for (com.trading.scanner.model.Position p : openPositions) {
                                liveSnapshots.add(mapPositionToSnapshot(p));
                            }
                        }

                        java.util.List<com.trading.scanner.strategy.model.PositionSnapshot> paperSnapshots = new java.util.ArrayList<>();
                        if (simPosRepo != null) {
                            java.util.List<com.trading.scanner.model.SimulatedPosition> openSimPositions = simPosRepo.findByPositionStatus("OPEN");
                            for (com.trading.scanner.model.SimulatedPosition p : openSimPositions) {
                                paperSnapshots.add(mapSimulatedPositionToSnapshot(p));
                            }
                        }

                        pms.rehydrateActiveState(com.trading.scanner.risk.model.PortfolioMode.PAPER, paperSnapshots);
                        pms.rehydrateActiveState(com.trading.scanner.risk.model.PortfolioMode.LIVE, liveSnapshots);

                        // 4.1 Rehydrate pending resting SL orders into SimulatedOrderAdapter
                        if (simulatedOrderAdapterProvider != null) {
                            com.trading.scanner.execution.adapter.SimulatedOrderAdapter adapter = simulatedOrderAdapterProvider.getIfAvailable();
                            if (adapter != null) {
                                adapter.rehydratePendingOrdersFromDb();
                            }
                        }

                        // 4.2 Sync compounded capital into simulation management
                        if (simulationManagementServiceProvider != null) {
                            com.trading.scanner.execution.SimulationManagementService sms = simulationManagementServiceProvider.getIfAvailable();
                            if (sms != null) {
                                sms.syncCompoundedCapital();
                            }
                        }

                        // 4.3 Rehydrate traded symbols and strategy limits into RiskEngine
                        if (riskEngineProvider != null) {
                            com.trading.scanner.risk.RiskEngine re = riskEngineProvider.getIfAvailable();
                            if (re != null) {
                                String todayPrefix = today.toString();
                                if (simPosRepo != null) {
                                    java.util.List<com.trading.scanner.model.SimulatedPosition> todayPaperPositions =
                                            simPosRepo.findByEntryTimeStartingWith(todayPrefix);
                                    java.util.Set<String> paperTradedSymbols = new java.util.HashSet<>();
                                    java.util.Map<Integer, Integer> paperTradesCount = new java.util.HashMap<>();
                                    java.util.Map<Integer, Integer> paperLossCount = new java.util.HashMap<>();

                                    for (com.trading.scanner.model.SimulatedPosition pos : todayPaperPositions) {
                                        if (pos.getSymbol() != null) {
                                            paperTradedSymbols.add(pos.getSymbol());
                                        }
                                        if (pos.getStrategyId() != null) {
                                            paperTradesCount.merge(pos.getStrategyId(), 1, Integer::sum);
                                            if ("CLOSED".equalsIgnoreCase(pos.getPositionStatus()) && pos.getNetPnl() != null && pos.getNetPnl() < 0.0) {
                                                paperLossCount.merge(pos.getStrategyId(), 1, Integer::sum);
                                            }
                                        }
                                    }

                                    re.rehydrateState(com.trading.scanner.risk.model.PortfolioMode.PAPER,
                                            paperSnapshots, todayPaperPositions.size(), paperTradedSymbols, null);

                                    paperTradesCount.forEach((stratId, count) ->
                                            re.recordStrategyTradeCount(com.trading.scanner.risk.model.PortfolioMode.PAPER, stratId, count));
                                    paperLossCount.forEach((stratId, count) ->
                                            re.recordStrategyLossCount(com.trading.scanner.risk.model.PortfolioMode.PAPER, stratId, count));
                                }

                                if (posRepo != null) {
                                    java.util.List<com.trading.scanner.model.Position> todayLivePositions =
                                            posRepo.findByEntryTimeStartingWith(todayPrefix);
                                    java.util.Set<String> liveTradedSymbols = new java.util.HashSet<>();
                                    java.util.Map<Integer, Integer> liveTradesCount = new java.util.HashMap<>();
                                    java.util.Map<Integer, Integer> liveLossCount = new java.util.HashMap<>();

                                    for (com.trading.scanner.model.Position pos : todayLivePositions) {
                                        if (pos.getSymbol() != null) {
                                            liveTradedSymbols.add(pos.getSymbol());
                                        }
                                        if (pos.getStrategyId() != null) {
                                            liveTradesCount.merge(pos.getStrategyId(), 1, Integer::sum);
                                            if ("CLOSED".equalsIgnoreCase(pos.getPositionStatus()) && pos.getNetPnl() != null && pos.getNetPnl() < 0.0) {
                                                liveLossCount.merge(pos.getStrategyId(), 1, Integer::sum);
                                            }
                                        }
                                    }

                                    re.rehydrateState(com.trading.scanner.risk.model.PortfolioMode.LIVE,
                                            liveSnapshots, todayLivePositions.size(), liveTradedSymbols, null);

                                    liveTradesCount.forEach((stratId, count) ->
                                            re.recordStrategyTradeCount(com.trading.scanner.risk.model.PortfolioMode.LIVE, stratId, count));
                                    liveLossCount.forEach((stratId, count) ->
                                            re.recordStrategyLossCount(com.trading.scanner.risk.model.PortfolioMode.LIVE, stratId, count));
                                }
                            }
                        }
                    }
                } catch (Exception ex) {
                    log.warn("Position rehydration on startup warning: {}", ex.getMessage());
                }
            }

            // 5. If market has already opened (>= 09:15) on an active trading day, warm up StockContextCache
            LocalTime marketOpen = LocalTime.of(9, 15);
            if (!now.toLocalTime().isBefore(marketOpen) && stockContextCacheProvider != null) {
                try {
                    com.trading.scanner.strategy.cache.StockContextCache cache = stockContextCacheProvider.getIfAvailable();
                    if (cache != null && stockUniverseRepositoryProvider != null) {
                        com.trading.scanner.repository.StockUniverseRepository universeRepo = stockUniverseRepositoryProvider.getIfAvailable();
                        if (universeRepo != null) {
                            java.util.List<String> activeSymbols = universeRepo.findByIsActiveTrueOrderBySymbolAsc()
                                    .stream()
                                    .map(com.trading.scanner.model.StockUniverse::getSymbol)
                                    .toList();
                            log.info("Midday startup detected on active trading day ({}, time={}). Warming up StockContextCache for {} equities + indices...",
                                    today, now.toLocalTime(), activeSymbols.size());
                            cache.warmUp(today, activeSymbols, "NSE");
                        }
                    }
                } catch (Exception ex) {
                    log.warn("StockContextCache warmup on startup warning: {}", ex.getMessage());
                }
            }
        }

        return new RecoveryResult(
                today,
                staleJobs,
                detectedGaps,
                "Startup recovery completed");
    }

    private com.trading.scanner.strategy.model.PositionSnapshot mapPositionToSnapshot(com.trading.scanner.model.Position p) {
        LocalDateTime entryTime;
        try {
            entryTime = p.getEntryTime() != null ? LocalDateTime.parse(p.getEntryTime()) : LocalDateTime.now();
        } catch (Exception ex) {
            entryTime = LocalDateTime.now();
        }
        boolean target1Booked = p.getCurrentQuantity() != null && p.getInitialQuantity() != null
                && p.getCurrentQuantity() < p.getInitialQuantity();
        Double sl = p.getUpdatedStopLoss() != null ? p.getUpdatedStopLoss() : p.getInitialStopLoss();
        Double target = p.getUpdatedTarget() != null ? p.getUpdatedTarget() : p.getInitialTarget();
        Double peak = p.getPeakPrice() != null ? p.getPeakPrice() : p.getEntryPrice();
        Double trough = p.getTroughPrice() != null ? p.getTroughPrice() : p.getEntryPrice();

        return new com.trading.scanner.strategy.model.PositionSnapshot(
                p.getId(),
                p.getStrategyId(),
                p.getSymbol(),
                p.getExchange(),
                p.getPositionSide(),
                p.getInitialQuantity(),
                p.getCurrentQuantity(),
                p.getEntryPrice(),
                sl,
                target,
                p.getEntryPrice(),
                target1Booked,
                peak,
                trough,
                entryTime,
                p.getRestingSlOrderId()
        );
    }

    private com.trading.scanner.strategy.model.PositionSnapshot mapSimulatedPositionToSnapshot(com.trading.scanner.model.SimulatedPosition p) {
        LocalDateTime entryTime;
        try {
            entryTime = p.getEntryTime() != null ? LocalDateTime.parse(p.getEntryTime()) : LocalDateTime.now();
        } catch (Exception ex) {
            entryTime = LocalDateTime.now();
        }
        boolean target1Booked = p.getCurrentQuantity() != null && p.getInitialQuantity() != null
                && p.getCurrentQuantity() < p.getInitialQuantity();
        Double sl = p.getUpdatedStopLoss() != null ? p.getUpdatedStopLoss() : p.getInitialStopLoss();
        Double target = p.getUpdatedTarget() != null ? p.getUpdatedTarget() : p.getInitialTarget();
        Double peak = p.getPeakPrice() != null ? p.getPeakPrice() : p.getEntryPrice();
        Double trough = p.getTroughPrice() != null ? p.getTroughPrice() : p.getEntryPrice();

        return new com.trading.scanner.strategy.model.PositionSnapshot(
                p.getId(),
                p.getStrategyId(),
                p.getSymbol(),
                p.getExchange(),
                p.getPositionSide(),
                p.getInitialQuantity(),
                p.getCurrentQuantity(),
                p.getEntryPrice(),
                sl,
                target,
                p.getEntryPrice(),
                target1Booked,
                peak,
                trough,
                entryTime,
                p.getRestingSlOrderId()
        );
    }

    public record RecoveryResult(
            LocalDate tradingDate,
            int staleJobsRecovered,
            int gapsDetected,
            String message) {
    }
}
