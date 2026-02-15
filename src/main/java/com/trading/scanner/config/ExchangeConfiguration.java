package com.trading.scanner.config;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import java.time.*;

@Configuration
@Getter
@RequiredArgsConstructor
public class ExchangeConfiguration {
    
    private final ExchangeClock exchangeClock;
    
    @Value("${exchange.timezone:Asia/Kolkata}")
    private String timezoneId;
    
    @Value("${exchange.marketOpen:09:15}")
    private String marketOpenTime;
    
    @Value("${exchange.marketClose:15:30}")
    private String marketCloseTime;
    
    @Value("${provider.publishBufferHours:3}")
    private int publishBufferHours;
    
    @Value("${scanner.allowHistoricalReload:false}")
    private boolean allowHistoricalReload;
    
    @Value("${scanner.historical.reload.confirm:false}")
    private boolean historicalReloadConfirm;
    
    @Value("${scanner.historicalYears:5}")
    private int historicalYears;
    
    @Value("${provider.retry.maxAttempts:3}")
    private int providerRetryMaxAttempts;
    
    @Value("${provider.retry.baseBackoffMs:1000}")
    private long providerRetryBaseBackoffMs;
    
    @Value("${provider.retry.jitterMaxMs:500}")
    private long providerRetryJitterMaxMs;
    
    @Value("${provider.circuitBreaker.failureThreshold:5}")
    private int circuitBreakerFailureThreshold;
    
    @Value("${provider.circuitBreaker.cooldownMinutes:30}")
    private int circuitBreakerCooldownMinutes;
    
    @Value("${provider.rateLimitMs:500}")
    private long providerRateLimitMs;
    
    @Value("${validation.maxPriceSpike:0.40}")
    private double maxPriceSpike;
    
    public ZoneId getExchangeZone() {
        return exchangeClock.getExchangeZone();
    }
    
    public LocalDate getTodayInExchangeZone() {
        return exchangeClock.today();
    }
    
    public LocalTime getCurrentTimeInExchangeZone() {
        return exchangeClock.nowDateTime().toLocalTime();
    }
    
    public LocalDateTime getCurrentDateTimeInExchangeZone() {
        return exchangeClock.nowDateTime();
    }
    
    public boolean hasPublishBufferElapsed() {
        LocalTime now = getCurrentTimeInExchangeZone();
        LocalTime close = LocalTime.parse(marketCloseTime);
        LocalTime bufferEnd = close.plusHours(publishBufferHours);
        return now.isAfter(bufferEnd);
    }
    
    public LocalDate getSafeFetchEndDate() {
        LocalDate today = getTodayInExchangeZone();
        return hasPublishBufferElapsed() ? today : today.minusDays(1);
    }
    
    public boolean isHistoricalReloadAllowed() {
        return allowHistoricalReload && historicalReloadConfirm;
    }
    
    public LocalTime getMarketOpen() {
        return LocalTime.parse(marketOpenTime);
    }
    
    public LocalTime getMarketClose() {
        return LocalTime.parse(marketCloseTime);
    }
}
