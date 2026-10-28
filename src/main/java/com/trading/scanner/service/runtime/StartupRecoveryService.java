package com.trading.scanner.service.runtime;

import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.service.data.BackfillQueueService;
import com.trading.scanner.service.data.EodReconciliationService;
import com.trading.scanner.service.provider.angelone.LiveMarketSnapshotService;
import com.trading.scanner.service.workflow.WorkflowStatusService;
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
            org.springframework.beans.factory.ObjectProvider<com.trading.scanner.repository.SimulatedPositionRepository> simulatedPositionRepositoryProvider) {
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
                preMarketWorkflowService, eodReconciliationService, workflowStatusService, null, null, null);
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
                    }
                } catch (Exception ex) {
                    log.warn("Position rehydration on startup warning: {}", ex.getMessage());
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
                entryTime
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
                entryTime
        );
    }

    public record RecoveryResult(
            LocalDate tradingDate,
            int staleJobsRecovered,
            int gapsDetected,
            String message) {
    }
}
