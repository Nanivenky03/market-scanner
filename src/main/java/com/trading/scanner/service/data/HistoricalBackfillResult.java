package com.trading.scanner.service.data;

public record HistoricalBackfillResult(
        String symbol,
        int requestedBars,
        int inserted,
        int updated,
        String message
) {
}