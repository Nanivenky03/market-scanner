package com.trading.scanner.config;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Central Time Authority for the Scanner System
 * 
 * All temporal operations MUST go through this abstraction.
 * 
 * Production: Uses system clock
 * Simulation: Uses controllable clock
 * 
 * CRITICAL: This is the ONLY source of "now" in the system.
 */
public class ExchangeClock {
    
    private final Clock clock;
    private final ZoneId exchangeZone;
    
    /**
     * Constructor injection for immutability and testability
     */
    public ExchangeClock(Clock clock, ZoneId exchangeZone) {
        this.clock = clock;
        this.exchangeZone = exchangeZone;
    }
    
    /**
     * Get current instant (UTC)
     */
    public Instant now() {
        return clock.instant();
    }
    
    /**
     * Get today's date in exchange timezone
     */
    public LocalDate today() {
        return LocalDate.now(clock.withZone(exchangeZone));
    }
    
    /**
     * Get exchange timezone
     */
    public ZoneId getExchangeZone() {
        return exchangeZone;
    }
    
    /**
     * Get underlying clock (for advanced use cases)
     */
    public Clock getClock() {
        return clock;
    }
}
