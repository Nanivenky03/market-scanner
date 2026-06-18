package com.trading.scanner.model;

import com.trading.scanner.config.LocalDateTimeConverter;
import com.trading.scanner.strategy.StrategyStatus;
import com.trading.scanner.strategy.StrategyTimeframe;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "simulation_variant",
        uniqueConstraints = @UniqueConstraint(columnNames = {"run_group_id", "variant_key"})
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimulationVariant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "run_group_id", nullable = false)
    private Integer runGroupId;

    @Column(name = "strategy_id", nullable = false)
    private String strategyId;

    @Column(name = "strategy_version", nullable = false)
    private String strategyVersion;

    @Column(name = "strategy_display_name", nullable = false)
    private String strategyDisplayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "strategy_status", nullable = false)
    private StrategyStatus strategyStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StrategyTimeframe timeframe;

    @Column(name = "variant_key", nullable = false)
    private String variantKey;

    @Column(name = "variant_name", nullable = false)
    private String variantName;

    @Column(name = "simulation_enabled", nullable = false)
    @Builder.Default
    private Boolean simulationEnabled = true;

    @Column(name = "live_enabled", nullable = false)
    @Builder.Default
    private Boolean liveEnabled = false;

    @Column(name = "capital_per_trade", nullable = false)
    @Builder.Default
    private Double capitalPerTrade = 10000.0;

    @Column(name = "slippage_bps", nullable = false)
    @Builder.Default
    private Integer slippageBps = 0;

    @Column(name = "charge_per_trade", nullable = false)
    @Builder.Default
    private Double chargePerTrade = 0.0;

    @Column(name = "min_trading_days", nullable = false)
    @Builder.Default
    private Integer minTradingDays = 20;

    @Column(name = "min_trade_count", nullable = false)
    @Builder.Default
    private Integer minTradeCount = 10;

    @Column(name = "created_at", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = LocalDateTimeConverter.class)
    private LocalDateTime createdAt;

    @Column(name = "completed_at", columnDefinition = "TEXT")
    @Convert(converter = LocalDateTimeConverter.class)
    private LocalDateTime completedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SimulationStatus status;

    @Column(name = "total_trades", nullable = false)
    @Builder.Default
    private Integer totalTrades = 0;

    @Column(name = "winning_trades", nullable = false)
    @Builder.Default
    private Integer winningTrades = 0;

    @Column(name = "losing_trades", nullable = false)
    @Builder.Default
    private Integer losingTrades = 0;

    @Column(name = "gross_pnl", nullable = false)
    @Builder.Default
    private Double grossPnl = 0.0;

    @Column(name = "net_pnl", nullable = false)
    @Builder.Default
    private Double netPnl = 0.0;

    private String notes;
}