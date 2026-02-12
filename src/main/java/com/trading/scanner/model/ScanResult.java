package com.trading.scanner.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Scan Result Entity
 * 
 * Stores daily classification snapshots (not mutable buckets).
 * Each scan creates new records - enabling behavioral history analysis.
 * 
 * Forward return fields added to schema NOW (Phase 1)
 * but will be computed LATER (Phase 2) via async process.
 * 
 * Scanner version field enables tracking rule evolution over time.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "scan_results")
public class ScanResult {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "scan_date", nullable = false)
    private LocalDate scanDate;
    
    @Column(nullable = false)
    private String symbol;
    
    @Column(nullable = false)
    private String classification;
    
    @Column
    private String confidence;
    
    /**
     * Structured metadata (JSON string)
     * Contains context: volume_ratio, compression_days, breakout_level, etc.
     * Critical for discovering trading edges later.
     */
    @Column(columnDefinition = "TEXT")
    private String metadata;
    
    /**
     * Scanner version that generated this signal.
     * Institutional standard - enables tracking rule evolution.
     * Format: "1.0.0", "1.1.0", etc.
     */
    @Column(name = "scanner_version")
    private String scannerVersion;
    
    /**
     * Forward return fields (Phase 2 - outcome tracking)
     * Schema added NOW, computation deferred.
     * Will be populated asynchronously after signal is generated.
     */
    @Column(name = "forward_return_7d")
    private Double forwardReturn7d;
    
    @Column(name = "forward_return_14d")
    private Double forwardReturn14d;
    
    @Column(name = "forward_return_30d")
    private Double forwardReturn30d;
    
    /**
     * Timestamp when forward returns were last computed
     */
    @Column(name = "outcome_updated_at")
    private LocalDateTime outcomeUpdatedAt;
    
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
