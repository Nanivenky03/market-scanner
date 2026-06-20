package com.trading.scanner.service.provider.angelone;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.scanner.service.provider.ProviderException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class AngelOneApiExecutor {

    public enum ApiEndpoint {
        AUTH_LOGIN,
        SEARCH_SCRIP,
        HISTORICAL_CANDLE
    }

    private final ObjectMapper objectMapper;

    @Value("${provider.retry.baseBackoffMs:1000}")
    private long baseBackoffMs;

    @Value("${provider.retry.jitterMaxMs:500}")
    private long jitterMaxMs;

    @Value("${provider.timeout:30000}")
    private long timeoutMs;

    @Value("${provider.angelone.rate.auth.ms:1500}")
    private long authRateMs;

    @Value("${provider.angelone.rate.search.ms:500}")
    private long searchRateMs;

    @Value("${provider.angelone.rate.historical.ms:500}")
    private long historicalRateMs;

    @Value("${provider.angelone.retry.auth.max-attempts:2}")
    private int authMaxAttempts;

    @Value("${provider.angelone.retry.search.max-attempts:3}")
    private int searchMaxAttempts;

    @Value("${provider.angelone.retry.historical.max-attempts:3}")
    private int historicalMaxAttempts;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private final Object throttleLock = new Object();
    private final Map<ApiEndpoint, Long> lastRequestAtMs = new EnumMap<>(ApiEndpoint.class);

    public <T> T postJson(
            ApiEndpoint endpoint,
            String url,
            Map<String, String> headers,
            Object requestBody,
            Class<T> responseType) {
        String body = postJsonForBody(endpoint, url, headers, requestBody);

        String trimmed = body == null ? "" : body.trim();
        if (trimmed.isBlank()) {
            throw new ProviderException("Angel One API returned empty response");
        }

        if (!trimmed.startsWith("{")) {
            throw new ProviderException("Unexpected non-JSON response from Angel One API: " + abbreviate(trimmed, 200));
        }

        try {
            return objectMapper.readValue(trimmed, responseType);
        } catch (Exception ex) {
            throw new ProviderException("Failed to parse Angel One API JSON response", ex);
        }
    }

    public String postJsonForBody(
            ApiEndpoint endpoint,
            String url,
            Map<String, String> headers,
            Object requestBody) {
        Exception lastException = null;
        int maxAttempts = maxAttemptsFor(endpoint);

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                throttleBeforeRequest(endpoint);
                return executeOnce(url, headers, requestBody);

            } catch (RetryableProviderException ex) {
                lastException = ex;

                if (attempt == maxAttempts) {
                    break;
                }

                sleepQuietly(backoffForAttempt(attempt));
            } catch (ProviderException ex) {
                throw ex;
            } catch (Exception ex) {
                lastException = ex;

                if (attempt == maxAttempts) {
                    break;
                }

                sleepQuietly(backoffForAttempt(attempt));
            }
        }

        throw new ProviderException("Angel One API request failed after retries for endpoint=" + endpoint,
                lastException);
    }

    private String executeOnce(
            String url,
            Map<String, String> headers,
            Object requestBody) throws Exception {
        String jsonBody = objectMapper.writeValueAsString(requestBody);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));

        if (headers != null) {
            headers.forEach(builder::header);
        }

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());

        int status = response.statusCode();
        String body = response.body();
        String normalizedBody = body == null ? "" : body.trim();
        String lowerBody = normalizedBody.toLowerCase(Locale.ROOT);

        if (lowerBody.contains("exceeding access rate")) {
            throw new RetryableProviderException("Angel One rate limit hit: " + abbreviate(normalizedBody, 200));
        }

        if (status == 429) {
            throw new RetryableProviderException("Angel One returned HTTP 429 rate limit");
        }

        if (status >= 500) {
            throw new RetryableProviderException("Angel One server error: HTTP " + status);
        }

        if (status >= 400) {
            throw new ProviderException(
                    "Angel One API request failed. status=" + status + ", body=" + abbreviate(normalizedBody, 200));
        }

        return normalizedBody;
    }

    private void throttleBeforeRequest(ApiEndpoint endpoint) {
        synchronized (throttleLock) {
            long now = System.currentTimeMillis();
            long last = lastRequestAtMs.getOrDefault(endpoint, 0L);
            long elapsed = now - last;
            long waitMs = rateMsFor(endpoint) - elapsed;

            if (waitMs > 0) {
                sleepQuietly(waitMs);
            }

            lastRequestAtMs.put(endpoint, System.currentTimeMillis());
        }
    }

    private long rateMsFor(ApiEndpoint endpoint) {
        return switch (endpoint) {
            case AUTH_LOGIN -> authRateMs;
            case SEARCH_SCRIP -> searchRateMs;
            case HISTORICAL_CANDLE -> historicalRateMs;
        };
    }

    private int maxAttemptsFor(ApiEndpoint endpoint) {
        return switch (endpoint) {
            case AUTH_LOGIN -> authMaxAttempts;
            case SEARCH_SCRIP -> searchMaxAttempts;
            case HISTORICAL_CANDLE -> historicalMaxAttempts;
        };
    }

    private long backoffForAttempt(int attempt) {
        long jitter = jitterMaxMs <= 0 ? 0 : ThreadLocalRandom.current().nextLong(jitterMaxMs + 1);
        return (baseBackoffMs * attempt) + jitter;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ProviderException("Interrupted while waiting for Angel One retry/throttle", ex);
        }
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static final class RetryableProviderException extends RuntimeException {
        private RetryableProviderException(String message) {
            super(message);
        }
    }
}