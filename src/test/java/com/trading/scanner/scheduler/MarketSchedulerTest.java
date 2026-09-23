package com.trading.scanner.scheduler;

import com.trading.scanner.calendar.TradingCalendar;
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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
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

        @Mock
        private com.trading.scanner.service.data.LiveMarketCandleService liveMarketCandleService;

        @Mock
        private MarketCalendarService marketCalendarService;

        @Mock
        private TradingCalendar tradingCalendar;

        @InjectMocks
        private MarketScheduler marketScheduler;


        @Test
        void scheduledMorningMaintenance_shouldDelegate() {
                marketScheduler.scheduledMorningMaintenance();

                verify(preMarketWorkflowService)
                                .runMorningMaintenance();
        }

        @Test
        void scheduledPreMarketDataPipeline_shouldDelegate() {
                marketScheduler.scheduledPreMarketDataPipeline();

                verify(preMarketWorkflowService)
                                .runPreMarketDataPipeline();
        }

        @Test
        void scheduledLiveRuntimeStart_shouldDelegate() {
                marketScheduler.scheduledLiveRuntimeStart();

                verify(preMarketWorkflowService)
                                .startLiveRuntime();
        }

        @Test
        void scheduledMinuteRollover_shouldDelegate() {
                marketScheduler.scheduledMinuteRollover();

                verify(liveMarketCandleService)
                                .rolloverCompletedMinutes();
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
        void scheduledEodReconciliation_shouldSkipOnNonTradingDay() {
                LocalDate today = LocalDate.of(2026, 8, 29);
                when(timeProvider.today()).thenReturn(today);
                when(tradingCalendar.isTradingDay(today)).thenReturn(false);

                marketScheduler.scheduledEodReconciliation();

                verify(eodReconciliationService, never())
                                .reconcileTradingDay(any());
        }

        @Test
        void scheduledEodReconciliation_shouldSkipWhenWebsocketIsOpen() {
                LocalDate today = LocalDate.of(2026, 8, 27);
                when(timeProvider.today()).thenReturn(today);
                when(tradingCalendar.isTradingDay(today)).thenReturn(true);
                when(runtimeAutomationService.runtimeStatus())
                                .thenReturn(runtimeStatus(true, false));

                marketScheduler.scheduledEodReconciliation();

                verify(eodReconciliationService, never())
                                .reconcileTradingDay(any());
        }

        @Test
        void scheduledEodReconciliation_shouldSkipWhenWebsocketIsConnecting() {
                LocalDate today = LocalDate.of(2026, 8, 27);
                when(timeProvider.today()).thenReturn(today);
                when(tradingCalendar.isTradingDay(today)).thenReturn(true);
                when(runtimeAutomationService.runtimeStatus())
                                .thenReturn(runtimeStatus(false, true));

                marketScheduler.scheduledEodReconciliation();

                verify(eodReconciliationService, never())
                                .reconcileTradingDay(any());
        }

        @Test
        void scheduledEodReconciliation_shouldRunWhenWebsocketIsClosed() {
                LocalDate today = LocalDate.of(2026, 8, 27);
                when(timeProvider.today()).thenReturn(today);
                when(tradingCalendar.isTradingDay(today)).thenReturn(true);
                when(runtimeAutomationService.runtimeStatus())
                                .thenReturn(runtimeStatus(false, false));

                when(eodReconciliationService
                                .reconcileTradingDay(today))
                                .thenReturn(
                                                new EodReconciliationService.ReconciliationBatchResult(
                                                                today,
                                                                2,
                                                                1,
                                                                1,
                                                                0,
                                                                "completed"));

                marketScheduler.scheduledEodReconciliation();

                verify(eodReconciliationService)
                                .reconcileTradingDay(today);
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
                LocalDate today = LocalDate.of(2026, 8, 27);
                when(timeProvider.today()).thenReturn(today);
                when(tradingCalendar.isTradingDay(today)).thenReturn(true);
                when(runtimeAutomationService.runtimeStatus())
                                .thenReturn(runtimeStatus(true, false));

                marketScheduler.scheduledEodReconciliation();

                verify(scheduledJobAlertService)
                                .reportFailure(
                                                eq("eod-reconciliation"),
                                                any(IllegalStateException.class));

                verify(eodReconciliationService, never())
                                .reconcileTradingDay(any());
        }

}
