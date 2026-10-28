package com.trading.scanner.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "signals")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Signal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "strategy_id", nullable = false)
    private Integer strategyId;

    @Column(name = "symbol", nullable = false)
    private String symbol;

    @Column(name = "exchange", nullable = false)
    private String exchange;

    @Column(name = "timeframe", nullable = false)
    private String timeframe;

    @Column(name = "signal_type", nullable = false)
    private String signalType;

    @Column(name = "signal_price", nullable = false)
    private Double signalPrice;

    @Column(name = "initial_stop_loss")
    private Double initialStopLoss;

    @Column(name = "updated_stop_loss")
    private Double updatedStopLoss;

    @Column(name = "initial_target")
    private Double initialTarget;

    @Column(name = "updated_target")
    private Double updatedTarget;

    @Column(name = "signal_time", nullable = false)
    private String signalTime;

    @Column(name = "trigger_candle_time", nullable = false)
    private String triggerCandleTime;

    @Column(name = "rule_context", columnDefinition = "TEXT")
    private String ruleContext;

    @Column(name = "status", nullable = false)
    private String status;

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
