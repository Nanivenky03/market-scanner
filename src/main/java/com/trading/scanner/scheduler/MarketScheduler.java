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
import com.trading.scanner.service.runtime.RuntimeSettingService;
import com.trading.scanner.service.runtime.ScheduledJobAlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.time.ZoneId;

@Component
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
public class MarketScheduler implements SchedulingConfigurer {

    private final RuntimeAutomationService runtimeAutomationService;
    private final RuntimeHousekeepingService runtimeHousekeepingService;
    private final RuntimeAlertService runtimeAlertService;
    private final ScheduledJobAlertService scheduledJobAlertService;
    private final MarketCalendarService marketCalendarService;
    private final com.trading.scanner.calendar.TradingCalendar tradingCalendar;
    private final VolumeBaselineService volumeBaselineService;
    private final MarketStateService marketStateService;
    private final DailyDataStatusService dailyDataStatusService;
    private final BackfillQueueService backfillQueueService;
    private final TimeProvider timeProvider;
    private final LiveMarketSnapshotService liveMarketSnapshotService;
    private final EodReconciliationService eodReconciliationService;
    private final FeedHealthService feedHealthService;
    private final PreMarketWorkflowService preMarketWorkflowService;
    private final RuntimeSettingService runtimeSettingService;
    private final com.trading.scanner.service.data.LiveMarketCandleService liveMarketCandleService;

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        // 1. Dynamic Database-Driven Trigger for Morning Maintenance (Default: 07:00 AM)
        taskRegistrar.addTriggerTask(
                this::scheduledMorningMaintenance,
                triggerContext -> {
                    String cron = runtimeSettingService.morningMaintenanceCron();
                    ZoneId zone = safeZone(runtimeSettingService.scheduleZone());
                    return safeCronTrigger(cron, zone, "0 0 7 * * MON-FRI").nextExecution(triggerContext);
                });

        // 2. Dynamic Database-Driven Trigger for Pre-Market Data Pipeline (Default: 08:00 AM)
        taskRegistrar.addTriggerTask(
                this::scheduledPreMarketDataPipeline,
                triggerContext -> {
                    String cron = runtimeSettingService.preMarketDataPipelineCron();
                    ZoneId zone = safeZone(runtimeSettingService.scheduleZone());
                    return safeCronTrigger(cron, zone, "0 0 8 * * MON-FRI").nextExecution(triggerContext);
                });

        // 3. Dynamic Database-Driven Trigger for Live Runtime Start (Default: 08:55 AM)
        taskRegistrar.addTriggerTask(
                this::scheduledLiveRuntimeStart,
                triggerContext -> {
                    String cron = runtimeSettingService.liveRuntimeStartCron();
                    ZoneId zone = safeZone(runtimeSettingService.scheduleZone());
                    return safeCronTrigger(cron, zone, "0 55 8 * * MON-FRI").nextExecution(triggerContext);
                });

        // 4. Dynamic Database-Driven Trigger for Minute Rollover (Default: 5s after each minute)
        taskRegistrar.addTriggerTask(
                this::scheduledMinuteRollover,
                triggerContext -> {
                    String cron = runtimeSettingService.minuteRolloverCron();
                    ZoneId zone = safeZone(runtimeSettingService.scheduleZone());
                    return safeCronTrigger(cron, zone, "5 * 9-15 * * MON-FRI").nextExecution(triggerContext);
                });
    }

    private CronTrigger safeCronTrigger(String cron, ZoneId zone, String defaultCron) {
        try {
            return new CronTrigger(cron, zone);
        } catch (IllegalArgumentException ex) {
            log.warn("Invalid cron expression '{}'. Falling back to default '{}'", cron, defaultCron);
            return new CronTrigger(defaultCron, zone);
        }
    }

    private ZoneId safeZone(String zoneStr) {
        try {
            return ZoneId.of(zoneStr);
        } catch (Exception ex) {
            log.warn("Invalid timezone '{}'. Falling back to 'Asia/Kolkata'", zoneStr);
            return ZoneId.of("Asia/Kolkata");
        }
    }

    public void scheduledMorningMaintenance() {
        try {
            PreMarketWorkflowService.WorkflowResult result = preMarketWorkflowService
                    .runMorningMaintenance();

            scheduledJobAlertService.reportResult(
                    "morning-maintenance",
                    result);
        } catch (Exception ex) {
            scheduledJobAlertService.reportFailure(
                    "morning-maintenance",
                    ex);

            log.warn(
                    "Morning maintenance failed: {}",
                    ex.getMessage(),
                    ex);
        }
    }

    public void scheduledPreMarketDataPipeline() {
        try {
            PreMarketWorkflowService.WorkflowResult result = preMarketWorkflowService
                    .runPreMarketDataPipeline();

            scheduledJobAlertService.reportResult(
                    "premarket-data-pipeline",
                    result);
        } catch (Exception ex) {
            scheduledJobAlertService.reportFailure(
                    "premarket-data-pipeline",
                    ex);

            log.warn(
                    "Pre-market data pipeline failed: {}",
                    ex.getMessage(),
                    ex);
        }
    }

    public void scheduledLiveRuntimeStart() {
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

    @Scheduled(cron = "${runtime.schedule.minute-rollover-cron:5 * 9-15 * * MON-FRI}", zone = "${runtime.schedule.zone}")
    public void scheduledMinuteRollover() {
        liveMarketCandleService.rolloverCompletedMinutes();
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
        java.time.LocalDate today = timeProvider.today();
        if (!tradingCalendar.isTradingDay(today)) {
            return;
        }

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
                    .reconcileTradingDay(today);

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
}