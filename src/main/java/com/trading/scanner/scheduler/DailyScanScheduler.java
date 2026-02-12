package com.trading.scanner.scheduler;

import com.trading.scanner.service.data.DataIngestionService;
import com.trading.scanner.service.scanner.ScannerEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "scanner.enabled", havingValue = "true", matchIfMissing = true)
public class DailyScanScheduler {

    private final DataIngestionService dataIngestionService;
    private final ScannerEngine scannerEngine;

    /**
     * Daily scan at 7 PM IST
     * FIXED: Better error handling, no transaction wrapping
     */
    @Scheduled(cron = "${scanner.schedule.cron:0 0 19 * * *}")
    public void executeDailyScan() {
        log.info("========================================");
        log.info("Triggered: Daily Scanner Job");
        log.info("========================================");

        try {
            // Step 1: Ingest today's data (NOT transactional at this level)
            log.info("Step 1: Ingesting daily market data...");
            dataIngestionService.ingestDailyData();

            // Step 2: Run scanner
            log.info("Step 2: Executing scanner...");
            scannerEngine.executeDailyScan();

            log.info("Daily scanner job completed successfully");

        } catch (Exception e) {
            log.error("Daily scanner job failed: {}", e.getMessage(), e);
            // Don't re-throw - let next scheduled run happen
        }
    }

    /**
     * Manual trigger for testing
     */
    public void triggerManualScan() {
        log.info("Manual scan triggered");
        executeDailyScan();
    }
}