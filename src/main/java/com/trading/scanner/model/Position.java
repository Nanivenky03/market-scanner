package com.trading.scanner.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "positions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Position {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "signal_id")
    private Integer signalId;

    @Column(name = "strategy_id", nullable = false)
    private Integer strategyId;

    @Column(name = "symbol", nullable = false)
    private String symbol;

    @Column(name = "exchange", nullable = false)
    private String exchange;

    @Column(name = "product_type", nullable = false)
    private String productType;

    @Column(name = "position_side", nullable = false)
    private String positionSide;

    @Column(name = "initial_quantity", nullable = false)
    private Integer initialQuantity;

    @Column(name = "current_quantity", nullable = false)
    private Integer currentQuantity;

    @Column(name = "entry_price", nullable = false)
    private Double entryPrice;

    @Column(name = "exit_price")
    private Double exitPrice;

    @Column(name = "initial_stop_loss")
    private Double initialStopLoss;

    @Column(name = "updated_stop_loss")
    private Double updatedStopLoss;

    @Column(name = "initial_target")
    private Double initialTarget;

    @Column(name = "updated_target")
    private Double updatedTarget;

    @Column(name = "pnl")
    private Double pnl;

    @Column(name = "pnl_percent")
    private Double pnlPercent;

    @Column(name = "position_status", nullable = false)
    private String positionStatus;

    @Column(name = "entry_time", nullable = false)
    private String entryTime;

    @Column(name = "exit_time")
    private String exitTime;

    @Column(name = "exit_reason")
    private String exitReason;

    @Column(name = "created_at", nullable = false)
    private String createdAt;

    @Column(name = "updated_at", nullable = false)
    private String updatedAt;

    @PrePersist
    public void prePersist() {
        String now = java.time.LocalDateTime.now().toString();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = java.time.LocalDateTime.now().toString();
    }
}
