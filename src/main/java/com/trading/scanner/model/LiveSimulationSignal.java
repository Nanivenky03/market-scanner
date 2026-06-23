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
@Table(name = "live_simulation_signal", uniqueConstraints = @UniqueConstraint(columnNames = { "strategy_id", "symbol",
        "timeframe", "candle_time" }))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LiveSimulationSignal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "strategy_id", nullable = false)
    private String strategyId;

    @Column(name = "strategy_version", nullable = false)
    private String strategyVersion;

    @Column(nullable = false)
    private String timeframe;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private String exchange;

    @Column(name = "candle_time", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = LocalDateTimeConverter.class)
    private LocalDateTime candleTime;

    @Column(name = "signal_date", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = LocalDateConverter.class)
    private LocalDate signalDate;

    @Column(name = "lifecycle_status", nullable = false)
    private String lifecycleStatus;

    @Column(name = "confirmation_required", nullable = false)
    private Boolean confirmationRequired;

    @Column(nullable = false)
    private Double score;

    @Column(name = "close_price", nullable = false)
    private Double closePrice;

    @Column(name = "previous_day_high", nullable = false)
    private Double previousDayHigh;

    @Column(name = "breakout_percent", nullable = false)
    private Double breakoutPercent;

    @Column(name = "volume_ratio", nullable = false)
    private Double volumeRatio;

    @Column(nullable = false)
    private Double rsi;

    @Column(nullable = false)
    private Double vwap;

    @Column(name = "close_strength", nullable = false)
    private Double closeStrength;

    @Column(name = "signal_context", columnDefinition = "TEXT")
    private String signalContext;

    @Column(name = "decision_candle_time", columnDefinition = "TEXT")
    @Convert(converter = LocalDateTimeConverter.class)
    private LocalDateTime decisionCandleTime;

    @Column(name = "confirmation_decision")
    private String confirmationDecision;

    @Column(name = "confirmation_context", columnDefinition = "TEXT")
    private String confirmationContext;

    @Column(name = "created_at", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = LocalDateTimeConverter.class)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = LocalDateTimeConverter.class)
    private LocalDateTime updatedAt;
}