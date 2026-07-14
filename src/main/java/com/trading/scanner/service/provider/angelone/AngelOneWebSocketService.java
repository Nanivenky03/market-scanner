package com.trading.scanner.service.provider.angelone;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.config.provider.AngelOneProperties;
import com.trading.scanner.service.data.LiveMarketCandleService;
import com.trading.scanner.service.provider.ProviderException;
import com.trading.scanner.service.provider.angelone.dto.AngelOneAuthDtos;
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
    private volatile String lastError;
    private volatile String lastTextMessagePreview;
    private volatile String lastBinaryMessagePreview;
    private volatile Integer lastBinarySize;
    private volatile long messagesReceived;
    private volatile long messagesSent;
    private volatile long subscriptionsSent;
    private volatile long parsedTicksReceived;
    private volatile long parserFailures;

    public synchronized Status connect() {
        validateConfig();

        if (connected || connecting) {
            return status();
        }

        try {
            connecting = true;
            lastError = null;

            AngelOneAuthDtos.AngelOneSessionTokens sessionTokens = angelOneSessionService.createSessionTokens();

            webSocket = httpClient.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + sessionTokens.jwtToken())
                    .header("x-api-key", properties.apiKey())
                    .header("x-client-code", properties.clientId())
                    .header("x-feed-token", sessionTokens.feedToken())
                    .buildAsync(URI.create(properties.websocketUrl()), new SocketListener())
                    .join();

            return status();
        } catch (Exception ex) {
            connecting = false;
            connected = false;
            lastError = ex.getMessage();
            throw new ProviderException("Failed to connect Angel One websocket", ex);
        }
    }

    public synchronized Status disconnect() {
        try {
            if (webSocket != null) {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "client_disconnect").join();
                webSocket = null;
            }
        } catch (Exception ex) {
            lastError = ex.getMessage();
            log.warn("Angel One websocket close error: {}", ex.getMessage());
        } finally {
            connected = false;
            connecting = false;
            lastDisconnectedAt = timeProvider.nowDateTime();
        }

        return status();
    }

    public synchronized Status sendRaw(String payload) {
        ensureConnected();

        try {
            webSocket.sendText(payload, true).join();
            messagesSent++;
            return status();
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

            webSocket.sendText(objectMapper.writeValueAsString(payload), true).join();
            messagesSent++;
            subscriptionsSent += tokenRefs.size();

            return status();
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

            webSocket.sendText(objectMapper.writeValueAsString(payload), true).join();
            messagesSent++;

            return status();
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
        try {
            if (tick.symbol() == null || tick.exchange() == null || tick.tickTime() == null
                    || tick.lastPrice() == null) {
                parserFailures++;
                return;
            }

            liveMarketSnapshotService.update(tick);

            liveMarketCandleService.ingestTick(
                    new LiveMarketCandleService.TickInput(
                            tick.symbol(),
                            tick.exchange(),
                            tick.tickTime(),
                            tick.lastPrice(),
                            tick.lastTradedQuantity()));

            parsedTicksReceived++;
        } catch (Exception ex) {
            parserFailures++;
            log.warn("Failed to ingest parsed websocket tick: {}", ex.getMessage());
        }
    }

    private final class SocketListener implements WebSocket.Listener {

        @Override
        public void onOpen(WebSocket webSocket) {
            connected = true;
            connecting = false;
            lastConnectedAt = timeProvider.nowDateTime();
            lastError = null;
            log.info("Angel One websocket connected");

            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            messagesReceived++;
            lastMessageReceivedAt = timeProvider.nowDateTime();
            lastTextMessagePreview = data == null ? null : abbreviate(data.toString(), 500);
            frameCaptureService.captureText(data != null ? data.toString() : null);

            angelOneTickParserService.tryParseText(data != null ? data.toString() : null)
                    .ifPresent(AngelOneWebSocketService.this::handleParsedTick);

            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            messagesReceived++;
            lastMessageReceivedAt = timeProvider.nowDateTime();

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
            lastDisconnectedAt = timeProvider.nowDateTime();
            log.info("Angel One websocket closed. statusCode={} reason={}", statusCode, reason);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            connected = false;
            connecting = false;
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
            String lastError,
            String lastTextMessagePreview,
            String lastBinaryMessagePreview,
            Integer lastBinarySize,
            long messagesReceived,
            long messagesSent,
            long subscriptionsSent,
            long parsedTicksReceived,
            long parserFailures) {
    }
}