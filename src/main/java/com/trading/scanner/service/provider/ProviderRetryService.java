package com.trading.scanner.service.provider;

import com.trading.scanner.config.ExchangeConfiguration;
import com.trading.scanner.model.StockPrice;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.List;
import java.util.Random;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderRetryService {
    
    private final MarketDataProvider provider;
    private final ProviderCircuitBreaker circuitBreaker;
    private final ExchangeConfiguration config;
    private final Random random = new Random();
    
    public ProviderResult<List<StockPrice>> fetchHistoricalDataWithRetry(
            String symbol, LocalDate startDate, LocalDate endDate) {
        
        if (!circuitBreaker.isCallAllowed()) {
            return ProviderResult.circuitOpen();
        }
        
        int maxAttempts = config.getProviderRetryMaxAttempts();
        long baseBackoffMs = config.getProviderRetryBaseBackoffMs();
        long jitterMaxMs = config.getProviderRetryJitterMaxMs();
        
        Exception lastException = null;
        
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                List<StockPrice> prices = provider.fetchHistoricalData(symbol, startDate, endDate);
                circuitBreaker.recordSuccess();
                return ProviderResult.success(prices);
                
            } catch (DataProviderException e) {
                lastException = e;
                circuitBreaker.recordFailure();
                
                if (attempt < maxAttempts) {
                    long backoff = baseBackoffMs * (1L << (attempt - 1));
                    long jitter = random.nextLong(jitterMaxMs + 1);
                    long sleepTime = backoff + jitter;
                    
                    try {
                        Thread.sleep(sleepTime);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        
        return ProviderResult.failure(lastException);
    }
    
    public static class ProviderResult<T> {
        private final T data;
        private final boolean success;
        private final boolean circuitOpen;
        private final Exception exception;
        
        private ProviderResult(T data, boolean success, boolean circuitOpen, Exception exception) {
            this.data = data;
            this.success = success;
            this.circuitOpen = circuitOpen;
            this.exception = exception;
        }
        
        public static <T> ProviderResult<T> success(T data) {
            return new ProviderResult<>(data, true, false, null);
        }
        
        public static <T> ProviderResult<T> failure(Exception exception) {
            return new ProviderResult<>(null, false, false, exception);
        }
        
        public static <T> ProviderResult<T> circuitOpen() {
            return new ProviderResult<>(null, false, true, null);
        }
        
        public boolean isSuccess() {
            return success;
        }
        
        public T getData() {
            return data;
        }
        
        public boolean isCircuitOpen() {
            return circuitOpen;
        }
        
        public Exception getException() {
            return exception;
        }
    }
}
