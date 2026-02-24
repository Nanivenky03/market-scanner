package com.trading.scanner.scheduler;

import com.trading.scanner.config.ExchangeConfiguration;
import com.trading.scanner.model.ScanExecutionState.ExecutionMode;
import com.trading.scanner.service.data.DataIngestionService;
import com.trading.scanner.service.scanner.ScannerEngine;
import com.trading.scanner.service.state.ExecutionStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Daily Scanner Scheduler
 * 
 * ONLY runs in production profile
 * Disabled in simulation mode (manual control via /simulation/advance)
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Profile({"production", "default"})
@ConditionalOnProperty(name = "scanner.enabled", havingValue = "true", matchIfMissing = true)
public class DailyScanScheduler {
    
    private final DataIngestionService dataIngestionService;
    private final ScannerEngine scannerEngine;
    private final ExecutionStateService executionStateService;
    private final ExchangeConfiguration config;
    
    @Scheduled(cron = "${scanner.schedule.cron:0 0 19 * * *}", 
               zone = "${scanner.schedule.zone:Asia/Kolkata}")
    public void executeDailyScan() {
        LocalDate today = config.getTodayInExchangeZone();
        
        log.info("========================================");
        log.info("Daily Scanner Job Triggered (SCHEDULED)");
        log.info("Trading Date: {}", today);
        log.info("========================================");
        
        try {
            if (executionStateService.canIngestToday()) {
                log.info("Starting daily data ingestion...");
                dataIngestionService.ingestDailyData(ExecutionMode.SCHEDULED);
            } else {
                log.info("Ingestion already completed for {}", today);
            }
            
            if (executionStateService.canScanToday()) {
                log.info("Starting scanner execution...");
                scannerEngine.executeDailyScan();
            } else {
                log.info("Scan not needed or already completed for {}", today);
            }
            
            log.info("========================================");
            log.info("Daily Scanner Job Completed");
            log.info("========================================");
            
        } catch (Exception e) {
            log.error("Daily scanner job failed: {}", e.getMessage(), e);
        }
    }
}
