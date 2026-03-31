package com.trading.scanner.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;

/**
 * Signal Outcome Entity - Immutable forward return measurements
 * 
 * Append-only measurement layer for deterministic forward return calculation
 */
@Entity
@Table(name = "signal_outcomes", 
       uniqueConstraints = @UniqueConstraint(columnNames = {"signal_id", "horizon_days"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Immutable
public class SignalOutcome {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    
    @Column(name = "signal_id", nullable = false)
    private Integer signalId;
    
    @Column(name = "horizon_days", nullable = false)
    private Integer horizonDays;
    
    @Column(name = "entry_price", nullable = false)
    private Double entryPrice;
    
    @Column(name = "exit_price", nullable = false)
    private Double exitPrice;
    
    @Column(name = "forward_return", nullable = false)
    private Double forwardReturn;
    
    @Column(name = "mfe")
    private Double mfe;
    
    @Column(name = "mae")
    private Double mae;
    
    @Column(name = "computed_at", nullable = false)
    private LocalDateTime computedAt;
}
