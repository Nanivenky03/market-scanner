package com.trading.scanner.controller;

import com.trading.scanner.config.ExchangeClock;
import com.trading.scanner.model.SimulationState;
import com.trading.scanner.repository.SimulationStateRepository;
import com.trading.scanner.service.simulation.SimulationBatchResult;
import com.trading.scanner.service.simulation.SimulationCycleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Simulation Controller
 * 
 * ONLY active in simulation profile
 * 
 * Provides manual control over simulation timeline
 */
@Slf4j
@RestController
@RequestMapping("/simulation")
@Profile("simulation")
@RequiredArgsConstructor
public class SimulationController {
    
    private final SimulationStateRepository simulationStateRepository;
    private final SimulationCycleService simulationCycleService;
    private final ExchangeClock exchangeClock;
    
    /**
     * Get current simulation state
     */
    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        SimulationState state = simulationStateRepository.findById(1)
            .orElseThrow(() -> new RuntimeException("Simulation state not initialized"));
        
        Map<String, Object> status = new HashMap<>();
        status.put("baseDate", state.getBaseDate().toString());
        status.put("tradingOffset", state.getTradingOffset());
        status.put("currentDate", exchangeClock.today().toString());
        
        return status;
    }

    /**
     * Executes a batch of N simulation cycles (advance day, ingest, scan).
     * @param days The number of trading days to simulate.
     */
    @PostMapping("/advance")
    public ResponseEntity<SimulationBatchResult> advanceCycles(@RequestParam(defaultValue = "1") int days) {
        SimulationBatchResult result = simulationCycleService.advanceSimulation(days);
        return ResponseEntity.ok(result);
    }
    
    /**
     * Resets the simulation to the base date by clearing the trading offset.
     * This will fail if a cycle is in progress.
     */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> reset() {
        simulationCycleService.resetSimulation();
        exchangeClock.invalidateSimulationCache();

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("currentDate", exchangeClock.today().toString());
        response.put("message", "Simulation reset to base date.");
        
        return ResponseEntity.ok(response);
    }
}
