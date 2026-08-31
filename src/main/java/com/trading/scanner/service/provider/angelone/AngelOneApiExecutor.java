package com.trading.scanner.service.provider.angelone;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.scanner.service.provider.ProviderException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
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
    private long baseBackoffMs = 1000L;

    @Value("${provider.retry.jitterMaxMs:500}")
    private long jitterMaxMs = 500L;

    @Value("${provider.timeout:30000}")
    private long timeoutMs = 30000L;

    @Value("${provider.angelone.rate.auth.ms:1500}")
    private long authRateMs = 1500L;

    @Value("${provider.angelone.rate.search.ms:500}")
    private long searchRateMs = 500L;

    @Value("${provider.angelone.rate.historical.ms:500}")
    private long historicalRateMs = 500L;

    @Value("${provider.angelone.limit.auth.requests-per-second:1}")
    private int authRequestsPerSecond = 1;

    @Value("${provider.angelone.limit.search.requests-per-second:2}")
    private int searchRequestsPerSecond = 2;

    @Value("${provider.angelone.limit.historical.requests-per-second:2}")
    private int historicalRequestsPerSecond = 2;

    @Value("${provider.angelone.limit.auth.requests-per-minute:30}")
    private int authRequestsPerMinute = 30;

    @Value("${provider.angelone.limit.search.requests-per-minute:100}")
    private int searchRequestsPerMinute = 100;

    @Value("${provider.angelone.limit.historical.requests-per-minute:120}")
    private int historicalRequestsPerMinute = 120;

    @Value("${provider.angelone.retry.auth.max-attempts:2}")
    private int authMaxAttempts = 2;

    @Value("${provider.angelone.retry.search.max-attempts:3}")
    private int searchMaxAttempts = 3;

    @Value("${provider.angelone.retry.historical.max-attempts:3}")
    private int historicalMaxAttempts = 3;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private final Object throttleLock = new Object();
    private final Map<ApiEndpoint, Deque<Long>> requestTimes = new EnumMap<>(ApiEndpoint.class);
    private final Map<ApiEndpoint, Long> lastRequestAt = new EnumMap<>(ApiEndpoint.class);

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
            throw new ProviderException(
                    "Unexpected non-JSON response: " + abbreviate(trimmed, 200));
        }

        try {
            return objectMapper.readValue(trimmed, responseType);
        } catch (Exception ex) {
            throw new ProviderException(
                    "Failed to parse Angel One API JSON response", ex);
        }
    }

    public String postJsonForBody(
            ApiEndpoint endpoint,
            String url,
            Map<String, String> headers,
            Object requestBody) {

        Exception lastException = null;
        int attempts = Math.max(1, maxAttemptsFor(endpoint));

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                throttleBeforeRequest(endpoint);
                return executeOnce(url, headers, requestBody);
            } catch (RetryableProviderException ex) {
                lastException = ex;

                if (attempt < attempts) {
                    sleepQuietly(Math.max(
                            backoffForAttempt(attempt),
                            ex.retryAfterMs()));
                }
            } catch (ProviderException ex) {
                throw ex;
            } catch (Exception ex) {
                lastException = ex;

                if (attempt < attempts) {
                    sleepQuietly(backoffForAttempt(attempt));
                }
            }
        }

        throw new ProviderException(
                "Angel One API request failed after retries for endpoint=" + endpoint,
                lastException);
    }

    private String executeOnce(
            String url,
            Map<String, String> headers,
            Object requestBody) throws Exception {

        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(requestBody)));

        if (headers != null) {
            headers.forEach(request::header);
        }

        HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());

        int status = response.statusCode();
        String body = response.body() == null ? "" : response.body().trim();
        String lowerBody = body.toLowerCase(Locale.ROOT);
        long retryAfterMs = retryAfterMillis(response.headers());

        if (status == 429
                || lowerBody.contains("exceeding access rate")) {
            throw new RetryableProviderException(
                    "Angel One rate limit exceeded",
                    retryAfterMs);
        }

        if (status >= 500) {
            throw new RetryableProviderException(
                    "Angel One server error: HTTP " + status,
                    retryAfterMs);
        }

        if (status >= 400) {
            throw new ProviderException(
                    "Angel One API request failed. status=" + status
                            + ", body=" + abbreviate(body, 200));
        }

        return body;
    }

    private void throttleBeforeRequest(ApiEndpoint endpoint) {
        while (true) {
            long waitMs;

            synchronized (throttleLock) {
                long now = System.currentTimeMillis();
                Deque<Long> times = requestTimes.computeIfAbsent(
                        endpoint, ignored -> new ArrayDeque<>());

                while (!times.isEmpty() && times.peekFirst() <= now - 60_000L) {
                    times.removeFirst();
                }

                long intervalWait = Math.max(
                        0L,
                        rateMsFor(endpoint)
                                - (now - lastRequestAt.getOrDefault(endpoint, 0L)));

                long secondWait = windowWait(
                        times, now, 1_000L, requestsPerSecondFor(endpoint));

                long minuteWait = windowWait(
                        times, now, 60_000L, requestsPerMinuteFor(endpoint));

                waitMs = Math.max(intervalWait, Math.max(secondWait, minuteWait));

                if (waitMs <= 0L) {
                    times.addLast(now);
                    lastRequestAt.put(endpoint, now);
                    return;
                }
            }

            sleepQuietly(waitMs);
        }
    }

    private long windowWait(
            Deque<Long> times,
            long now,
            long windowMs,
            int limit) {

        if (limit <= 0) {
            return windowMs;
        }

        long cutoff = now - windowMs;
        int count = 0;
        long first = Long.MAX_VALUE;

        for (Long time : times) {
            if (time > cutoff) {
                count++;
                first = Math.min(first, time);
            }
        }

        return count >= limit
                ? Math.max(1L, first + windowMs - now)
                : 0L;
    }

    private long retryAfterMillis(HttpHeaders headers) {
        String value = headers.firstValue("Retry-After").orElse(null);

        if (value == null || value.isBlank()) {
            return 0L;
        }

        try {
            return Math.max(0L, Long.parseLong(value.trim()) * 1000L);
        } catch (NumberFormatException ignored) {
        }

        try {
            long target = ZonedDateTime.parse(
                    value,
                    DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant()
                    .toEpochMilli();

            return Math.max(0L, target - System.currentTimeMillis());
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private long rateMsFor(ApiEndpoint endpoint) {
        return switch (endpoint) {
            case AUTH_LOGIN -> authRateMs;
            case SEARCH_SCRIP -> searchRateMs;
            case HISTORICAL_CANDLE -> historicalRateMs;
        };
    }

    private int requestsPerSecondFor(ApiEndpoint endpoint) {
        return switch (endpoint) {
            case AUTH_LOGIN -> authRequestsPerSecond;
            case SEARCH_SCRIP -> searchRequestsPerSecond;
            case HISTORICAL_CANDLE -> historicalRequestsPerSecond;
        };
    }

    private int requestsPerMinuteFor(ApiEndpoint endpoint) {
        return switch (endpoint) {
            case AUTH_LOGIN -> authRequestsPerMinute;
            case SEARCH_SCRIP -> searchRequestsPerMinute;
            case HISTORICAL_CANDLE -> historicalRequestsPerMinute;
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
        long jitter = jitterMaxMs <= 0
                ? 0L
                : ThreadLocalRandom.current().nextLong(jitterMaxMs + 1);

        return baseBackoffMs * attempt + jitter;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(Math.max(1L, millis));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ProviderException(
                    "Interrupted during Angel One API throttling/retry", ex);
        }
    }

    private String abbreviate(String value, int maxLength) {
        return value == null || value.length() <= maxLength
                ? value
                : value.substring(0, maxLength);
    }

    private static final class RetryableProviderException
            extends RuntimeException {

        private final long retryAfterMs;

        private RetryableProviderException(
                String message,
                long retryAfterMs) {
            super(message);
            this.retryAfterMs = retryAfterMs;
        }

        private long retryAfterMs() {
            return retryAfterMs;
        }
    }
}
