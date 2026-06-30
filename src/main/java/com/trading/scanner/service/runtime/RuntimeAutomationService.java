package com.trading.scanner.service.runtime;

import com.trading.scanner.config.RuntimeAutomationProperties;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.service.data.LiveMarketCandleService;
import com.trading.scanner.service.provider.angelone.AngelOneSessionService;
import com.trading.scanner.service.provider.angelone.AngelOneWebSocketService;
import com.trading.scanner.service.provider.angelone.UniverseWebSocketSubscriptionService;
import com.trading.scanner.service.provider.angelone.WebSocketFrameCaptureService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class RuntimeAutomationService {

    private static final String UPDATED_BY_SYSTEM = "system";

    private static final String KEY_LIFECYCLE_STATE = "runtime.lifecycle.state";
    private static final String KEY_LAST_STARTUP_AT = "runtime.last.startup.at";
    private static final String KEY_LAST_SHUTDOWN_AT = "runtime.last.shutdown.at";
    private static final String KEY_LAST_SHUTDOWN_GRACEFUL = "runtime.last.shutdown.graceful";
    private static final String KEY_LAST_SHUTDOWN_MESSAGE = "runtime.last.shutdown.message";

    private static final String STATE_STARTING = "STARTING";
    private static final String STATE_RUNNING = "RUNNING";
    private static final String STATE_STOPPING = "STOPPING";
    private static final String STATE_STOPPED = "STOPPED";

    private final AngelOneSessionService angelOneSessionService;
    private final AngelOneWebSocketService angelOneWebSocketService;
    private final UniverseWebSocketSubscriptionService universeWebSocketSubscriptionService;
    private final LiveMarketCandleService liveMarketCandleService;
    private final WebSocketFrameCaptureService webSocketFrameCaptureService;
    private final RuntimeSettingService runtimeSettingService;
    private final RuntimeAutomationProperties runtimeAutomationProperties;
    private final RuntimeReadinessService runtimeReadinessService;
    private final TimeProvider timeProvider;

    private volatile LocalDateTime nextWebsocketCloseCheckAt;
    private volatile String startupRecoveryWarning;

    public RuntimeActionResult warmUpBrokerSession() {
        AngelOneSessionService.SessionWarmupResult result = angelOneSessionService.warmUpSession();
        return new RuntimeActionResult(
                "BROKER_SESSION_WARMUP",
                angelOneWebSocketService.status().connected(),
                0,
                liveMarketCandleService.openCandles().size(),
                result.message());
    }

    public RuntimeActionResult connectAndSubscribe() {
        webSocketFrameCaptureService.clear();
        AngelOneWebSocketService.Status websocketStatus = angelOneWebSocketService.connect();
        UniverseWebSocketSubscriptionService.SubscriptionResult subscriptionResult = universeWebSocketSubscriptionService
                .subscribeActiveUniverse(runtimeSettingService.subscriptionMode());

        return new RuntimeActionResult(
                "CONNECT_AND_SUBSCRIBE",
                websocketStatus.connected(),
                subscriptionResult.processedSubscriptions(),
                liveMarketCandleService.openCandles().size(),
                "Connected websocket and subscribed active universe");
    }

    public RuntimeActionResult flushAndDisconnect() {
        LiveMarketCandleService.FlushResult flushResult = liveMarketCandleService.flushOpenCandles();
        AngelOneWebSocketService.Status websocketStatus = angelOneWebSocketService.disconnect();
        nextWebsocketCloseCheckAt = null;

        return new RuntimeActionResult(
                "FLUSH_AND_DISCONNECT",
                websocketStatus.connected(),
                0,
                flushResult.flushed(),
                "Flushed live candles and disconnected websocket");
    }

    public RuntimeActionResult clearBrokerSession() {
        AngelOneSessionService.SessionClearResult result = angelOneSessionService.clearCachedSession();
        return new RuntimeActionResult(
                "BROKER_SESSION_CLEAR",
                angelOneWebSocketService.status().connected(),
                0,
                liveMarketCandleService.openCandles().size(),
                result.message());
    }

    public RuntimeActionResult reconcileStartupState() {
        LocalDateTime now = timeProvider.nowDateTime();
        LocalTime currentTime = now.toLocalTime();

        String previousLifecycleState = runtimeSettingService.getString(KEY_LIFECYCLE_STATE, STATE_STOPPED);
        String previousShutdownAt = runtimeSettingService.getString(KEY_LAST_SHUTDOWN_AT, "");
        String previousShutdownGraceful = runtimeSettingService.getString(KEY_LAST_SHUTDOWN_GRACEFUL, "true");

        markLifecycleState(STATE_STARTING);
        recordStartup(now);

        if (!STATE_STOPPED.equals(previousLifecycleState) || !"true".equalsIgnoreCase(previousShutdownGraceful)) {
            startupRecoveryWarning = "Previous runtime ended uncleanly. previousLifecycleState="
                    + previousLifecycleState + ", previousShutdownAt=" + previousShutdownAt;
            log.warn(startupRecoveryWarning);
        } else {
            startupRecoveryWarning = null;
        }

        if (!runtimeReadinessService.isTradingDay(now.toLocalDate())) {
            markLifecycleState(STATE_RUNNING);
            return new RuntimeActionResult(
                    "STARTUP_RECONCILE",
                    angelOneWebSocketService.status().connected(),
                    0,
                    liveMarketCandleService.openCandles().size(),
                    "Startup reconcile completed. Non-trading day detected, no broker/websocket actions executed");
        }

        LocalTime loginTime = runtimeSettingService.loginTime();
        LocalTime websocketConnectTime = runtimeSettingService.websocketConnectTime();
        LocalTime websocketDisconnectTime = runtimeSettingService.websocketDisconnectTime();
        LocalTime brokerDisconnectTime = runtimeSettingService.angeloneDisconnectTime();

        boolean warmed = false;
        boolean connected = false;

        if (!currentTime.isBefore(loginTime) && currentTime.isBefore(brokerDisconnectTime)) {
            if (!angelOneSessionService.sessionStatus().cachedSessionPresent()) {
                warmUpBrokerSession();
                warmed = true;
            }
        }

        if (!currentTime.isBefore(websocketConnectTime) && currentTime.isBefore(websocketDisconnectTime)) {
            if (!angelOneWebSocketService.status().connected()) {
                if (!angelOneSessionService.sessionStatus().cachedSessionPresent()) {
                    warmUpBrokerSession();
                    warmed = true;
                }
                connectAndSubscribe();
                connected = true;
            }
        }

        markLifecycleState(STATE_RUNNING);

        return new RuntimeActionResult(
                "STARTUP_RECONCILE",
                angelOneWebSocketService.status().connected(),
                0,
                liveMarketCandleService.openCandles().size(),
                "Startup reconcile completed. warmedSession=" + warmed + ", connectedWebsocket=" + connected);
    }

    public RuntimeStatus runtimeStatus() {
        AngelOneWebSocketService.Status websocketStatus = angelOneWebSocketService.status();
        AngelOneSessionService.SessionStatus sessionStatus = angelOneSessionService.sessionStatus();

        return new RuntimeStatus(
                runtimeAutomationProperties.getLive().isAutoRun(),
                runtimeSettingService.subscriptionMode(),
                angelOneWebSocketService.status().connected(),
                websocketStatus.connecting(),
                websocketStatus.lastConnectedAt(),
                websocketStatus.lastDisconnectedAt(),
                websocketStatus.lastMessageReceivedAt(),
                websocketStatus.lastError(),
                websocketStatus.messagesReceived(),
                websocketStatus.messagesSent(),
                websocketStatus.subscriptionsSent(),
                websocketStatus.parsedTicksReceived(),
                websocketStatus.parserFailures(),
                sessionStatus.cachedSessionPresent(),
                sessionStatus.lastLoginAt(),
                liveMarketCandleService.openCandles().size(),
                runtimeSettingService.getString(KEY_LIFECYCLE_STATE, STATE_STOPPED),
                runtimeSettingService.getString(KEY_LAST_STARTUP_AT, null),
                runtimeSettingService.getString(KEY_LAST_SHUTDOWN_AT, null),
                parseBoolean(runtimeSettingService.getString(KEY_LAST_SHUTDOWN_GRACEFUL, "true")),
                startupRecoveryWarning);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!runtimeAutomationProperties.getLive().isAutoRun()) {
            return;
        }

        try {
            RuntimeActionResult result = reconcileStartupState();
            log.info("Runtime startup reconcile completed: {}", result);
        } catch (Exception ex) {
            log.warn("Runtime startup reconcile failed: {}", ex.getMessage(), ex);
        }
    }

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Kolkata")
    public void scheduledBrokerWarmup() {
        if (!runtimeAutomationProperties.getLive().isAutoRun()) {
            return;
        }

        LocalDateTime nowDateTime = timeProvider.nowDateTime();
        if (!runtimeReadinessService.isTradingDay(nowDateTime.toLocalDate())) {
            return;
        }

        LocalTime now = nowDateTime.toLocalTime();
        LocalTime loginTime = runtimeSettingService.loginTime();

        if (now.getHour() != loginTime.getHour() || now.getMinute() != loginTime.getMinute()) {
            return;
        }

        try {
            RuntimeActionResult result = warmUpBrokerSession();
            log.info("Scheduled broker session warmup completed: {}", result);
        } catch (Exception ex) {
            log.warn("Scheduled broker session warmup failed: {}", ex.getMessage(), ex);
        }
    }

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Kolkata")
    public void scheduledConnectAndSubscribe() {
        if (!runtimeAutomationProperties.getLive().isAutoRun()) {
            return;
        }

        LocalDateTime nowDateTime = timeProvider.nowDateTime();
        if (!runtimeReadinessService.isTradingDay(nowDateTime.toLocalDate())) {
            return;
        }

        LocalTime now = nowDateTime.toLocalTime();
        LocalTime connectTime = runtimeSettingService.websocketConnectTime();

        if (now.getHour() != connectTime.getHour() || now.getMinute() != connectTime.getMinute()) {
            return;
        }

        try {
            RuntimeActionResult result = connectAndSubscribe();
            log.info("Scheduled runtime connect/subscription completed: {}", result);
        } catch (Exception ex) {
            log.warn("Scheduled runtime connect/subscription failed: {}", ex.getMessage(), ex);
        }
    }

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Kolkata")
    public void scheduledConditionalFlushAndDisconnect() {
        if (!runtimeAutomationProperties.getLive().isAutoRun()) {
            return;
        }

        LocalDateTime now = timeProvider.nowDateTime();
        if (!runtimeReadinessService.isTradingDay(now.toLocalDate())) {
            return;
        }

        LocalTime disconnectCheckTime = runtimeSettingService.websocketDisconnectTime();

        if (now.toLocalTime().isBefore(disconnectCheckTime)) {
            return;
        }

        if (nextWebsocketCloseCheckAt != null && now.isBefore(nextWebsocketCloseCheckAt)) {
            return;
        }

        AngelOneWebSocketService.Status status = angelOneWebSocketService.status();
        if (!status.connected()) {
            nextWebsocketCloseCheckAt = null;
            return;
        }

        LocalDateTime lastMessageTime = status.lastMessageReceivedAt();
        if (lastMessageTime == null) {
            try {
                RuntimeActionResult result = flushAndDisconnect();
                log.info("Websocket disconnected because no message timestamp was available: {}", result);
            } catch (Exception ex) {
                log.warn("Websocket disconnect after missing timestamp failed: {}", ex.getMessage(), ex);
            }
            return;
        }

        long idleForMinutes = Duration.between(lastMessageTime, now).toMinutes();

        if (idleForMinutes >= runtimeSettingService.websocketIdleCloseMinutes()) {
            try {
                RuntimeActionResult result = flushAndDisconnect();
                log.info("Websocket disconnected after {} idle minutes: {}", idleForMinutes, result);
            } catch (Exception ex) {
                log.warn("Scheduled conditional websocket disconnect failed: {}", ex.getMessage(), ex);
            }
        } else {
            nextWebsocketCloseCheckAt = now.plusMinutes(runtimeSettingService.websocketCloseRecheckMinutes());
            log.info("Websocket still active after close-check time. idleMinutes={}, nextCheckAt={}",
                    idleForMinutes, nextWebsocketCloseCheckAt);
        }
    }

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Kolkata")
    public void scheduledBrokerSessionClear() {
        if (!runtimeAutomationProperties.getLive().isAutoRun()) {
            return;
        }

        LocalDateTime nowDateTime = timeProvider.nowDateTime();
        if (!runtimeReadinessService.isTradingDay(nowDateTime.toLocalDate())) {
            return;
        }

        LocalTime now = nowDateTime.toLocalTime();
        LocalTime brokerDisconnectTime = runtimeSettingService.angeloneDisconnectTime();

        if (now.isBefore(brokerDisconnectTime)) {
            return;
        }

        if (angelOneWebSocketService.status().connected()) {
            return;
        }

        if (!angelOneSessionService.sessionStatus().cachedSessionPresent()) {
            return;
        }

        try {
            RuntimeActionResult result = clearBrokerSession();
            log.info("Scheduled broker session clear completed: {}", result);
        } catch (Exception ex) {
            log.warn("Scheduled broker session clear failed: {}", ex.getMessage(), ex);
        }
    }

    @PreDestroy
    public void onShutdown() {
        LocalDateTime now = timeProvider.nowDateTime();
        markLifecycleState(STATE_STOPPING);
        recordShutdown(now, false, "Shutdown started");

        try {
            if (!liveMarketCandleService.openCandles().isEmpty()) {
                liveMarketCandleService.flushOpenCandles();
            }

            if (angelOneWebSocketService.status().connected()) {
                angelOneWebSocketService.disconnect();
            }

            if (angelOneSessionService.sessionStatus().cachedSessionPresent()) {
                angelOneSessionService.clearCachedSession();
            }

            recordShutdown(timeProvider.nowDateTime(), true, "Shutdown completed gracefully");
            markLifecycleState(STATE_STOPPED);
        } catch (Exception ex) {
            recordShutdown(timeProvider.nowDateTime(), false, "Shutdown error: " + ex.getMessage());
            log.warn("Runtime shutdown hook failed: {}", ex.getMessage(), ex);
        }
    }

    private void markLifecycleState(String state) {
        runtimeSettingService.upsert(KEY_LIFECYCLE_STATE, state, "STRING", "Runtime lifecycle state",
                UPDATED_BY_SYSTEM);
    }

    private void recordStartup(LocalDateTime startupAt) {
        runtimeSettingService.upsert(KEY_LAST_STARTUP_AT, startupAt.toString(), "DATETIME", "Last startup timestamp",
                UPDATED_BY_SYSTEM);
    }

    private void recordShutdown(LocalDateTime shutdownAt, boolean graceful, String message) {
        runtimeSettingService.upsert(KEY_LAST_SHUTDOWN_AT, shutdownAt.toString(), "DATETIME", "Last shutdown timestamp",
                UPDATED_BY_SYSTEM);
        runtimeSettingService.upsert(KEY_LAST_SHUTDOWN_GRACEFUL, Boolean.toString(graceful), "BOOLEAN",
                "Whether previous shutdown was graceful", UPDATED_BY_SYSTEM);
        runtimeSettingService.upsert(KEY_LAST_SHUTDOWN_MESSAGE, message, "STRING", "Last shutdown message",
                UPDATED_BY_SYSTEM);
    }

    private boolean parseBoolean(String value) {
        return value != null && Boolean.parseBoolean(value);
    }

    public record RuntimeActionResult(
            String action,
            boolean websocketConnected,
            int processedSubscriptions,
            int openCandlesOrFlushedCandles,
            String message) {
    }

    public record RuntimeStatus(
            boolean autoRunEnabled,
            int subscriptionMode,
            boolean websocketConnected,
            boolean websocketConnecting,
            LocalDateTime lastConnectedAt,
            LocalDateTime lastDisconnectedAt,
            LocalDateTime lastMessageReceivedAt,
            String lastError,
            long messagesReceived,
            long messagesSent,
            long subscriptionsSent,
            long parsedTicksReceived,
            long parserFailures,
            boolean cachedBrokerSessionPresent,
            LocalDateTime lastBrokerLoginAt,
            int openLiveCandles,
            String lifecycleState,
            String lastStartupAt,
            String lastShutdownAt,
            boolean lastShutdownGraceful,
            String startupRecoveryWarning) {
    }
}