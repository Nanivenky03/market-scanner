package com.trading.scanner.controller;

import com.trading.scanner.service.data.HistoricalBackfillResult;
import com.trading.scanner.service.data.HistoricalBackfillService;
import com.trading.scanner.service.data.LiveMarketCandleService;
import com.trading.scanner.service.instrument.InstrumentTokenSyncService;
import com.trading.scanner.service.provider.DailyBarDto;
import com.trading.scanner.service.provider.angelone.*;
import com.trading.scanner.service.provider.angelone.dto.AngelOneAuthDtos;
import com.trading.scanner.service.runtime.RuntimeSettingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/dev/angelone")
@Profile("simulation")
@RequiredArgsConstructor
@Tag(name = "Angel One Dev", description = "Simulation-only broker utilities for auth, token sync, historical backfill, websocket control, and live candle debugging")
public class AngelOneDevController {

        private final AngelOneSessionService angelOneSessionService;
        private final InstrumentTokenSyncService instrumentTokenSyncService;
        private final AngelOneMarketDataProvider angelOneMarketDataProvider;
        private final HistoricalBackfillService historicalBackfillService;
        private final AngelOneWebSocketService angelOneWebSocketService;
        private final UniverseWebSocketSubscriptionService universeWebSocketSubscriptionService;
        private final LiveMarketCandleService liveMarketCandleService;
        private final WebSocketFrameCaptureService webSocketFrameCaptureService;
        private final LiveMarketSnapshotService liveMarketSnapshotService;
        private final RuntimeSettingService runtimeSettingService;

        public record TotpRequest(
                        @Schema(description = "Manual 6-digit TOTP for debugging", example = "123456") String totp) {
        }

        public record BatchHistoryRequest(
                        @Schema(description = "List of symbols; lowercase is accepted and normalized internally", example = "[\"ongc\", \"itc\", \"wipro\"]") List<String> symbols,

                        @Schema(description = "Start date in yyyy-MM-dd format", example = "2023-01-01") LocalDate from,

                        @Schema(description = "End date in yyyy-MM-dd format", example = "2026-06-17") LocalDate to) {
        }

        public record RawWebSocketRequest(
                        @Schema(description = "Raw websocket JSON payload", example = "{\"action\":1}") String payload) {
        }

        public record ManualTickRequest(
                        @Schema(description = "Trading symbol; lowercase accepted", example = "ongc") String symbol,

                        @Schema(description = "Exchange code", example = "NSE") String exchange,

                        @Schema(description = "Tick time in ISO datetime format", example = "2026-06-18T10:15:21") LocalDateTime tickTime,

                        @Schema(description = "Last traded price", example = "250.75") Double lastPrice,

                        @Schema(description = "Last traded quantity", example = "120") Long lastTradedQuantity) {
        }

        @Operation(summary = "Test Angel One session using auto-generated TOTP", description = "Uses stored credentials and TOTP secret from environment to validate broker login")
        @PostMapping("/session/test-auto")
        public ResponseEntity<AngelOneAuthDtos.AngelOneSessionInfo> testAutoSession() {
                return ResponseEntity.ok(angelOneSessionService.createSession());
        }

        @Operation(summary = "Test Angel One session using manual TOTP", description = "Useful for debugging TOTP issues with a manually entered one-time password")
        @PostMapping("/session/test-manual")
        public ResponseEntity<AngelOneAuthDtos.AngelOneSessionInfo> testManualSession(
                        @RequestBody TotpRequest request) {
                return ResponseEntity.ok(angelOneSessionService.createSession(request.totp()));
        }

        @Operation(summary = "Connect Angel One websocket", description = "Opens a websocket session using current Angel One credentials and feed token")
        @PostMapping("/websocket/connect")
        public ResponseEntity<AngelOneWebSocketService.Status> connectWebSocket() {
                return ResponseEntity.ok(angelOneWebSocketService.connect());
        }

        @Operation(summary = "Disconnect Angel One websocket", description = "Closes the current websocket connection")
        @PostMapping("/websocket/disconnect")
        public ResponseEntity<AngelOneWebSocketService.Status> disconnectWebSocket() {
                return ResponseEntity.ok(angelOneWebSocketService.disconnect());
        }

        @Operation(summary = "Get websocket status", description = "Returns current websocket connection plus latest text/binary frame preview")
        @GetMapping("/websocket/status")
        public ResponseEntity<AngelOneWebSocketService.Status> websocketStatus() {
                return ResponseEntity.ok(angelOneWebSocketService.status());
        }

        @Operation(summary = "List recently captured websocket frames", description = "Shows recent raw websocket text/binary frame previews captured for analysis")
        @GetMapping("/websocket/frames")
        public ResponseEntity<List<WebSocketFrameCaptureService.FrameRecord>> recentFrames(
                        @Parameter(description = "Maximum number of frames to return", example = "20") @RequestParam(defaultValue = "20") int limit) {
                return ResponseEntity.ok(webSocketFrameCaptureService.recent(limit));
        }

        @Operation(summary = "Clear captured websocket frames", description = "Removes buffered raw websocket frame previews")
        @PostMapping("/websocket/frames/clear")
        public ResponseEntity<WebSocketFrameCaptureService.ClearResult> clearFrames() {
                return ResponseEntity.ok(webSocketFrameCaptureService.clear());
        }

        @Operation(summary = "List latest normalized live market snapshots", description = "Shows the latest parsed tick/quote snapshots currently held in memory")
        @GetMapping("/websocket/ticks/latest")
        public ResponseEntity<List<LiveMarketSnapshotService.SnapshotView>> latestTicks(
                        @Parameter(description = "Maximum number of snapshots to return", example = "20") @RequestParam(defaultValue = "20") int limit) {
                return ResponseEntity.ok(liveMarketSnapshotService.latest(limit));
        }

        @Operation(summary = "Clear latest normalized live market snapshots", description = "Removes in-memory latest parsed tick/quote snapshots")
        @PostMapping("/websocket/ticks/clear")
        public ResponseEntity<LiveMarketSnapshotService.ClearResult> clearLatestTicks() {
                return ResponseEntity.ok(liveMarketSnapshotService.clear());
        }

        @Operation(summary = "Send raw websocket payload", description = "Utility endpoint for manually sending raw websocket JSON during development")
        @PostMapping("/websocket/send-raw")
        public ResponseEntity<AngelOneWebSocketService.Status> sendRawWebSocketPayload(
                        @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(examples = @ExampleObject(value = """
                                        {
                                          "payload": "{\"action\":1}"
                                        }
                                        """))) @RequestBody RawWebSocketRequest request) {
                return ResponseEntity.ok(angelOneWebSocketService.sendRaw(request.payload()));
        }

        @Operation(summary = "Subscribe active universe on websocket", description = "Subscribes all active universe symbols using broker tokens, chunked by websocket quota")
        @PostMapping("/websocket/subscribe-active-universe")
        public ResponseEntity<UniverseWebSocketSubscriptionService.SubscriptionResult> subscribeActiveUniverse(
                        @Parameter(description = "SmartAPI mode; if omitted, current runtime setting is used", example = "3") @RequestParam(required = false) Integer mode) {
                return ResponseEntity
                                .ok(universeWebSocketSubscriptionService.subscribeActiveUniverse(resolveMode(mode)));
        }

        @Operation(summary = "Unsubscribe active universe on websocket", description = "Unsubscribes all active universe symbols using broker tokens, chunked by websocket quota")
        @PostMapping("/websocket/unsubscribe-active-universe")
        public ResponseEntity<UniverseWebSocketSubscriptionService.SubscriptionResult> unsubscribeActiveUniverse(
                        @Parameter(description = "SmartAPI mode; if omitted, current runtime setting is used", example = "3") @RequestParam(required = false) Integer mode) {
                return ResponseEntity
                                .ok(universeWebSocketSubscriptionService.unsubscribeActiveUniverse(resolveMode(mode)));
        }

        @Operation(summary = "Manually ingest one tick into the live candle builder", description = "Testing endpoint for validating in-memory 1-minute candle building before wiring real websocket parsing")
        @PostMapping("/websocket/ticks/manual")
        public ResponseEntity<LiveMarketCandleService.IngestResult> ingestManualTick(
                        @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(examples = @ExampleObject(value = """
                                        {
                                          "symbol": "ongc",
                                          "exchange": "NSE",
                                          "tickTime": "2026-06-18T10:15:21",
                                          "lastPrice": 250.75,
                                          "lastTradedQuantity": 120
                                        }
                                        """))) @RequestBody ManualTickRequest request) {
                return ResponseEntity.ok(
                                liveMarketCandleService.ingestTick(
                                                new LiveMarketCandleService.TickInput(
                                                                request.symbol(),
                                                                request.exchange(),
                                                                request.tickTime(),
                                                                request.lastPrice(),
                                                                request.lastTradedQuantity())));
        }

        @Operation(summary = "View currently open in-memory 1-minute candles", description = "Returns the current minute candle snapshot for symbols being built in memory")
        @GetMapping("/websocket/live-candles/open")
        public ResponseEntity<List<LiveMarketCandleService.OpenCandleView>> openLiveCandles() {
                return ResponseEntity.ok(liveMarketCandleService.openCandles());
        }

        @Operation(summary = "Flush open in-memory 1-minute candles to market_candles", description = "Finalizes all open in-memory 1-minute candles and persists them")
        @PostMapping("/websocket/live-candles/flush")
        public ResponseEntity<LiveMarketCandleService.FlushResult> flushLiveCandles() {
                return ResponseEntity.ok(liveMarketCandleService.flushOpenCandles());
        }

        @Operation(summary = "Sync broker tokens into instrument master", description = "Fetches Angel One tradingsymbol and token mappings for instruments in the catalog")
        @PostMapping("/instruments/sync-tokens")
        public ResponseEntity<InstrumentTokenSyncService.TokenSyncResult> syncTokens(
                        @Parameter(description = "Sync only rows where broker token is currently missing", example = "true") @RequestParam(defaultValue = "true") boolean onlyMissing) {
                return ResponseEntity.ok(instrumentTokenSyncService.syncAngelOneTokens(onlyMissing));
        }

        @Operation(summary = "Debug Angel One scrip search", description = "Returns raw searchScrip response for one symbol")
        @GetMapping("/instruments/search-scrip-debug")
        public ResponseEntity<String> searchScripDebug(
                        @Parameter(description = "Exchange code", example = "NSE") @RequestParam String exchange,
                        @Parameter(description = "Trading symbol; lowercase accepted", example = "itc") @RequestParam String symbol) {
                return ResponseEntity.ok(
                                instrumentTokenSyncService.debugSearchScrip(
                                                exchange,
                                                symbol.trim().toUpperCase(Locale.ROOT)));
        }

        @Operation(summary = "Fetch historical daily candles from Angel One", description = "Reads broker daily candle history for a single symbol without persisting it")
        @GetMapping("/history/daily")
        public ResponseEntity<List<DailyBarDto>> getDailyHistory(
                        @Parameter(description = "Trading symbol; lowercase accepted", example = "ongc") @RequestParam String symbol,
                        @Parameter(description = "Start date in yyyy-MM-dd format", example = "2024-05-01") @RequestParam LocalDate from,
                        @Parameter(description = "End date in yyyy-MM-dd format", example = "2024-05-10") @RequestParam LocalDate to) {
                return ResponseEntity.ok(
                                angelOneMarketDataProvider.fetchHistoricalBars(
                                                symbol.trim().toUpperCase(Locale.ROOT),
                                                from,
                                                to));
        }

        @Operation(summary = "Backfill daily candles for one symbol into stock_prices", description = "Fetches and persists daily historical candles for one symbol")
        @PostMapping("/history/backfill")
        public ResponseEntity<HistoricalBackfillResult> backfillHistory(
                        @Parameter(description = "Trading symbol; lowercase accepted", example = "ongc") @RequestParam String symbol,
                        @Parameter(description = "Start date in yyyy-MM-dd format", example = "2023-01-01") @RequestParam LocalDate from,
                        @Parameter(description = "End date in yyyy-MM-dd format", example = "2026-06-17") @RequestParam LocalDate to) {
                return ResponseEntity.ok(
                                historicalBackfillService.backfillSymbol(
                                                symbol.trim().toUpperCase(Locale.ROOT),
                                                from,
                                                to));
        }

        @Operation(summary = "Backfill daily candles for multiple symbols", description = "Batch historical daily backfill into stock_prices")
        @PostMapping("/history/backfill-batch")
        public ResponseEntity<List<HistoricalBackfillResult>> backfillHistoryBatch(
                        @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, description = "Batch daily backfill request", content = @Content(examples = @ExampleObject(value = """
                                        {
                                          "symbols": ["ongc", "itc", "wipro"],
                                          "from": "2023-01-01",
                                          "to": "2026-06-17"
                                        }
                                        """))) @RequestBody BatchHistoryRequest request) {
                return ResponseEntity.ok(
                                historicalBackfillService.backfillSymbols(
                                                request.symbols(),
                                                request.from(),
                                                request.to()));
        }

        private int resolveMode(Integer mode) {
                return mode != null ? mode : runtimeSettingService.subscriptionMode();
        }
}