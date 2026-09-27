package com.trading.scanner.service.data;

import java.time.LocalDateTime;

public record CandleRepairCompletedEvent(
        String symbol,
        String exchange,
        LocalDateTime fromTime,
        LocalDateTime toTime) {
}



