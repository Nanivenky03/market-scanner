package com.trading.scanner.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "workflow_status")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowStatus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workflow_id", nullable = false, unique = true, updatable = false)
    private UUID workflowId;

    @Column(nullable = false)
    private String name;

    @Column(name = "workflow_group", nullable = false)
    private String workflowGroup;

    @Column(name = "process_date")
    private String processDate;

    @Column(name = "depends_on_workflow_id")
    private UUID dependsOnWorkflowId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Status status = Status.READY;

    @Column(name = "insert_timestamp", nullable = false, updatable = false)
    private LocalDateTime insertTimestamp;

    @Column(name = "update_timestamp", nullable = false)
    private LocalDateTime updateTimestamp;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = Boolean.TRUE;

    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private Integer attemptCount = 0;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(name = "error_details", columnDefinition = "TEXT")
    private String errorDetails;

    @Version
    @Column(nullable = false)
    @Builder.Default
    private Integer version = 0;

    @PrePersist
    protected void onCreate() {
        if (workflowId == null) {
            workflowId = UUID.randomUUID();
        }

        if (status == null) {
            status = Status.READY;
        }

        if (isActive == null) {
            isActive = Boolean.TRUE;
        }

        if (attemptCount == null) {
            attemptCount = 0;
        }

        if (version == null) {
            version = 0;
        }
    }

    public boolean isDependencySatisfied(WorkflowStatus dependency) {
        return dependsOnWorkflowId == null
                || dependency != null
                        && dependency.getStatus() == Status.SUCCESS;
    }

    public enum Status {
        READY,
        RUNNING,
        SUCCESS,
        FAILED,
        SKIPPED
    }
}