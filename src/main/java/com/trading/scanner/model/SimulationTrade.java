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
@Table(name = "simulation_trade")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimulationTrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "variant_id", nullable = false)
    private Integer variantId;

    @Column(nullable = false)
    private String symbol;

    @Column(name = "signal_date", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = LocalDateConverter.class)
    private LocalDate signalDate;

    @Column(name = "entry_date", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = LocalDateConverter.class)
    private LocalDate entryDate;

    @Column(name = "exit_date", columnDefinition = "TEXT")
    @Convert(converter = LocalDateConverter.class)
    private LocalDate exitDate;

    @Column(name = "entry_price", nullable = false)
    private Double entryPrice;

    @Column(name = "exit_price")
    private Double exitPrice;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "initial_stop_loss")
    private Double initialStopLoss;

    @Column(name = "final_stop_loss")
    private Double finalStopLoss;

    @Column(name = "initial_target")
    private Double initialTarget;

    @Column(name = "final_target")
    private Double finalTarget;

    @Column(name = "day_close_price")
    private Double dayClosePrice;

    private Double confidence;

    @Column(name = "gross_pnl")
    private Double grossPnl;

    @Column(name = "net_pnl")
    private Double netPnl;

    private Double fees;

    @Enumerated(EnumType.STRING)
    @Column(name = "trade_status", nullable = false)
    @Builder.Default
    private SimulationTradeStatus tradeStatus = SimulationTradeStatus.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(name = "trade_result", nullable = false)
    @Builder.Default
    private SimulationTradeResult tradeResult = SimulationTradeResult.UNKNOWN;

    @Column(name = "exit_reason")
    private String exitReason;

    @Column(name = "entry_context", columnDefinition = "TEXT")
    private String entryContext;

    @Column(name = "exit_context", columnDefinition = "TEXT")
    private String exitContext;

    @Column(name = "evaluation_context", columnDefinition = "TEXT")
    private String evaluationContext;

    @Column(name = "created_at", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = LocalDateTimeConverter.class)
    private LocalDateTime createdAt;
}