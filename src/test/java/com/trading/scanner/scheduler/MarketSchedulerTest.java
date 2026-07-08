package com.trading.scanner.scheduler;

import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.service.runtime.MarketCalendarService;
import com.trading.scanner.service.runtime.RuntimeAlertService;
import com.trading.scanner.service.runtime.RuntimeAutomationService;
import com.trading.scanner.service.runtime.RuntimeHousekeepingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.mockito.Mockito.*;

class MarketSchedulerTest {

    private RuntimeAutomationService runtimeAutomationService;
    private RuntimeHousekeepingService runtimeHousekeepingService;
    private RuntimeAlertService runtimeAlertService;
    private MarketCalendarService marketCalendarService;
    private TimeProvider timeProvider;
    private MarketScheduler marketScheduler;

    @BeforeEach
    void setUp() {
        runtimeAutomationService = mock(RuntimeAutomationService.class);
        runtimeHousekeepingService = mock(RuntimeHousekeepingService.class);
        runtimeAlertService = mock(RuntimeAlertService.class);
        marketCalendarService = mock(MarketCalendarService.class);
        timeProvider = mock(TimeProvider.class);

        marketScheduler = new MarketScheduler(
                runtimeAutomationService,
                runtimeHousekeepingService,
                runtimeAlertService,
                marketCalendarService,
                timeProvider);
    }

    @Test
    void scheduledBrokerWarmup_shouldDelegateToRuntimeAutomationService() {
        marketScheduler.scheduledBrokerWarmup();

        verify(runtimeAutomationService, times(1)).scheduledBrokerWarmup();
    }

    @Test
    void scheduledConnectAndSubscribe_shouldDelegateToRuntimeAutomationService() {
        marketScheduler.scheduledConnectAndSubscribe();

        verify(runtimeAutomationService, times(1)).scheduledConnectAndSubscribe();
    }

    @Test
    void scheduledRecoverLiveRuntime_shouldDelegateToRuntimeAutomationService() {
        marketScheduler.scheduledRecoverLiveRuntime();

        verify(runtimeAutomationService, times(1)).scheduledRecoverLiveRuntime();
    }

    @Test
    void scheduledConditionalFlushAndDisconnect_shouldDelegateToRuntimeAutomationService() {
        marketScheduler.scheduledConditionalFlushAndDisconnect();

        verify(runtimeAutomationService, times(1)).scheduledConditionalFlushAndDisconnect();
    }

    @Test
    void scheduledBrokerSessionClear_shouldDelegateToRuntimeAutomationService() {
        marketScheduler.scheduledBrokerSessionClear();

        verify(runtimeAutomationService, times(1)).scheduledBrokerSessionClear();
    }

    @Test
    void scheduledHousekeeping_shouldDelegateToRuntimeHousekeepingService() {
        marketScheduler.scheduledHousekeeping();

        verify(runtimeHousekeepingService, times(1)).scheduledHousekeeping();
    }

    @Test
    void scheduledAlertEvaluation_shouldDelegateToRuntimeAlertService() {
        marketScheduler.scheduledAlertEvaluation();

        verify(runtimeAlertService, times(1)).scheduledEvaluate();
    }

    @Test
    void scheduledCalendarRefresh_shouldDelegateToMarketCalendarService() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 25, 10, 0);

        when(timeProvider.nowDateTime()).thenReturn(now);
        when(marketCalendarService.refreshFromOfficialSourceIfDue(now))
                .thenReturn(new MarketCalendarService.ScheduledCalendarRefreshResult(
                        "REFRESHED",
                        10,
                        2,
                        1,
                        "Calendar refreshed from official NSE source"));

        marketScheduler.scheduledCalendarRefresh();

        verify(marketCalendarService, times(1)).refreshFromOfficialSourceIfDue(now);
    }
}