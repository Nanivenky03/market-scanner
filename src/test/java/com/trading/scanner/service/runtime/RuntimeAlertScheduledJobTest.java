package com.trading.scanner.service.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.scanner.config.RuntimeAutomationProperties;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.RuntimeAlertState;
import com.trading.scanner.repository.RuntimeAlertStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RuntimeAlertScheduledJobTest {

    private RuntimeAlertStateRepository repository;
    private RuntimeReadinessService readinessService;
    private RuntimeAutomationService automationService;
    private RuntimeSettingService settingService;
    private RuntimeAutomationProperties properties;
    private TimeProvider timeProvider;
    private JavaMailSender mailSender;
    private RuntimeAlertService service;

    @BeforeEach
    void setUp() {
        repository = mock(RuntimeAlertStateRepository.class);

        readinessService = mock(RuntimeReadinessService.class);

        automationService = mock(RuntimeAutomationService.class);

        settingService = mock(RuntimeSettingService.class);

        properties = new RuntimeAutomationProperties();

        timeProvider = mock(TimeProvider.class);

        mailSender = mock(JavaMailSender.class);

        service = new RuntimeAlertService(
                repository,
                readinessService,
                automationService,
                settingService,
                properties,
                timeProvider,
                new ObjectMapper(),
                mailSender);

        when(repository.save(any(RuntimeAlertState.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void reportScheduledJobResult_shouldCreateNewRecordForEveryExecution() {
        LocalDateTime firstRun = LocalDateTime.of(2026, 8, 28, 6, 45);

        LocalDateTime secondRun = LocalDateTime.of(2026, 8, 28, 6, 50);

        when(timeProvider.nowDateTime())
                .thenReturn(firstRun, secondRun);

        List<RuntimeAlertState> savedAlerts = new ArrayList<>();

        when(repository.save(any(RuntimeAlertState.class)))
                .thenAnswer(invocation -> {
                    RuntimeAlertState alert = invocation.getArgument(0);

                    savedAlerts.add(alert);
                    return alert;
                });

        RuntimeAlertService.ScheduledJobAlertResult first = service.reportScheduledJobResult(
                "historical-bootstrap",
                false,
                "First attempt failed",
                "attempt-1");

        RuntimeAlertService.ScheduledJobAlertResult second = service.reportScheduledJobResult(
                "historical-bootstrap",
                true,
                "Retry succeeded",
                "attempt-2");

        assertEquals(2, savedAlerts.size());
        assertNotEquals(
                first.alertKey(),
                second.alertKey());
        assertNotEquals(
                first.executionId(),
                second.executionId());

        assertEquals("FAILED", savedAlerts.get(0).getStatus());
        assertEquals("SUCCESS", savedAlerts.get(1).getStatus());

        assertEquals(
                "historical-bootstrap",
                second.jobName());

        assertEquals(
                true,
                second.success());

        verify(repository, times(2))
                .save(any(RuntimeAlertState.class));
    }
}
