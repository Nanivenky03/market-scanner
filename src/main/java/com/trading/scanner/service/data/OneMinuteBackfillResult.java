package com.trading.scanner.service.data;

import java.time.LocalDate;

public record OneMinuteBackfillResult(
        String symbol,
        LocalDate from,
        LocalDate to,
        int requestedCandles,
        int inserted,
        int updated,
        String message
) {
}