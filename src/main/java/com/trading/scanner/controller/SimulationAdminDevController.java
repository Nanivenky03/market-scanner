package com.trading.scanner.controller;

import com.trading.scanner.service.simulation.SimulationResetService;
import com.trading.scanner.service.simulation.SimulationRuntimeAutomationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/dev/admin/simulation")
@Profile("simulation")
@RequiredArgsConstructor
@Tag(name = "Simulation Admin Dev", description = "Simulation-only admin endpoints for reset, reseed, and runtime automation workflows")
public class SimulationAdminDevController {

    private final SimulationResetService simulationResetService;
    private final SimulationRuntimeAutomationService simulationRuntimeAutomationService;

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
}