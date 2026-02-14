package com.trading.scanner.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Clock;
import java.time.ZoneId;

/**
 * Production Clock Configuration
 * 
 * Active when: spring.profiles.active=production (or default)
 * 
 * Uses system time - standard production behavior
 */
@Configuration
@Profile({"production", "default"})
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
