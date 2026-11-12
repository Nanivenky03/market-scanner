package com.trading.scanner.controller;

import com.trading.scanner.execution.SimulationManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Diagnostic and Operational REST Controller for Mithron Paper Trading Simulation.
 * Exposes endpoints for capital setting, intraday vs total resets, open positions,
 * order ledger, performance metrics (lifetime and date-range), and strategy execution controls.
 */
@RestController
@RequestMapping("/api/v1/simulation")
@RequiredArgsConstructor
@Tag(name = "Simulation Engine", description = "Live Paper Trading Simulation & Diagnostic REST APIs")
public class SimulationDashboardController {

    private final SimulationManagementService simulationManagementService;

    @GetMapping("/capital")
    @Operation(summary = "Get current simulation capital status, starting balance, and compounded equity")
    public ResponseEntity<SimulationManagementService.SimulationCapitalResponse> getCapitalStatus() {
        return ResponseEntity.ok(simulationManagementService.getCapitalStatus());
    }

    @PostMapping("/capital")
    @Operation(summary = "Set baseline simulation capital (persisted across restarts)")
    public ResponseEntity<SimulationManagementService.SimulationCapitalResponse> setCapital(
            @Parameter(description = "Capital amount in INR (e.g. 50000)")
            @RequestParam double amount) {
        return ResponseEntity.ok(simulationManagementService.setInitialCapital(amount));
    }

    @PostMapping("/reset")
    @Operation(summary = "Reset simulation state (scope=TODAY restores morning opening balance; scope=TOTAL wipes all history)")
    public ResponseEntity<SimulationManagementService.SimulationResetResponse> resetSimulation(
            @Parameter(description = "Reset scope: TODAY or TOTAL (defaults to TODAY)")
            @RequestParam(defaultValue = "TODAY") String scope) {
        return ResponseEntity.ok(simulationManagementService.resetSimulation(scope));
    }

    @GetMapping("/positions/open")
    @Operation(summary = "Get currently active open positions with live LTP, unrealized PnL, MFE and MAE")
    public ResponseEntity<List<SimulationManagementService.OpenPositionResponse>> getOpenPositions() {
        return ResponseEntity.ok(simulationManagementService.getOpenPositionsToday());
    }

    @GetMapping("/trades/today")
    @Operation(summary = "Get closed trades executed today with entry, exit, quantity, gross and net PnL")
    public ResponseEntity<List<SimulationManagementService.ClosedTradeResponse>> getTodayClosedTrades() {
        return ResponseEntity.ok(simulationManagementService.getTodayClosedTrades());
    }

    @GetMapping("/orders")
    @Operation(summary = "Get simulated orders ledger for a date or date range")
    public ResponseEntity<List<SimulationManagementService.SimulationOrderResponse>> getOrders(
            @Parameter(description = "Single date in YYYY-MM-DD format (defaults to today)")
            @RequestParam(required = false) String date,
            @Parameter(description = "Start date in YYYY-MM-DD format for date range")
            @RequestParam(required = false) String fromDate,
            @Parameter(description = "End date in YYYY-MM-DD format for date range")
            @RequestParam(required = false) String toDate) {
        return ResponseEntity.ok(simulationManagementService.getOrders(date, fromDate, toDate));
    }

    @GetMapping("/performance")
    @Operation(summary = "Get simulation quantitative performance metrics (Lifetime or specific date range)")
    public ResponseEntity<SimulationManagementService.SimulationPerformanceResponse> getPerformance(
            @Parameter(description = "Start date in YYYY-MM-DD format (optional, omit for Lifetime)")
            @RequestParam(required = false) String fromDate,
            @Parameter(description = "End date in YYYY-MM-DD format (optional, omit for Lifetime)")
            @RequestParam(required = false) String toDate) {
        return ResponseEntity.ok(simulationManagementService.getPerformance(fromDate, toDate));
    }

    @GetMapping("/strategies")
    @Operation(summary = "Get all strategies in simulation, their execution modes, and intraday trade counts")
    public ResponseEntity<List<SimulationManagementService.StrategySimulationResponse>> getStrategies() {
        return ResponseEntity.ok(simulationManagementService.getStrategies());
    }

    @PostMapping("/strategies/{strategyId}/mode")
    @Operation(summary = "Dynamically toggle execution mode for a strategy (PAPER, LIVE, DISABLED)")
    public ResponseEntity<SimulationManagementService.StrategySimulationResponse> updateStrategyMode(
            @PathVariable Integer strategyId,
            @Parameter(description = "Target mode: PAPER, LIVE, or DISABLED")
            @RequestParam String mode) {
        return ResponseEntity.ok(simulationManagementService.updateStrategyMode(strategyId, mode));
    }
}
