package com.trading.scanner.repository;

import com.trading.scanner.model.WorkflowStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowStatusRepository
        extends JpaRepository<WorkflowStatus, Long> {

    Optional<WorkflowStatus> findByWorkflowId(UUID workflowId);

    // Queries for startup workflows (process_date IS NULL)
    List<WorkflowStatus> findByNameAndWorkflowGroupAndProcessDateIsNullAndIsActiveTrueOrderByInsertTimestampDesc(
            String name,
            String workflowGroup);

    List<WorkflowStatus> findByWorkflowGroupAndProcessDateIsNullAndIsActiveTrueOrderByInsertTimestampAsc(
            String workflowGroup);

    // Queries for dated workflows (daily market workflows)
    List<WorkflowStatus> findByNameAndWorkflowGroupAndProcessDateAndIsActiveTrueOrderByInsertTimestampDesc(
            String name,
            String workflowGroup,
            String processDate);

    List<WorkflowStatus> findByWorkflowGroupAndProcessDateAndIsActiveTrueOrderByInsertTimestampAsc(
            String workflowGroup,
            String processDate);

    // General queries
    List<WorkflowStatus> findByNameAndWorkflowGroupAndIsActiveTrueOrderByInsertTimestampDesc(
            String name,
            String workflowGroup);

    List<WorkflowStatus> findByWorkflowGroupAndIsActiveTrueOrderByInsertTimestampAsc(
            String workflowGroup);
}