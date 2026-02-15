package com.trading.scanner.controller;

import com.trading.scanner.config.ExchangeClock;
import com.trading.scanner.model.SimulationState;
import com.trading.scanner.repository.SimulationStateRepository;
import com.trading.scanner.service.simulation.SimulationBatchResult;
import com.trading.scanner.service.simulation.SimulationCycleService;
import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
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
        status.put("isCycling", state.isCycling());
        status.put("currentDate", exchangeClock.today().toString());
        
        return status;
    }

    /**
     * Executes a batch of N simulation cycles (advance day, ingest, scan).
     * @param days The number of trading days to simulate.
     */
    @PostMapping("/advance")
    public ResponseEntity<SimulationBatchResult> advanceCycles(@RequestParam(defaultValue = "1") int days) {
        if (days <= 0) {
            throw new IllegalArgumentException("Number of days must be positive.");
        }
        SimulationBatchResult result = simulationCycleService.executeCycles(days);
        return ResponseEntity.ok(result);
    }
    
    /**
     * @deprecated Replaced by POST /simulation/advance for atomic cycle execution.
     */
    @Deprecated(since = "1.6", forRemoval = true)
    @PostMapping("/advance-day")
    public ResponseEntity<Map<String, Object>> advanceDay() {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", "This endpoint is deprecated and will be removed. Use POST /simulation/advance?days=1 instead.");
        return new ResponseEntity<>(response, HttpStatus.GONE);
    }
    
    /**
     * Resets the simulation to the base date by clearing the trading offset.
     * This will fail if a cycle is in progress.
     */
    @PostMapping("/reset")
    public Map<String, Object> reset() {
        SimulationState state = simulationStateRepository.findById(1)
            .orElseThrow(() -> new RuntimeException("Simulation state not initialized"));

        if (state.isCycling()) {
            throw new IllegalStateException("Cannot reset while a multi-day simulation cycle is in progress.");
        }
        
        state.setTradingOffset(0);
        // Also clear the deprecated field for consistency
        state.setOffsetDays(0);
        simulationStateRepository.save(state);

        // Invalidate cache after reset
        exchangeClock.invalidateSimulationCache();
        
        log.warn("SIMULATION RESET to base date: {}", state.getBaseDate());
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("currentDate", exchangeClock.today().toString());
        response.put("message", "Simulation reset to base date.");
        
        return response;
    }
}
