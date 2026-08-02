package com.trading.scanner.scheduler;

import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.service.engine.MarketStateService;
import com.trading.scanner.service.engine.VolumeBaselineService;
import com.trading.scanner.service.runtime.MarketCalendarService;
import com.trading.scanner.service.runtime.RuntimeAlertService;
import com.trading.scanner.service.runtime.RuntimeAutomationService;
import com.trading.scanner.service.runtime.RuntimeHousekeepingService;
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
    private final MarketCalendarService marketCalendarService;
    private final VolumeBaselineService volumeBaselineService;
    private final MarketStateService marketStateService;
    private final TimeProvider timeProvider;

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Kolkata")
    public void scheduledBrokerWarmup() {
        runtimeAutomationService.scheduledBrokerWarmup();
    }

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Kolkata")
    public void scheduledConnectAndSubscribe() {
        runtimeAutomationService.scheduledConnectAndSubscribe();
    }

    @Scheduled(fixedDelayString = "${runtime.live.recovery-interval-ms:60000}", initialDelayString = "${runtime.live.recovery-interval-ms:60000}")
    public void scheduledRecoverLiveRuntime() {
        runtimeAutomationService.scheduledRecoverLiveRuntime();
    }

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Kolkata")
    public void scheduledConditionalFlushAndDisconnect() {
        runtimeAutomationService.scheduledConditionalFlushAndDisconnect();
    }

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Kolkata")
    public void scheduledBrokerSessionClear() {
        runtimeAutomationService.scheduledBrokerSessionClear();
    }

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Kolkata")
    public void scheduledHousekeeping() {
        runtimeHousekeepingService.scheduledHousekeeping();
    }

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Kolkata")
    public void scheduledVolumeBaselinePreCalculation() {
        volumeBaselineService.scheduledPreCalculateBaselines();
    }

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Kolkata")
    public void scheduledMarketStateUpdate() {
        marketStateService.scheduledUpdate();
    }

    @Scheduled(fixedDelayString = "${runtime.alert.evaluation-interval-ms:60000}", initialDelayString = "${runtime.alert.evaluation-interval-ms:60000}")
    public void scheduledAlertEvaluation() {
        runtimeAlertService.scheduledEvaluate();
    }

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Kolkata")
    public void scheduledCalendarRefresh() {
        try {
            MarketCalendarService.ScheduledCalendarRefreshResult result = marketCalendarService
                    .refreshFromOfficialSourceIfDue(timeProvider.nowDateTime());
            if (!"NO_ACTION".equals(result.action())) {
                log.info("Scheduled market calendar refresh completed: {}", result);
            }
        } catch (Exception ex) {
            log.warn("Scheduled market calendar refresh failed: {}", ex.getMessage(), ex);
        }
    }
}
