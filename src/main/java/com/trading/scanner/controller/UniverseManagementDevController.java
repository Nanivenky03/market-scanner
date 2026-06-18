package com.trading.scanner.controller;

import com.trading.scanner.service.instrument.UniverseManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/dev/universe")
@Profile("simulation")
@RequiredArgsConstructor
@Tag(name = "Universe Dev", description = "Simulation-only endpoints for searching, adding, and deactivating stocks in the active universe")
public class UniverseManagementDevController {

        private final UniverseManagementService universeManagementService;

        public record AddInstrumentRequest(
                        @Schema(description = "Instrument master id", example = "1") Integer instrumentId) {
        }

        @Operation(summary = "Search active instruments in instrument master")
        @GetMapping("/search")
        public ResponseEntity<List<UniverseManagementService.InstrumentSearchResult>> search(
                        @Parameter(description = "Search text for symbol or company name", example = "ongc") @RequestParam String query,
                        @Parameter(description = "Exchange code", example = "NSE") @RequestParam(defaultValue = "NSE") String exchange) {
                return ResponseEntity.ok(
                                universeManagementService.searchActiveInstruments(query, exchange));
        }

        @Operation(summary = "Add or reactivate an instrument in stock universe")
        @PostMapping("/add")
        public ResponseEntity<UniverseManagementService.AddToUniverseResult> addToUniverse(
                        @RequestBody AddInstrumentRequest request) {
                return ResponseEntity.ok(
                                universeManagementService.addInstrumentToUniverse(request.instrumentId()));
        }

        @Operation(summary = "Deactivate a symbol in stock universe")
        @PostMapping("/deactivate")
        public ResponseEntity<UniverseManagementService.RemoveFromUniverseResult> deactivate(
                        @Parameter(description = "Trading symbol; lowercase accepted", example = "ongc") @RequestParam String symbol,
                        @Parameter(description = "Exchange code", example = "NSE") @RequestParam(defaultValue = "NSE") String exchange) {
                return ResponseEntity.ok(
                                universeManagementService.deactivateInstrumentFromUniverse(symbol, exchange));
        }
}