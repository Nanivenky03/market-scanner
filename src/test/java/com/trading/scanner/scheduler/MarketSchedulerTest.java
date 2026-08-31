package com.trading.scanner.scheduler;

import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.service.data.BackfillQueueService;
import com.trading.scanner.service.data.EodReconciliationService;
import com.trading.scanner.service.engine.DailyDataStatusService;
import com.trading.scanner.service.engine.MarketStateService;
import com.trading.scanner.service.engine.VolumeBaselineService;
import com.trading.scanner.service.provider.angelone.AngelOneWebSocketService;
import com.trading.scanner.service.provider.angelone.LiveMarketSnapshotService;
import com.trading.scanner.service.runtime.FeedHealthService;
import com.trading.scanner.service.runtime.MarketCalendarService;
import com.trading.scanner.service.runtime.PreMarketWorkflowService;
import com.trading.scanner.service.runtime.RuntimeAlertService;
import com.trading.scanner.service.runtime.RuntimeAutomationService;
import com.trading.scanner.service.runtime.RuntimeHousekeepingService;
import com.trading.scanner.service.runtime.ScheduledJobAlertService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MarketSchedulerTest {

    @Mock
    private RuntimeAutomationService runtimeAutomationService;

    @Mock
    private RuntimeHousekeepingService runtimeHousekeepingService;

    @Mock
    private RuntimeAlertService runtimeAlertService;

    @Mock
    private MarketCalendarService marketCalendarService;

    @Mock
    private VolumeBaselineService volumeBaselineService;

    @Mock
    private MarketStateService marketStateService;

    @Mock
    private DailyDataStatusService dailyDataStatusService;

    @Mock
    private BackfillQueueService backfillQueueService;

    @Mock
    private TimeProvider timeProvider;

    @Mock
    private LiveMarketSnapshotService liveMarketSnapshotService;

    @Mock
    private EodReconciliationService eodReconciliationService;

    @Mock
    private FeedHealthService feedHealthService;

    @Mock
    private PreMarketWorkflowService preMarketWorkflowService;

    @Mock
    private ScheduledJobAlertService scheduledJobAlertService;

    @InjectMocks
    private MarketScheduler marketScheduler;

    @Test
    void scheduledBrokerWarmup_shouldDelegate() {
        marketScheduler.scheduledBrokerWarmup();

        verify(runtimeAutomationService)
                .scheduledBrokerWarmup();
    }

    @Test
    void scheduledPreMarketFoundationRefresh_shouldDelegate() {
        marketScheduler.scheduledPreMarketFoundationRefresh();

        verify(preMarketWorkflowService)
                .refreshFoundation();
    }

    @Test
    void scheduledHistoricalBootstrap_shouldDelegate() {
        marketScheduler.scheduledHistoricalBootstrap();

        verify(preMarketWorkflowService)
                .runHistoricalBootstrap();
    }

    @Test
    void scheduledReadinessVerification_shouldDelegate() {
        marketScheduler.scheduledReadinessVerification();

        verify(preMarketWorkflowService)
                .verifyReadiness();
    }

    @Test
    void scheduledInstrumentMasterRefresh_shouldDelegate() {
        marketScheduler.scheduledInstrumentMasterRefresh();

        verify(preMarketWorkflowService)
                .refreshFinalInstruments();
    }

    @Test
    void scheduledConnectAndSubscribe_shouldDelegate() {
        marketScheduler.scheduledConnectAndSubscribe();

        verify(preMarketWorkflowService)
                .startLiveRuntime();
    }

    @Test
    void scheduledRecoverLiveRuntime_shouldDelegate() {
        marketScheduler.scheduledRecoverLiveRuntime();

        verify(runtimeAutomationService)
                .scheduledRecoverLiveRuntime();
    }

    @Test
    void scheduledConditionalFlushAndDisconnect_shouldDelegate() {
        marketScheduler.scheduledConditionalFlushAndDisconnect();

        verify(runtimeAutomationService)
                .scheduledConditionalFlushAndDisconnect();
    }

    @Test
    void scheduledBrokerSessionClear_shouldDelegate() {
        marketScheduler.scheduledBrokerSessionClear();

        verify(runtimeAutomationService)
                .scheduledBrokerSessionClear();
    }

    @Test
    void scheduledHousekeeping_shouldDelegate() {
        marketScheduler.scheduledHousekeeping();

        verify(runtimeHousekeepingService)
                .scheduledHousekeeping();
    }

    @Test
    void scheduledVolumeBaselinePreCalculation_shouldDelegate() {
        // This method is intentionally retained if present in the project.
        marketScheduler.scheduledVolumeBaselinePreCalculation();

        verify(volumeBaselineService)
                .scheduledPreCalculateBaselines();
    }

    @Test
    void scheduledMarketStateUpdate_shouldDelegate() {
        marketScheduler.scheduledMarketStateUpdate();

        verify(marketStateService)
                .scheduledUpdate();
    }

    @Test
    void scheduledDataCompletenessCheck_shouldDelegate() {
        marketScheduler.scheduledDataCompletenessCheck();

        verify(dailyDataStatusService)
                .scheduledCompletenessCheck();
    }

    @Test
    void scheduledBackfillProcess_shouldDelegate() {
        marketScheduler.scheduledBackfillProcess();

        verify(backfillQueueService)
                .scheduledProcess();
    }

    @Test
    void scheduledLiveGapCheck_shouldDelegate() {
        marketScheduler.scheduledLiveGapCheck();

        verify(liveMarketSnapshotService)
                .checkForClosedMinuteGaps();
    }

    @Test
    void scheduledFeedHealthCheck_shouldDelegate() {
        marketScheduler.scheduledFeedHealthCheck();

        verify(feedHealthService)
                .evaluate();
    }

    @Test
    void scheduledAlertEvaluation_shouldDelegate() {
        marketScheduler.scheduledAlertEvaluation();

        verify(runtimeAlertService)
                .scheduledEvaluate();
    }

    @Test
    void scheduledEodReconciliation_shouldSkipWhenWebsocketIsOpen() {
        when(runtimeAutomationService.runtimeStatus())
                .thenReturn(runtimeStatus(true, false));

        marketScheduler.scheduledEodReconciliation();

        verify(eodReconciliationService, never())
                .reconcilePreviousTradingDay();
    }

    @Test
    void scheduledEodReconciliation_shouldSkipWhenWebsocketIsConnecting() {
        when(runtimeAutomationService.runtimeStatus())
                .thenReturn(runtimeStatus(false, true));

        marketScheduler.scheduledEodReconciliation();

        verify(eodReconciliationService, never())
                .reconcilePreviousTradingDay();
    }

    @Test
    void scheduledEodReconciliation_shouldRunWhenWebsocketIsClosed() {
        when(runtimeAutomationService.runtimeStatus())
                .thenReturn(runtimeStatus(false, false));

        when(eodReconciliationService
                .reconcilePreviousTradingDay())
                .thenReturn(
                        new EodReconciliationService.ReconciliationBatchResult(
                                LocalDate.of(2026, 8, 27),
                                2,
                                1,
                                1,
                                0,
                                "completed"));

        marketScheduler.scheduledEodReconciliation();

        verify(eodReconciliationService)
                .reconcilePreviousTradingDay();
    }

    @Test
    void scheduledCalendarRefresh_shouldDelegate() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 30, 17, 0);

        when(timeProvider.nowDateTime())
                .thenReturn(now);

        when(marketCalendarService
                .refreshFromOfficialSourceIfDue(now))
                .thenReturn(
                        new MarketCalendarService.ScheduledCalendarRefreshResult(
                                "REFRESHED",
                                10,
                                2,
                                1,
                                "Calendar refreshed"));

        marketScheduler.scheduledCalendarRefresh();

        verify(marketCalendarService)
                .refreshFromOfficialSourceIfDue(now);
    }

    private RuntimeAutomationService.RuntimeStatus runtimeStatus(
            boolean connected,
            boolean connecting) {

        return new RuntimeAutomationService.RuntimeStatus(
                true,
                true,
                3,
                connected,
                connecting,
                null,
                null,
                null,
                null,
                0L,
                0L,
                0L,
                0L,
                0L,
                true,
                null,
                0,
                "RUNNING",
                null,
                null,
                true,
                null,
                null,
                null,
                null,
                null);
    }

    @Test
    void scheduledEodReconciliation_shouldAlertFailureWhenWebsocketIsOpen() {
        when(runtimeAutomationService.runtimeStatus())
                .thenReturn(runtimeStatus(true, false));

        marketScheduler.scheduledEodReconciliation();

        verify(scheduledJobAlertService)
                .reportFailure(
                        eq("eod-reconciliation"),
                        any(IllegalStateException.class));

        verify(eodReconciliationService, never())
                .reconcilePreviousTradingDay();
    }

    @Test
    void scheduledHousekeeping_shouldReportSuccess() {
        RuntimeHousekeepingService.HousekeepingResult result = new RuntimeHousekeepingService.HousekeepingResult(
                LocalDateTime.of(2026, 8, 27, 5, 30),
                30,
                30,
                10,
                2,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                "Housekeeping completed");

        when(runtimeHousekeepingService.scheduledHousekeeping())
                .thenReturn(result);

        marketScheduler.scheduledHousekeeping();

        verify(scheduledJobAlertService)
                .reportSuccess(
                        "housekeeping",
                        "Housekeeping completed",
                        result);
    }

}
