package com.trading.scanner.execution;

import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.execution.adapter.SimulatedOrderAdapter;
import com.trading.scanner.model.RuntimeSetting;
import com.trading.scanner.model.SimulatedOrder;
import com.trading.scanner.model.SimulatedPosition;
import com.trading.scanner.model.StrategyConfig;
import com.trading.scanner.position.PositionManagerService;
import com.trading.scanner.repository.RuntimeSettingRepository;
import com.trading.scanner.repository.SimulatedOrderRepository;
import com.trading.scanner.repository.SimulatedPositionRepository;
import com.trading.scanner.repository.StrategyConfigRepository;
import com.trading.scanner.risk.RiskEngine;
import com.trading.scanner.risk.model.PortfolioMode;
import com.trading.scanner.strategy.cache.StockContextCache;
import com.trading.scanner.strategy.model.PositionSnapshot;
import com.trading.scanner.strategy.model.StockContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SimulationManagementServiceTest {

    @Mock
    private RuntimeSettingRepository runtimeSettingRepository;

    @Mock
    private SimulatedPositionRepository simulatedPositionRepository;

    @Mock
    private SimulatedOrderRepository simulatedOrderRepository;

    @Mock
    private StrategyConfigRepository strategyConfigRepository;

    @Mock
    private RiskEngine riskEngine;

    @Mock
    private PositionManagerService positionManagerService;

    @Mock
    private SimulatedOrderAdapter simulatedOrderAdapter;

    @Mock
    private StockContextCache stockContextCache;

    @Mock
    private TimeProvider timeProvider;

    private SimulationManagementService simulationService;

    private final LocalDate today = LocalDate.of(2026, 10, 11);

    @BeforeEach
    void setUp() {
        lenient().when(timeProvider.today()).thenReturn(today);

        simulationService = new SimulationManagementService(
                runtimeSettingRepository,
                simulatedPositionRepository,
                simulatedOrderRepository,
                strategyConfigRepository,
                riskEngine,
                positionManagerService,
                simulatedOrderAdapter,
                stockContextCache,
                timeProvider
        );
    }

    @Test
    @DisplayName("getBaselineCapital returns default 50000 when unconfigured, or saved amount")
    void getBaselineCapital_shouldReturnConfiguredOrDefault() {
        when(runtimeSettingRepository.findByNameAndIsActiveTrue(SimulationManagementService.SETTING_INITIAL_CAPITAL))
                .thenReturn(Optional.empty());

        assertEquals(50000.0, simulationService.getBaselineCapital());

        RuntimeSetting setting = RuntimeSetting.builder().value("75000.0").build();
        when(runtimeSettingRepository.findByNameAndIsActiveTrue(SimulationManagementService.SETTING_INITIAL_CAPITAL))
                .thenReturn(Optional.of(setting));

        assertEquals(75000.0, simulationService.getBaselineCapital());
    }

    @Test
    @DisplayName("setInitialCapital updates runtime setting and syncs RiskEngine")
    void setInitialCapital_shouldPersistAndSync() {
        RuntimeSetting setting = RuntimeSetting.builder().value("60000.0").build();
        when(runtimeSettingRepository.findByName(SimulationManagementService.SETTING_INITIAL_CAPITAL))
                .thenReturn(Optional.empty());
        when(runtimeSettingRepository.findByNameAndIsActiveTrue(SimulationManagementService.SETTING_INITIAL_CAPITAL))
                .thenReturn(Optional.of(setting));
        when(runtimeSettingRepository.save(any(RuntimeSetting.class))).thenAnswer(inv -> inv.getArgument(0));
        when(simulatedPositionRepository.findByPositionStatus("CLOSED")).thenReturn(Collections.emptyList());

        SimulationManagementService.SimulationCapitalResponse response = simulationService.setInitialCapital(60000.0);

        verify(runtimeSettingRepository).save(any(RuntimeSetting.class));
        verify(riskEngine).initializePortfolio(PortfolioMode.PAPER, 60000.0, today);
        assertEquals(60000.0, response.baselineCapital());
        assertEquals(60000.0, response.currentCompoundedCapital());
    }

    @Test
    @DisplayName("getCapitalStatus correctly compounds prior days profits and isolates today's PnL")
    void getCapitalStatus_shouldCompoundCapitalAcrossDays() {
        when(runtimeSettingRepository.findByNameAndIsActiveTrue(SimulationManagementService.SETTING_INITIAL_CAPITAL))
                .thenReturn(Optional.empty()); // default 50000.0

        SimulatedPosition pastTrade = SimulatedPosition.builder()
                .positionStatus("CLOSED")
                .entryTime("2026-10-10T09:35:00")
                .netPnl(1500.0)
                .build();

        SimulatedPosition todayTrade = SimulatedPosition.builder()
                .positionStatus("CLOSED")
                .entryTime("2026-10-11T09:40:00")
                .netPnl(600.0)
                .build();

        when(simulatedPositionRepository.findByPositionStatus("CLOSED"))
                .thenReturn(List.of(pastTrade, todayTrade));

        SimulationManagementService.SimulationCapitalResponse status = simulationService.getCapitalStatus();

        assertEquals(50000.0, status.baselineCapital());
        // Starting capital today = 50000 + 1500 = 51500
        assertEquals(51500.0, status.startingCapitalToday());
        // Current compounded capital = 51500 + 600 = 52100
        assertEquals(52100.0, status.currentCompoundedCapital());
        assertEquals(600.0, status.realizedPnlToday());
        assertEquals(2100.0, status.cumulativeRealizedPnl());
        assertEquals(4.2, status.cumulativeReturnPercent(), 0.001);
    }

    @Test
    @DisplayName("resetSimulation with scope=TODAY clears today's trades and restores morning balance")
    void resetSimulation_todayScope_shouldRestoreMorningOpeningBalance() {
        when(runtimeSettingRepository.findByNameAndIsActiveTrue(SimulationManagementService.SETTING_INITIAL_CAPITAL))
                .thenReturn(Optional.empty());

        SimulatedPosition pastTrade = SimulatedPosition.builder()
                .positionStatus("CLOSED")
                .entryTime("2026-10-10T09:35:00")
                .netPnl(2000.0)
                .build();
        when(simulatedPositionRepository.findByPositionStatus("CLOSED")).thenReturn(List.of(pastTrade));

        SimulationManagementService.SimulationResetResponse response = simulationService.resetSimulation("TODAY");

        verify(positionManagerService).clearPositionsForMode(PortfolioMode.PAPER);
        verify(simulatedOrderAdapter).clearAllRestingOrders();
        verify(simulatedOrderRepository).deleteByPlacedAtStartingWith("2026-10-11");
        verify(simulatedPositionRepository).deleteByEntryTimeStartingWith("2026-10-11");
        verify(riskEngine).initializePortfolio(PortfolioMode.PAPER, 52000.0, today);

        assertEquals("SUCCESS", response.status());
        assertEquals("TODAY", response.scope());
        assertEquals(52000.0, response.resultingCapital());
    }

    @Test
    @DisplayName("resetSimulation with scope=TOTAL wipes all simulation records and resets to baseline")
    void resetSimulation_totalScope_shouldWipeAllHistory() {
        when(runtimeSettingRepository.findByNameAndIsActiveTrue(SimulationManagementService.SETTING_INITIAL_CAPITAL))
                .thenReturn(Optional.empty());

        SimulationManagementService.SimulationResetResponse response = simulationService.resetSimulation("TOTAL");

        verify(positionManagerService).clearPositionsForMode(PortfolioMode.PAPER);
        verify(simulatedOrderAdapter).clearAllRestingOrders();
        verify(simulatedOrderRepository).deleteAll();
        verify(simulatedPositionRepository).deleteAll();
        verify(riskEngine).initializePortfolio(PortfolioMode.PAPER, 50000.0, today);

        assertEquals("SUCCESS", response.status());
        assertEquals("TOTAL", response.scope());
        assertEquals(50000.0, response.resultingCapital());
    }

    @Test
    @DisplayName("getOpenPositionsToday enriches in-memory snapshot with live LTP and unrealized PnL")
    void getOpenPositionsToday_shouldEnrichWithLiveLtpAndPnl() {
        PositionSnapshot pos = new PositionSnapshot(
                101, 2, "INFY", "NSE", "LONG", 50, 50, 1500.0, 1485.0, 1511.0, 1495.0, false, 1500.0, today.atTime(9, 35)
        );
        when(positionManagerService.getActivePositions(PortfolioMode.PAPER)).thenReturn(Map.of("INFY", pos));

        StockContext mockCtx = mock(StockContext.class);
        when(mockCtx.currentLtp()).thenReturn(1508.0);
        when(stockContextCache.getStockContext("INFY", "NSE", today)).thenReturn(Optional.of(mockCtx));

        List<SimulationManagementService.OpenPositionResponse> result = simulationService.getOpenPositionsToday();

        assertEquals(1, result.size());
        SimulationManagementService.OpenPositionResponse openPos = result.get(0);
        assertEquals("INFY", openPos.symbol());
        assertEquals(1500.0, openPos.entryPrice());
        assertEquals(1508.0, openPos.currentLtp());
        // Unrealized PnL: (1508.0 - 1500.0) * 50 = +400.0
        assertEquals(400.0, openPos.unrealizedPnl(), 0.001);
        assertEquals(0.5333, openPos.unrealizedPnlPercent(), 0.001);
    }

    @Test
    @DisplayName("getPerformance calculates lifetime analytics including profit factor and win rate")
    void getPerformance_lifetime_shouldComputeMetrics() {
        when(runtimeSettingRepository.findByNameAndIsActiveTrue(SimulationManagementService.SETTING_INITIAL_CAPITAL))
                .thenReturn(Optional.empty());

        SimulatedPosition win1 = SimulatedPosition.builder()
                .positionStatus("CLOSED")
                .entryTime("2026-10-10T09:35:00")
                .grossPnl(1000.0)
                .statutoryCharges(50.0)
                .netPnl(950.0)
                .mfeR(1.5)
                .maeR(-0.2)
                .holdingDurationSeconds(3600)
                .build();

        SimulatedPosition loss1 = SimulatedPosition.builder()
                .positionStatus("CLOSED")
                .entryTime("2026-10-11T10:00:00")
                .grossPnl(-400.0)
                .statutoryCharges(40.0)
                .netPnl(-440.0)
                .mfeR(0.3)
                .maeR(-1.0)
                .holdingDurationSeconds(1800)
                .build();

        when(simulatedPositionRepository.findAll()).thenReturn(List.of(win1, loss1));
        when(positionManagerService.getActivePositions(PortfolioMode.PAPER)).thenReturn(Collections.emptyMap());

        SimulationManagementService.SimulationPerformanceResponse perf = simulationService.getPerformance(null, null);

        assertEquals("LIFETIME", perf.period());
        assertEquals(2, perf.totalTrades());
        assertEquals(1, perf.winningTrades());
        assertEquals(1, perf.losingTrades());
        assertEquals(50.0, perf.winRatePercent(), 0.001);
        assertEquals(600.0, perf.grossPnl(), 0.001);
        assertEquals(90.0, perf.totalStatutoryCharges(), 0.001);
        assertEquals(510.0, perf.netRealizedPnl(), 0.001);
        // Profit factor = 1000.0 / 400.0 = 2.5
        assertEquals(2.5, perf.profitFactor(), 0.001);
        assertEquals(1.5, perf.maxMfeR());
        assertEquals(-1.0, perf.maxMaeR());
        assertEquals(2700L, perf.averageHoldingDurationSeconds());

        // Today's summary has loss1
        assertNotNull(perf.todaySummary());
        assertEquals(1, perf.todaySummary().tradesCount());
        assertEquals(-400.0, perf.todaySummary().grossPnl());
        assertEquals(-440.0, perf.todaySummary().netPnl());
    }

    @Test
    @DisplayName("updateStrategyMode toggles mode and active status in DB")
    void updateStrategyMode_shouldUpdateConfig() {
        StrategyConfig cfg = StrategyConfig.builder()
                .id(2)
                .strategyKey("FCHB")
                .executionMode("PAPER")
                .isActive(true)
                .build();

        when(strategyConfigRepository.findById(2)).thenReturn(Optional.of(cfg));
        when(strategyConfigRepository.save(any(StrategyConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        SimulationManagementService.StrategySimulationResponse resp = simulationService.updateStrategyMode(2, "LIVE");

        assertEquals("LIVE", resp.executionMode());
        assertTrue(resp.isActive());
        verify(strategyConfigRepository).save(cfg);
    }
}
