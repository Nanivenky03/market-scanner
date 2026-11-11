package com.trading.scanner.service.runtime;

import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.config.TimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ExternalHeartbeatWatchdogServiceTest {

    private TradingCalendar tradingCalendar;
    private TimeProvider timeProvider;
    private HttpClient httpClient;

    private static final String TEST_URL = "https://hc-ping.com/e7295f50-bc13-46da-bfae-71d99b693777";

    @BeforeEach
    void setUp() {
        tradingCalendar = mock(TradingCalendar.class);
        timeProvider = mock(TimeProvider.class);
        httpClient = mock(HttpClient.class);
    }

    @Test
    void shouldPing_whenTradingDayAndInsideWindow_shouldReturnTrue() {
        when(tradingCalendar.isTradingDay(LocalDate.of(2026, 11, 11))).thenReturn(true);
        ExternalHeartbeatWatchdogService service = new ExternalHeartbeatWatchdogService(
                tradingCalendar, timeProvider, TEST_URL, httpClient);

        LocalDateTime marketTime = LocalDateTime.of(2026, 11, 11, 10, 30);
        assertTrue(service.shouldPing(marketTime));
    }

    @Test
    void shouldPing_whenOutsideMarketHours_shouldReturnFalse() {
        when(tradingCalendar.isTradingDay(LocalDate.of(2026, 11, 11))).thenReturn(true);
        ExternalHeartbeatWatchdogService service = new ExternalHeartbeatWatchdogService(
                tradingCalendar, timeProvider, TEST_URL, httpClient);

        LocalDateTime earlyMorning = LocalDateTime.of(2026, 11, 11, 8, 45);
        LocalDateTime evening = LocalDateTime.of(2026, 11, 11, 16, 15);

        assertFalse(service.shouldPing(earlyMorning));
        assertFalse(service.shouldPing(evening));
    }

    @Test
    void shouldPing_whenHolidayOrWeekend_shouldReturnFalse() {
        when(tradingCalendar.isTradingDay(LocalDate.of(2026, 11, 15))).thenReturn(false);
        ExternalHeartbeatWatchdogService service = new ExternalHeartbeatWatchdogService(
                tradingCalendar, timeProvider, TEST_URL, httpClient);

        LocalDateTime sundayNoon = LocalDateTime.of(2026, 11, 15, 12, 0);
        assertFalse(service.shouldPing(sundayNoon));
    }

    @Test
    void shouldPing_whenUrlBlank_shouldReturnFalse() {
        when(tradingCalendar.isTradingDay(LocalDate.of(2026, 11, 11))).thenReturn(true);
        ExternalHeartbeatWatchdogService service = new ExternalHeartbeatWatchdogService(
                tradingCalendar, timeProvider, "", httpClient);

        LocalDateTime marketTime = LocalDateTime.of(2026, 11, 11, 11, 0);
        assertFalse(service.shouldPing(marketTime));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendPing_whenHttpSuccess_shouldIncrementSuccessCounter() throws Exception {
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(mockResponse);

        ExternalHeartbeatWatchdogService service = new ExternalHeartbeatWatchdogService(
                tradingCalendar, timeProvider, TEST_URL, httpClient);

        LocalDateTime now = LocalDateTime.of(2026, 11, 11, 11, 30);
        boolean result = service.sendPing(now);

        assertTrue(result);
        assertEquals(1, service.status().successfulPings());
        assertEquals(0, service.status().failedPings());
        assertEquals(now, service.status().lastPingSuccessAt());
        assertNull(service.status().lastError());
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendPing_whenHttpFailsOrThrows_shouldIncrementFailedCounterWithoutCrashing() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("Connection reset"));

        ExternalHeartbeatWatchdogService service = new ExternalHeartbeatWatchdogService(
                tradingCalendar, timeProvider, TEST_URL, httpClient);

        LocalDateTime now = LocalDateTime.of(2026, 11, 11, 11, 30);
        boolean result = service.sendPing(now);

        assertFalse(result);
        assertEquals(0, service.status().successfulPings());
        assertEquals(1, service.status().failedPings());
        assertEquals("Connection reset", service.status().lastError());
    }
}
