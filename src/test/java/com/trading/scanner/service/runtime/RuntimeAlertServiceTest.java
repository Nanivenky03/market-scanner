package com.trading.scanner.service.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.scanner.config.RuntimeAutomationProperties;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.repository.RuntimeAlertStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class RuntimeAlertServiceTest {

        private RuntimeAlertStateRepository runtimeAlertStateRepository;
        private RuntimeReadinessService runtimeReadinessService;
        private RuntimeAutomationService runtimeAutomationService;
        private RuntimeSettingService runtimeSettingService;
        private RuntimeAutomationProperties runtimeAutomationProperties;
        private TimeProvider timeProvider;
        private ObjectMapper objectMapper;
        private org.springframework.mail.javamail.JavaMailSender javaMailSender;
        private RuntimeAlertService service;

        @BeforeEach
        void setUp() {
                runtimeAlertStateRepository = mock(RuntimeAlertStateRepository.class);

                runtimeReadinessService = mock(RuntimeReadinessService.class);

                runtimeAutomationService = mock(RuntimeAutomationService.class);

                runtimeSettingService = mock(RuntimeSettingService.class);

                runtimeAutomationProperties = mock(
                                RuntimeAutomationProperties.class,
                                RETURNS_DEEP_STUBS);

                timeProvider = mock(TimeProvider.class);

                objectMapper = mock(ObjectMapper.class);

                javaMailSender = mock(org.springframework.mail.javamail.JavaMailSender.class);

                service = new RuntimeAlertService(
                                runtimeAlertStateRepository,
                                runtimeReadinessService,
                                runtimeAutomationService,
                                runtimeSettingService,
                                runtimeAutomationProperties,
                                timeProvider,
                                objectMapper,
                                javaMailSender);
        }

        @Test
        void evaluateNow_shouldOpenAlertWhenRuntimeIsNotReady() {
                LocalDate date = LocalDate.of(2026, 8, 27);

                LocalDateTime now = date.atTime(10, 0);

                when(timeProvider.nowDateTime())
                                .thenReturn(now);

                when(runtimeReadinessService.status())
                                .thenReturn(readiness(
                                                date,
                                                false,
                                                "COMPLETE",
                                                true));

                when(runtimeAutomationService.runtimeStatus())
                                .thenReturn(runtimeStatus());

                when(runtimeSettingService.parserFailuresThreshold())
                                .thenReturn(10);

                when(runtimeSettingService.staleTicksMinutes())
                                .thenReturn(5);

                when(runtimeSettingService.getString(
                                anyString(),
                                anyString()))
                                .thenReturn("");

                when(runtimeAlertStateRepository.findByAlertKey(
                                anyString()))
                                .thenReturn(Optional.empty());

                when(runtimeAlertStateRepository.save(
                                any()))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                when(runtimeAlertStateRepository
                                .countByStatusAndSeverity(
                                                "OPEN",
                                                "HIGH"))
                                .thenReturn(1L);

                RuntimeAlertService.AlertEvaluationResult result = service.evaluateNow();

                assertEquals(1, result.openedAlerts());
                assertEquals(now, result.evaluatedAt());

                verify(runtimeAlertStateRepository, atLeastOnce())
                                .save(any());
        }

        @Test
        void evaluateNow_shouldNotOpenReadinessAlertWhenRuntimeIsReady() {
                LocalDate date = LocalDate.of(2026, 8, 27);

                LocalDateTime now = date.atTime(10, 0);

                when(timeProvider.nowDateTime())
                                .thenReturn(now);

                when(runtimeReadinessService.status())
                                .thenReturn(readiness(
                                                date,
                                                true,
                                                "COMPLETE",
                                                true));

                when(runtimeAutomationService.runtimeStatus())
                                .thenReturn(runtimeStatus());

                when(runtimeSettingService.parserFailuresThreshold())
                                .thenReturn(10);

                when(runtimeSettingService.staleTicksMinutes())
                                .thenReturn(5);

                when(runtimeSettingService.getString(
                                anyString(),
                                anyString()))
                                .thenReturn("");

                when(runtimeAlertStateRepository.findByAlertKey(
                                anyString()))
                                .thenReturn(Optional.empty());

                when(runtimeAlertStateRepository
                                .countByStatusAndSeverity(
                                                "OPEN",
                                                "HIGH"))
                                .thenReturn(0L);

                RuntimeAlertService.AlertEvaluationResult result = service.evaluateNow();

                assertEquals(0, result.openedAlerts());
                assertEquals(0, result.currentlyOpenHighAlerts());

                verify(runtimeAlertStateRepository, never())
                                .save(any());
        }

        private RuntimeReadinessService.ReadinessStatus readiness(
                        LocalDate date,
                        boolean readyForLiveRuntime,
                        String bootstrapStatus,
                        boolean bootstrapReady) {

                return new RuntimeReadinessService.ReadinessStatus(
                                date,
                                true,
                                true,
                                5,
                                5L,
                                150L,
                                1500L,
                                100L,
                                100L,
                                100L,
                                0L,
                                true,
                                1,
                                false,
                                0L,
                                0L,
                                0L,
                                0,
                                List.<RuntimeReadinessService.MissingBrokerToken>of(),
                                bootstrapStatus,
                                bootstrapReady,
                                bootstrapReady,
                                readyForLiveRuntime,
                                readyForLiveRuntime);
        }

        private RuntimeAutomationService.RuntimeStatus runtimeStatus() {
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
