package com.trading.scanner.controller;

import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.repository.MarketCandleRepository;
import com.trading.scanner.service.data.FifteenMinuteMaterializationResult;
import com.trading.scanner.service.data.IntradayCandleBackfillService;
import com.trading.scanner.service.data.OneMinuteBackfillResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/dev/market-candles")
@Profile("simulation")
@RequiredArgsConstructor
@Tag(name = "Market Candles Dev", description = "Simulation-only endpoints for 1-minute backfill, 15-minute materialization, and candle inspection")
public class MarketCandleDevController {

    private final MarketCandleRepository marketCandleRepository;
    private final IntradayCandleBackfillService intradayCandleBackfillService;

    public record BatchIntradayRequest(
            @Schema(description = "List of symbols; lowercase is accepted and normalized internally", example = "[\"ongc\", \"itc\", \"wipro\"]") List<String> symbols,

            @Schema(description = "Start date in yyyy-MM-dd format", example = "2026-05-20") LocalDate from,

            @Schema(description = "End date in yyyy-MM-dd format", example = "2026-06-10") LocalDate to) {
    }

    @Operation(summary = "Query stored candles by datetime range", description = "Returns persisted market candles for one symbol, timeframe, and datetime range")
    @GetMapping
    public ResponseEntity<List<MarketCandle>> getCandles(
            @Parameter(description = "Trading symbol; lowercase accepted", example = "ongc") @RequestParam String symbol,
            @Parameter(description = "Exchange code", example = "NSE") @RequestParam(defaultValue = "NSE") String exchange,
            @Parameter(description = "Timeframe enum", example = "ONE_MINUTE") @RequestParam CandleTimeframe timeframe,
            @Parameter(description = "Start datetime in ISO format", example = "2026-05-20T09:15:00") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @Parameter(description = "End datetime in ISO format", example = "2026-05-20T15:30:00") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return ResponseEntity.ok(
                marketCandleRepository.findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                        symbol.trim().toUpperCase(Locale.ROOT),
                        exchange.trim().toUpperCase(Locale.ROOT),
                        timeframe,
                        from,
                        to));
    }

    @Operation(summary = "Query stored candles for one full day", description = "Convenience endpoint for date-only candle inspection")
    @GetMapping("/by-day")
    public ResponseEntity<List<MarketCandle>> getCandlesByDay(
            @Parameter(description = "Trading symbol; lowercase accepted", example = "ongc") @RequestParam String symbol,
            @Parameter(description = "Exchange code", example = "NSE") @RequestParam(defaultValue = "NSE") String exchange,
            @Parameter(description = "Timeframe enum", example = "FIFTEEN_MINUTE") @RequestParam CandleTimeframe timeframe,
            @Parameter(description = "Date in yyyy-MM-dd format", example = "2026-05-20") @RequestParam LocalDate date) {
        LocalDateTime from = date.atTime(0, 0);
        LocalDateTime to = date.atTime(23, 59);

        return ResponseEntity.ok(
                marketCandleRepository.findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                        symbol.trim().toUpperCase(Locale.ROOT),
                        exchange.trim().toUpperCase(Locale.ROOT),
                        timeframe,
                        from,
                        to));
    }

    @Operation(summary = "Backfill 1-minute candles for one symbol", description = "Fetches recent 1-minute historical candles from Angel One and persists them")
    @PostMapping("/backfill-one-minute")
    public ResponseEntity<OneMinuteBackfillResult> backfillOneMinute(
            @Parameter(description = "Trading symbol; lowercase accepted", example = "ongc") @RequestParam String symbol,
            @Parameter(description = "Start date in yyyy-MM-dd format", example = "2026-05-20") @RequestParam LocalDate from,
            @Parameter(description = "End date in yyyy-MM-dd format", example = "2026-06-10") @RequestParam LocalDate to) {
        return ResponseEntity.ok(
                intradayCandleBackfillService.backfillOneMinuteCandles(
                        symbol.trim().toUpperCase(Locale.ROOT),
                        from,
                        to));
    }

    @Operation(summary = "Backfill 1-minute candles for multiple symbols", description = "Batch recent 1-minute backfill into market_candles")
    @PostMapping("/backfill-one-minute-batch")
    public ResponseEntity<List<OneMinuteBackfillResult>> backfillOneMinuteBatch(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, description = "Batch 1-minute backfill request", content = @Content(examples = @ExampleObject(value = """
                    {
                      "symbols": ["ongc", "itc", "wipro"],
                      "from": "2026-05-20",
                      "to": "2026-06-10"
                    }
                    """))) @RequestBody BatchIntradayRequest request) {
        return ResponseEntity.ok(
                intradayCandleBackfillService.backfillOneMinuteCandles(
                        request.symbols(),
                        request.from(),
                        request.to()));
    }

    @Operation(summary = "Materialize 15-minute candles for one symbol", description = "Aggregates persisted 1-minute candles into 15-minute candles")
    @PostMapping("/materialize-fifteen-minute")
    public ResponseEntity<FifteenMinuteMaterializationResult> materializeFifteenMinute(
            @Parameter(description = "Trading symbol; lowercase accepted", example = "ongc") @RequestParam String symbol,
            @Parameter(description = "Start date in yyyy-MM-dd format", example = "2026-05-20") @RequestParam LocalDate from,
            @Parameter(description = "End date in yyyy-MM-dd format", example = "2026-06-10") @RequestParam LocalDate to) {
        return ResponseEntity.ok(
                intradayCandleBackfillService.materializeFifteenMinuteCandles(
                        symbol.trim().toUpperCase(Locale.ROOT),
                        from,
                        to));
    }

    @Operation(summary = "Materialize 15-minute candles for multiple symbols", description = "Batch aggregation from stored 1-minute candles into 15-minute candles")
    @PostMapping("/materialize-fifteen-minute-batch")
    public ResponseEntity<List<FifteenMinuteMaterializationResult>> materializeFifteenMinuteBatch(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, description = "Batch 15-minute materialization request", content = @Content(examples = @ExampleObject(value = """
                    {
                      "symbols": ["ongc", "itc", "wipro"],
                      "from": "2026-05-20",
                      "to": "2026-06-10"
                    }
                    """))) @RequestBody BatchIntradayRequest request) {
        return ResponseEntity.ok(
                intradayCandleBackfillService.materializeFifteenMinuteCandles(
                        request.symbols(),
                        request.from(),
                        request.to()));
    }
}