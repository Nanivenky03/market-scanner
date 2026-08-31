package com.trading.scanner.service.runtime;

import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.service.data.BackfillQueueService;
import com.trading.scanner.service.provider.angelone.LiveMarketSnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
@Slf4j
public class StartupRecoveryService {

    private final TimeProvider timeProvider;
    private final TradingCalendar tradingCalendar;
    private final BackfillQueueService backfillQueueService;
    private final LiveMarketSnapshotService liveMarketSnapshotService;

    @EventListener(ApplicationReadyEvent.class)
    public void recoverAtStartup() {
        try {
            RecoveryResult result = recoverNow();
            log.info("Startup recovery completed: {}", result);
        } catch (Exception ex) {
            log.error(
                    "Startup recovery failed; scheduled recovery remains active",
                    ex);
        }
    }

    public RecoveryResult recoverNow() {
        LocalDate today = timeProvider.today();

        int staleJobs = backfillQueueService.recoverStaleJobs();

        int detectedGaps = 0;

        if (tradingCalendar.isTradingDay(today)) {
            detectedGaps = liveMarketSnapshotService.checkForClosedMinuteGaps();
        }

        return new RecoveryResult(
                today,
                staleJobs,
                detectedGaps,
                "Startup recovery completed");
    }

    public record RecoveryResult(
            LocalDate tradingDate,
            int staleJobsRecovered,
            int gapsDetected,
            String message) {
    }
}
