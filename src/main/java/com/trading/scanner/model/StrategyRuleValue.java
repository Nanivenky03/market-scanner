package com.trading.scanner.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "strategy_rule_value", uniqueConstraints = @UniqueConstraint(name = "uq_strategy_rule", columnNames = {
        "strategy_id", "rule_key" }))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StrategyRuleValue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "strategy_id", nullable = false)
    private Integer strategyId;

    @Column(name = "rule_key", nullable = false)
    private String ruleKey;

    @Column(name = "rule_value", nullable = false)
    private String ruleValue;

    @Column(name = "value_type", nullable = false)
    private String valueType;

    @Column(name = "description")
    private String description;

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
