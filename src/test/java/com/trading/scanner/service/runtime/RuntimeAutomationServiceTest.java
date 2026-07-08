package com.trading.scanner.service.runtime;

import com.trading.scanner.config.RuntimeAutomationProperties;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.service.data.LiveMarketCandleService;
import com.trading.scanner.service.provider.angelone.AngelOneSessionService;
import com.trading.scanner.service.provider.angelone.AngelOneWebSocketService;
import com.trading.scanner.service.provider.angelone.UniverseWebSocketSubscriptionService;
import com.trading.scanner.service.provider.angelone.WebSocketFrameCaptureService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RuntimeAutomationServiceTest {

    @Mock
    private AngelOneSessionService angelOneSessionService;

    @Mock
    private AngelOneWebSocketService angelOneWebSocketService;

    @Mock
    private UniverseWebSocketSubscriptionService universeWebSocketSubscriptionService;

    @Mock
    private LiveMarketCandleService liveMarketCandleService;

    @Mock
    private WebSocketFrameCaptureService webSocketFrameCaptureService;

    @Mock
    private RuntimeSettingService runtimeSettingService;

    @Mock
    private RuntimeReadinessService runtimeReadinessService;

    @Mock
    private RuntimeBootstrapService runtimeBootstrapService;

    @Mock
    private TimeProvider timeProvider;

    private RuntimeAutomationProperties runtimeAutomationProperties;
    private RuntimeAutomationService runtimeAutomationService;

    @BeforeEach
    void setUp() {
        runtimeAutomationProperties = new RuntimeAutomationProperties();
        runtimeAutomationProperties.getLive().setAutoRun(true);
        runtimeAutomationProperties.getLive().setAutoRecover(true);
        runtimeAutomationProperties.getLive().setReconnectBackoffSeconds(30);
        runtimeAutomationProperties.getLive().setRecoveryIntervalMs(60000L);

        runtimeAutomationService = spy(new RuntimeAutomationService(
                angelOneSessionService,
                angelOneWebSocketService,
                universeWebSocketSubscriptionService,
                liveMarketCandleService,
                webSocketFrameCaptureService,
                runtimeSettingService,
                runtimeAutomationProperties,
                runtimeReadinessService,
                runtimeBootstrapService,
                timeProvider));
    }

    @Test
    void recoverLiveRuntimeIfNeeded_shouldReconnectWhenWebsocketDisconnectedDuringTradingWindow() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 1, 10, 0);

        when(timeProvider.nowDateTime()).thenReturn(now);
        when(runtimeReadinessService.isTradingDay(now.toLocalDate())).thenReturn(true);
        when(runtimeSettingService.websocketConnectTime()).thenReturn(LocalTime.of(9, 0));
        when(runtimeSettingService.websocketDisconnectTime()).thenReturn(LocalTime.of(15, 40));
        when(angelOneWebSocketService.status()).thenReturn(disconnectedStatus(now));
        when(angelOneSessionService.sessionStatus()).thenReturn(sessionStatus(now, true));

        doReturn(new RuntimeAutomationService.RuntimeActionResult(
                "CONNECT_AND_SUBSCRIBE",
                true,
                25,
                0,
                "Connected websocket and subscribed active universe")).when(runtimeAutomationService)
                .connectAndSubscribe();

        RuntimeAutomationService.RuntimeActionResult result = runtimeAutomationService.recoverLiveRuntimeIfNeeded();

        assertEquals("LIVE_RUNTIME_RECOVERY", result.action());
        assertEquals(true, result.websocketConnected());
        verify(runtimeAutomationService, times(1)).connectAndSubscribe();
        verify(runtimeAutomationService, never()).warmUpBrokerSession();
    }

    @Test
    void recoverLiveRuntimeIfNeeded_shouldWarmBrokerSessionBeforeReconnectWhenSessionMissing() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 1, 10, 5);

        when(timeProvider.nowDateTime()).thenReturn(now);
        when(runtimeReadinessService.isTradingDay(now.toLocalDate())).thenReturn(true);
        when(runtimeSettingService.websocketConnectTime()).thenReturn(LocalTime.of(9, 0));
        when(runtimeSettingService.websocketDisconnectTime()).thenReturn(LocalTime.of(15, 40));
        when(angelOneWebSocketService.status()).thenReturn(disconnectedStatus(now));
        when(angelOneSessionService.sessionStatus()).thenReturn(sessionStatus(now, false));

        doReturn(new RuntimeAutomationService.RuntimeActionResult(
                "BROKER_SESSION_WARMUP",
                false,
                0,
                0,
                "Angel One session warmed up")).when(runtimeAutomationService).warmUpBrokerSession();

        doReturn(new RuntimeAutomationService.RuntimeActionResult(
                "CONNECT_AND_SUBSCRIBE",
                true,
                25,
                0,
                "Connected websocket and subscribed active universe")).when(runtimeAutomationService)
                .connectAndSubscribe();

        RuntimeAutomationService.RuntimeActionResult result = runtimeAutomationService.recoverLiveRuntimeIfNeeded();

        assertEquals("LIVE_RUNTIME_RECOVERY", result.action());
        verify(runtimeAutomationService, times(1)).warmUpBrokerSession();
        verify(runtimeAutomationService, times(1)).connectAndSubscribe();
    }

    @Test
    void recoverLiveRuntimeIfNeeded_shouldDisconnectAndReconnectWhenWebsocketIsConnectedButStale() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 1, 10, 10);

        when(timeProvider.nowDateTime()).thenReturn(now);
        when(runtimeReadinessService.isTradingDay(now.toLocalDate())).thenReturn(true);
        when(runtimeSettingService.websocketConnectTime()).thenReturn(LocalTime.of(9, 0));
        when(runtimeSettingService.websocketDisconnectTime()).thenReturn(LocalTime.of(15, 40));
        when(runtimeSettingService.staleTicksMinutes()).thenReturn(10);
        when(angelOneWebSocketService.status()).thenReturn(staleConnectedStatus(now));
        when(angelOneSessionService.sessionStatus()).thenReturn(sessionStatus(now, true));

        doReturn(new RuntimeAutomationService.RuntimeActionResult(
                "CONNECT_AND_SUBSCRIBE",
                true,
                25,
                0,
                "Connected websocket and subscribed active universe")).when(runtimeAutomationService)
                .connectAndSubscribe();

        RuntimeAutomationService.RuntimeActionResult result = runtimeAutomationService.recoverLiveRuntimeIfNeeded();

        assertEquals("LIVE_RUNTIME_RECOVERY", result.action());
        verify(angelOneWebSocketService, times(1)).disconnect();
        verify(runtimeAutomationService, times(1)).connectAndSubscribe();
    }

    @Test
    void recoverLiveRuntimeIfNeeded_shouldSkipWhenNotTradingDay() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 4, 10, 15);

        when(timeProvider.nowDateTime()).thenReturn(now);
        when(runtimeReadinessService.isTradingDay(now.toLocalDate())).thenReturn(false);
        when(liveMarketCandleService.openCandles()).thenReturn(List.of());

        RuntimeAutomationService.RuntimeActionResult result = runtimeAutomationService.recoverLiveRuntimeIfNeeded();

        assertEquals("NO_ACTION", result.action());
        verify(runtimeAutomationService, never()).warmUpBrokerSession();
        verify(runtimeAutomationService, never()).connectAndSubscribe();
        verify(angelOneWebSocketService, never()).disconnect();
    }

    @Test
    void recoverLiveRuntimeIfNeeded_shouldSkipWhenWebsocketIsHealthy() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 1, 10, 15);

        when(timeProvider.nowDateTime()).thenReturn(now);
        when(runtimeReadinessService.isTradingDay(now.toLocalDate())).thenReturn(true);
        when(runtimeSettingService.websocketConnectTime()).thenReturn(LocalTime.of(9, 0));
        when(runtimeSettingService.websocketDisconnectTime()).thenReturn(LocalTime.of(15, 40));
        when(runtimeSettingService.staleTicksMinutes()).thenReturn(10);
        when(angelOneWebSocketService.status()).thenReturn(healthyConnectedStatus(now));
        when(liveMarketCandleService.openCandles()).thenReturn(List.of());

        RuntimeAutomationService.RuntimeActionResult result = runtimeAutomationService.recoverLiveRuntimeIfNeeded();

        assertEquals("NO_ACTION", result.action());
        verify(runtimeAutomationService, never()).warmUpBrokerSession();
        verify(runtimeAutomationService, never()).connectAndSubscribe();
        verify(angelOneWebSocketService, never()).disconnect();
    }

    @Test
    void recoverLiveRuntimeIfNeeded_shouldRespectReconnectBackoff() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 1, 10, 20);

        when(timeProvider.nowDateTime()).thenReturn(now);
        when(runtimeReadinessService.isTradingDay(now.toLocalDate())).thenReturn(true);
        when(runtimeSettingService.websocketConnectTime()).thenReturn(LocalTime.of(9, 0));
        when(runtimeSettingService.websocketDisconnectTime()).thenReturn(LocalTime.of(15, 40));
        when(angelOneWebSocketService.status()).thenReturn(disconnectedStatus(now));
        when(angelOneSessionService.sessionStatus()).thenReturn(sessionStatus(now, true));

        doReturn(new RuntimeAutomationService.RuntimeActionResult(
                "CONNECT_AND_SUBSCRIBE",
                true,
                25,
                0,
                "Connected websocket and subscribed active universe")).when(runtimeAutomationService)
                .connectAndSubscribe();

        RuntimeAutomationService.RuntimeActionResult first = runtimeAutomationService.recoverLiveRuntimeIfNeeded();
        RuntimeAutomationService.RuntimeActionResult second = runtimeAutomationService.recoverLiveRuntimeIfNeeded();

        assertEquals("LIVE_RUNTIME_RECOVERY", first.action());
        assertEquals("NO_ACTION", second.action());
        verify(runtimeAutomationService, times(1)).connectAndSubscribe();
    }

    @Test
    void reconcileStartupState_shouldSkipBrokerAndWebsocketOnNonTradingDay() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 4, 8, 0);

        when(timeProvider.nowDateTime()).thenReturn(now);
        when(runtimeReadinessService.isTradingDay(now.toLocalDate())).thenReturn(false);
        when(runtimeSettingService.getString("runtime.lifecycle.state", "STOPPED")).thenReturn("STOPPED");
        when(runtimeSettingService.getString("runtime.last.shutdown.at", "")).thenReturn("");
        when(runtimeSettingService.getString("runtime.last.shutdown.graceful", "true")).thenReturn("true");
        when(liveMarketCandleService.openCandles()).thenReturn(List.of());
        when(angelOneWebSocketService.status()).thenReturn(disconnectedStatus(now));

        RuntimeAutomationService.RuntimeActionResult result = runtimeAutomationService.reconcileStartupState();

        assertEquals("STARTUP_RECONCILE", result.action());
        verify(runtimeAutomationService, never()).warmUpBrokerSession();
        verify(runtimeAutomationService, never()).connectAndSubscribe();
    }

    @Test
    void scheduledBrokerWarmup_shouldSkipOnNonTradingDay() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 4, 8, 50);

        when(timeProvider.nowDateTime()).thenReturn(now);
        when(runtimeReadinessService.isTradingDay(now.toLocalDate())).thenReturn(false);

        runtimeAutomationService.scheduledBrokerWarmup();

        verify(runtimeAutomationService, never()).warmUpBrokerSession();
        verify(runtimeSettingService, never()).loginTime();
    }

    @Test
    void scheduledConnectAndSubscribe_shouldSkipOnNonTradingDay() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 4, 9, 0);

        when(timeProvider.nowDateTime()).thenReturn(now);
        when(runtimeReadinessService.isTradingDay(now.toLocalDate())).thenReturn(false);

        runtimeAutomationService.scheduledConnectAndSubscribe();

        verify(runtimeAutomationService, never()).connectAndSubscribe();
        verify(runtimeSettingService, never()).websocketConnectTime();
    }

    @Test
    void scheduledConditionalFlushAndDisconnect_shouldSkipOnNonTradingDay() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 4, 15, 45);

        when(timeProvider.nowDateTime()).thenReturn(now);
        when(runtimeReadinessService.isTradingDay(now.toLocalDate())).thenReturn(false);

        runtimeAutomationService.scheduledConditionalFlushAndDisconnect();

        verify(runtimeAutomationService, never()).flushAndDisconnect();
        verify(angelOneWebSocketService, never()).status();
    }

    @Test
    void scheduledBrokerSessionClear_shouldSkipOnNonTradingDay() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 4, 16, 0);

        when(timeProvider.nowDateTime()).thenReturn(now);
        when(runtimeReadinessService.isTradingDay(now.toLocalDate())).thenReturn(false);

        runtimeAutomationService.scheduledBrokerSessionClear();

        verify(runtimeAutomationService, never()).clearBrokerSession();
        verify(angelOneWebSocketService, never()).status();
    }

    private AngelOneWebSocketService.Status disconnectedStatus(LocalDateTime now) {
        return new AngelOneWebSocketService.Status(
                false,
                false,
                null,
                now.minusMinutes(1),
                now.minusMinutes(20),
                null,
                null,
                null,
                null,
                0L,
                0L,
                0L,
                0L,
                0L);
    }

    private AngelOneWebSocketService.Status staleConnectedStatus(LocalDateTime now) {
        return new AngelOneWebSocketService.Status(
                true,
                false,
                now.minusHours(1),
                null,
                now.minusMinutes(15),
                null,
                null,
                null,
                null,
                100L,
                10L,
                10L,
                50L,
                0L);
    }

    private AngelOneWebSocketService.Status healthyConnectedStatus(LocalDateTime now) {
        return new AngelOneWebSocketService.Status(
                true,
                false,
                now.minusHours(1),
                null,
                now.minusMinutes(1),
                null,
                null,
                null,
                null,
                100L,
                10L,
                10L,
                50L,
                0L);
    }

    private AngelOneSessionService.SessionStatus sessionStatus(LocalDateTime now, boolean cachedSessionPresent) {
        return new AngelOneSessionService.SessionStatus(
                cachedSessionPresent,
                cachedSessionPresent ? now.minusMinutes(30) : null,
                cachedSessionPresent,
                cachedSessionPresent,
                cachedSessionPresent);
    }
}