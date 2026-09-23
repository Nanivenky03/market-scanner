package com.trading.scanner.service.runtime;

import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.config.RuntimeAutomationProperties;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.config.provider.AngelOneProperties;
import com.trading.scanner.model.Exchange;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PreMarketWorkflowServiceTest {

    @Mock
    private MarketCalendarService marketCalendarService;

    @Mock
    private TradingCalendar tradingCalendar;

    @Mock
    private RuntimeHousekeepingService runtimeHousekeepingService;

    @Mock
    private AngelOneInstrumentCatalogSyncService catalogSyncService;

    @Mock
    private InstrumentMasterSyncService instrumentMasterSyncService;

    @Mock
    private AngelOneSessionService angelOneSessionService;

    @Mock
    private InstrumentTokenSyncService instrumentTokenSyncService;

    @Mock
    private StockUniverseRepository stockUniverseRepository;

    @Mock
    private InstrumentMasterRepository instrumentMasterRepository;

    @Mock
    private EodDataEntryService eodDataEntryService;

    @Mock
    private VolumeBaselineService volumeBaselineService;

    @Mock
    private RuntimeSettingService runtimeSettingService;

    @Mock
    private WorkflowStatusService workflowStatusService;

    @Mock
    private RuntimeAutomationService runtimeAutomationService;

    @Mock
    private RuntimeAutomationProperties runtimeAutomationProperties;

    @Mock
    private AngelOneProperties angelOneProperties;

    @Mock
    private TimeProvider timeProvider;

    private PreMarketWorkflowService service;

    private final LocalDate today = LocalDate.of(2026, 9, 21);
    private final LocalDate prevDay = LocalDate.of(2026, 9, 18);
    private final LocalDateTime now = LocalDateTime.of(2026, 9, 21, 7, 0);

    @BeforeEach
    void setUp() {
        service = new PreMarketWorkflowService(
                marketCalendarService,
                tradingCalendar,
                runtimeHousekeepingService,
                catalogSyncService,
                instrumentMasterSyncService,
                angelOneSessionService,
                instrumentTokenSyncService,
                stockUniverseRepository,
                instrumentMasterRepository,
                eodDataEntryService,
                volumeBaselineService,
                runtimeSettingService,
                workflowStatusService,
                runtimeAutomationService,
                runtimeAutomationProperties,
                angelOneProperties,
                timeProvider);

        lenient().when(timeProvider.today()).thenReturn(today);
        lenient().when(timeProvider.nowDateTime()).thenReturn(now);
        lenient().when(workflowStatusService.isSuccessfulActiveStartupWorkflow(WorkflowStatusService.RUNTIME_BOOTSTRAP_COMPLETE))
                .thenReturn(true);
    }

    // =========================================================================
    // 07:00 AM Morning Maintenance Chain Tests
    // =========================================================================

    @Test
    void runMorningMaintenance_whenStartupBootstrapNotComplete_throwsIllegalState() {
        when(workflowStatusService.isSuccessfulActiveStartupWorkflow(WorkflowStatusService.RUNTIME_BOOTSTRAP_COMPLETE))
                .thenReturn(false);

        assertThrows(IllegalStateException.class, () -> service.runMorningMaintenance());

        verify(tradingCalendar, never()).isTradingDay(any());
        verify(runtimeSettingService, never()).setProcessDate(any());
    }

    @Test
    void runMorningMaintenance_whenNonTradingDay_skipsExecution() {
        when(tradingCalendar.isTradingDay(today)).thenReturn(false);

        PreMarketWorkflowService.WorkflowResult result = service.runMorningMaintenance();

        assertEquals("SKIPPED", result.status());
        assertEquals(WorkflowStatusService.TRADING_DAY_INIT, result.stage());
        verify(runtimeSettingService, never()).setProcessDate(any());
        verify(runtimeHousekeepingService, never()).runHousekeeping();
    }

    @Test
    void runMorningMaintenance_whenTradingDay_executesInitAndHousekeeping() {
        when(tradingCalendar.isTradingDay(today)).thenReturn(true);

        UUID initId = UUID.randomUUID();
        UUID houseId = UUID.randomUUID();

        when(workflowStatusService.prepareForDaily(
                eq(WorkflowStatusService.TRADING_DAY_INIT),
                eq(WorkflowStatusService.PREMARKET_GROUP),
                eq(today.toString()),
                isNull()))
                .thenReturn(workflow(initId, WorkflowStatusService.TRADING_DAY_INIT, WorkflowStatus.Status.READY));

        when(workflowStatusService.prepareForDaily(
                eq(WorkflowStatusService.PREMARKET_HOUSEKEEPING),
                eq(WorkflowStatusService.PREMARKET_GROUP),
                eq(today.toString()),
                eq(initId)))
                .thenReturn(workflow(houseId, WorkflowStatusService.PREMARKET_HOUSEKEEPING, WorkflowStatus.Status.READY));

        when(workflowStatusService.markRunning(initId))
                .thenReturn(workflow(initId, WorkflowStatusService.TRADING_DAY_INIT, WorkflowStatus.Status.RUNNING));
        when(workflowStatusService.markRunning(houseId))
                .thenReturn(workflow(houseId, WorkflowStatusService.PREMARKET_HOUSEKEEPING, WorkflowStatus.Status.RUNNING));

        when(workflowStatusService.markSuccess(eq(initId), anyString()))
                .thenReturn(workflow(initId, WorkflowStatusService.TRADING_DAY_INIT, WorkflowStatus.Status.SUCCESS));
        when(workflowStatusService.markSuccess(eq(houseId), anyString()))
                .thenReturn(workflow(houseId, WorkflowStatusService.PREMARKET_HOUSEKEEPING, WorkflowStatus.Status.SUCCESS));

        when(runtimeHousekeepingService.runHousekeeping()).thenReturn(new RuntimeHousekeepingService.HousekeepingResult(
                now, 30, 30, 10, 5, 2, 0, 0, 0, 0, 0, 0, 0, "Housekeeping ok"));

        PreMarketWorkflowService.WorkflowResult result = service.runMorningMaintenance();

        assertEquals("COMPLETE", result.status());
        assertEquals(WorkflowStatusService.PREMARKET_HOUSEKEEPING, result.stage());

        verify(marketCalendarService).refreshFromOfficialSourceIfDue(now);
        verify(runtimeSettingService).setProcessDate(today);
        verify(runtimeHousekeepingService).runHousekeeping();
        verify(workflowStatusService).markSuccess(eq(initId), anyString());
        verify(workflowStatusService).markSuccess(eq(houseId), anyString());
    }

    @Test
    void runMorningMaintenance_whenAlreadySuccessful_doesNotReExecute() {
        when(tradingCalendar.isTradingDay(today)).thenReturn(true);

        UUID initId = UUID.randomUUID();
        UUID houseId = UUID.randomUUID();

        when(workflowStatusService.prepareForDaily(
                eq(WorkflowStatusService.TRADING_DAY_INIT),
                eq(WorkflowStatusService.PREMARKET_GROUP),
                eq(today.toString()),
                isNull()))
                .thenReturn(workflow(initId, WorkflowStatusService.TRADING_DAY_INIT, WorkflowStatus.Status.SUCCESS));

        when(workflowStatusService.prepareForDaily(
                eq(WorkflowStatusService.PREMARKET_HOUSEKEEPING),
                eq(WorkflowStatusService.PREMARKET_GROUP),
                eq(today.toString()),
                eq(initId)))
                .thenReturn(workflow(houseId, WorkflowStatusService.PREMARKET_HOUSEKEEPING, WorkflowStatus.Status.SUCCESS));

        PreMarketWorkflowService.WorkflowResult result = service.runMorningMaintenance();

        assertEquals("COMPLETE", result.status());
        verify(runtimeSettingService, never()).setProcessDate(any());
        verify(runtimeHousekeepingService, never()).runHousekeeping();
        verify(workflowStatusService, never()).markRunning(any());
    }

    @Test
    void runMorningMaintenance_whenHousekeepingFails_marksFailedAndThrows() {
        when(tradingCalendar.isTradingDay(today)).thenReturn(true);

        UUID initId = UUID.randomUUID();
        UUID houseId = UUID.randomUUID();

        when(workflowStatusService.prepareForDaily(
                eq(WorkflowStatusService.TRADING_DAY_INIT),
                eq(WorkflowStatusService.PREMARKET_GROUP),
                eq(today.toString()),
                isNull()))
                .thenReturn(workflow(initId, WorkflowStatusService.TRADING_DAY_INIT, WorkflowStatus.Status.READY));

        when(workflowStatusService.prepareForDaily(
                eq(WorkflowStatusService.PREMARKET_HOUSEKEEPING),
                eq(WorkflowStatusService.PREMARKET_GROUP),
                eq(today.toString()),
                eq(initId)))
                .thenReturn(workflow(houseId, WorkflowStatusService.PREMARKET_HOUSEKEEPING, WorkflowStatus.Status.READY));

        when(workflowStatusService.markRunning(initId))
                .thenReturn(workflow(initId, WorkflowStatusService.TRADING_DAY_INIT, WorkflowStatus.Status.RUNNING));
        when(workflowStatusService.markRunning(houseId))
                .thenReturn(workflow(houseId, WorkflowStatusService.PREMARKET_HOUSEKEEPING, WorkflowStatus.Status.RUNNING));

        when(workflowStatusService.markSuccess(eq(initId), anyString()))
                .thenReturn(workflow(initId, WorkflowStatusService.TRADING_DAY_INIT, WorkflowStatus.Status.SUCCESS));

        when(runtimeHousekeepingService.runHousekeeping()).thenThrow(new RuntimeException("DB Lock timeout"));

        assertThrows(IllegalStateException.class, () -> service.runMorningMaintenance());

        verify(workflowStatusService).markFailed(eq(houseId), anyString(), any(Throwable.class));
    }

    // =========================================================================
    // 08:00 AM Pre-Market Data Preparation Pipeline Tests
    // =========================================================================

    @Test
    void runPreMarketDataPipeline_whenNonTradingDay_skipsExecution() {
        when(tradingCalendar.isTradingDay(today)).thenReturn(false);

        PreMarketWorkflowService.WorkflowResult result = service.runPreMarketDataPipeline();

        assertEquals("SKIPPED", result.status());
        assertEquals(WorkflowStatusService.PREMARKET_CATALOG_SYNC, result.stage());
    }

    @Test
    void runPreMarketDataPipeline_whenTradingDayInitNotSuccess_throwsIllegalState() {
        when(tradingCalendar.isTradingDay(today)).thenReturn(true);
        when(workflowStatusService.isSuccessfulActiveDailyWorkflow(
                WorkflowStatusService.TRADING_DAY_INIT,
                WorkflowStatusService.PREMARKET_GROUP,
                today.toString())).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> service.runPreMarketDataPipeline());
    }

    @Test
    void runPreMarketDataPipeline_whenHousekeepingNotSuccess_throwsIllegalState() {
        when(tradingCalendar.isTradingDay(today)).thenReturn(true);
        when(workflowStatusService.isSuccessfulActiveDailyWorkflow(
                WorkflowStatusService.TRADING_DAY_INIT,
                WorkflowStatusService.PREMARKET_GROUP,
                today.toString())).thenReturn(true);
        when(workflowStatusService.isSuccessfulActiveDailyWorkflow(
                WorkflowStatusService.PREMARKET_HOUSEKEEPING,
                WorkflowStatusService.PREMARKET_GROUP,
                today.toString())).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> service.runPreMarketDataPipeline());
    }

    @Test
    void runPreMarketDataPipeline_whenSuccessful_executesAllThreeStages() {
        when(tradingCalendar.isTradingDay(today)).thenReturn(true);
        when(tradingCalendar.previousTradingDay(today)).thenReturn(prevDay);
        when(workflowStatusService.isSuccessfulActiveDailyWorkflow(
                WorkflowStatusService.TRADING_DAY_INIT,
                WorkflowStatusService.PREMARKET_GROUP,
                today.toString())).thenReturn(true);
        when(workflowStatusService.isSuccessfulActiveDailyWorkflow(
                WorkflowStatusService.PREMARKET_HOUSEKEEPING,
                WorkflowStatusService.PREMARKET_GROUP,
                today.toString())).thenReturn(true);

        UUID catId = UUID.randomUUID();
        UUID uniId = UUID.randomUUID();
        UUID refId = UUID.randomUUID();

        when(workflowStatusService.prepareForDaily(
                eq(WorkflowStatusService.PREMARKET_CATALOG_SYNC),
                eq(WorkflowStatusService.PREMARKET_GROUP),
                eq(today.toString()),
                isNull()))
                .thenReturn(workflow(catId, WorkflowStatusService.PREMARKET_CATALOG_SYNC, WorkflowStatus.Status.READY));

        when(workflowStatusService.prepareForDaily(
                eq(WorkflowStatusService.PREMARKET_UNIVERSE_SYNC),
                eq(WorkflowStatusService.PREMARKET_GROUP),
                eq(today.toString()),
                eq(catId)))
                .thenReturn(workflow(uniId, WorkflowStatusService.PREMARKET_UNIVERSE_SYNC, WorkflowStatus.Status.READY));

        when(workflowStatusService.prepareForDaily(
                eq(WorkflowStatusService.PREMARKET_MORNING_REFERENCE),
                eq(WorkflowStatusService.PREMARKET_GROUP),
                eq(today.toString()),
                eq(uniId)))
                .thenReturn(workflow(refId, WorkflowStatusService.PREMARKET_MORNING_REFERENCE, WorkflowStatus.Status.READY));

        when(workflowStatusService.markRunning(any(UUID.class)))
                .thenAnswer(inv -> workflow(inv.getArgument(0), "stage", WorkflowStatus.Status.RUNNING));
        when(workflowStatusService.markSuccess(any(UUID.class), anyString()))
                .thenAnswer(inv -> workflow(inv.getArgument(0), "stage", WorkflowStatus.Status.SUCCESS));

        // Catalog sync mocks
        when(angelOneProperties.enabled()).thenReturn(true);
        when(catalogSyncService.syncNseCatalog()).thenReturn(new AngelOneInstrumentCatalogSyncService.CatalogSyncResult(
                500, 500, 20, 0, 0, true, "Success"));
        when(instrumentMasterSyncService.ensureRequiredMarketReferences()).thenReturn(0);
        when(instrumentTokenSyncService.syncActiveInstruments()).thenReturn(new InstrumentTokenSyncService.TokenSyncResult(
                50, 50, 50, 0, List.of(), "All mapped"));

        // Universe sync mocks
        StockUniverse tcs = StockUniverse.builder().symbol("TCS").exchange(Exchange.NSE).isActive(true).isTradable(true).build();
        StockUniverse infy = StockUniverse.builder().symbol("INFY").exchange(Exchange.NSE).isActive(true).isTradable(true).build();
        StockUniverse delisted = StockUniverse.builder().symbol("OLDCO").exchange(Exchange.NSE).isActive(true).isTradable(true).build();

        when(stockUniverseRepository.findAll()).thenReturn(List.of(tcs, infy, delisted));

        InstrumentMaster tcsMaster = InstrumentMaster.builder().symbol("TCS").exchange("NSE").isActive(true).build();
        InstrumentMaster infyMaster = InstrumentMaster.builder().symbol("INFY").exchange("NSE").isActive(true).build();
        InstrumentMaster delistedMaster = InstrumentMaster.builder().symbol("OLDCO").exchange("NSE").isActive(false).build();

        when(instrumentMasterRepository.findBySymbolAndExchange("TCS", "NSE")).thenReturn(Optional.of(tcsMaster));
        when(instrumentMasterRepository.findBySymbolAndExchange("INFY", "NSE")).thenReturn(Optional.of(infyMaster));
        when(instrumentMasterRepository.findBySymbolAndExchange("OLDCO", "NSE")).thenReturn(Optional.of(delistedMaster));

        // EOD completeness: TCS complete, INFY missing/incomplete
        when(eodDataEntryService.isSuccessful("TCS", "NSE", prevDay)).thenReturn(true);
        when(eodDataEntryService.isSuccessful("INFY", "NSE", prevDay)).thenReturn(false);

        // Morning reference mock
        when(volumeBaselineService.preCalculateForCompletedTradingDay(prevDay))
                .thenReturn(new VolumeBaselineService.PreCalculationResult(prevDay, today, 50, 50, 50, 0, "Calculated"));

        PreMarketWorkflowService.WorkflowResult result = service.runPreMarketDataPipeline();

        assertEquals("COMPLETE", result.status());
        assertEquals(WorkflowStatusService.PREMARKET_MORNING_REFERENCE, result.stage());

        // Verify INFY demoted to isTradable=false, but isActive remains true!
        assertTrue(infy.getIsActive());
        assertFalse(infy.getIsTradable());

        // Verify TCS stays isTradable=true and isActive=true
        assertTrue(tcs.getIsActive());
        assertTrue(tcs.getIsTradable());

        // Verify delisted stock deactivated and untradable
        assertFalse(delisted.getIsActive());
        assertFalse(delisted.getIsTradable());

        verify(stockUniverseRepository).save(infy);
        verify(stockUniverseRepository).save(delisted);
        verify(volumeBaselineService).preCalculateForCompletedTradingDay(prevDay);
    }

    @Test
    void runPreMarketDataPipeline_whenProviderDisabled_failsStage3() {
        when(tradingCalendar.isTradingDay(today)).thenReturn(true);
        when(workflowStatusService.isSuccessfulActiveDailyWorkflow(
                WorkflowStatusService.TRADING_DAY_INIT,
                WorkflowStatusService.PREMARKET_GROUP,
                today.toString())).thenReturn(true);
        when(workflowStatusService.isSuccessfulActiveDailyWorkflow(
                WorkflowStatusService.PREMARKET_HOUSEKEEPING,
                WorkflowStatusService.PREMARKET_GROUP,
                today.toString())).thenReturn(true);

        UUID catId = UUID.randomUUID();
        when(workflowStatusService.prepareForDaily(
                eq(WorkflowStatusService.PREMARKET_CATALOG_SYNC),
                eq(WorkflowStatusService.PREMARKET_GROUP),
                eq(today.toString()),
                isNull()))
                .thenReturn(workflow(catId, WorkflowStatusService.PREMARKET_CATALOG_SYNC, WorkflowStatus.Status.READY));
        when(workflowStatusService.markRunning(catId))
                .thenReturn(workflow(catId, WorkflowStatusService.PREMARKET_CATALOG_SYNC, WorkflowStatus.Status.RUNNING));

        when(angelOneProperties.enabled()).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> service.runPreMarketDataPipeline());

        verify(workflowStatusService).markFailed(eq(catId), anyString(), any(Throwable.class));
    }

    // =========================================================================
    // 08:55 AM Live Runtime Start Gate Tests
    // =========================================================================

    @Test
    void startLiveRuntime_whenNonTradingDay_skipsExecution() {
        when(tradingCalendar.isTradingDay(today)).thenReturn(false);

        PreMarketWorkflowService.WorkflowResult result = service.startLiveRuntime();

        assertEquals("SKIPPED", result.status());
        assertEquals(WorkflowStatusService.PREMARKET_LIVE_START, result.stage());
    }

    @Test
    void startLiveRuntime_whenAutoRunDisabled_skipsExecution() {
        when(tradingCalendar.isTradingDay(today)).thenReturn(true);
        RuntimeAutomationProperties.Live liveProps = new RuntimeAutomationProperties.Live();
        liveProps.setAutoRun(false);
        when(runtimeAutomationProperties.getLive()).thenReturn(liveProps);

        PreMarketWorkflowService.WorkflowResult result = service.startLiveRuntime();

        assertEquals("SKIPPED", result.status());
        assertEquals(WorkflowStatusService.PREMARKET_LIVE_START, result.stage());
    }

    @Test
    void startLiveRuntime_whenPrerequisiteMissing_throwsIllegalState() {
        when(tradingCalendar.isTradingDay(today)).thenReturn(true);
        RuntimeAutomationProperties.Live liveProps = new RuntimeAutomationProperties.Live();
        liveProps.setAutoRun(true);
        when(runtimeAutomationProperties.getLive()).thenReturn(liveProps);

        // Fail at premarket-universe-sync
        when(workflowStatusService.isSuccessfulActiveDailyWorkflow(
                WorkflowStatusService.TRADING_DAY_INIT,
                WorkflowStatusService.PREMARKET_GROUP,
                today.toString())).thenReturn(true);
        when(workflowStatusService.isSuccessfulActiveDailyWorkflow(
                WorkflowStatusService.PREMARKET_HOUSEKEEPING,
                WorkflowStatusService.PREMARKET_GROUP,
                today.toString())).thenReturn(true);
        when(workflowStatusService.isSuccessfulActiveDailyWorkflow(
                WorkflowStatusService.PREMARKET_CATALOG_SYNC,
                WorkflowStatusService.PREMARKET_GROUP,
                today.toString())).thenReturn(true);
        when(workflowStatusService.isSuccessfulActiveDailyWorkflow(
                WorkflowStatusService.PREMARKET_UNIVERSE_SYNC,
                WorkflowStatusService.PREMARKET_GROUP,
                today.toString())).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> service.startLiveRuntime());
    }

    @Test
    void startLiveRuntime_whenAllPrerequisitesSuccess_connectsAndSubscribes() {
        when(tradingCalendar.isTradingDay(today)).thenReturn(true);
        RuntimeAutomationProperties.Live liveProps = new RuntimeAutomationProperties.Live();
        liveProps.setAutoRun(true);
        when(runtimeAutomationProperties.getLive()).thenReturn(liveProps);

        when(workflowStatusService.isSuccessfulActiveDailyWorkflow(anyString(), eq(WorkflowStatusService.PREMARKET_GROUP), eq(today.toString())))
                .thenReturn(true);

        UUID liveId = UUID.randomUUID();
        when(workflowStatusService.prepareForDaily(
                eq(WorkflowStatusService.PREMARKET_LIVE_START),
                eq(WorkflowStatusService.PREMARKET_GROUP),
                eq(today.toString()),
                isNull()))
                .thenReturn(workflow(liveId, WorkflowStatusService.PREMARKET_LIVE_START, WorkflowStatus.Status.READY));
        when(workflowStatusService.markRunning(liveId))
                .thenReturn(workflow(liveId, WorkflowStatusService.PREMARKET_LIVE_START, WorkflowStatus.Status.RUNNING));
        when(workflowStatusService.markSuccess(eq(liveId), anyString()))
                .thenReturn(workflow(liveId, WorkflowStatusService.PREMARKET_LIVE_START, WorkflowStatus.Status.SUCCESS));

        when(runtimeAutomationService.connectAndSubscribe()).thenReturn(new RuntimeAutomationService.RuntimeActionResult(
                "CONNECT_AND_SUBSCRIBE", true, 50, 0, "Connected and subscribed"));

        PreMarketWorkflowService.WorkflowResult result = service.startLiveRuntime();

        assertEquals("COMPLETE", result.status());
        assertEquals(WorkflowStatusService.PREMARKET_LIVE_START, result.stage());

        verify(runtimeAutomationService).connectAndSubscribe();
        verify(workflowStatusService).markSuccess(eq(liveId), anyString());
    }

    private WorkflowStatus workflow(UUID id, String name, WorkflowStatus.Status status) {
        return WorkflowStatus.builder()
                .workflowId(id)
                .name(name)
                .workflowGroup(WorkflowStatusService.PREMARKET_GROUP)
                .processDate(today.toString())
                .status(status)
                .isActive(true)
                .attemptCount(0)
                .build();
    }
}

