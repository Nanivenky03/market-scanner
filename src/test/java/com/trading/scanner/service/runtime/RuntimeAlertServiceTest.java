package com.trading.scanner.service.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.scanner.config.RuntimeAutomationProperties;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.RuntimeAlertState;
import com.trading.scanner.repository.RuntimeAlertStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RuntimeAlertServiceTest {

    private RuntimeAlertStateRepository runtimeAlertStateRepository;
    private RuntimeReadinessService runtimeReadinessService;
    private RuntimeAutomationService runtimeAutomationService;
    private RuntimeSettingService runtimeSettingService;
    private RuntimeAutomationProperties runtimeAutomationProperties;
    private TimeProvider timeProvider;
    private JavaMailSender javaMailSender;
    private RuntimeAlertService runtimeAlertService;

    @BeforeEach
    void setUp() {
        runtimeAlertStateRepository = mock(RuntimeAlertStateRepository.class);
        runtimeReadinessService = mock(RuntimeReadinessService.class);
        runtimeAutomationService = mock(RuntimeAutomationService.class);
        runtimeSettingService = mock(RuntimeSettingService.class);
        runtimeAutomationProperties = new RuntimeAutomationProperties();
        timeProvider = mock(TimeProvider.class);
        javaMailSender = mock(JavaMailSender.class);

        runtimeAlertService = new RuntimeAlertService(
                runtimeAlertStateRepository,
                runtimeReadinessService,
                runtimeAutomationService,
                runtimeSettingService,
                runtimeAutomationProperties,
                timeProvider,
                new ObjectMapper(),
                javaMailSender);
    }

    @Test
    void evaluateNow_shouldOpenCalendarRefreshFailedAlert() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 26, 20, 5);

        when(timeProvider.nowDateTime()).thenReturn(now);
        when(runtimeReadinessService.status()).thenReturn(healthyReadiness());
        when(runtimeAutomationService.runtimeStatus()).thenReturn(healthyRuntimeStatus());
        when(runtimeSettingService.parserFailuresThreshold()).thenReturn(10);
        when(runtimeSettingService.getString("calendar.last.official.refresh.status", "")).thenReturn("FAILED");
        when(runtimeSettingService.getString("calendar.last.official.refresh.message", ""))
                .thenReturn("NSE source unavailable");
        when(runtimeSettingService.getString("calendar.last.official.refresh.at", ""))
                .thenReturn("2026-07-26T20:00:00");
        when(runtimeAlertStateRepository.findByAlertKey(any())).thenReturn(Optional.empty());
        when(runtimeAlertStateRepository.countByStatusAndSeverity("OPEN", "HIGH")).thenReturn(1L);

        RuntimeAlertService.AlertEvaluationResult result = runtimeAlertService.evaluateNow();

        assertEquals(1, result.openedAlerts());

        verify(runtimeAlertStateRepository, atLeastOnce())
                .save(argThat(alert -> "calendar.official_refresh_failed".equals(alert.getAlertKey()) &&
                        "OPEN".equals(alert.getStatus())));
    }

    @Test
    void evaluateNow_shouldResolveCalendarRefreshFailedAlertWhenStatusIsNotFailed() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 27, 9, 0);

        RuntimeAlertState existingAlert = RuntimeAlertState.builder()
                .alertKey("calendar.official_refresh_failed")
                .severity("HIGH")
                .status("OPEN")
                .message("Official NSE holiday refresh failed")
                .createdAt(now.minusDays(1))
                .firstTriggeredAt(now.minusDays(1))
                .lastTriggeredAt(now.minusDays(1))
                .updatedAt(now.minusDays(1))
                .build();

        when(timeProvider.nowDateTime()).thenReturn(now);
        when(runtimeReadinessService.status()).thenReturn(healthyReadiness());
        when(runtimeAutomationService.runtimeStatus()).thenReturn(healthyRuntimeStatus());
        when(runtimeSettingService.parserFailuresThreshold()).thenReturn(10);
        when(runtimeSettingService.getString("calendar.last.official.refresh.status", "")).thenReturn("SUCCESS");
        when(runtimeAlertStateRepository.findByAlertKey(any())).thenReturn(Optional.empty());
        when(runtimeAlertStateRepository.findByAlertKey("calendar.official_refresh_failed"))
                .thenReturn(Optional.of(existingAlert));
        when(runtimeAlertStateRepository.countByStatusAndSeverity("OPEN", "HIGH")).thenReturn(0L);

        RuntimeAlertService.AlertEvaluationResult result = runtimeAlertService.evaluateNow();

        assertEquals(1, result.resolvedAlerts());

        verify(runtimeAlertStateRepository, atLeastOnce())
                .save(argThat(alert -> "calendar.official_refresh_failed".equals(alert.getAlertKey()) &&
                        "RESOLVED".equals(alert.getStatus())));
    }

    private RuntimeReadinessService.ReadinessStatus healthyReadiness() {
        return new RuntimeReadinessService.ReadinessStatus(
                LocalDate.of(2026, 7, 26),
                false,
                false,
                10,
                10L,
                100L,
                100L,
                10L,
                10L,
                10L,
                0L,
                true,
                1,
                false,
                0L,
                0L,
                0L,
                0,
                List.of(),
                true,
                true,
                true);
    }

    private RuntimeAutomationService.RuntimeStatus healthyRuntimeStatus() {
        return new RuntimeAutomationService.RuntimeStatus(
                true,
                true,
                1,
                false,
                false,
                null,
                null,
                null,
                null,
                0L,
                0L,
                0L,
                0L,
                0L,
                true,
                null,
                0,
                "RUNNING",
                null,
                null,
                true,
                null,
                null,
                null,
                null,
                null);
    }
}