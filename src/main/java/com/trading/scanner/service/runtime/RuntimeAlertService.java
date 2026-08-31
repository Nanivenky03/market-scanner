package com.trading.scanner.service.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.scanner.config.RuntimeAutomationProperties;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.RuntimeAlertState;
import com.trading.scanner.repository.RuntimeAlertStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RuntimeAlertService {

    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_RESOLVED = "RESOLVED";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";

    private static final String KEY_CALENDAR_REFRESH_STATUS = "calendar.last.official.refresh.status";
    private static final String KEY_CALENDAR_REFRESH_MESSAGE = "calendar.last.official.refresh.message";
    private static final String KEY_CALENDAR_REFRESH_AT = "calendar.last.official.refresh.at";
    private static final String ALERT_KEY_CALENDAR_REFRESH_FAILED = "calendar.official_refresh_failed";
    private static final String ALERT_KEY_FEED_HEALTH = "runtime.feed_health";

    private final RuntimeAlertStateRepository runtimeAlertStateRepository;
    private final RuntimeReadinessService runtimeReadinessService;
    private final RuntimeAutomationService runtimeAutomationService;
    private final RuntimeSettingService runtimeSettingService;
    private final RuntimeAutomationProperties runtimeAutomationProperties;
    private final TimeProvider timeProvider;
    private final ObjectMapper objectMapper;
    private final JavaMailSender javaMailSender;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Transactional
    public AlertEvaluationResult evaluateNow() {
        RuntimeReadinessService.ReadinessStatus readiness = runtimeReadinessService.status();
        RuntimeAutomationService.RuntimeStatus runtimeStatus = runtimeAutomationService.runtimeStatus();

        int opened = 0;
        int resolved = 0;

        if (!readiness.readyForLiveRuntime()) {
            upsertOpen(
                    "runtime.not_ready",
                    "HIGH",
                    "Runtime is not ready for live operation",
                    Map.of(
                            "missingBrokerTokenCount", readiness.missingBrokerTokenCount(),
                            "activeUniverseCount", readiness.activeUniverseCount(),
                            "instrumentMasterCount", readiness.instrumentMasterCount()));
            opened++;
        } else if (resolve("runtime.not_ready")) {
            resolved++;
        }

        if (!runtimeStatus.autoRunEnabled()) {
            upsertOpen(
                    "runtime.auto_run_disabled",
                    "MEDIUM",
                    "Auto-run is disabled",
                    Map.of("subscriptionMode", runtimeStatus.subscriptionMode()));
            opened++;
        } else if (resolve("runtime.auto_run_disabled")) {
            resolved++;
        }

        if (runtimeStatus.parserFailures() >= runtimeSettingService.parserFailuresThreshold()) {
            upsertOpen(
                    "runtime.parser_failures_high",
                    "HIGH",
                    "Parser failures crossed threshold",
                    Map.of(
                            "parserFailures", runtimeStatus.parserFailures(),
                            "threshold", runtimeSettingService.parserFailuresThreshold()));
            opened++;
        } else if (resolve("runtime.parser_failures_high")) {
            resolved++;
        }

        if (runtimeStatus.websocketConnected() && runtimeStatus.lastMessageReceivedAt() != null) {
            long idleMinutes = Duration.between(runtimeStatus.lastMessageReceivedAt(), timeProvider.nowDateTime())
                    .toMinutes();

            if (idleMinutes >= runtimeSettingService.staleTicksMinutes()) {
                upsertOpen(
                        "runtime.stale_ticks",
                        "HIGH",
                        "No ticks/messages received within threshold",
                        Map.of(
                                "idleMinutes", idleMinutes,
                                "threshold", runtimeSettingService.staleTicksMinutes()));
                opened++;
            } else if (resolve("runtime.stale_ticks")) {
                resolved++;
            }
        } else if (resolve("runtime.stale_ticks")) {
            resolved++;
        }

        if (runtimeStatus.startupRecoveryWarning() != null && !runtimeStatus.startupRecoveryWarning().isBlank()) {
            upsertOpen(
                    "runtime.unclean_previous_shutdown",
                    "HIGH",
                    "Previous runtime shutdown was not graceful",
                    Map.of(
                            "startupRecoveryWarning", runtimeStatus.startupRecoveryWarning(),
                            "lastShutdownAt", runtimeStatus.lastShutdownAt(),
                            "lastShutdownGraceful", runtimeStatus.lastShutdownGraceful()));
            opened++;
        } else if (resolve("runtime.unclean_previous_shutdown")) {
            resolved++;
        }

        String calendarRefreshStatus = runtimeSettingService.getString(KEY_CALENDAR_REFRESH_STATUS, "");
        if ("FAILED".equalsIgnoreCase(calendarRefreshStatus)) {
            upsertOpen(
                    ALERT_KEY_CALENDAR_REFRESH_FAILED,
                    "HIGH",
                    "Official NSE holiday refresh failed",
                    Map.of(
                            "lastRefreshAt", runtimeSettingService.getString(KEY_CALENDAR_REFRESH_AT, ""),
                            "failureMessage", runtimeSettingService.getString(KEY_CALENDAR_REFRESH_MESSAGE, "")));
            opened++;
        } else if (resolve(ALERT_KEY_CALENDAR_REFRESH_FAILED)) {
            resolved++;
        }

        return new AlertEvaluationResult(
                timeProvider.nowDateTime(),
                opened,
                resolved,
                openHighAlertCount(),
                "Runtime alert evaluation completed");
    }

    @Transactional
    public void evaluateFeedHealthAggregate(
            List<String> staleSymbols,
            List<String> recoveringSymbols) {

        List<String> stale = staleSymbols == null
                ? List.of()
                : List.copyOf(staleSymbols);

        List<String> recovering = recoveringSymbols == null
                ? List.of()
                : List.copyOf(recoveringSymbols);

        if (stale.isEmpty() && recovering.isEmpty()) {
            resolve(ALERT_KEY_FEED_HEALTH);
            return;
        }

        upsertOpen(
                ALERT_KEY_FEED_HEALTH,
                "MEDIUM",
                "Subscribed market-data feed health degraded",
                Map.of(
                        "staleSymbols", stale,
                        "recoveringSymbols", recovering,
                        "staleCount", stale.size(),
                        "recoveringCount", recovering.size()));
    }

    @Transactional
    public ScheduledJobAlertResult reportScheduledJobResult(
            String jobName,
            boolean success,
            String message,
            Object details) {

        LocalDateTime now = timeProvider.nowDateTime();

        String safeJobName = jobName == null || jobName.isBlank()
                ? "unknown"
                : jobName.trim()
                        .toLowerCase(java.util.Locale.ROOT)
                        .replaceAll("[^a-z0-9]+", "_");

        String executionId = UUID.randomUUID().toString();

        String alertKey = "runtime.scheduled_job."
                + safeJobName
                + "."
                + now.toLocalDate()
                + "."
                + executionId;

        String status = success
                ? STATUS_SUCCESS
                : STATUS_FAILED;

        String safeMessage = message == null || message.isBlank()
                ? success
                        ? "Scheduled job completed"
                        : "Scheduled job failed"
                : message;

        Map<String, Object> alertDetails = new LinkedHashMap<>();

        alertDetails.put("jobName", jobName);
        alertDetails.put("executionId", executionId);
        alertDetails.put("success", success);
        alertDetails.put("status", status);
        alertDetails.put("executedAt", now.toString());
        alertDetails.put("details", details);

        RuntimeAlertState alert = RuntimeAlertState.builder()
                .alertKey(alertKey)
                .firstTriggeredAt(now)
                .createdAt(now)
                .build();

        alert.setSeverity("MEDIUM");
        alert.setStatus(status);
        alert.setMessage(safeMessage);
        alert.setDetails(toJson(alertDetails));
        alert.setLastTriggeredAt(now);
        alert.setUpdatedAt(now);

        runtimeAlertStateRepository.save(alert);

        boolean webhookDelivered = notifyWebhook(
                alertKey,
                "MEDIUM",
                status,
                safeMessage,
                alert.getDetails());

        boolean emailDelivered = notifyEmail(
                alertKey,
                "MEDIUM",
                status,
                safeMessage,
                alert.getDetails());

        return new ScheduledJobAlertResult(
                alertKey,
                executionId,
                jobName,
                success,
                status,
                now,
                webhookDelivered || emailDelivered,
                safeMessage);
    }

    public void scheduledEvaluate() {
        if (!runtimeAutomationProperties.getAlert().isAutoRun()) {
            return;
        }

        try {
            AlertEvaluationResult result = evaluateNow();
            log.info("Scheduled runtime alert evaluation completed: {}", result);
        } catch (Exception ex) {
            log.warn("Scheduled runtime alert evaluation failed: {}", ex.getMessage(), ex);
        }
    }

    @Transactional(readOnly = true)
    public List<RuntimeAlertState> allAlerts() {
        return runtimeAlertStateRepository.findAllRecent();
    }

    @Transactional(readOnly = true)
    public List<RuntimeAlertState> openAlerts() {
        return runtimeAlertStateRepository.findByStatusOrderByUpdatedAtDesc(STATUS_OPEN);
    }

    @Transactional(readOnly = true)
    public List<RuntimeAlertState> openHighAlerts() {
        return runtimeAlertStateRepository.findByStatusAndSeverityOrderByUpdatedAtDesc(STATUS_OPEN, "HIGH");
    }

    @Transactional(readOnly = true)
    public long openHighAlertCount() {
        return runtimeAlertStateRepository.countByStatusAndSeverity(STATUS_OPEN, "HIGH");
    }

    @Transactional(readOnly = true)
    public boolean hasBlockingOpenAlerts() {
        return openHighAlertCount() > 0;
    }

    public TestAlertResult sendTestAlert() {
        boolean webhookDelivered = notifyWebhook(
                "runtime.test",
                "HIGH",
                "TEST",
                "Runtime test alert",
                "{\"type\":\"test\"}");

        boolean emailDelivered = notifyEmail(
                "runtime.test",
                "HIGH",
                "TEST",
                "Runtime test alert",
                "{\"type\":\"test\"}");

        return new TestAlertResult(
                webhookDelivered,
                emailDelivered,
                (webhookDelivered || emailDelivered)
                        ? "Test alert delivered through at least one channel"
                        : "No alert channel delivered the test alert");
    }

    private void upsertOpen(String key, String severity, String message, Object details) {
        LocalDateTime now = timeProvider.nowDateTime();

        RuntimeAlertState alert = runtimeAlertStateRepository.findByAlertKey(key)
                .orElseGet(() -> RuntimeAlertState.builder()
                        .alertKey(key)
                        .firstTriggeredAt(now)
                        .createdAt(now)
                        .build());

        boolean wasOpen = STATUS_OPEN.equalsIgnoreCase(alert.getStatus());

        alert.setSeverity(severity);
        alert.setStatus(STATUS_OPEN);
        alert.setMessage(message);
        alert.setDetails(toJson(details));
        alert.setLastTriggeredAt(now);
        alert.setUpdatedAt(now);

        runtimeAlertStateRepository.save(alert);

        if (!wasOpen) {
            notifyWebhook(key, severity, STATUS_OPEN, message, alert.getDetails());
            notifyEmail(key, severity, STATUS_OPEN, message, alert.getDetails());
        }
    }

    private boolean resolve(String key) {
        Optional<RuntimeAlertState> alertOpt = runtimeAlertStateRepository.findByAlertKey(key);
        if (alertOpt.isEmpty()) {
            return false;
        }

        RuntimeAlertState alert = alertOpt.get();
        if (!STATUS_OPEN.equalsIgnoreCase(alert.getStatus())) {
            return false;
        }

        LocalDateTime now = timeProvider.nowDateTime();
        alert.setStatus(STATUS_RESOLVED);
        alert.setLastResolvedAt(now);
        alert.setUpdatedAt(now);
        runtimeAlertStateRepository.save(alert);

        notifyWebhook(
                alert.getAlertKey(),
                alert.getSeverity(),
                STATUS_RESOLVED,
                alert.getMessage(),
                alert.getDetails());
        notifyEmail(
                alert.getAlertKey(),
                alert.getSeverity(),
                STATUS_RESOLVED,
                alert.getMessage(),
                alert.getDetails());

        return true;
    }

    private boolean notifyWebhook(String alertKey, String severity, String status, String message, String details) {
        if (!runtimeAutomationProperties.getAlert().isWebhookEnabled()) {
            return false;
        }

        String webhookUrl = runtimeAutomationProperties.getAlert().getWebhookUrl();
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return false;
        }

        if (!shouldNotifySeverity(severity, runtimeAutomationProperties.getAlert().getWebhookMinSeverity())) {
            return false;
        }

        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "alertKey", alertKey,
                    "severity", severity,
                    "status", status,
                    "message", message,
                    "details", details,
                    "timestamp", timeProvider.nowDateTime().toString()));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .timeout(Duration.ofMillis(runtimeAutomationProperties.getAlert().getWebhookTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("Runtime alert webhook delivered. alertKey={} status={} severity={}", alertKey, status,
                        severity);
                return true;
            }

            log.warn("Runtime alert webhook failed. alertKey={} status={} severity={} httpStatus={}",
                    alertKey, status, severity, response.statusCode());
            return false;
        } catch (Exception ex) {
            log.warn("Runtime alert webhook delivery error. alertKey={} status={} severity={} error={}",
                    alertKey, status, severity, ex.getMessage());
            return false;
        }
    }

    private boolean notifyEmail(String alertKey, String severity, String status, String message, String details) {
        if (!runtimeAutomationProperties.getAlert().isEmailEnabled()) {
            return false;
        }

        String emailTo = runtimeAutomationProperties.getAlert().getEmailTo();
        String emailFrom = runtimeAutomationProperties.getAlert().getEmailFrom();

        if (emailTo == null || emailTo.isBlank()) {
            return false;
        }

        if (!shouldNotifySeverity(severity, runtimeAutomationProperties.getAlert().getEmailMinSeverity())) {
            return false;
        }

        try {
            SimpleMailMessage mail = new SimpleMailMessage();
            if (emailFrom != null && !emailFrom.isBlank()) {
                mail.setFrom(emailFrom);
            }
            mail.setTo(emailTo);
            mail.setSubject(runtimeAutomationProperties.getAlert().getEmailSubjectPrefix()
                    + " " + severity + " " + status + " " + alertKey);
            mail.setText(buildEmailBody(alertKey, severity, status, message, details));

            javaMailSender.send(mail);

            log.info("Runtime alert email delivered. alertKey={} status={} severity={}", alertKey, status, severity);
            return true;
        } catch (Exception ex) {
            log.warn("Runtime alert email delivery error. alertKey={} status={} severity={} error={}",
                    alertKey, status, severity, ex.getMessage());
            return false;
        }
    }

    private boolean shouldNotifySeverity(String severity, String minimumSeverity) {
        return severityRank(severity) >= severityRank(minimumSeverity);
    }

    private int severityRank(String severity) {
        if (severity == null) {
            return 0;
        }

        return switch (severity.toUpperCase()) {
            case "CRITICAL" -> 4;
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            case "LOW" -> 1;
            default -> 0;
        };
    }

    private String buildEmailBody(String alertKey, String severity, String status, String message, String details) {
        return "Mithron Runtime Alert\n\n"
                + "Alert Key: " + alertKey + "\n"
                + "Severity: " + severity + "\n"
                + "Status: " + status + "\n"
                + "Message: " + message + "\n"
                + "Timestamp: " + timeProvider.nowDateTime() + "\n\n"
                + "Details:\n" + details + "\n";
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{\"error\":\"serialization_failed\"}";
        }
    }

    public record AlertEvaluationResult(
            LocalDateTime evaluatedAt,
            int openedAlerts,
            int resolvedAlerts,
            long currentlyOpenHighAlerts,
            String message) {
    }

    public record TestAlertResult(
            boolean webhookDelivered,
            boolean emailDelivered,
            String message) {
    }

    public record ScheduledJobAlertResult(
            String alertKey,
            String executionId,
            String jobName,
            boolean success,
            String status,
            LocalDateTime reportedAt,
            boolean notificationDelivered,
            String message) {
    }

}