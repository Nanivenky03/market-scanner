package com.trading.scanner.service.provider.angelone;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.config.provider.AngelOneProperties;
import com.trading.scanner.service.data.LiveMarketCandleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class AngelOneWebSocketServiceTest {

    private AngelOneProperties properties;
    private AngelOneSessionService angelOneSessionService;
    private TimeProvider timeProvider;
    private WebSocketFrameCaptureService frameCaptureService;
    private AngelOneTickParserService angelOneTickParserService;
    private LiveMarketCandleService liveMarketCandleService;
    private LiveMarketSnapshotService liveMarketSnapshotService;
    private AngelOneWebSocketService angelOneWebSocketService;

    @BeforeEach
    void setUp() {
        properties = mock(AngelOneProperties.class);
        angelOneSessionService = mock(AngelOneSessionService.class);
        timeProvider = mock(TimeProvider.class);
        frameCaptureService = mock(WebSocketFrameCaptureService.class);
        angelOneTickParserService = mock(AngelOneTickParserService.class);
        liveMarketCandleService = mock(LiveMarketCandleService.class);
        liveMarketSnapshotService = mock(LiveMarketSnapshotService.class);

        angelOneWebSocketService = new AngelOneWebSocketService(
                properties,
                angelOneSessionService,
                timeProvider,
                new ObjectMapper(),
                frameCaptureService,
                angelOneTickParserService,
                liveMarketCandleService,
                liveMarketSnapshotService);
    }

    @Test
    void handleParsedTick_shouldUpdateSnapshotAndIngestCandleTick() {
        AngelOneTickParserService.NormalizedTick tick = new AngelOneTickParserService.NormalizedTick(
                "WIPRO",
                "NSE",
                LocalDateTime.of(2026, 7, 10, 10, 0),
                1770.90,
                25L,
                "16675",
                3,
                1,
                1L,
                1000L,
                177090L,
                1750.0,
                1780.0,
                1740.0,
                1760.0,
                1768.4,
                100000L,
                50000L,
                45000L,
                900L,
                1000L,
                25.0,
                1900.0,
                1500.0,
                2000.0,
                1200.0);

        angelOneWebSocketService.handleParsedTick(tick);

        verify(liveMarketSnapshotService, times(1)).update(tick);
        verify(liveMarketCandleService, times(1)).ingestTick(
                new LiveMarketCandleService.TickInput(
                        "WIPRO",
                        "NSE",
                        LocalDateTime.of(2026, 7, 10, 10, 0),
                        1770.90,
                        25L,
                        100000L));
        assertEquals(1L, angelOneWebSocketService.status().parsedTicksReceived());
        assertEquals(0L, angelOneWebSocketService.status().parserFailures());
    }

    @Test
    void handleParsedTick_shouldRejectInvalidTick() {
        AngelOneTickParserService.NormalizedTick tick = new AngelOneTickParserService.NormalizedTick(
                "WIPRO",
                "NSE",
                LocalDateTime.of(2026, 7, 10, 10, 0),
                null,
                25L,
                "16675",
                3,
                1,
                1L,
                1000L,
                177090L,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        angelOneWebSocketService.handleParsedTick(tick);

        verify(liveMarketSnapshotService, never()).update(any());
        verify(liveMarketCandleService, never()).ingestTick(any());
        assertEquals(0L, angelOneWebSocketService.status().parsedTicksReceived());
        assertEquals(1L, angelOneWebSocketService.status().parserFailures());
    }

    @Test
    void disconnect_shouldResetLastMessageReceivedAt() {
        when(timeProvider.nowDateTime()).thenReturn(LocalDateTime.of(2026, 10, 8, 16, 0));

        AngelOneWebSocketService.Status status = angelOneWebSocketService.disconnect();

        org.junit.jupiter.api.Assertions.assertFalse(status.connected());
        org.junit.jupiter.api.Assertions.assertNull(status.lastMessageReceivedAt());
        org.junit.jupiter.api.Assertions.assertEquals(LocalDateTime.of(2026, 10, 8, 16, 0), status.lastDisconnectedAt());
    }
    @Test
    void onText_withPong_shouldNotCallTickParser_andNotUpdateMarketDataTime() {
        java.net.http.WebSocket mockWs = mock(java.net.http.WebSocket.class);
        when(timeProvider.nowDateTime()).thenReturn(LocalDateTime.of(2026, 11, 2, 10, 0));

        AngelOneWebSocketService.SocketListener listener = angelOneWebSocketService.createSocketListener();
        listener.onText(mockWs, "pong", true);

        verify(mockWs, times(1)).request(1);
        verify(angelOneTickParserService, never()).tryParseText(any());
        assertEquals(LocalDateTime.of(2026, 11, 2, 10, 0), angelOneWebSocketService.status().lastMessageReceivedAt());
        org.junit.jupiter.api.Assertions.assertNull(angelOneWebSocketService.status().lastMarketDataReceivedAt());
    }

    @Test
    void onBinary_shouldUpdateBothLastMessageReceivedAtAndLastMarketDataReceivedAt() {
        java.net.http.WebSocket mockWs = mock(java.net.http.WebSocket.class);
        when(timeProvider.nowDateTime()).thenReturn(LocalDateTime.of(2026, 11, 2, 10, 5));

        AngelOneWebSocketService.SocketListener listener = angelOneWebSocketService.createSocketListener();
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(new byte[]{0x01, 0x02});
        listener.onBinary(mockWs, buffer, true);

        verify(mockWs, times(1)).request(1);
        assertEquals(LocalDateTime.of(2026, 11, 2, 10, 5), angelOneWebSocketService.status().lastMessageReceivedAt());
        assertEquals(LocalDateTime.of(2026, 11, 2, 10, 5), angelOneWebSocketService.status().lastMarketDataReceivedAt());
    }

    @Test
    void sendHeartbeatPing_whenConnected_shouldSendPingTextFrame() {
        java.net.http.WebSocket mockWs = mock(java.net.http.WebSocket.class);
        when(mockWs.sendText("ping", true)).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(mockWs));

        angelOneWebSocketService.setWebSocketForTesting(mockWs, true);

        angelOneWebSocketService.sendHeartbeatPing();

        verify(mockWs, times(1)).sendText("ping", true);
    }

    @Test
    void heartbeatLifecycle_startAndStop_shouldManageScheduler() {
        org.junit.jupiter.api.Assertions.assertFalse(angelOneWebSocketService.isHeartbeatActive());

        angelOneWebSocketService.startHeartbeat();
        org.junit.jupiter.api.Assertions.assertTrue(angelOneWebSocketService.isHeartbeatActive());

        angelOneWebSocketService.stopHeartbeat();
        org.junit.jupiter.api.Assertions.assertFalse(angelOneWebSocketService.isHeartbeatActive());
    }

    @Test
    void onOpen_shouldSetConnectedAndStartHeartbeatWithoutDeadlock() {
        java.net.http.WebSocket mockWs = mock(java.net.http.WebSocket.class);
        when(timeProvider.nowDateTime()).thenReturn(LocalDateTime.of(2026, 11, 11, 9, 15));

        AngelOneWebSocketService.SocketListener listener = angelOneWebSocketService.createSocketListener();
        listener.onOpen(mockWs);

        org.junit.jupiter.api.Assertions.assertTrue(angelOneWebSocketService.status().connected());
        org.junit.jupiter.api.Assertions.assertTrue(angelOneWebSocketService.isHeartbeatActive());
        verify(mockWs, times(1)).request(1);
    }

    @Test
    void disconnect_shouldAbortWebsocketIfCloseThrows() {
        java.net.http.WebSocket mockWs = mock(java.net.http.WebSocket.class);
        when(mockWs.sendClose(anyInt(), anyString()))
                .thenReturn(java.util.concurrent.CompletableFuture.failedFuture(new RuntimeException("Network down")));
        angelOneWebSocketService.setWebSocketForTesting(mockWs, true);

        AngelOneWebSocketService.Status status = angelOneWebSocketService.disconnect();

        org.junit.jupiter.api.Assertions.assertFalse(status.connected());
        verify(mockWs, times(1)).abort();
    }
}
