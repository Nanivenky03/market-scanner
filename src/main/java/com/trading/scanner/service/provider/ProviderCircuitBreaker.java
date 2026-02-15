package com.trading.scanner.service.provider;

import com.trading.scanner.config.TimeProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProviderCircuitBreaker {

    private final TimeProvider timeProvider;

    @Value("${provider.circuitBreaker.failureThreshold:5}")
    private int failureThreshold;
    
    @Value("${provider.circuitBreaker.cooldownMinutes:30}")
    private int cooldownMinutes;
    
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicReference<LocalDateTime> circuitOpenedAt = new AtomicReference<>(null);
    private final AtomicReference<CircuitState> state = new AtomicReference<>(CircuitState.CLOSED);
    
    public enum CircuitState {
        CLOSED, OPEN, HALF_OPEN
    }
    
    public boolean isCallAllowed() {
        CircuitState currentState = state.get();
        
        if (currentState == CircuitState.CLOSED) {
            return true;
        }
        
        if (currentState == CircuitState.OPEN) {
            LocalDateTime openedAt = circuitOpenedAt.get();
            if (openedAt != null &&
                timeProvider.nowDateTime().isAfter(openedAt.plusMinutes(cooldownMinutes))) {
                if (state.compareAndSet(CircuitState.OPEN, CircuitState.HALF_OPEN)) {
                    log.info("Circuit breaker entering HALF_OPEN state");
                    return true;
                }
            }
            log.warn("Circuit breaker OPEN - blocking call");
            return false;
        }
        
        return true;
    }
    
    public void recordSuccess() {
        int failures = consecutiveFailures.getAndSet(0);
        CircuitState previousState = state.getAndSet(CircuitState.CLOSED);
        
        if (previousState != CircuitState.CLOSED) {
            log.info("Circuit breaker CLOSED - provider recovered");
            circuitOpenedAt.set(null);
        }
    }
    
    public void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        
        if (failures >= failureThreshold && state.get() != CircuitState.OPEN) {
            if (state.compareAndSet(CircuitState.CLOSED, CircuitState.OPEN) ||
                state.compareAndSet(CircuitState.HALF_OPEN, CircuitState.OPEN)) {
                
                LocalDateTime now = timeProvider.nowDateTime();
                circuitOpenedAt.set(now);
                
                log.error("CRITICAL: Provider circuit breaker OPENED after {} failures", failures);
                log.error("CRITICAL: Calls blocked for {} minutes", cooldownMinutes);
            }
        }
    }
    
    public CircuitState getState() {
        return state.get();
    }
    
    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }
}
