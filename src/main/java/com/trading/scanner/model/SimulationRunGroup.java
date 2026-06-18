package com.trading.scanner.model;

import com.trading.scanner.config.LocalDateConverter;
import com.trading.scanner.config.LocalDateTimeConverter;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "simulation_run_group")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimulationRunGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(name = "from_date", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = LocalDateConverter.class)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = LocalDateConverter.class)
    private LocalDate toDate;

    @Column(name = "created_at", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = LocalDateTimeConverter.class)
    private LocalDateTime createdAt;

    @Column(name = "completed_at", columnDefinition = "TEXT")
    @Convert(converter = LocalDateTimeConverter.class)
    private LocalDateTime completedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SimulationStatus status;

    private String notes;

    @Column(name = "created_by")
    private String createdBy;
}