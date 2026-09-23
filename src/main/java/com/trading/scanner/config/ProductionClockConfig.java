package com.trading.scanner.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * Production Clock Configuration.
 * Creates an ExchangeClock instance backed by system UTC clock and the configured exchange timezone.
 */
@Configuration
public class ProductionClockConfig {

    @Value("${exchange.timezone:Asia/Kolkata}")
    private String timezoneId;

    @Bean
    public ExchangeClock exchangeClock() {
        ZoneId exchangeZone = ZoneId.of(timezoneId);
        Clock systemClock = Clock.systemUTC();

        return new ExchangeClock(systemClock, exchangeZone);
    }
}
