package com.trading.scanner.service.data;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record IntradayGapDetectedEvent(
        String symbol,
        String exchange,
        LocalDate tradingDate,
        LocalDateTime fromTime,
        LocalDateTime toTime) {
}
