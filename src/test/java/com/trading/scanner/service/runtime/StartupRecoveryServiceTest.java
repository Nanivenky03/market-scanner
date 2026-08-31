package com.trading.scanner.service.runtime;

import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.service.data.BackfillQueueService;
import com.trading.scanner.service.provider.angelone.LiveMarketSnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class StartupRecoveryServiceTest {

    private TimeProvider timeProvider;
    private TradingCalendar tradingCalendar;
    private BackfillQueueService backfillQueueService;
    private LiveMarketSnapshotService liveMarketSnapshotService;
    private StartupRecoveryService service;

    @BeforeEach
    void setUp() {
        timeProvider = mock(TimeProvider.class);
        tradingCalendar = mock(TradingCalendar.class);
        backfillQueueService = mock(BackfillQueueService.class);
        liveMarketSnapshotService = mock(LiveMarketSnapshotService.class);

        service = new StartupRecoveryService(
                timeProvider,
                tradingCalendar,
                backfillQueueService,
                liveMarketSnapshotService);
    }

    @Test
    void recoverNow_shouldRecoverStaleJobsAndScanTradingDay() {
        LocalDate today = LocalDate.of(2026, 8, 24);

        when(timeProvider.today()).thenReturn(today);
        when(tradingCalendar.isTradingDay(today)).thenReturn(true);
        when(backfillQueueService.recoverStaleJobs())
                .thenReturn(2);
        when(liveMarketSnapshotService.checkForClosedMinuteGaps())
                .thenReturn(3);

        StartupRecoveryService.RecoveryResult result = service.recoverNow();

        assertEquals(today, result.tradingDate());
        assertEquals(2, result.staleJobsRecovered());
        assertEquals(3, result.gapsDetected());

        verify(backfillQueueService).recoverStaleJobs();
        verify(liveMarketSnapshotService)
                .checkForClosedMinuteGaps();
    }

    @Test
    void recoverNow_shouldSkipLiveGapScanOnNonTradingDay() {
        LocalDate today = LocalDate.of(2026, 8, 23);

        when(timeProvider.today()).thenReturn(today);
        when(tradingCalendar.isTradingDay(today)).thenReturn(false);
        when(backfillQueueService.recoverStaleJobs())
                .thenReturn(1);

        StartupRecoveryService.RecoveryResult result = service.recoverNow();

        assertEquals(today, result.tradingDate());
        assertEquals(1, result.staleJobsRecovered());
        assertEquals(0, result.gapsDetected());

        verify(backfillQueueService).recoverStaleJobs();
        verify(liveMarketSnapshotService, never())
                .checkForClosedMinuteGaps();
    }

    @Test
    void recoverAtStartup_shouldNotPropagateRecoveryFailure() {
        when(timeProvider.today())
                .thenThrow(new IllegalStateException("database unavailable"));

        service.recoverAtStartup();

        verify(timeProvider).today();
    }
}
