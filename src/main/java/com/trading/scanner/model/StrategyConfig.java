package com.trading.scanner.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "strategy_config")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StrategyConfig {

    @Id
    @Column(name = "id")
    private Integer id;

    @Column(name = "strategy_key", nullable = false)
    private String strategyKey;

    @Column(name = "strategy_name", nullable = false)
    private String strategyName;

    @Column(name = "description")
    private String description;

    @Column(name = "priority", nullable = false)
    private Integer priority;

    @Column(name = "execution_mode", nullable = false)
    @Builder.Default
    private String executionMode = "PAPER";

    @Column(name = "allocated_capital", nullable = false)
    @Builder.Default
    private Double allocatedCapital = 50000.0;

    @Column(name = "risk_per_trade_inr", nullable = false)
    @Builder.Default
    private Double riskPerTradeInr = 1000.0;

    @Column(name = "max_daily_trades", nullable = false)
    @Builder.Default
    private Integer maxDailyTrades = 5;

    @Column(name = "max_daily_losses", nullable = false)
    @Builder.Default
    private Integer maxDailyLosses = 3;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

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
