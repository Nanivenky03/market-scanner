package com.trading.scanner.controller;

import com.trading.scanner.model.ScanResult;
import com.trading.scanner.model.ScannerRun;
import com.trading.scanner.repository.ScanResultRepository;
import com.trading.scanner.repository.ScannerRunRepository;
import com.trading.scanner.scheduler.DailyScanScheduler;
import com.trading.scanner.service.data.DataIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Controller
@RequiredArgsConstructor
public class DashboardController {
    
    private final ScanResultRepository scanResultRepository;
    private final ScannerRunRepository scannerRunRepository;
    private final DailyScanScheduler scheduler;
    private final DataIngestionService dataIngestionService;
    
    /**
     * Main dashboard page
     */
    @GetMapping("/")
    public String dashboard(Model model) {
        LocalDate today = LocalDate.now();
        
        // Get today's scan results
        List<ScanResult> todaysResults = scanResultRepository
            .findByScanDateOrderByConfidenceDesc(today);
        
        // Get latest scanner run info
        ScannerRun latestRun = scannerRunRepository
            .findTopByOrderByRunDateDesc()
            .orElse(null);
        
        // Group results by classification and confidence
        Map<String, Long> highConfidence = new HashMap<>();
        Map<String, Long> moderateConfidence = new HashMap<>();
        
        for (ScanResult result : todaysResults) {
            String key = result.getClassification();
            
            if ("HIGH".equals(result.getConfidence())) {
                highConfidence.put(key, highConfidence.getOrDefault(key, 0L) + 1);
            } else {
                moderateConfidence.put(key, moderateConfidence.getOrDefault(key, 0L) + 1);
            }
        }
        
        model.addAttribute("scanDate", today);
        model.addAttribute("results", todaysResults);
        model.addAttribute("totalSignals", todaysResults.size());
        model.addAttribute("highConfidence", highConfidence);
        model.addAttribute("moderateConfidence", moderateConfidence);
        model.addAttribute("latestRun", latestRun);
        
        return "dashboard";
    }
    
    /**
     * Trigger manual scan
     */
    @PostMapping("/scan/trigger")
    @ResponseBody
    public Map<String, String> triggerScan() {
        log.info("Manual scan triggered from dashboard");
        
        try {
            // Run in separate thread to avoid timeout
            new Thread(() -> scheduler.triggerManualScan()).start();
            
            return Map.of(
                "status", "success",
                "message", "Scanner job started. Check logs for progress."
            );
        } catch (Exception e) {
            return Map.of(
                "status", "error",
                "message", "Failed to trigger scan: " + e.getMessage()
            );
        }
    }
    
    /**
     * Load historical data (one-time setup)
     */
    @PostMapping("/data/historical")
    @ResponseBody
    public Map<String, String> loadHistoricalData(
            @RequestParam(defaultValue = "5") int years) {
        
        log.info("Historical data load triggered: {} years", years);
        
        try {
            // Run in separate thread
            new Thread(() -> dataIngestionService.ingestHistoricalDataForUniverse(years)).start();
            
            return Map.of(
                "status", "success",
                "message", "Historical data ingestion started for " + years + " years. " +
                          "This will take 15-30 minutes. Check logs for progress."
            );
        } catch (Exception e) {
            return Map.of(
                "status", "error",
                "message", "Failed to start historical data load: " + e.getMessage()
            );
        }
    }
    
    /**
     * API endpoint to get today's results as JSON
     */
    @GetMapping("/api/results/today")
    @ResponseBody
    public List<ScanResult> getTodaysResults() {
        return scanResultRepository.findByScanDateOrderByConfidenceDesc(LocalDate.now());
    }
    
    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    @ResponseBody
    public Map<String, Object> health() {
        ScannerRun latestRun = scannerRunRepository
            .findTopByOrderByRunDateDesc()
            .orElse(null);
        
        return Map.of(
            "status", "UP",
            "lastScanDate", latestRun != null ? latestRun.getRunDate() : "Never",
            "lastScanStatus", latestRun != null ? latestRun.getStatus() : "N/A"
        );
    }
}
