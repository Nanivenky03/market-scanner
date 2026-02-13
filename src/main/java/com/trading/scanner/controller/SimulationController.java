package com.trading.scanner.controller;

import com.trading.scanner.model.ScanExecutionState.ExecutionMode;
import com.trading.scanner.model.SimulationState;
import com.trading.scanner.repository.SimulationStateRepository;
import com.trading.scanner.service.data.DataIngestionService;
import com.trading.scanner.service.scanner.ScannerEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
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
    
    /**
     * Get current simulation state
     */
    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        SimulationState state = simulationStateRepository.findById(1)
            .orElseThrow(() -> new RuntimeException("Simulation state not initialized"));
        
        Map<String, Object> status = new HashMap<>();
        status.put("baseDate", state.getBaseDate().toString());
        status.put("offsetDays", state.getOffsetDays());
        status.put("currentDate", state.getCurrentDate().toString());
        
        return status;
    }
    
    /**
     * Advance simulation by one day
     * 
     * This is the primary simulation control endpoint
     */
    @PostMapping("/advance-day")
    public Map<String, Object> advanceDay() {
        SimulationState state = simulationStateRepository.findById(1)
            .orElseThrow(() -> new RuntimeException("Simulation state not initialized"));
        
        LocalDate previousDate = state.getCurrentDate();
        
        // Advance simulation (forward-only)
        state.advanceDay();
        simulationStateRepository.save(state);
        
        LocalDate newDate = state.getCurrentDate();
        
        log.info("========================================");
        log.info("SIMULATION: Advanced from {} to {}", previousDate, newDate);
        log.info("========================================");
        
        // NOTE: After advancing, the ExchangeClock bean needs to be refreshed
        // For now, app restart is required after advance
        // Future enhancement: Make ExchangeClock reference SimulationState dynamically
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("previousDate", previousDate.toString());
        response.put("currentDate", newDate.toString());
        response.put("offsetDays", state.getOffsetDays());
        response.put("message", "Simulation advanced. Restart app to load new date.");
        
        return response;
    }
    
    /**
     * Execute full simulation step (advance + ingest + scan)
     * 
     * This requires app restart between calls due to fixed clock
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
            
            // Get current state
            SimulationState state = simulationStateRepository.findById(1)
                .orElseThrow(() -> new RuntimeException("Simulation state not initialized"));
            
            response.put("success", true);
            response.put("currentDate", state.getCurrentDate().toString());
            response.put("message", "Simulation step completed");
            
        } catch (Exception e) {
            log.error("Simulation step failed: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        
        return response;
    }
    
    /**
     * Reset simulation to base date
     * 
     * DANGEROUS: Only use for testing
     */
    @PostMapping("/reset")
    public Map<String, Object> reset() {
        SimulationState state = simulationStateRepository.findById(1)
            .orElseThrow(() -> new RuntimeException("Simulation state not initialized"));
        
        state.setOffsetDays(0);
        simulationStateRepository.save(state);
        
        log.warn("SIMULATION RESET to base date: {}", state.getBaseDate());
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("currentDate", state.getCurrentDate().toString());
        response.put("message", "Simulation reset to base date. Restart app.");
        
        return response;
    }
}
