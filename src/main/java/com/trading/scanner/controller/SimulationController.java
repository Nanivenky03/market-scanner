package com.trading.scanner.controller;

import com.trading.scanner.config.ExchangeClock;
import com.trading.scanner.model.ScanExecutionState.ExecutionMode;
import com.trading.scanner.model.SimulationState;
import com.trading.scanner.repository.SimulationStateRepository;
import com.trading.scanner.service.data.DataIngestionService;
import com.trading.scanner.service.scanner.ScannerEngine;
import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
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
    private final DataIngestionService dataIngestionService;
    private final ScannerEngine scannerEngine;
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
     * Advances the simulation by one trading day with optimistic lock handling.
     * This is the primary simulation control endpoint.
     */
    @PostMapping("/advance-day")
    public ResponseEntity<Map<String, Object>> advanceDay() {
        int maxAttempts = 3;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                // Get the date BEFORE advancing. This still uses the cache.
                LocalDate previousDate = exchangeClock.today();

                // Must load a fresh state inside the loop for retry to work
                SimulationState state = simulationStateRepository.findById(1)
                    .orElseThrow(() -> new RuntimeException("Simulation state not initialized"));

                // Advance simulation by one TRADING day
                state.advanceDay();
                simulationStateRepository.save(state);

                // IMPORTANT: Invalidate the clock cache after a successful save
                exchangeClock.invalidateSimulationCache();
                LocalDate newDate = exchangeClock.today(); // This call now recomputes the date

                log.info("========================================");
                log.info("SIMULATION: Advanced from {} to {}", previousDate, newDate);
                log.info("========================================");

                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("previousDate", previousDate.toString());
                response.put("currentDate", newDate.toString());
                response.put("tradingOffset", state.getTradingOffset());
                response.put("message", "Simulation advanced by one trading day.");
                
                return ResponseEntity.ok(response);

            } catch (OptimisticLockException e) {
                log.warn("Optimistic lock exception on attempt {}/{}. Retrying...", attempt + 1, maxAttempts);
                if (attempt + 1 >= maxAttempts) {
                    log.error("Failed to advance simulation after {} attempts due to concurrent modifications.", maxAttempts);
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("success", false);
                    errorResponse.put("message", "Simulation state modified concurrently. Please retry the request.");
                    return new ResponseEntity<>(errorResponse, HttpStatus.CONFLICT);
                }
            }
        }
        // Should not be reached, but is required for compilation
        return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
    }
    
    /**
     * Execute full simulation step (ingest + scan) for the current day.
     */
    @PostMapping("/step")
    public Map<String, Object> executeStep() {
        Map<String, Object> response = new HashMap<>();
        
        try {
            // Ingest data for current simulation date
            log.info("SIMULATION STEP: Starting ingestion...");
            dataIngestionService.ingestDailyData(ExecutionMode.MANUAL);
            
            // Execute scanner
            log.info("SIMULATION STEP: Starting scanner...");
            scannerEngine.executeDailyScan();

            response.put("success", true);
            response.put("currentDate", exchangeClock.today().toString());
            response.put("message", "Simulation step completed for " + exchangeClock.today());
            
        } catch (Exception e) {
            log.error("Simulation step failed: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        
        return response;
    }
    
    /**
     * Resets the simulation to the base date by clearing the trading offset.
     */
    @PostMapping("/reset")
    public Map<String, Object> reset() {
        SimulationState state = simulationStateRepository.findById(1)
            .orElseThrow(() -> new RuntimeException("Simulation state not initialized"));
        
        state.setTradingOffset(0);
        simulationStateRepository.save(state);
        
        log.warn("SIMULATION RESET to base date: {}", state.getBaseDate());
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("currentDate", exchangeClock.today().toString());
        response.put("message", "Simulation reset to base date.");
        
        return response;
    }
}
