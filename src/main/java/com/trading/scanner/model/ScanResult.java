package com.trading.scanner.model;

import com.trading.scanner.config.LocalDateConverter;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDate;

@Entity
@Table(name = "scan_results")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScanResult {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private String symbol;
    
    @Column(name = "scan_date", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = LocalDateConverter.class)
    private LocalDate scanDate;
    
    @Column(name = "rule_name", nullable = false)
    private String ruleName;
    
    private Double confidence;
    
    @Column(name = "scanner_version")
    private String scannerVersion;

    @Column(name = "rule_version")
    private String ruleVersion;

    @Column(name = "parameter_snapshot", columnDefinition = "TEXT")
    private String parameterSnapshot;
    
    @Column(columnDefinition = "TEXT")
    private String metadata;
    
    @Column(name = "forward_return_7d")
    private Double forwardReturn7d;
    
    @Column(name = "forward_return_14d")
    private Double forwardReturn14d;
    
    @Column(name = "forward_return_30d")
    private Double forwardReturn30d;
}
