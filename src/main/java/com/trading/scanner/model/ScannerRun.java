package com.trading.scanner.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "scanner_runs")
public class ScannerRun {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "run_date", nullable = false)
    private LocalDate runDate;
    
    @Column(nullable = false)
    private String status;  // SUCCESS, PARTIAL, FAILED
    
    @Column(name = "stocks_scanned")
    private Integer stocksScanned;
    
    @Column(name = "stocks_flagged")
    private Integer stocksFlagged;
    
    @Column(columnDefinition = "TEXT")
    private String errors;  // JSON string
    
    @Column(name = "execution_time_ms")
    private Long executionTimeMs;
    
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
