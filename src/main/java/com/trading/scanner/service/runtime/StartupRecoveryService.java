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
@RequiredArgsConstructor
@Slf4j
public class StartupRecoveryService {

    private final TimeProvider timeProvider;
    private final TradingCalendar tradingCalendar;
    private final BackfillQueueService backfillQueueService;
    private final LiveMarketSnapshotService liveMarketSnapshotService;
    private final PreMarketWorkflowService preMarketWorkflowService;
    private final EodReconciliationService eodReconciliationService;
    private final WorkflowStatusService workflowStatusService;

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
        }

        return new RecoveryResult(
                today,
                staleJobs,
                detectedGaps,
                "Startup recovery completed");
    }

    public record RecoveryResult(
            LocalDate tradingDate,
            int staleJobsRecovered,
            int gapsDetected,
            String message) {
    }
}
