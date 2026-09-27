package com.trading.scanner.service.data;

import java.time.LocalDate;

public record FifteenMinuteMaterializationResult(
        String symbol,
        LocalDate from,
        LocalDate to,
        int sourceCandles,
        int producedCandles,
        int inserted,
        int updated,
        String message
) {
}