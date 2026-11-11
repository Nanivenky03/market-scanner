package com.trading.scanner.service.runtime;

import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.config.TimeProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class ExternalHeartbeatWatchdogService {

    private static final LocalTime WINDOW_START = LocalTime.of(9, 0);
    private static final LocalTime WINDOW_END = LocalTime.of(16, 0);

    private final TradingCalendar tradingCalendar;
    private final TimeProvider timeProvider;
    private final HttpClient httpClient;
    private final String heartbeatUrl;

    private final AtomicLong successfulPings = new AtomicLong(0);
    private final AtomicLong failedPings = new AtomicLong(0);
    private volatile LocalDateTime lastPingAttemptAt;
    private volatile LocalDateTime lastPingSuccessAt;
    private volatile String lastError;

    @org.springframework.beans.factory.annotation.Autowired
    public ExternalHeartbeatWatchdogService(
            TradingCalendar tradingCalendar,
            TimeProvider timeProvider,
            @Value("${monitoring.heartbeat.url:}") String heartbeatUrl) {
        this(tradingCalendar, timeProvider, heartbeatUrl, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build());
    }

    ExternalHeartbeatWatchdogService(
            TradingCalendar tradingCalendar,
            TimeProvider timeProvider,
            String heartbeatUrl,
            HttpClient httpClient) {
        this.tradingCalendar = tradingCalendar;
        this.timeProvider = timeProvider;
        this.heartbeatUrl = heartbeatUrl == null ? "" : heartbeatUrl.trim();
        this.httpClient = httpClient;
    }

    @Scheduled(fixedRate = 60_000)
    public void scheduledPing() {
        if (heartbeatUrl.isBlank()) {
            return;
        }

        LocalDateTime now = timeProvider.nowDateTime();
        if (!shouldPing(now)) {
            return;
        }

        sendPing(now);
    }

    public boolean shouldPing(LocalDateTime now) {
        if (heartbeatUrl.isBlank()) {
            return false;
        }
        if (!tradingCalendar.isTradingDay(now.toLocalDate())) {
            return false;
        }
        LocalTime time = now.toLocalTime();
        return !time.isBefore(WINDOW_START) && time.isBefore(WINDOW_END);
    }

    public boolean sendPing(LocalDateTime now) {
        if (heartbeatUrl.isBlank()) {
            log.debug("Heartbeat URL is not configured. Skipping external ping.");
            return false;
        }

        lastPingAttemptAt = now;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(heartbeatUrl))
                    .timeout(Duration.ofSeconds(5))
                    .header("User-Agent", "Mithron-Scanner/2.5")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                successfulPings.incrementAndGet();
                lastPingSuccessAt = now;
                lastError = null;
                log.debug("External heartbeat ping sent successfully to Healthchecks (status={})", response.statusCode());
                return true;
            } else {
                failedPings.incrementAndGet();
                lastError = "HTTP " + response.statusCode();
                log.warn("External heartbeat ping failed with status {}", response.statusCode());
                return false;
            }
        } catch (Exception ex) {
            failedPings.incrementAndGet();
            lastError = ex.getMessage();
            log.warn("Failed to dispatch external heartbeat ping: {}", ex.getMessage());
            return false;
        }
    }

    public Status status() {
        return new Status(
                !heartbeatUrl.isBlank(),
                heartbeatUrl.isBlank() ? null : maskUrl(heartbeatUrl),
                successfulPings.get(),
                failedPings.get(),
                lastPingAttemptAt,
                lastPingSuccessAt,
                lastError
        );
    }

    private String maskUrl(String url) {
        if (url.length() <= 12) return "***";
        return url.substring(0, 10) + "..." + url.substring(url.length() - 6);
    }

    public record Status(
            boolean enabled,
            String maskedUrl,
            long successfulPings,
            long failedPings,
            LocalDateTime lastPingAttemptAt,
            LocalDateTime lastPingSuccessAt,
            String lastError
    ) {}
}
