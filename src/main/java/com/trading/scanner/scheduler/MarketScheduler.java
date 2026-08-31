package com.trading.scanner.scheduler;

import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.service.data.BackfillQueueService;
import com.trading.scanner.service.data.EodReconciliationService;
import com.trading.scanner.service.engine.DailyDataStatusService;
import com.trading.scanner.service.engine.MarketStateService;
import com.trading.scanner.service.engine.VolumeBaselineService;
import com.trading.scanner.service.provider.angelone.LiveMarketSnapshotService;
import com.trading.scanner.service.runtime.FeedHealthService;
import com.trading.scanner.service.runtime.MarketCalendarService;
import com.trading.scanner.service.runtime.PreMarketWorkflowService;
import com.trading.scanner.service.runtime.RuntimeAlertService;
import com.trading.scanner.service.runtime.RuntimeAutomationService;
import com.trading.scanner.service.runtime.RuntimeHousekeepingService;
import com.trading.scanner.service.runtime.ScheduledJobAlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class MarketScheduler {

    private final RuntimeAutomationService runtimeAutomationService;
    private final RuntimeHousekeepingService runtimeHousekeepingService;
    private final RuntimeAlertService runtimeAlertService;
    private final ScheduledJobAlertService scheduledJobAlertService;
    private final MarketCalendarService marketCalendarService;
    private final VolumeBaselineService volumeBaselineService;
    private final MarketStateService marketStateService;
    private final DailyDataStatusService dailyDataStatusService;
    private final BackfillQueueService backfillQueueService;
    private final TimeProvider timeProvider;
    private final LiveMarketSnapshotService liveMarketSnapshotService;
    private final EodReconciliationService eodReconciliationService;
    private final FeedHealthService feedHealthService;
    private final PreMarketWorkflowService preMarketWorkflowService;

    @Scheduled(cron = "${runtime.schedule.broker-warmup-cron}", zone = "${runtime.schedule.zone}")
    public void scheduledBrokerWarmup() {
        try {
            runtimeAutomationService
                    .scheduledBrokerWarmup();

            scheduledJobAlertService.reportSuccess(
                    "broker-warmup",
                    "Broker warmup completed",
                    "Broker warmup invoked");
        } catch (Exception ex) {
            scheduledJobAlertService
                    .reportFailure("broker-warmup", ex);

            log.warn(
                    "Scheduled broker warmup failed: {}",
                    ex.getMessage(),
                    ex);
        }
    }

    @Scheduled(cron = "${runtime.schedule.pre-market-foundation-cron}", zone = "${runtime.schedule.zone}")
    public void scheduledPreMarketFoundationRefresh() {
        try {
            PreMarketWorkflowService.WorkflowResult result = preMarketWorkflowService
                    .refreshFoundation();

            scheduledJobAlertService.reportResult(
                    "pre-market-foundation",
                    result);
        } catch (Exception ex) {
            scheduledJobAlertService.reportFailure(
                    "pre-market-foundation",
                    ex);

            log.warn(
                    "Pre-market foundation failed: {}",
                    ex.getMessage(),
                    ex);
        }
    }

    @Scheduled(cron = "${runtime.schedule.historical-bootstrap-cron}", zone = "${runtime.schedule.zone}")
    public void scheduledHistoricalBootstrap() {
        try {
            PreMarketWorkflowService.WorkflowResult result = preMarketWorkflowService
                    .runHistoricalBootstrap();

            scheduledJobAlertService.reportResult(
                    "historical-bootstrap",
                    result);
        } catch (Exception ex) {
            scheduledJobAlertService.reportFailure(
                    "historical-bootstrap",
                    ex);

            log.warn(
                    "Historical bootstrap failed: {}",
                    ex.getMessage(),
                    ex);
        }
    }

    @Scheduled(cron = "${runtime.schedule.readiness-verification-cron}", zone = "${runtime.schedule.zone}")
    public void scheduledReadinessVerification() {
        try {
            PreMarketWorkflowService.WorkflowResult result = preMarketWorkflowService
                    .verifyReadiness();

            scheduledJobAlertService.reportResult(
                    "readiness-verification",
                    result);
        } catch (Exception ex) {
            scheduledJobAlertService.reportFailure(
                    "readiness-verification",
                    ex);

            log.warn(
                    "Readiness verification failed: {}",
                    ex.getMessage(),
                    ex);
        }
    }

    @Scheduled(cron = "${runtime.schedule.final-instrument-refresh-cron}", zone = "${runtime.schedule.zone}")
    public void scheduledInstrumentMasterRefresh() {
        try {
            PreMarketWorkflowService.WorkflowResult result = preMarketWorkflowService
                    .refreshFinalInstruments();

            scheduledJobAlertService.reportResult(
                    "final-instrument-refresh",
                    result);
        } catch (Exception ex) {
            scheduledJobAlertService.reportFailure(
                    "final-instrument-refresh",
                    ex);

            log.warn(
                    "Final instrument refresh failed: {}",
                    ex.getMessage(),
                    ex);
        }
    }

    @Scheduled(cron = "${runtime.schedule.live-runtime-start-cron}", zone = "${runtime.schedule.zone}")
    public void scheduledConnectAndSubscribe() {
        try {
            PreMarketWorkflowService.WorkflowResult result = preMarketWorkflowService
                    .startLiveRuntime();

            scheduledJobAlertService.reportResult(
                    "live-runtime-start",
                    result);
        } catch (Exception ex) {
            scheduledJobAlertService.reportFailure(
                    "live-runtime-start",
                    ex);

            log.warn(
                    "Live runtime start failed: {}",
                    ex.getMessage(),
                    ex);
        }
    }

    @Scheduled(fixedDelayString = "${runtime.live.recovery-interval-ms:60000}", initialDelayString = "${runtime.live.recovery-interval-ms:60000}")
    public void scheduledRecoverLiveRuntime() {
        runtimeAutomationService
                .scheduledRecoverLiveRuntime();
    }

    @Scheduled(cron = "${runtime.schedule.conditional-flush-cron}", zone = "${runtime.schedule.zone}")
    public void scheduledConditionalFlushAndDisconnect() {
        runtimeAutomationService
                .scheduledConditionalFlushAndDisconnect();
    }

    @Scheduled(cron = "${runtime.schedule.broker-session-clear-cron}", zone = "${runtime.schedule.zone}")
    public void scheduledBrokerSessionClear() {
        try {
            runtimeAutomationService
                    .scheduledBrokerSessionClear();

            scheduledJobAlertService.reportSuccess(
                    "broker-session-clear",
                    "Broker session clear completed",
                    "Broker session clear invoked");
        } catch (Exception ex) {
            scheduledJobAlertService.reportFailure(
                    "broker-session-clear",
                    ex);

            log.warn(
                    "Scheduled broker session clear failed: {}",
                    ex.getMessage(),
                    ex);
        }
    }

    @Scheduled(cron = "${runtime.schedule.housekeeping-cron}", zone = "${runtime.schedule.zone}")
    public void scheduledHousekeeping() {
        try {
            RuntimeHousekeepingService.HousekeepingResult result = runtimeHousekeepingService
                    .scheduledHousekeeping();

            if (result != null) {
                scheduledJobAlertService.reportSuccess(
                        "housekeeping",
                        result.message(),
                        result);
            }
        } catch (Exception ex) {
            scheduledJobAlertService.reportFailure(
                    "housekeeping",
                    ex);

            log.warn(
                    "Scheduled housekeeping failed: {}",
                    ex.getMessage(),
                    ex);
        }
    }

    @Scheduled(cron = "${runtime.schedule.volume-baseline-cron}", zone = "${runtime.schedule.zone}")
    public void scheduledVolumeBaselinePreCalculation() {
        volumeBaselineService
                .scheduledPreCalculateBaselines();
    }

    @Scheduled(cron = "${runtime.schedule.market-state-update-cron}", zone = "${runtime.schedule.zone}")
    public void scheduledMarketStateUpdate() {
        marketStateService.scheduledUpdate();
    }

    @Scheduled(cron = "${runtime.schedule.data-completeness-check-cron}", zone = "${runtime.schedule.zone}")
    public void scheduledDataCompletenessCheck() {
        dailyDataStatusService
                .scheduledCompletenessCheck();
    }

    @Scheduled(cron = "${runtime.schedule.backfill-process-cron}", zone = "${runtime.schedule.zone}")
    public void scheduledBackfillProcess() {
        backfillQueueService.scheduledProcess();
    }

    @Scheduled(cron = "${runtime.schedule.live-gap-check-cron}", zone = "${runtime.schedule.zone}")
    public void scheduledLiveGapCheck() {
        liveMarketSnapshotService
                .checkForClosedMinuteGaps();
    }

    @Scheduled(fixedDelayString = "${runtime.feed-health.check-interval-ms:15000}", initialDelayString = "${runtime.feed-health.check-interval-ms:15000}")
    public void scheduledFeedHealthCheck() {
        feedHealthService.evaluate();
    }

    @Scheduled(fixedDelayString = "${runtime.alert.evaluation-interval-ms:60000}", initialDelayString = "${runtime.alert.evaluation-interval-ms:60000}")
    public void scheduledAlertEvaluation() {
        runtimeAlertService.scheduledEvaluate();
    }

    @Scheduled(cron = "${runtime.schedule.eod-reconciliation-cron}", zone = "${runtime.schedule.zone}")
    public void scheduledEodReconciliation() {
        RuntimeAutomationService.RuntimeStatus status = runtimeAutomationService.runtimeStatus();

        if (status.websocketConnected()
                || status.websocketConnecting()) {

            IllegalStateException error = new IllegalStateException(
                    "EOD reconciliation blocked because websocket is not closed");

            scheduledJobAlertService.reportFailure(
                    "eod-reconciliation",
                    error);

            log.warn(
                    "EOD reconciliation skipped because websocket is not closed. connected={} connecting={}",
                    status.websocketConnected(),
                    status.websocketConnecting());

            return;
        }

        try {
            EodReconciliationService.ReconciliationBatchResult result = eodReconciliationService
                    .reconcilePreviousTradingDay();

            scheduledJobAlertService.reportResult(
                    "eod-reconciliation",
                    result);

            log.info(
                    "EOD reconciliation completed: {}",
                    result);
        } catch (Exception ex) {
            scheduledJobAlertService.reportFailure(
                    "eod-reconciliation",
                    ex);

            log.warn(
                    "EOD reconciliation failed: {}",
                    ex.getMessage(),
                    ex);
        }
    }

    @Scheduled(cron = "${runtime.schedule.calendar-refresh-cron}", zone = "${runtime.schedule.zone}")
    public void scheduledCalendarRefresh() {
        try {
            MarketCalendarService.ScheduledCalendarRefreshResult result = marketCalendarService
                    .refreshFromOfficialSourceIfDue(
                            timeProvider.nowDateTime());

            scheduledJobAlertService.reportResult(
                    "calendar-refresh",
                    result);
        } catch (Exception ex) {
            scheduledJobAlertService.reportFailure(
                    "calendar-refresh",
                    ex);

            log.warn(
                    "Scheduled calendar refresh failed: {}",
                    ex.getMessage(),
                    ex);
        }
    }
}
