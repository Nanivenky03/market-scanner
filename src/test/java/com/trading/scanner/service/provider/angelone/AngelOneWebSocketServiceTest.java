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
                        25L));
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
}