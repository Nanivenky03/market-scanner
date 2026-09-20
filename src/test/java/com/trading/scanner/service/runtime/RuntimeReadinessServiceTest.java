package com.trading.scanner.service.runtime;

import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.config.ExchangeConfiguration;
import com.trading.scanner.config.RuntimeAutomationProperties;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.Exchange;
import com.trading.scanner.model.InstrumentMaster;
import com.trading.scanner.model.StockUniverse;
import com.trading.scanner.repository.InstrumentMasterRepository;
import com.trading.scanner.repository.LiveSimulationSignalRepository;
import com.trading.scanner.repository.MarketCandleRepository;
import com.trading.scanner.repository.StockPriceRepository;
import com.trading.scanner.repository.StockUniverseRepository;
import com.trading.scanner.service.data.EodDataEntryService;
import com.trading.scanner.service.provider.angelone.AngelOneWebSocketService;
import com.trading.scanner.service.workflow.WorkflowStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuntimeReadinessServiceTest {

    @Mock
    private StockUniverseRepository stockUniverseRepository;

    @Mock
    private InstrumentMasterRepository instrumentMasterRepository;

    @Mock
    private StockPriceRepository stockPriceRepository;

    @Mock
    private MarketCandleRepository marketCandleRepository;

    @Mock
    private LiveSimulationSignalRepository liveSimulationSignalRepository;

    @Mock
    private AngelOneWebSocketService angelOneWebSocketService;

    @Mock
    private RuntimeSettingService runtimeSettingService;

    @Mock
    private WorkflowStatusService workflowStatusService;

    @Mock
    private TimeProvider timeProvider;

    @Mock
    private TradingCalendar tradingCalendar;

    @Mock
    private ExchangeConfiguration exchangeConfiguration;

    @Mock
    private EodDataEntryService eodDataEntryService;

    private RuntimeAutomationProperties runtimeAutomationProperties;
    private RuntimeReadinessService runtimeReadinessService;

    private final LocalDate today = LocalDate.of(2026, 9, 21);
    private final LocalDate prevTradingDay = LocalDate.of(2026, 9, 18);

    @BeforeEach
    void setUp() {
        runtimeAutomationProperties = new RuntimeAutomationProperties();
        runtimeAutomationProperties.getLive().setAutoRun(true);

        runtimeReadinessService = new RuntimeReadinessService(
                stockUniverseRepository,
                instrumentMasterRepository,
                stockPriceRepository,
                marketCandleRepository,
                liveSimulationSignalRepository,
                angelOneWebSocketService,
                runtimeSettingService,
                runtimeAutomationProperties,
                timeProvider,
                tradingCalendar,
                exchangeConfiguration,
                workflowStatusService,
                eodDataEntryService);
    }

    @Test
    void status_shouldReportReadyWhenAllGatesPass() {
        when(timeProvider.today()).thenReturn(today);
        when(timeProvider.nowDateTime()).thenReturn(today.atTime(9, 30));
        when(tradingCalendar.isTradingDay(today)).thenReturn(true);
        when(tradingCalendar.previousTradingDay(today)).thenReturn(prevTradingDay);
        when(exchangeConfiguration.getMarketOpen()).thenReturn(LocalTime.of(9, 15));
        when(exchangeConfiguration.getMarketClose()).thenReturn(LocalTime.of(15, 30));

        StockUniverse stock = StockUniverse.builder().symbol("TCS").exchange(Exchange.NSE).isActive(true).build();
        when(stockUniverseRepository.findByIsActiveTrueOrderBySymbolAsc()).thenReturn(List.of(stock));
        when(instrumentMasterRepository.count()).thenReturn(51L);

        InstrumentMaster tcsMaster = InstrumentMaster.builder().symbol("TCS").exchange("NSE").brokerToken("11536").isActive(true).build();
        InstrumentMaster niftyMaster = InstrumentMaster.builder().symbol("NIFTY").exchange("NSE").brokerToken("99926000").isActive(true).build();
        when(instrumentMasterRepository.findBySymbolAndExchange("TCS", "NSE")).thenReturn(Optional.of(tcsMaster));
        when(instrumentMasterRepository.findBySymbolAndExchange("NIFTY", "NSE")).thenReturn(Optional.of(niftyMaster));

        when(angelOneWebSocketService.status()).thenReturn(wsStatus(true));
        when(workflowStatusService.isSuccessfulActiveStartupWorkflow(WorkflowStatusService.RUNTIME_BOOTSTRAP_COMPLETE)).thenReturn(true);
        when(eodDataEntryService.isSuccessful(anyString(), anyString(), any(LocalDate.class))).thenReturn(true);

        RuntimeReadinessService.ReadinessStatus status = runtimeReadinessService.status();

        assertTrue(status.tradingDay());
        assertTrue(status.marketSessionOpen());
        assertEquals("COMPLETE", status.bootstrapStatus());
        assertTrue(status.bootstrapReady());
        assertTrue(status.readyForLiveRuntime());
        assertTrue(status.readyForCloudDeployment());
        assertEquals(0, status.missingBrokerTokenCount());
    }

    @Test
    void status_shouldReportNotReadyWhenBootstrapIncomplete() {
        when(timeProvider.today()).thenReturn(today);
        when(timeProvider.nowDateTime()).thenReturn(today.atTime(9, 30));
        when(tradingCalendar.isTradingDay(today)).thenReturn(true);
        when(exchangeConfiguration.getMarketOpen()).thenReturn(LocalTime.of(9, 15));
        when(exchangeConfiguration.getMarketClose()).thenReturn(LocalTime.of(15, 30));

        when(angelOneWebSocketService.status()).thenReturn(wsStatus(false));
        when(workflowStatusService.isSuccessfulActiveStartupWorkflow(WorkflowStatusService.RUNTIME_BOOTSTRAP_COMPLETE)).thenReturn(false);

        RuntimeReadinessService.ReadinessStatus status = runtimeReadinessService.status();

        assertEquals("REQUIRED", status.bootstrapStatus());
        assertFalse(status.bootstrapReady());
        assertFalse(status.readyForLiveRuntime());
    }

    @Test
    void bootstrapReady_shouldReturnTrueWhenWorkflowCompleteAndEodReady() {
        when(workflowStatusService.isSuccessfulActiveStartupWorkflow(WorkflowStatusService.RUNTIME_BOOTSTRAP_COMPLETE)).thenReturn(true);
        when(timeProvider.today()).thenReturn(today);
        when(tradingCalendar.isTradingDay(today)).thenReturn(true);
        when(tradingCalendar.previousTradingDay(today)).thenReturn(prevTradingDay);

        StockUniverse stock = StockUniverse.builder().symbol("TCS").exchange(Exchange.NSE).isActive(true).build();
        when(stockUniverseRepository.findByIsActiveTrueOrderBySymbolAsc()).thenReturn(List.of(stock));
        when(eodDataEntryService.isSuccessful(anyString(), anyString(), any(LocalDate.class))).thenReturn(true);

        assertTrue(runtimeReadinessService.bootstrapReady());
    }

    private AngelOneWebSocketService.Status wsStatus(boolean connected) {
        return new AngelOneWebSocketService.Status(
                connected,
                false,
                LocalDateTime.now(),
                null,
                LocalDateTime.now(),
                null,
                null,
                null,
                null,
                100L,
                10L,
                10L,
                100L,
                0L);
    }
}