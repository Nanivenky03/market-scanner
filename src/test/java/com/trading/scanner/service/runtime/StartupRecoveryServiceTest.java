package com.trading.scanner.service.runtime;

import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.service.data.BackfillQueueService;
import com.trading.scanner.service.data.EodReconciliationService;
import com.trading.scanner.service.provider.angelone.LiveMarketSnapshotService;
import com.trading.scanner.service.workflow.WorkflowStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class StartupRecoveryServiceTest {

        private TimeProvider timeProvider;
        private TradingCalendar tradingCalendar;
        private BackfillQueueService backfillQueueService;
        private LiveMarketSnapshotService liveMarketSnapshotService;
        private PreMarketWorkflowService preMarketWorkflowService;
        private EodReconciliationService eodReconciliationService;
        private WorkflowStatusService workflowStatusService;
        private StartupRecoveryService service;

        @BeforeEach
        void setUp() {
                timeProvider = mock(TimeProvider.class);
                tradingCalendar = mock(TradingCalendar.class);
                backfillQueueService = mock(BackfillQueueService.class);
                liveMarketSnapshotService = mock(LiveMarketSnapshotService.class);
                preMarketWorkflowService = mock(PreMarketWorkflowService.class);
                eodReconciliationService = mock(EodReconciliationService.class);
                workflowStatusService = mock(WorkflowStatusService.class);

                when(timeProvider.nowDateTime()).thenReturn(LocalDateTime.of(2026, 8, 24, 10, 0));

                service = new StartupRecoveryService(
                                timeProvider,
                                tradingCalendar,
                                backfillQueueService,
                                liveMarketSnapshotService,
                                preMarketWorkflowService,
                                eodReconciliationService,
                                workflowStatusService);
        }

        @Test
        void recoverNow_shouldRecoverStaleJobsAndScanTradingDay() {
                LocalDate today = LocalDate.of(2026, 8, 24);

                when(timeProvider.today()).thenReturn(today);
                when(timeProvider.nowDateTime()).thenReturn(today.atTime(10, 0));
                when(tradingCalendar.isTradingDay(today)).thenReturn(true);
                when(backfillQueueService.recoverStaleJobs())
                                .thenReturn(2);
                when(liveMarketSnapshotService.checkForClosedMinuteGaps())
                                .thenReturn(3);

                StartupRecoveryService.RecoveryResult result = service.recoverNow();

                assertEquals(today, result.tradingDate());
                assertEquals(2, result.staleJobsRecovered());
                assertEquals(3, result.gapsDetected());

                verify(backfillQueueService).recoverStaleJobs();
                verify(preMarketWorkflowService).catchUpPreMarketWorkflowsIfDue();
                verify(liveMarketSnapshotService)
                                .checkForClosedMinuteGaps();
        }

        @Test
        void recoverNow_shouldSkipLiveGapScanOnNonTradingDay() {
                LocalDate today = LocalDate.of(2026, 8, 23);

                when(timeProvider.today()).thenReturn(today);
                when(timeProvider.nowDateTime()).thenReturn(today.atTime(10, 0));
                when(tradingCalendar.isTradingDay(today)).thenReturn(false);
                when(backfillQueueService.recoverStaleJobs())
                                .thenReturn(1);

                StartupRecoveryService.RecoveryResult result = service.recoverNow();

                assertEquals(today, result.tradingDate());
                assertEquals(1, result.staleJobsRecovered());
                assertEquals(0, result.gapsDetected());

                verify(backfillQueueService).recoverStaleJobs();
                verify(preMarketWorkflowService, never()).catchUpPreMarketWorkflowsIfDue();
                verify(liveMarketSnapshotService, never())
                                .checkForClosedMinuteGaps();
        }

        @Test
        void recoverAtStartup_shouldNotPropagateRecoveryFailure() {
                when(timeProvider.today())
                                .thenThrow(new IllegalStateException("database unavailable"));

                service.recoverAtStartup();

                verify(timeProvider).today();
        }

        @Test
        @SuppressWarnings("unchecked")
        void recoverNow_shouldRehydrateOpenPositionsFromRepositories() {
                LocalDate today = LocalDate.of(2026, 8, 24);
                when(timeProvider.today()).thenReturn(today);
                when(timeProvider.nowDateTime()).thenReturn(today.atTime(10, 0));
                when(tradingCalendar.isTradingDay(today)).thenReturn(true);

                com.trading.scanner.position.PositionManagerService pms = mock(com.trading.scanner.position.PositionManagerService.class);
                com.trading.scanner.repository.PositionRepository posRepo = mock(com.trading.scanner.repository.PositionRepository.class);
                com.trading.scanner.repository.SimulatedPositionRepository simPosRepo = mock(com.trading.scanner.repository.SimulatedPositionRepository.class);

                org.springframework.beans.factory.ObjectProvider<com.trading.scanner.position.PositionManagerService> pmsProvider = mock(org.springframework.beans.factory.ObjectProvider.class);
                org.springframework.beans.factory.ObjectProvider<com.trading.scanner.repository.PositionRepository> posRepoProvider = mock(org.springframework.beans.factory.ObjectProvider.class);
                org.springframework.beans.factory.ObjectProvider<com.trading.scanner.repository.SimulatedPositionRepository> simPosRepoProvider = mock(org.springframework.beans.factory.ObjectProvider.class);

                when(pmsProvider.getIfAvailable()).thenReturn(pms);
                when(posRepoProvider.getIfAvailable()).thenReturn(posRepo);
                when(simPosRepoProvider.getIfAvailable()).thenReturn(simPosRepo);

                com.trading.scanner.model.Position livePos = com.trading.scanner.model.Position.builder()
                                .id(101).strategyId(2).symbol("SBIN").exchange("NSE").positionSide("LONG")
                                .initialQuantity(50).currentQuantity(50).entryPrice(800.0).initialStopLoss(790.0)
                                .initialTarget(810.0).entryTime("2026-08-24T09:35:00").positionStatus("OPEN").build();

                com.trading.scanner.model.SimulatedPosition paperPos = com.trading.scanner.model.SimulatedPosition.builder()
                                .id(201).strategyId(2).symbol("RELIANCE").exchange("NSE").positionSide("LONG")
                                .initialQuantity(20).currentQuantity(20).entryPrice(2500.0).initialStopLoss(2480.0)
                                .initialTarget(2520.0).entryTime("2026-08-24T09:35:00").positionStatus("OPEN").build();

                when(posRepo.findByPositionStatus("OPEN")).thenReturn(java.util.List.of(livePos));
                when(simPosRepo.findByPositionStatus("OPEN")).thenReturn(java.util.List.of(paperPos));

                StartupRecoveryService recoveryServiceWithPms = new StartupRecoveryService(
                                timeProvider, tradingCalendar, backfillQueueService, liveMarketSnapshotService,
                                preMarketWorkflowService, eodReconciliationService, workflowStatusService,
                                pmsProvider, posRepoProvider, simPosRepoProvider);

                recoveryServiceWithPms.recoverNow();

                verify(pms).rehydrateActiveState(eq(com.trading.scanner.risk.model.PortfolioMode.LIVE), anyList());
                verify(pms).rehydrateActiveState(eq(com.trading.scanner.risk.model.PortfolioMode.PAPER), anyList());
        }
}
