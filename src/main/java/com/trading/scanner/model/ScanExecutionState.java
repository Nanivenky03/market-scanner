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
@Table(name = "scan_execution_state", 
       uniqueConstraints = @UniqueConstraint(columnNames = "trading_date"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScanExecutionState {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    
    @Column(name = "trading_date", nullable = false, unique = true, columnDefinition = "TEXT")
    @Convert(converter = LocalDateConverter.class)
    private LocalDate tradingDate;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "ingestion_status", nullable = false)
    private ExecutionStatus ingestionStatus;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "scan_status", nullable = false)
    private ExecutionStatus scanStatus;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "data_source_status")
    private DataSourceStatus dataSourceStatus;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "execution_mode")
    private ExecutionMode executionMode;
    
    @Column(name = "last_ingestion_time", columnDefinition = "TEXT")
    @Convert(converter = LocalDateTimeConverter.class)
    private LocalDateTime lastIngestionTime;
    
    @Column(name = "last_scan_time", columnDefinition = "TEXT")
    @Convert(converter = LocalDateTimeConverter.class)
    private LocalDateTime lastScanTime;
    
    @Column(name = "stocks_ingested")
    private Integer stocksIngested;
    
    @Column(name = "signals_generated")
    private Integer signalsGenerated;
    
    @Column(name = "error_message", length = 1000)
    private String errorMessage;
    
    public enum ExecutionStatus {
        PENDING, IN_PROGRESS, SUCCESS, SUCCESS_NO_DATA, FAILED, SKIPPED
    }
    
    public enum DataSourceStatus {
        HEALTHY, NO_DATA, DEGRADED, UNAVAILABLE, UNKNOWN
    }
    
    public enum ExecutionMode {
        MANUAL, SCHEDULED, API
    }
    
    public boolean isIngestionComplete() {
        return ingestionStatus == ExecutionStatus.SUCCESS || 
               ingestionStatus == ExecutionStatus.SUCCESS_NO_DATA;
    }
    
    public boolean isScanComplete() {
        return scanStatus == ExecutionStatus.SUCCESS || 
               scanStatus == ExecutionStatus.FAILED ||
               scanStatus == ExecutionStatus.SKIPPED;
    }
    
    public boolean hasData() {
        return ingestionStatus == ExecutionStatus.SUCCESS && 
               stocksIngested != null && 
               stocksIngested > 0;
    }
    
    public void startIngestion(ExecutionMode mode, LocalDateTime timestamp) {
        this.ingestionStatus = ExecutionStatus.IN_PROGRESS;
        this.executionMode = mode;
        this.lastIngestionTime = timestamp;
    }
    
    public void completeIngestion(int stocksIngested, DataSourceStatus sourceStatus, LocalDateTime timestamp) {
        this.ingestionStatus = ExecutionStatus.SUCCESS;
        this.stocksIngested = stocksIngested;
        this.dataSourceStatus = sourceStatus;
        this.lastIngestionTime = timestamp;
    }
    
    public void completeIngestionNoData(DataSourceStatus sourceStatus, LocalDateTime timestamp) {
        this.ingestionStatus = ExecutionStatus.SUCCESS_NO_DATA;
        this.stocksIngested = 0;
        this.dataSourceStatus = sourceStatus;
        this.lastIngestionTime = timestamp;
    }
    
    public void failIngestion(String errorMessage, DataSourceStatus sourceStatus, LocalDateTime timestamp) {
        this.ingestionStatus = ExecutionStatus.FAILED;
        this.errorMessage = errorMessage;
        this.dataSourceStatus = sourceStatus;
        this.lastIngestionTime = timestamp;
    }
    
    public void startScan(LocalDateTime timestamp) {
        this.scanStatus = ExecutionStatus.IN_PROGRESS;
        this.lastScanTime = timestamp;
    }
    
    public void completeScan(int signalsGenerated, LocalDateTime timestamp) {
        this.scanStatus = ExecutionStatus.SUCCESS;
        this.signalsGenerated = signalsGenerated;
        this.lastScanTime = timestamp;
    }
    
    public void skipScan(String reason, LocalDateTime timestamp) {
        this.scanStatus = ExecutionStatus.SKIPPED;
        this.errorMessage = reason;
        this.lastScanTime = timestamp;
    }
    
    public void failScan(String errorMessage, LocalDateTime timestamp) {
        this.scanStatus = ExecutionStatus.FAILED;
        this.errorMessage = errorMessage;
        this.lastScanTime = timestamp;
    }
}
