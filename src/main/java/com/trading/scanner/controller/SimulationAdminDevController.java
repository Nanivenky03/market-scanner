package com.trading.scanner.controller;

import com.trading.scanner.model.LiveSimulationSignal;
import com.trading.scanner.service.simulation.LiveSimulationSignalService;
import com.trading.scanner.service.simulation.SimulationResetService;
import com.trading.scanner.service.simulation.SimulationRuntimeAutomationService;
import com.trading.scanner.service.simulation.SimulationRuntimePreflightService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/dev/admin/simulation")
@Profile("simulation")
@RequiredArgsConstructor
@Tag(name = "Simulation Admin Dev", description = "Simulation-only admin endpoints for reset, reseed, runtime automation, readiness checks, and live signal inspection")
public class SimulationAdminDevController {

    private final SimulationResetService simulationResetService;
    private final SimulationRuntimeAutomationService simulationRuntimeAutomationService;
    private final LiveSimulationSignalService liveSimulationSignalService;
    private final SimulationRuntimePreflightService simulationRuntimePreflightService;

    @Operation(summary = "Reset and reseed the simulation dataset", description = "Clears simulation/history tables, reloads the universe from the CSV seed file, rebuilds instrument master, and resets simulation state")
    @PostMapping("/reset-and-reseed")
    public ResponseEntity<SimulationResetService.ResetResult> resetAndReseed() {
        return ResponseEntity.ok(simulationResetService.resetAndReseed());
    }

    @Operation(summary = "Manually connect websocket and subscribe active universe", description = "Useful for cloud or local simulation testing without waiting for scheduled jobs")
    @PostMapping("/runtime/connect-and-subscribe")
    public ResponseEntity<SimulationRuntimeAutomationService.RuntimeActionResult> connectAndSubscribe() {
        return ResponseEntity.ok(simulationRuntimeAutomationService.connectAndSubscribe());
    }

    @Operation(summary = "Manually flush live candles and disconnect websocket", description = "Useful for end-of-session testing without waiting for scheduled jobs")
    @PostMapping("/runtime/flush-and-disconnect")
    public ResponseEntity<SimulationRuntimeAutomationService.RuntimeActionResult> flushAndDisconnect() {
        return ResponseEntity.ok(simulationRuntimeAutomationService.flushAndDisconnect());
    }

    @Operation(summary = "Get live runtime automation status", description = "Returns websocket counters, parser counters, open live candle count, and automation config state")
    @GetMapping("/runtime/status")
    public ResponseEntity<SimulationRuntimeAutomationService.RuntimeStatus> runtimeStatus() {
        return ResponseEntity.ok(simulationRuntimeAutomationService.runtimeStatus());
    }

    @Operation(summary = "Get simulation preflight/readiness status", description = "Returns whether the environment is ready for historical simulation, Monday live validation, and cloud simulation")
    @GetMapping("/runtime/preflight")
    public ResponseEntity<SimulationRuntimePreflightService.PreflightStatus> runtimePreflight() {
        return ResponseEntity.ok(simulationRuntimePreflightService.status());
    }

    @Operation(summary = "List recent live simulation signals", description = "Returns the most recent live signals generated from finalized 5m/15m candles")
    @GetMapping("/runtime/live-signals")
    public ResponseEntity<List<LiveSimulationSignal>> recentLiveSignals(
            @Parameter(description = "Maximum number of recent signals to return", example = "50") @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(liveSimulationSignalService.recentSignals(limit));
    }

    @Operation(summary = "Clear live simulation signals", description = "Removes all persisted live simulation signals")
    @PostMapping("/runtime/live-signals/clear")
    public ResponseEntity<LiveSimulationSignalService.ClearSignalsResult> clearLiveSignals() {
        return ResponseEntity.ok(liveSimulationSignalService.clearSignals());
    }
}