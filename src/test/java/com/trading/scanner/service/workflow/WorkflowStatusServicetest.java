package com.trading.scanner.service.workflow;

import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.WorkflowStatus;
import com.trading.scanner.repository.WorkflowStatusRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WorkflowStatusServiceTest {

    private WorkflowStatusRepository repository;
    private TimeProvider timeProvider;
    private WorkflowStatusService service;

    private final LocalDateTime now =
            LocalDateTime.of(2026, 9, 14, 8, 30);

    @BeforeEach
    void setUp() {
        repository = mock(WorkflowStatusRepository.class);
        timeProvider = mock(TimeProvider.class);

        when(timeProvider.nowDateTime())
                .thenReturn(now);

        when(repository.save(any(WorkflowStatus.class)))
                .thenAnswer(invocation ->
                        invocation.getArgument(0));

        service = new WorkflowStatusService(
                repository,
                timeProvider);
    }

    @Test
    void missingActiveStartupWorkflow_createsReadyWorkflowWithNullProcessDate() {
        when(repository
                .findByNameAndWorkflowGroupAndProcessDateIsNullAndIsActiveTrueOrderByInsertTimestampDesc(
                        WorkflowStatusService.INSTRUMENT_MASTER_SYNC,
                        WorkflowStatusService.STARTUP_GROUP))
                .thenReturn(List.of());

        WorkflowStatus result =
                service.findOrCreateStartupWorkflow(
                        WorkflowStatusService.INSTRUMENT_MASTER_SYNC);

        assertEquals(
                WorkflowStatus.Status.READY,
                result.getStatus());

        assertEquals(
                WorkflowStatusService.INSTRUMENT_MASTER_SYNC,
                result.getName());

        assertEquals(
                WorkflowStatusService.STARTUP_GROUP,
                result.getWorkflowGroup());

        assertNull(result.getProcessDate());

        verify(repository).save(any(WorkflowStatus.class));
    }

    @Test
    void successfulStartupWorkflow_isReturnedWithoutResettingIt() {
        WorkflowStatus workflow = workflow(
                WorkflowStatus.Status.SUCCESS,
                true);

        when(repository
                .findByNameAndWorkflowGroupAndProcessDateIsNullAndIsActiveTrueOrderByInsertTimestampDesc(
                        WorkflowStatusService.INSTRUMENT_MASTER_SYNC,
                        WorkflowStatusService.STARTUP_GROUP))
                .thenReturn(List.of(workflow));

        WorkflowStatus result =
                service.prepareForStartup(
                        WorkflowStatusService.INSTRUMENT_MASTER_SYNC);

        assertEquals(
                WorkflowStatus.Status.SUCCESS,
                result.getStatus());

        verify(repository, never())
                .save(any(WorkflowStatus.class));
    }

    @Test
    void failedStartupWorkflow_isPreparedForRetry() {
        WorkflowStatus workflow = workflow(
                WorkflowStatus.Status.FAILED,
                true);

        when(repository
                .findByNameAndWorkflowGroupAndProcessDateIsNullAndIsActiveTrueOrderByInsertTimestampDesc(
                        WorkflowStatusService.INSTRUMENT_MASTER_SYNC,
                        WorkflowStatusService.STARTUP_GROUP))
                .thenReturn(List.of(workflow));

        WorkflowStatus result =
                service.prepareForStartup(
                        WorkflowStatusService.INSTRUMENT_MASTER_SYNC);

        assertEquals(
                WorkflowStatus.Status.READY,
                result.getStatus());

        assertEquals(
                "Workflow is ready for startup execution",
                result.getMessage());

        verify(repository).save(workflow);
    }

    @Test
    void runningStartupWorkflow_isPreparedForRetry() {
        WorkflowStatus workflow = workflow(
                WorkflowStatus.Status.RUNNING,
                true);

        when(repository
                .findByNameAndWorkflowGroupAndProcessDateIsNullAndIsActiveTrueOrderByInsertTimestampDesc(
                        WorkflowStatusService.INSTRUMENT_MASTER_SYNC,
                        WorkflowStatusService.STARTUP_GROUP))
                .thenReturn(List.of(workflow));

        WorkflowStatus result =
                service.prepareForStartup(
                        WorkflowStatusService.INSTRUMENT_MASTER_SYNC);

        assertEquals(
                WorkflowStatus.Status.READY,
                result.getStatus());

        verify(repository).save(workflow);
    }

    @Test
    void multipleActiveStartupWorkflows_failClosed() {
        WorkflowStatus first = workflow(
                WorkflowStatus.Status.READY,
                true);

        WorkflowStatus second = workflow(
                WorkflowStatus.Status.FAILED,
                true);

        when(repository
                .findByNameAndWorkflowGroupAndProcessDateIsNullAndIsActiveTrueOrderByInsertTimestampDesc(
                        WorkflowStatusService.INSTRUMENT_MASTER_SYNC,
                        WorkflowStatusService.STARTUP_GROUP))
                .thenReturn(List.of(first, second));

        assertThrows(
                IllegalStateException.class,
                () -> service.findOrCreateStartupWorkflow(
                        WorkflowStatusService.INSTRUMENT_MASTER_SYNC));
    }

    @Test
    void findOrCreateDailyWorkflow_createsWorkflowWithProcessDate() {
        String processDate = "2026-09-15";
        String stageName = "pre-market-context";
        String group = "pre-market";

        when(repository
                .findByNameAndWorkflowGroupAndProcessDateAndIsActiveTrueOrderByInsertTimestampDesc(
                        stageName,
                        group,
                        processDate))
                .thenReturn(List.of());

        WorkflowStatus result = service.findOrCreateDailyWorkflow(
                stageName,
                group,
                processDate,
                null);

        assertEquals(WorkflowStatus.Status.READY, result.getStatus());
        assertEquals(stageName, result.getName());
        assertEquals(group, result.getWorkflowGroup());
        assertEquals(processDate, result.getProcessDate());

        verify(repository).save(any(WorkflowStatus.class));
    }

    @Test
    void isSuccessfulActiveDailyWorkflow_returnsTrueWhenSuccess() {
        String processDate = "2026-09-15";
        String stageName = "pre-market-context";
        String group = "pre-market";

        WorkflowStatus workflow = WorkflowStatus.builder()
                .workflowId(UUID.randomUUID())
                .name(stageName)
                .workflowGroup(group)
                .processDate(processDate)
                .status(WorkflowStatus.Status.SUCCESS)
                .isActive(true)
                .build();

        when(repository
                .findByNameAndWorkflowGroupAndProcessDateAndIsActiveTrueOrderByInsertTimestampDesc(
                        stageName,
                        group,
                        processDate))
                .thenReturn(List.of(workflow));

        assertTrue(service.isSuccessfulActiveDailyWorkflow(stageName, group, processDate));
    }

    @Test
    void markRunning_incrementsAttemptCount() {
        UUID workflowId = UUID.randomUUID();

        WorkflowStatus workflow = workflow(
                workflowId,
                WorkflowStatus.Status.READY,
                true);

        workflow.setAttemptCount(2);

        when(repository.findByWorkflowId(workflowId))
                .thenReturn(Optional.of(workflow));

        WorkflowStatus result =
                service.markRunning(workflowId);

        assertEquals(
                WorkflowStatus.Status.RUNNING,
                result.getStatus());

        assertEquals(
                3,
                result.getAttemptCount());

        verify(repository).save(workflow);
    }

    @Test
    void markFailed_storesExceptionDetails() {
        UUID workflowId = UUID.randomUUID();

        WorkflowStatus workflow = workflow(
                workflowId,
                WorkflowStatus.Status.RUNNING,
                true);

        RuntimeException error =
                new RuntimeException("catalog unavailable");

        when(repository.findByWorkflowId(workflowId))
                .thenReturn(Optional.of(workflow));

        service.markFailed(
                workflowId,
                "Instrument sync failed",
                error);

        assertEquals(
                WorkflowStatus.Status.FAILED,
                workflow.getStatus());

        assertEquals(
                "Instrument sync failed",
                workflow.getMessage());

        assertEquals(
                "java.lang.RuntimeException: catalog unavailable",
                workflow.getErrorDetails());
    }

    @Test
    void inactiveWorkflow_cannotRun() {
        UUID workflowId = UUID.randomUUID();

        WorkflowStatus workflow = workflow(
                workflowId,
                WorkflowStatus.Status.READY,
                false);

        when(repository.findByWorkflowId(workflowId))
                .thenReturn(Optional.of(workflow));

        assertThrows(
                IllegalStateException.class,
                () -> service.markRunning(workflowId));
    }

    private WorkflowStatus workflow(
            WorkflowStatus.Status status,
            boolean active) {

        return workflow(
                UUID.randomUUID(),
                status,
                active);
    }

    private WorkflowStatus workflow(
            UUID workflowId,
            WorkflowStatus.Status status,
            boolean active) {

        return WorkflowStatus.builder()
                .workflowId(workflowId)
                .name(WorkflowStatusService.INSTRUMENT_MASTER_SYNC)
                .workflowGroup(WorkflowStatusService.STARTUP_GROUP)
                .processDate(null)
                .status(status)
                .isActive(active)
                .attemptCount(0)
                .insertTimestamp(now)
                .updateTimestamp(now)
                .version(0)
                .build();
    }
}