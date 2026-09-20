package com.trading.scanner.service.runtime;

import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.config.RuntimeAutomationProperties;
import com.trading.scanner.config.StockUniverseSeeder;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.config.provider.AngelOneProperties;
import com.trading.scanner.model.Exchange;
import com.trading.scanner.model.InstrumentMaster;
import com.trading.scanner.model.StockUniverse;
import com.trading.scanner.model.WorkflowStatus;
import com.trading.scanner.repository.InstrumentMasterRepository;
import com.trading.scanner.repository.StockUniverseRepository;
import com.trading.scanner.service.data.EodDataEntryService;
import com.trading.scanner.service.instrument.AngelOneInstrumentCatalogSyncService;
import com.trading.scanner.service.instrument.InstrumentMasterSyncService;
import com.trading.scanner.service.workflow.WorkflowStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RuntimeBootstrapServiceTest {

    @Mock
    private RuntimeAutomationProperties runtimeAutomationProperties;

    @Mock
    private StockUniverseSeeder stockUniverseSeeder;

    @Mock
    private InstrumentMasterSyncService instrumentMasterSyncService;

    @Mock
    private AngelOneInstrumentCatalogSyncService catalogSyncService;

    @Mock
    private AngelOneProperties angelOneProperties;

    @Mock
    private RuntimeSettingService runtimeSettingService;

    @Mock
    private WorkflowStatusService workflowStatusService;

    @Mock
    private StockUniverseRepository stockUniverseRepository;

    @Mock
    private InstrumentMasterRepository instrumentMasterRepository;

    @Mock
    private EodDataEntryService eodDataEntryService;

    @Mock
    private TradingCalendar tradingCalendar;

    @Mock
    private TimeProvider timeProvider;

    @Mock
    private RuntimeAlertService runtimeAlertService;

    @Mock
    private ApplicationArguments applicationArguments;

    private RuntimeBootstrapService service;

    @BeforeEach
    void setUp() {
        service = new RuntimeBootstrapService(
                runtimeAutomationProperties,
                stockUniverseSeeder,
                instrumentMasterSyncService,
                catalogSyncService,
                angelOneProperties,
                runtimeSettingService,
                workflowStatusService,
                stockUniverseRepository,
                instrumentMasterRepository,
                eodDataEntryService,
                tradingCalendar,
                timeProvider,
                runtimeAlertService);
    }

    @Test
    void bootstrapIfNeeded_shouldExecuteAllSixStagesSequentially() {
        UUID w1 = UUID.randomUUID();
        UUID w2 = UUID.randomUUID();
        UUID w3 = UUID.randomUUID();
        UUID w4 = UUID.randomUUID();
        UUID w5 = UUID.randomUUID();
        UUID w6 = UUID.randomUUID();

        when(workflowStatusService.prepareForStartup(WorkflowStatusService.INSTRUMENT_MASTER_SYNC))
                .thenReturn(workflow(w1, WorkflowStatusService.INSTRUMENT_MASTER_SYNC, WorkflowStatus.Status.READY));
        when(workflowStatusService.prepareForStartup(WorkflowStatusService.MARKET_REFERENCE_SETUP))
                .thenReturn(workflow(w2, WorkflowStatusService.MARKET_REFERENCE_SETUP, WorkflowStatus.Status.READY));
        when(workflowStatusService.prepareForStartup(WorkflowStatusService.STOCK_UNIVERSE_SEED))
                .thenReturn(workflow(w3, WorkflowStatusService.STOCK_UNIVERSE_SEED, WorkflowStatus.Status.READY));
        when(workflowStatusService.prepareForStartup(WorkflowStatusService.RUNTIME_SETTING_SYNC))
                .thenReturn(workflow(w4, WorkflowStatusService.RUNTIME_SETTING_SYNC, WorkflowStatus.Status.READY));
        when(workflowStatusService.prepareForStartup(WorkflowStatusService.EOD_DATA_READINESS))
                .thenReturn(workflow(w5, WorkflowStatusService.EOD_DATA_READINESS, WorkflowStatus.Status.READY));
        when(workflowStatusService.prepareForStartup(WorkflowStatusService.RUNTIME_BOOTSTRAP_COMPLETE))
                .thenReturn(workflow(w6, WorkflowStatusService.RUNTIME_BOOTSTRAP_COMPLETE, WorkflowStatus.Status.READY));

        when(workflowStatusService.markRunning(any(UUID.class)))
                .thenAnswer(inv -> workflow(inv.getArgument(0), "stage", WorkflowStatus.Status.RUNNING));

        // Stage 1
        when(angelOneProperties.enabled()).thenReturn(true);
        when(catalogSyncService.syncNseCatalog()).thenReturn(new AngelOneInstrumentCatalogSyncService.CatalogSyncResult(
                100, 100, 10, 0, 0, true, "Success"));

        // Stage 2
        when(instrumentMasterSyncService.ensureRequiredMarketReferences()).thenReturn(1);

        // Stage 3
        when(stockUniverseSeeder.seedIfNeeded()).thenReturn(new StockUniverseSeeder.SeedResult(50, "Seeded"));

        // Stage 4
        when(runtimeSettingService.ensureRequiredSettingsExist()).thenReturn(12);

        // Stage 5 & 6
        StockUniverse stock = StockUniverse.builder().symbol("TCS").exchange(Exchange.NSE).isActive(true).build();
        List<StockUniverse> activeList = List.of(stock);
        LocalDate today = LocalDate.of(2026, 9, 20);
        LocalDate prevDay = LocalDate.of(2026, 9, 19);

        when(stockUniverseRepository.findByIsActiveTrueOrderBySymbolAsc()).thenReturn(activeList);
        when(timeProvider.today()).thenReturn(today);
        when(tradingCalendar.previousTradingDay(today)).thenReturn(prevDay);
        when(eodDataEntryService.ensureInitialBaselineIfEmpty(activeList, prevDay)).thenReturn(2);

        InstrumentMaster tcsMaster = InstrumentMaster.builder().symbol("TCS").exchange("NSE").brokerToken("11536").isActive(true).build();
        InstrumentMaster niftyMaster = InstrumentMaster.builder().symbol("NIFTY").exchange("NSE").brokerToken("99926000").isActive(true).build();
        when(instrumentMasterRepository.findBySymbolAndExchange("TCS", "NSE")).thenReturn(Optional.of(tcsMaster));
        when(instrumentMasterRepository.findBySymbolAndExchange("NIFTY", "NSE")).thenReturn(Optional.of(niftyMaster));

        RuntimeBootstrapService.BootstrapResult result = service.bootstrapIfNeeded();

        assertEquals("Runtime bootstrap 6-stage sequence completed", result.message());

        verify(workflowStatusService).markSuccess(eq(w1), anyString());
        verify(workflowStatusService).markSuccess(eq(w2), anyString());
        verify(workflowStatusService).markSuccess(eq(w3), anyString());
        verify(workflowStatusService).markSuccess(eq(w4), anyString());
        verify(workflowStatusService).markSuccess(eq(w5), anyString());
        verify(workflowStatusService).markSuccess(eq(w6), anyString());
        verify(runtimeAlertService).resolveInstrumentMasterStartupFailure();
    }

    @Test
    void stageAlreadySuccessful_shouldBeSkipped() {
        UUID w1 = UUID.randomUUID();
        when(workflowStatusService.prepareForStartup(WorkflowStatusService.INSTRUMENT_MASTER_SYNC))
                .thenReturn(workflow(w1, WorkflowStatusService.INSTRUMENT_MASTER_SYNC, WorkflowStatus.Status.SUCCESS));
        when(workflowStatusService.prepareForStartup(WorkflowStatusService.MARKET_REFERENCE_SETUP))
                .thenReturn(workflow(w1, WorkflowStatusService.MARKET_REFERENCE_SETUP, WorkflowStatus.Status.SUCCESS));
        when(workflowStatusService.prepareForStartup(WorkflowStatusService.STOCK_UNIVERSE_SEED))
                .thenReturn(workflow(w1, WorkflowStatusService.STOCK_UNIVERSE_SEED, WorkflowStatus.Status.SUCCESS));
        when(workflowStatusService.prepareForStartup(WorkflowStatusService.RUNTIME_SETTING_SYNC))
                .thenReturn(workflow(w1, WorkflowStatusService.RUNTIME_SETTING_SYNC, WorkflowStatus.Status.SUCCESS));
        when(workflowStatusService.prepareForStartup(WorkflowStatusService.EOD_DATA_READINESS))
                .thenReturn(workflow(w1, WorkflowStatusService.EOD_DATA_READINESS, WorkflowStatus.Status.SUCCESS));
        when(workflowStatusService.prepareForStartup(WorkflowStatusService.RUNTIME_BOOTSTRAP_COMPLETE))
                .thenReturn(workflow(w1, WorkflowStatusService.RUNTIME_BOOTSTRAP_COMPLETE, WorkflowStatus.Status.SUCCESS));

        service.run(applicationArguments);

        verify(catalogSyncService, never()).syncNseCatalog();
        verify(instrumentMasterSyncService, never()).ensureRequiredMarketReferences();
        verify(stockUniverseSeeder, never()).seedIfNeeded();
        verify(runtimeSettingService, never()).ensureRequiredSettingsExist();
        verify(eodDataEntryService, never()).ensureInitialBaselineIfEmpty(any(), any());
        verify(workflowStatusService, never()).markRunning(any());
    }

    @Test
    void disabledAngelOne_shouldFailStage1AndRecordError() {
        UUID w1 = UUID.randomUUID();
        when(workflowStatusService.prepareForStartup(WorkflowStatusService.INSTRUMENT_MASTER_SYNC))
                .thenReturn(workflow(w1, WorkflowStatusService.INSTRUMENT_MASTER_SYNC, WorkflowStatus.Status.READY));
        when(workflowStatusService.markRunning(w1))
                .thenReturn(workflow(w1, WorkflowStatusService.INSTRUMENT_MASTER_SYNC, WorkflowStatus.Status.RUNNING));
        when(angelOneProperties.enabled()).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> service.run(applicationArguments));

        verify(workflowStatusService).markFailed(eq(w1), anyString(), any(Throwable.class));
        verify(runtimeAlertService).reportInstrumentMasterStartupFailure(eq(w1), eq(0), any(Throwable.class));
    }

    @Test
    void missingBrokerToken_shouldFailStage6() {
        UUID w1 = UUID.randomUUID();
        when(workflowStatusService.prepareForStartup(WorkflowStatusService.INSTRUMENT_MASTER_SYNC))
                .thenReturn(workflow(w1, WorkflowStatusService.INSTRUMENT_MASTER_SYNC, WorkflowStatus.Status.SUCCESS));
        when(workflowStatusService.prepareForStartup(WorkflowStatusService.MARKET_REFERENCE_SETUP))
                .thenReturn(workflow(w1, WorkflowStatusService.MARKET_REFERENCE_SETUP, WorkflowStatus.Status.SUCCESS));
        when(workflowStatusService.prepareForStartup(WorkflowStatusService.STOCK_UNIVERSE_SEED))
                .thenReturn(workflow(w1, WorkflowStatusService.STOCK_UNIVERSE_SEED, WorkflowStatus.Status.SUCCESS));
        when(workflowStatusService.prepareForStartup(WorkflowStatusService.RUNTIME_SETTING_SYNC))
                .thenReturn(workflow(w1, WorkflowStatusService.RUNTIME_SETTING_SYNC, WorkflowStatus.Status.SUCCESS));
        when(workflowStatusService.prepareForStartup(WorkflowStatusService.EOD_DATA_READINESS))
                .thenReturn(workflow(w1, WorkflowStatusService.EOD_DATA_READINESS, WorkflowStatus.Status.SUCCESS));

        UUID w6 = UUID.randomUUID();
        when(workflowStatusService.prepareForStartup(WorkflowStatusService.RUNTIME_BOOTSTRAP_COMPLETE))
                .thenReturn(workflow(w6, WorkflowStatusService.RUNTIME_BOOTSTRAP_COMPLETE, WorkflowStatus.Status.READY));
        when(workflowStatusService.markRunning(w6))
                .thenReturn(workflow(w6, WorkflowStatusService.RUNTIME_BOOTSTRAP_COMPLETE, WorkflowStatus.Status.RUNNING));

        StockUniverse stock = StockUniverse.builder().symbol("INFY").exchange(Exchange.NSE).isActive(true).build();
        when(stockUniverseRepository.findByIsActiveTrueOrderBySymbolAsc()).thenReturn(List.of(stock));
        when(instrumentMasterRepository.findBySymbolAndExchange("INFY", "NSE")).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> service.bootstrapIfNeeded());

        verify(workflowStatusService).markFailed(eq(w6), anyString(), any(Throwable.class));
    }

    private WorkflowStatus workflow(UUID id, String name, WorkflowStatus.Status status) {
        return WorkflowStatus.builder()
                .workflowId(id)
                .name(name)
                .workflowGroup(WorkflowStatusService.STARTUP_GROUP)
                .status(status)
                .isActive(true)
                .attemptCount(0)
                .build();
    }
}