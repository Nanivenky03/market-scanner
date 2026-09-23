package com.trading.scanner.config;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Central Time Authority for the Scanner System.
 * Uses system clock configured with the exchange timezone.
 */
public class ExchangeClock implements TimeProvider {

    private final ZoneId exchangeZone;
    private final Clock systemClock;

    public ExchangeClock(Clock systemClock, ZoneId exchangeZone) {
        this.systemClock = systemClock;
        this.exchangeZone = exchangeZone;
    }

    @Override
    public LocalDate today() {
        return LocalDate.now(systemClock.withZone(exchangeZone));
    }

    @Override
    public LocalDateTime nowDateTime() {
        return LocalDateTime.now(systemClock.withZone(exchangeZone));
    }

    public ZoneId getExchangeZone() {
        return exchangeZone;
    }
}
