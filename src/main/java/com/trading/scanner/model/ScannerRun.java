package com.trading.scanner.model;

import com.trading.scanner.config.LocalDateConverter;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDate;

@Entity
@Table(name = "scanner_runs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScannerRun {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "run_date", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = LocalDateConverter.class)
    private LocalDate runDate;
    
    @Column(name = "stocks_scanned")
    private Integer stocksScanned;
    
    @Column(name = "stocks_flagged")
    private Integer stocksFlagged;
    
    private String status;
    
    @Column(name = "error_message")
    private String errorMessage;
}
