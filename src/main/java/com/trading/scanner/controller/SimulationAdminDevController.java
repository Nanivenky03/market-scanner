package com.trading.scanner.controller;

import com.trading.scanner.service.simulation.SimulationResetService;
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
@Tag(name = "Simulation Admin Dev", description = "Simulation-only admin endpoints for reset and reseed workflows")
public class SimulationAdminDevController {

    private final SimulationResetService simulationResetService;

    @Operation(summary = "Reset and reseed the simulation dataset", description = "Clears simulation/history tables, reloads the universe from the CSV seed file, rebuilds instrument master, and resets simulation state")
    @PostMapping("/reset-and-reseed")
    public ResponseEntity<SimulationResetService.ResetResult> resetAndReseed() {
        return ResponseEntity.ok(simulationResetService.resetAndReseed());
    }
}