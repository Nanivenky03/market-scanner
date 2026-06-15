package com.trading.scanner.controller;

import com.trading.scanner.service.data.HistoricalBackfillResult;
import com.trading.scanner.service.data.HistoricalBackfillService;
import com.trading.scanner.service.instrument.InstrumentTokenSyncService;
import com.trading.scanner.service.provider.DailyBarDto;
import com.trading.scanner.service.provider.angelone.AngelOneMarketDataProvider;
import com.trading.scanner.service.provider.angelone.AngelOneSessionService;
import com.trading.scanner.service.provider.angelone.dto.AngelOneSessionInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/dev/angelone")
@Profile({"default", "simulation"})
@RequiredArgsConstructor
public class AngelOneDevController {

    private final AngelOneSessionService angelOneSessionService;
    private final InstrumentTokenSyncService instrumentTokenSyncService;
    private final AngelOneMarketDataProvider angelOneMarketDataProvider;
    private final HistoricalBackfillService historicalBackfillService;

    public record TotpRequest(String totp) {
    }

    @PostMapping("/session/test-auto")
    public ResponseEntity<AngelOneSessionInfo> testAutoSession() {
        AngelOneSessionInfo sessionInfo = angelOneSessionService.createSession();
        return ResponseEntity.ok(sessionInfo);
    }

    @PostMapping("/session/test-manual")
    public ResponseEntity<AngelOneSessionInfo> testManualSession(@RequestBody TotpRequest request) {
        AngelOneSessionInfo sessionInfo = angelOneSessionService.createSession(request.totp());
        return ResponseEntity.ok(sessionInfo);
    }

    @PostMapping("/instruments/sync-tokens")
    public ResponseEntity<InstrumentTokenSyncService.TokenSyncResult> syncTokens(
            @RequestParam(defaultValue = "true") boolean onlyMissing
    ) {
        return ResponseEntity.ok(
                instrumentTokenSyncService.syncAngelOneTokens(onlyMissing)
        );
    }

    @GetMapping("/instruments/search-scrip-debug")
    public ResponseEntity<String> searchScripDebug(
            @RequestParam String exchange,
            @RequestParam String symbol
    ) {
        return ResponseEntity.ok(
                instrumentTokenSyncService.debugSearchScrip(exchange, symbol)
        );
    }

    @GetMapping("/history/daily")
    public ResponseEntity<List<DailyBarDto>> getDailyHistory(
            @RequestParam String symbol,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to
    ) {
        return ResponseEntity.ok(
                angelOneMarketDataProvider.fetchHistoricalBars(symbol, from, to)
        );
    }

    @PostMapping("/history/backfill")
public ResponseEntity<HistoricalBackfillResult> backfillHistory(
        @RequestParam String symbol,
        @RequestParam LocalDate from,
        @RequestParam LocalDate to
) {
    return ResponseEntity.ok(
            historicalBackfillService.backfillSymbol(symbol, from, to)
    );
}
}