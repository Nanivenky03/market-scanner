package com.trading.scanner.service.workflow;

import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.WorkflowStatus;
import com.trading.scanner.model.WorkflowStatus.Status;
import com.trading.scanner.repository.WorkflowStatusRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WorkflowStatusService {

        public static final String STARTUP_GROUP = "startup";
    public static final String PREMARKET_GROUP = "pre-market";
    public static final String MARKET_HOURS_GROUP = "market-hours";
    public static final String EOD_GROUP = "eod";
    public static final String SESSION_GROUP = "session";

    // Startup stages
    public static final String INSTRUMENT_MASTER_SYNC = "instrument-master-sync";
    public static final String MARKET_REFERENCE_SETUP = "market-reference-setup";
    public static final String STOCK_UNIVERSE_SEED = "stock-universe-seed";
    public static final String RUNTIME_SETTING_SYNC = "runtime-setting-sync";
    public static final String EOD_DATA_READINESS = "eod-data-readiness";
    public static final String RUNTIME_BOOTSTRAP_COMPLETE = "runtime-bootstrap-complete";

    // Daily Pre-Market stages
    public static final String TRADING_DAY_INIT = "trading-day-init";
    public static final String PREMARKET_HOUSEKEEPING = "premarket-housekeeping";
    public static final String PREMARKET_CATALOG_SYNC = "premarket-catalog-sync";
    public static final String PREMARKET_UNIVERSE_SYNC = "premarket-universe-sync";
    public static final String PREMARKET_MORNING_REFERENCE = "premarket-morning-reference";
    public static final String PREMARKET_LIVE_START = "premarket-live-start";

    // Daily Market Hours & EOD stages
    public static final String MARKET_HOURS = "market-hours";
    public static final String EOD_RECONCILIATION = "eod-reconciliation";
    public static final String DAILY_CYCLE_COMPLETE = "daily-cycle-complete";

    private final WorkflowStatusRepository workflowStatusRepository;

    private final TimeProvider timeProvider;

    @Transactional
    public WorkflowStatus create(
            String name,
            String workflowGroup,
            UUID dependsOnWorkflowId) {
        return create(name, workflowGroup, null, dependsOnWorkflowId);
    }

    @Transactional
    public WorkflowStatus create(
            String name,
            String workflowGroup,
            String processDate,
            UUID dependsOnWorkflowId) {

        LocalDateTime now = timeProvider.nowDateTime();

        WorkflowStatus workflowStatus = WorkflowStatus.builder()
                .name(name)
                .workflowGroup(workflowGroup)
                .processDate(processDate)
                .dependsOnWorkflowId(dependsOnWorkflowId)
                .status(Status.READY)
                .insertTimestamp(now)
                .updateTimestamp(now)
                .isActive(Boolean.TRUE)
                .attemptCount(0)
                .message("Workflow is ready")
                .build();

        return workflowStatusRepository.save(workflowStatus);
    }

    @Transactional(readOnly = true)
    public WorkflowStatus getByWorkflowId(UUID workflowId) {
        return workflowStatusRepository
                .findByWorkflowId(workflowId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Workflow not found: " + workflowId));
    }

    @Transactional(readOnly = true)
    public List<WorkflowStatus> findActiveByGroup(
            String workflowGroup) {

        return workflowStatusRepository
                .findByWorkflowGroupAndIsActiveTrueOrderByInsertTimestampAsc(
                        workflowGroup);
    }

    @Transactional(readOnly = true)
    public List<WorkflowStatus> findActiveDailyByGroup(
            String workflowGroup,
            String processDate) {

        return workflowStatusRepository
                .findByWorkflowGroupAndProcessDateAndIsActiveTrueOrderByInsertTimestampAsc(
                        workflowGroup,
                        processDate);
    }

    @Transactional(readOnly = true)
    public boolean canRun(UUID workflowId) {
        WorkflowStatus workflow = getByWorkflowId(workflowId);

        if (!Boolean.TRUE.equals(workflow.getIsActive())) {
            return false;
        }

        if (workflow.getStatus() != Status.READY
                && workflow.getStatus() != Status.FAILED) {
            return false;
        }

        if (workflow.getDependsOnWorkflowId() == null) {
            return true;
        }

        WorkflowStatus dependency = getByWorkflowId(
                workflow.getDependsOnWorkflowId());

        return dependency.getStatus() == Status.SUCCESS;
    }

    @Transactional
    public WorkflowStatus markRunning(UUID workflowId) {
        WorkflowStatus workflow = getByWorkflowId(workflowId);

        ensureCanRun(workflow);

        int attempts = workflow.getAttemptCount() == null
                ? 0
                : workflow.getAttemptCount();

        workflow.setStatus(Status.RUNNING);
        workflow.setAttemptCount(attempts + 1);
        workflow.setMessage("Workflow execution started");
        workflow.setErrorDetails(null);
        workflow.setUpdateTimestamp(timeProvider.nowDateTime());

        return workflowStatusRepository.save(workflow);
    }

    @Transactional
    public WorkflowStatus markSuccess(
            UUID workflowId,
            String message) {

        WorkflowStatus workflow = getByWorkflowId(workflowId);

        workflow.setStatus(Status.SUCCESS);
        workflow.setMessage(message);
        workflow.setErrorDetails(null);
        workflow.setUpdateTimestamp(timeProvider.nowDateTime());

        return workflowStatusRepository.save(workflow);
    }

    @Transactional
    public WorkflowStatus markFailed(
            UUID workflowId,
            String message,
            String errorDetails) {

        WorkflowStatus workflow = getByWorkflowId(workflowId);

        workflow.setStatus(Status.FAILED);
        workflow.setMessage(message);
        workflow.setErrorDetails(errorDetails);
        workflow.setUpdateTimestamp(timeProvider.nowDateTime());

        return workflowStatusRepository.save(workflow);
    }

    @Transactional
    public WorkflowStatus markFailed(
            UUID workflowId,
            String message,
            Throwable error) {

        String errorDetails = error == null
                ? null
                : error.getClass().getName()
                        + ": "
                        + safeMessage(error);

        return markFailed(
                workflowId,
                message,
                errorDetails);
    }

    @Transactional
    public WorkflowStatus markSkipped(
            UUID workflowId,
            String message) {

        WorkflowStatus workflow = getByWorkflowId(workflowId);

        workflow.setStatus(Status.SKIPPED);
        workflow.setMessage(message);
        workflow.setUpdateTimestamp(timeProvider.nowDateTime());

        return workflowStatusRepository.save(workflow);
    }

    @Transactional
    public WorkflowStatus deactivate(UUID workflowId) {
        WorkflowStatus workflow = getByWorkflowId(workflowId);

        workflow.setIsActive(Boolean.FALSE);
        workflow.setUpdateTimestamp(timeProvider.nowDateTime());

        return workflowStatusRepository.save(workflow);
    }

    @Transactional
    public WorkflowStatus findOrCreateStartupWorkflow(
            String workflowName) {

        List<WorkflowStatus> activeWorkflows =
                workflowStatusRepository
                        .findByNameAndWorkflowGroupAndProcessDateIsNullAndIsActiveTrueOrderByInsertTimestampDesc(
                                workflowName,
                                STARTUP_GROUP);

        if (activeWorkflows.size() > 1) {
            throw new IllegalStateException(
                    "Multiple active startup workflows found. name="
                            + workflowName
                            + ", group="
                            + STARTUP_GROUP);
        }

        if (activeWorkflows.size() == 1) {
            return activeWorkflows.get(0);
        }

        return create(
                workflowName,
                STARTUP_GROUP,
                null,
                null);
    }

    @Transactional(readOnly = true)
    public boolean isSuccessfulActiveStartupWorkflow(
            String workflowName) {

        List<WorkflowStatus> activeWorkflows =
                workflowStatusRepository
                        .findByNameAndWorkflowGroupAndProcessDateIsNullAndIsActiveTrueOrderByInsertTimestampDesc(
                                workflowName,
                                STARTUP_GROUP);

        if (activeWorkflows.size() > 1) {
            throw new IllegalStateException(
                    "Multiple active startup workflows found. name="
                            + workflowName
                            + ", group="
                            + STARTUP_GROUP);
        }

        return activeWorkflows.size() == 1
                && activeWorkflows.get(0).getStatus()
                        == Status.SUCCESS;
    }

    @Transactional
    public WorkflowStatus prepareForStartup(
            String workflowName) {

        WorkflowStatus workflow =
                findOrCreateStartupWorkflow(workflowName);

        if (workflow.getStatus() == Status.SUCCESS
                && Boolean.TRUE.equals(workflow.getIsActive())) {
            return workflow;
        }

        if (!Boolean.TRUE.equals(workflow.getIsActive())) {
            throw new IllegalStateException(
                    "Startup workflow is inactive. name="
                            + workflowName
                            + ", workflowId="
                            + workflow.getWorkflowId());
        }

        workflow.setStatus(Status.READY);
        workflow.setMessage(
                "Workflow is ready for startup execution");
        workflow.setErrorDetails(null);
        workflow.setUpdateTimestamp(
                timeProvider.nowDateTime());

        return workflowStatusRepository.save(workflow);
    }

        @Transactional
    public WorkflowStatus prepareForDaily(
            String workflowName,
            String workflowGroup,
            String processDate,
            UUID dependsOnWorkflowId) {

        List<WorkflowStatus> activeWorkflows = workflowStatusRepository
                .findByNameAndWorkflowGroupAndProcessDateAndIsActiveTrueOrderByInsertTimestampDesc(
                        workflowName,
                        workflowGroup,
                        processDate);

        if (activeWorkflows.size() > 1) {
            throw new IllegalStateException(
                    "Multiple active daily workflows found. name=" + workflowName
                            + ", group=" + workflowGroup + ", date=" + processDate);
        }

        if (activeWorkflows.size() == 1) {
            WorkflowStatus existing = activeWorkflows.get(0);

            // If already completed successfully, return it as is
            if (existing.getStatus() == Status.SUCCESS) {
                return existing;
            }

            // If failed (or retrying), deactivate the old failed row to preserve failure history
            if (existing.getStatus() == Status.FAILED) {
                existing.setIsActive(Boolean.FALSE);
                existing.setUpdateTimestamp(timeProvider.nowDateTime());
                workflowStatusRepository.save(existing);

                // Create a fresh active row for clean retry execution
                return create(
                        workflowName,
                        workflowGroup,
                        processDate,
                        dependsOnWorkflowId);
            }

            // If currently READY or RUNNING, reset status to READY
            existing.setStatus(Status.READY);
            existing.setMessage("Workflow is ready for daily execution");
            existing.setErrorDetails(null);
            existing.setUpdateTimestamp(timeProvider.nowDateTime());
            return workflowStatusRepository.save(existing);
        }

        // Fresh first-time execution for today
        return create(
                workflowName,
                workflowGroup,
                processDate,
                dependsOnWorkflowId);
    }

    @Transactional
    public WorkflowStatus findOrCreateDailyWorkflow(
            String workflowName,
            String workflowGroup,
            String processDate,
            UUID dependsOnWorkflowId) {

        List<WorkflowStatus> activeWorkflows =
                workflowStatusRepository
                        .findByNameAndWorkflowGroupAndProcessDateAndIsActiveTrueOrderByInsertTimestampDesc(
                                workflowName,
                                workflowGroup,
                                processDate);

        if (activeWorkflows.size() > 1) {
            throw new IllegalStateException(
                    "Multiple active daily workflows found. name="
                            + workflowName
                            + ", group="
                            + workflowGroup
                            + ", date="
                            + processDate);
        }

        if (activeWorkflows.size() == 1) {
            return activeWorkflows.get(0);
        }

        return create(
                workflowName,
                workflowGroup,
                processDate,
                dependsOnWorkflowId);
    }

    @Transactional(readOnly = true)
    public boolean isSuccessfulActiveDailyWorkflow(
            String workflowName,
            String workflowGroup,
            String processDate) {

        List<WorkflowStatus> activeWorkflows =
                workflowStatusRepository
                        .findByNameAndWorkflowGroupAndProcessDateAndIsActiveTrueOrderByInsertTimestampDesc(
                                workflowName,
                                workflowGroup,
                                processDate);

        if (activeWorkflows.size() > 1) {
            throw new IllegalStateException(
                    "Multiple active daily workflows found. name="
                            + workflowName
                            + ", group="
                            + workflowGroup
                            + ", date="
                            + processDate);
        }

        return activeWorkflows.size() == 1
                && activeWorkflows.get(0).getStatus()
                        == Status.SUCCESS;
    }

    private void ensureCanRun(WorkflowStatus workflow) {
        if (!Boolean.TRUE.equals(workflow.getIsActive())) {
            throw new IllegalStateException(
                    "Workflow is inactive: "
                            + workflow.getWorkflowId());
        }

        if (workflow.getStatus() != Status.READY
                && workflow.getStatus() != Status.FAILED) {
            throw new IllegalStateException(
                    "Workflow is not ready to run. workflowId="
                            + workflow.getWorkflowId()
                            + ", status="
                            + workflow.getStatus());
        }

        UUID dependencyId = workflow.getDependsOnWorkflowId();

        if (dependencyId == null) {
            return;
        }

        WorkflowStatus dependency = getByWorkflowId(dependencyId);

        if (dependency.getStatus() != Status.SUCCESS) {
            throw new IllegalStateException(
                    "Workflow dependency has not succeeded. workflowId="
                            + workflow.getWorkflowId()
                            + ", dependencyId="
                            + dependencyId
                            + ", dependencyStatus="
                            + dependency.getStatus());
        }
    }

    private String safeMessage(Throwable error) {
        return error.getMessage() == null
                || error.getMessage().isBlank()
                        ? "Unknown error"
                        : error.getMessage();
    }
}