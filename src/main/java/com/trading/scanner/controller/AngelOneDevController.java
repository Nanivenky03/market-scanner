package com.trading.scanner.controller;

import com.trading.scanner.service.data.HistoricalBackfillResult;
import com.trading.scanner.service.data.HistoricalBackfillService;
import com.trading.scanner.service.instrument.InstrumentTokenSyncService;
import com.trading.scanner.service.provider.DailyBarDto;
import com.trading.scanner.service.provider.angelone.AngelOneMarketDataProvider;
import com.trading.scanner.service.provider.angelone.AngelOneSessionService;
import com.trading.scanner.service.provider.angelone.dto.AngelOneAuthDtos;
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
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/dev/angelone")
@Profile("simulation")
@RequiredArgsConstructor
@Tag(name = "Angel One Dev", description = "Simulation-only broker utilities for auth, token sync, and historical backfill")
public class AngelOneDevController {

        private final AngelOneSessionService angelOneSessionService;
        private final InstrumentTokenSyncService instrumentTokenSyncService;
        private final AngelOneMarketDataProvider angelOneMarketDataProvider;
        private final HistoricalBackfillService historicalBackfillService;

        public record TotpRequest(
                        @Schema(description = "Manual 6-digit TOTP for debugging", example = "123456") String totp) {
        }

        public record BatchHistoryRequest(
                        @Schema(description = "List of symbols; lowercase is accepted and normalized internally", example = "[\"ongc\", \"itc\", \"wipro\"]") List<String> symbols,

                        @Schema(description = "Start date in yyyy-MM-dd format", example = "2023-01-01") LocalDate from,

                        @Schema(description = "End date in yyyy-MM-dd format", example = "2026-06-17") LocalDate to) {
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
}