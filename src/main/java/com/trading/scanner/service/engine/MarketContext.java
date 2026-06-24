package com.trading.scanner.service.engine;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record MarketContext(
        LocalDate tradingDate,
        LocalDateTime currentTime,
        Boolean niftyAboveVwap,
        DayType dayType,
        boolean highVolatilityDay) {
    public enum DayType {
        NORMAL,
        GAP_UP,
        GAP_DOWN,
        UNKNOWN
    }
}