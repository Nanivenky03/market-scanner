package com.trading.scanner.service.simulation;

import com.trading.scanner.service.data.LiveMarketCandleService;
import com.trading.scanner.service.provider.angelone.AngelOneWebSocketService;
import com.trading.scanner.service.provider.angelone.UniverseWebSocketSubscriptionService;
import com.trading.scanner.service.provider.angelone.WebSocketFrameCaptureService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Profile("simulation")
@RequiredArgsConstructor
@Slf4j
public class SimulationRuntimeAutomationService {

    private final AngelOneWebSocketService angelOneWebSocketService;
    private final UniverseWebSocketSubscriptionService universeWebSocketSubscriptionService;
    private final LiveMarketCandleService liveMarketCandleService;
    private final WebSocketFrameCaptureService webSocketFrameCaptureService;

    @Value("${runtime.live.auto-run:false}")
    private boolean autoRun;

    @Value("${runtime.live.subscription-mode:1}")
    private int subscriptionMode;

    public RuntimeActionResult connectAndSubscribe() {
        webSocketFrameCaptureService.clear();
        AngelOneWebSocketService.Status websocketStatus = angelOneWebSocketService.connect();
        UniverseWebSocketSubscriptionService.SubscriptionResult subscriptionResult = universeWebSocketSubscriptionService
                .subscribeActiveUniverse(subscriptionMode);

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

        return new RuntimeActionResult(
                "FLUSH_AND_DISCONNECT",
                websocketStatus.connected(),
                0,
                flushResult.flushed(),
                "Flushed live candles and disconnected websocket");
    }

    public RuntimeStatus runtimeStatus() {
        AngelOneWebSocketService.Status websocketStatus = angelOneWebSocketService.status();

        return new RuntimeStatus(
                autoRun,
                subscriptionMode,
                websocketStatus.connected(),
                websocketStatus.connecting(),
                websocketStatus.lastConnectedAt(),
                websocketStatus.lastDisconnectedAt(),
                websocketStatus.lastError(),
                websocketStatus.messagesReceived(),
                websocketStatus.messagesSent(),
                websocketStatus.subscriptionsSent(),
                websocketStatus.parsedTicksReceived(),
                websocketStatus.parserFailures(),
                liveMarketCandleService.openCandles().size());
    }

    @Scheduled(cron = "${runtime.live.connect-cron:0 10 9 * * MON-FRI}", zone = "Asia/Kolkata")
    public void scheduledConnectAndSubscribe() {
        if (!autoRun) {
            return;
        }

        try {
            RuntimeActionResult result = connectAndSubscribe();
            log.info("Scheduled runtime connect/subscription completed: {}", result);
        } catch (Exception ex) {
            log.warn("Scheduled runtime connect/subscription failed: {}", ex.getMessage(), ex);
        }
    }

    @Scheduled(cron = "${runtime.live.disconnect-cron:0 31 15 * * MON-FRI}", zone = "Asia/Kolkata")
    public void scheduledFlushAndDisconnect() {
        if (!autoRun) {
            return;
        }

        try {
            RuntimeActionResult result = flushAndDisconnect();
            log.info("Scheduled runtime flush/disconnect completed: {}", result);
        } catch (Exception ex) {
            log.warn("Scheduled runtime flush/disconnect failed: {}", ex.getMessage(), ex);
        }
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
            java.time.LocalDateTime lastConnectedAt,
            java.time.LocalDateTime lastDisconnectedAt,
            String lastError,
            long messagesReceived,
            long messagesSent,
            long subscriptionsSent,
            long parsedTicksReceived,
            long parserFailures,
            int openLiveCandles) {
    }
}