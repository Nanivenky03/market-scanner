package com.trading.scanner.controller;

import com.trading.scanner.execution.SimulationManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SimulationDashboardControllerTest {

    private SimulationManagementService simulationService;
    private SimulationDashboardController controller;

    private final LocalDate today = LocalDate.of(2026, 10, 11);

    @BeforeEach
    void setUp() {
        simulationService = mock(SimulationManagementService.class);
        controller = new SimulationDashboardController(simulationService);
    }

    @Test
    @DisplayName("GET /api/v1/simulation/capital returns capital status")
    void getCapitalStatus_shouldReturnStatus() {
        SimulationManagementService.SimulationCapitalResponse mockResp =
                new SimulationManagementService.SimulationCapitalResponse(50000.0, 51500.0, 52100.0, 600.0, 2100.0, 4.2, today);
        when(simulationService.getCapitalStatus()).thenReturn(mockResp);

        ResponseEntity<SimulationManagementService.SimulationCapitalResponse> response = controller.getCapitalStatus();
        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertEquals(52100.0, response.getBody().currentCompoundedCapital());
    }

    @Test
    @DisplayName("POST /api/v1/simulation/capital sets baseline capital")
    void setCapital_shouldUpdateBaseline() {
        SimulationManagementService.SimulationCapitalResponse mockResp =
                new SimulationManagementService.SimulationCapitalResponse(75000.0, 75000.0, 75000.0, 0.0, 0.0, 0.0, today);
        when(simulationService.setInitialCapital(75000.0)).thenReturn(mockResp);

        ResponseEntity<SimulationManagementService.SimulationCapitalResponse> response = controller.setCapital(75000.0);
        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertEquals(75000.0, response.getBody().baselineCapital());
    }

    @Test
    @DisplayName("POST /api/v1/simulation/reset executes reset with given scope")
    void resetSimulation_shouldExecuteReset() {
        SimulationManagementService.SimulationResetResponse mockResp =
                new SimulationManagementService.SimulationResetResponse("SUCCESS", "TODAY", "Reset completed", 51500.0, today);
        when(simulationService.resetSimulation("TODAY")).thenReturn(mockResp);

        ResponseEntity<SimulationManagementService.SimulationResetResponse> response = controller.resetSimulation("TODAY");
        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("TODAY", response.getBody().scope());
    }

    @Test
    @DisplayName("GET /api/v1/simulation/positions/open returns active positions")
    void getOpenPositions_shouldReturnList() {
        SimulationManagementService.OpenPositionResponse pos = new SimulationManagementService.OpenPositionResponse(
                1, 2, "INFY", "NSE", "LONG", 50, 50, 1500.0, 1508.0, 400.0, 0.53, 1485.0, 1511.0, false, 1508.0, 1500.0, "2026-10-11T09:35", "SL1"
        );
        when(simulationService.getOpenPositionsToday()).thenReturn(List.of(pos));

        ResponseEntity<List<SimulationManagementService.OpenPositionResponse>> response = controller.getOpenPositions();
        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());
        assertEquals("INFY", response.getBody().get(0).symbol());
    }

    @Test
    @DisplayName("GET /api/v1/simulation/orders returns ledger")
    void getOrders_shouldReturnOrderList() {
        SimulationManagementService.SimulationOrderResponse order = new SimulationManagementService.SimulationOrderResponse(
                101, "ORD1", "ORD1", 2, "TCS", "NSE", "BUY", "MARKET", 20, 20, 3000.0, null, 3000.05, 0.05, 1.0, 28.5, "FILLED", "ENTRY_BUY", "2026-10-11T09:35", "2026-10-11T09:35"
        );
        when(simulationService.getOrders("2026-10-11", null, null)).thenReturn(List.of(order));

        ResponseEntity<List<SimulationManagementService.SimulationOrderResponse>> response = controller.getOrders("2026-10-11", null, null);
        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());
    }

    @Test
    @DisplayName("GET /api/v1/simulation/performance returns metrics")
    void getPerformance_shouldReturnMetrics() {
        SimulationManagementService.SimulationPerformanceResponse.TodayPerformanceSummary todaySummary =
                new SimulationManagementService.SimulationPerformanceResponse.TodayPerformanceSummary(1, 500.0, 30.0, 470.0, 0);

        SimulationManagementService.SimulationPerformanceResponse perf = new SimulationManagementService.SimulationPerformanceResponse(
                "LIFETIME", 50000.0, 52100.0, 2100.0, 4.2, 5, 4, 1, 0, 80.0, 2300.0, 200.0, 3.5, 600.0, 100.0, 6.0, 1.8, -0.5, 2400L, todaySummary
        );
        when(simulationService.getPerformance(null, null)).thenReturn(perf);

        ResponseEntity<SimulationManagementService.SimulationPerformanceResponse> response = controller.getPerformance(null, null);
        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertEquals(80.0, response.getBody().winRatePercent());
    }

    @Test
    @DisplayName("GET /api/v1/simulation/strategies and POST mode toggle")
    void getStrategies_and_updateMode_shouldWork() {
        SimulationManagementService.StrategySimulationResponse s = new SimulationManagementService.StrategySimulationResponse(
                2, "FCHB", "First Candle High Breakout", "Desc", 2, "PAPER", 50000.0, 1000.0, 5, 3, true, 2, 0, false
        );
        when(simulationService.getStrategies()).thenReturn(List.of(s));

        ResponseEntity<List<SimulationManagementService.StrategySimulationResponse>> getResp = controller.getStrategies();
        assertEquals(200, getResp.getStatusCode().value());
        assertEquals(1, getResp.getBody().size());

        SimulationManagementService.StrategySimulationResponse updated = new SimulationManagementService.StrategySimulationResponse(
                2, "FCHB", "First Candle High Breakout", "Desc", 2, "LIVE", 50000.0, 1000.0, 5, 3, true, 2, 0, false
        );
        when(simulationService.updateStrategyMode(2, "LIVE")).thenReturn(updated);

        ResponseEntity<SimulationManagementService.StrategySimulationResponse> postResp = controller.updateStrategyMode(2, "LIVE");
        assertEquals(200, postResp.getStatusCode().value());
        assertEquals("LIVE", postResp.getBody().executionMode());
    }
}
