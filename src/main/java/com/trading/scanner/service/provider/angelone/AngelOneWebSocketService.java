package com.trading.scanner.service.provider.angelone;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.config.provider.AngelOneProperties;
import com.trading.scanner.service.data.LiveMarketCandleService;
import com.trading.scanner.service.provider.ProviderException;
import com.trading.scanner.service.provider.angelone.dto.AngelOneAuthDtos;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class AngelOneWebSocketService {

    private final AngelOneProperties properties;
    private final AngelOneSessionService angelOneSessionService;
    private final TimeProvider timeProvider;
    private final ObjectMapper objectMapper;
    private final WebSocketFrameCaptureService frameCaptureService;
    private final AngelOneTickParserService angelOneTickParserService;
    private final LiveMarketCandleService liveMarketCandleService;
    private final LiveMarketSnapshotService liveMarketSnapshotService;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private volatile WebSocket webSocket;
    private volatile boolean connected;
    private volatile boolean connecting;
    private volatile LocalDateTime lastConnectedAt;
    private volatile LocalDateTime lastDisconnectedAt;
    private volatile LocalDateTime lastMessageReceivedAt;
    private volatile LocalDateTime lastMarketDataReceivedAt;
    private volatile String lastError;
    private volatile String lastTextMessagePreview;
    private volatile String lastBinaryMessagePreview;
    private volatile Integer lastBinarySize;
    private volatile long messagesReceived;
    private volatile long messagesSent;
    private volatile long subscriptionsSent;
    private volatile long parsedTicksReceived;
    private volatile long parserFailures;

    private final Object heartbeatLock = new Object();
    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "angel-ws-heartbeat");
        thread.setDaemon(true);
        return thread;
    });
    private ScheduledFuture<?> heartbeatFuture;

    public synchronized Status connect() {
        validateConfig();

        if (connected || connecting) {
            return status();
        }

        if (webSocket != null) {
            try {
                webSocket.abort();
            } catch (Exception ex) {
                log.warn("Error aborting lingering websocket before reconnect: {}", ex.getMessage());
            } finally {
                webSocket = null;
            }
        }

        CompletableFuture<WebSocket> future = null;
        try {
            connecting = true;
            lastError = null;

            AngelOneAuthDtos.AngelOneSessionTokens sessionTokens = angelOneSessionService.createSessionTokens();

            future = httpClient.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + sessionTokens.jwtToken())
                    .header("x-api-key", properties.apiKey())
                    .header("x-client-code", properties.clientId())
                    .header("x-feed-token", sessionTokens.feedToken())
                    .buildAsync(URI.create(properties.websocketUrl()), new SocketListener());

            webSocket = future.get(20, TimeUnit.SECONDS);

            return status();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            handleConnectFailure(future, ex);
            throw new ProviderException("Thread interrupted while connecting Angel One websocket", ex);
        } catch (Exception ex) {
            handleConnectFailure(future, ex);
            throw new ProviderException("Failed to connect Angel One websocket", ex);
        }
    }

    private void handleConnectFailure(CompletableFuture<WebSocket> future, Exception ex) {
        connecting = false;
        connected = false;
        stopHeartbeat();
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
        if (webSocket != null) {
            try {
                webSocket.abort();
            } catch (Exception e) {
                // ignore
            }
            webSocket = null;
        }
        lastError = ex.getMessage();
    }

    public synchronized Status disconnect() {
        try {
            stopHeartbeat();
            if (webSocket != null) {
                try {
                    webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "client_disconnect")
                            .get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    log.warn("Graceful close timed out or failed, aborting websocket: {}", e.getMessage());
                    webSocket.abort();
                } finally {
                    webSocket = null;
                }
            }
        } catch (Exception ex) {
            lastError = ex.getMessage();
            log.warn("Angel One websocket close error: {}", ex.getMessage());
        } finally {
            connected = false;
            connecting = false;
            lastDisconnectedAt = timeProvider.nowDateTime();
            lastMessageReceivedAt = null;
            lastMarketDataReceivedAt = null;
        }

        return status();
    }

    public synchronized Status sendRaw(String payload) {
        ensureConnected();

        try {
            webSocket.sendText(payload, true).get(10, TimeUnit.SECONDS);
            messagesSent++;
            return status();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            lastError = ex.getMessage();
            throw new ProviderException("Interrupted while sending websocket payload", ex);
        } catch (Exception ex) {
            lastError = ex.getMessage();
            throw new ProviderException("Failed to send websocket payload", ex);
        }
    }

    public synchronized Status subscribe(String correlationId, int mode, List<TokenRef> tokenRefs) {
        ensureConnected();

        if (tokenRefs == null || tokenRefs.isEmpty()) {
            return status();
        }

        try {
            Map<String, Object> tokenGroup = new LinkedHashMap<>();
            tokenGroup.put("exchangeType", 1);
            tokenGroup.put("tokens", tokenRefs.stream().map(TokenRef::token).distinct().toList());

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("mode", mode);
            params.put("tokenList", List.of(tokenGroup));

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("correlationID", correlationId);
            payload.put("action", 1);
            payload.put("params", params);

            webSocket.sendText(objectMapper.writeValueAsString(payload), true).get(10, TimeUnit.SECONDS);
            messagesSent++;
            subscriptionsSent += tokenRefs.size();

            return status();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            lastError = ex.getMessage();
            throw new ProviderException("Interrupted while subscribing websocket tokens", ex);
        } catch (Exception ex) {
            lastError = ex.getMessage();
            throw new ProviderException("Failed to subscribe websocket tokens", ex);
        }
    }

    public synchronized Status unsubscribe(String correlationId, int mode, List<TokenRef> tokenRefs) {
        ensureConnected();

        if (tokenRefs == null || tokenRefs.isEmpty()) {
            return status();
        }

        try {
            Map<String, Object> tokenGroup = new LinkedHashMap<>();
            tokenGroup.put("exchangeType", 1);
            tokenGroup.put("tokens", tokenRefs.stream().map(TokenRef::token).distinct().toList());

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("mode", mode);
            params.put("tokenList", List.of(tokenGroup));

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("correlationID", correlationId);
            payload.put("action", 0);
            payload.put("params", params);

            webSocket.sendText(objectMapper.writeValueAsString(payload), true).get(10, TimeUnit.SECONDS);
            messagesSent++;

            return status();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            lastError = ex.getMessage();
            throw new ProviderException("Interrupted while unsubscribing websocket tokens", ex);
        } catch (Exception ex) {
            lastError = ex.getMessage();
            throw new ProviderException("Failed to unsubscribe websocket tokens", ex);
        }
    }

    public Status status() {
        return new Status(
                connected,
                connecting,
                lastConnectedAt,
                lastDisconnectedAt,
                lastMessageReceivedAt,
                lastMarketDataReceivedAt,
                lastError,
                lastTextMessagePreview,
                lastBinaryMessagePreview,
                lastBinarySize,
                messagesReceived,
                messagesSent,
                subscriptionsSent,
                parsedTicksReceived,
                parserFailures);
    }

    private void ensureConnected() {
        if (!connected || webSocket == null) {
            throw new ProviderException("Angel One websocket is not connected");
        }
    }

    private void validateConfig() {
        if (!properties.enabled()) {
            throw new ProviderException("Angel One provider is disabled. Set ANGELONE_ENABLED=true");
        }
        if (properties.websocketUrl() == null || properties.websocketUrl().isBlank()) {
            throw new ProviderException("ANGELONE_WEBSOCKET_URL is missing");
        }
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new ProviderException("ANGELONE_API_KEY is missing");
        }
        if (properties.clientId() == null || properties.clientId().isBlank()) {
            throw new ProviderException("ANGELONE_CLIENT_ID is missing");
        }
    }

    void handleParsedTick(AngelOneTickParserService.NormalizedTick tick) {
        if (tick.symbol() == null || tick.exchange() == null || tick.tickTime() == null
                || tick.lastPrice() == null) {
            parserFailures++;
            return;
        }

        try {
            liveMarketSnapshotService.update(tick);
        } catch (Exception ex) {
            log.debug("Failed to update snapshot for tick symbol={}: {}", tick.symbol(), ex.getMessage());
        }

        try {
            liveMarketCandleService.ingestTick(
                    new LiveMarketCandleService.TickInput(
                            tick.symbol(),
                            tick.exchange(),
                            tick.tickTime(),
                            tick.lastPrice(),
                            tick.lastTradedQuantity(),
                            tick.volumeTradedForDay()));

            parsedTicksReceived++;
        } catch (Exception ex) {
            parserFailures++;
            log.warn("Failed to ingest parsed websocket tick for symbol={}: {}", tick.symbol(), ex.getMessage());
        }
    }

    void startHeartbeat() {
        synchronized (heartbeatLock) {
            stopHeartbeatLocked();
            heartbeatFuture = heartbeatScheduler.scheduleAtFixedRate(
                    this::sendHeartbeatPing,
                    25,
                    25,
                    TimeUnit.SECONDS
            );
            log.info("Started Angel One websocket heartbeat scheduler (25s interval)");
        }
    }

    void stopHeartbeat() {
        synchronized (heartbeatLock) {
            stopHeartbeatLocked();
        }
    }

    private void stopHeartbeatLocked() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(true);
            heartbeatFuture = null;
            log.debug("Stopped Angel One websocket heartbeat scheduler");
        }
    }

    boolean isHeartbeatActive() {
        synchronized (heartbeatLock) {
            return heartbeatFuture != null && !heartbeatFuture.isDone();
        }
    }

    void sendHeartbeatPing() {
        try {
            WebSocket ws = this.webSocket;
            if (connected && ws != null && !ws.isOutputClosed() && !ws.isInputClosed()) {
                ws.sendText("ping", true).whenComplete((res, err) -> {
                    if (err != null) {
                        log.warn("Failed to send Angel One websocket heartbeat ping: {}", err.getMessage());
                    } else {
                        log.debug("Sent Angel One websocket heartbeat ping");
                    }
                });
            }
        } catch (Exception ex) {
            log.warn("Exception scheduling Angel One websocket heartbeat ping: {}", ex.getMessage());
        }
    }

    private boolean isPong(String text) {
        String trimmed = text.trim();
        return trimmed.equalsIgnoreCase("pong") || trimmed.equalsIgnoreCase("\"pong\"");
    }

    void setWebSocketForTesting(WebSocket ws, boolean isConnected) {
        this.webSocket = ws;
        this.connected = isConnected;
    }

    SocketListener createSocketListener() {
        return new SocketListener();
    }

    @PreDestroy
    public void cleanup() {
        disconnect();
        heartbeatScheduler.shutdownNow();
    }

    final class SocketListener implements WebSocket.Listener {

        @Override
        public void onOpen(WebSocket webSocket) {
            connected = true;
            connecting = false;
            lastConnectedAt = timeProvider.nowDateTime();
            lastMessageReceivedAt = lastConnectedAt;
            lastMarketDataReceivedAt = lastConnectedAt;
            lastError = null;
            log.info("Angel One websocket connected");

            startHeartbeat();

            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            messagesReceived++;
            lastMessageReceivedAt = timeProvider.nowDateTime();

            String text = data != null ? data.toString() : null;
            if (text != null && isPong(text)) {
                log.debug("Received pong from Angel One websocket");
                webSocket.request(1);
                return CompletableFuture.completedFuture(null);
            }

            lastMarketDataReceivedAt = timeProvider.nowDateTime();
            lastTextMessagePreview = text == null ? null : abbreviate(text, 500);
            frameCaptureService.captureText(text);

            angelOneTickParserService.tryParseText(text)
                    .ifPresent(AngelOneWebSocketService.this::handleParsedTick);

            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            messagesReceived++;
            LocalDateTime now = timeProvider.nowDateTime();
            lastMessageReceivedAt = now;
            lastMarketDataReceivedAt = now;

            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);

            lastBinarySize = bytes.length;
            lastBinaryMessagePreview = toHexPreview(bytes, 96);
            frameCaptureService.captureBinary(bytes);

            angelOneTickParserService.tryParseBinary(bytes)
                    .ifPresent(AngelOneWebSocketService.this::handleParsedTick);

            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            lastMessageReceivedAt = timeProvider.nowDateTime();
            webSocket.request(1);
            return WebSocket.Listener.super.onPing(webSocket, message);
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            lastMessageReceivedAt = timeProvider.nowDateTime();
            webSocket.request(1);
            return WebSocket.Listener.super.onPong(webSocket, message);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            connected = false;
            connecting = false;
            stopHeartbeat();
            lastDisconnectedAt = timeProvider.nowDateTime();
            log.info("Angel One websocket closed. statusCode={} reason={}", statusCode, reason);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            connected = false;
            connecting = false;
            stopHeartbeat();
            lastDisconnectedAt = timeProvider.nowDateTime();
            lastError = error != null ? error.getMessage() : "unknown websocket error";
            log.warn("Angel One websocket error: {}", lastError);
        }
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private String toHexPreview(byte[] bytes, int maxBytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }

        int limit = Math.min(bytes.length, maxBytes);
        StringBuilder sb = new StringBuilder(limit * 3);

        for (int i = 0; i < limit; i++) {
            sb.append(String.format("%02X", bytes[i]));
            if (i < limit - 1) {
                sb.append(' ');
            }
        }

        if (bytes.length > maxBytes) {
            sb.append(" ...");
        }

        return sb.toString();
    }

    public record TokenRef(String symbol, String token) {
    }

    public record Status(
            boolean connected,
            boolean connecting,
            LocalDateTime lastConnectedAt,
            LocalDateTime lastDisconnectedAt,
            LocalDateTime lastMessageReceivedAt,
            LocalDateTime lastMarketDataReceivedAt,
            String lastError,
            String lastTextMessagePreview,
            String lastBinaryMessagePreview,
            Integer lastBinarySize,
            long messagesReceived,
            long messagesSent,
            long subscriptionsSent,
            long parsedTicksReceived,
            long parserFailures) {

        public Status(
                boolean connected,
                boolean connecting,
                LocalDateTime lastConnectedAt,
                LocalDateTime lastDisconnectedAt,
                LocalDateTime lastMessageReceivedAt,
                String lastError,
                String lastTextMessagePreview,
                String lastBinaryMessagePreview,
                Integer lastBinarySize,
                long messagesReceived,
                long messagesSent,
                long subscriptionsSent,
                long parsedTicksReceived,
                long parserFailures) {
            this(connected, connecting, lastConnectedAt, lastDisconnectedAt,
                    lastMessageReceivedAt, lastMessageReceivedAt, lastError,
                    lastTextMessagePreview, lastBinaryMessagePreview, lastBinarySize,
                    messagesReceived, messagesSent, subscriptionsSent,
                    parsedTicksReceived, parserFailures);
        }
    }
}