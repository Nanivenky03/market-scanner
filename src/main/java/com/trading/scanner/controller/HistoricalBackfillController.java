package com.trading.scanner.controller;

import com.trading.scanner.service.data.HistoricalBackfillService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Starts, monitors and stops the historical data backfill. One call starts a run that paces and sequences
 * every Angel One request itself; nothing has to be called repeatedly.
 */
@RestController
@RequestMapping("/admin/runtime/historical-backfill")
@RequiredArgsConstructor
public class HistoricalBackfillController {

    private final HistoricalBackfillService historicalBackfillService;

    @Operation(summary = "Start a historical backfill (dryRun=true fetches and archives raw data only)")
    @PostMapping("/start")
    public ResponseEntity<?> start(@RequestBody(required = false) HistoricalBackfillService.RunRequest request) {
        try {
            return ResponseEntity.accepted().body(historicalBackfillService.start(request));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(409).body(Map.of("error", ex.getMessage()));
        }
    }

    @Operation(summary = "Progress of the current or last historical backfill run")
    @GetMapping("/status")
    public ResponseEntity<?> status() {
        HistoricalBackfillService.RunStatus status = historicalBackfillService.status();
        return status == null
                ? ResponseEntity.ok(Map.of("state", "NEVER_RUN"))
                : ResponseEntity.ok(status);
    }

    @Operation(summary = "Stop the running backfill after the current symbol-day; progress is checkpointed")
    @PostMapping("/stop")
    public ResponseEntity<?> stop() {
        HistoricalBackfillService.RunStatus status = historicalBackfillService.stop();
        return status == null
                ? ResponseEntity.ok(Map.of("state", "NEVER_RUN"))
                : ResponseEntity.ok(status);
    }
}
