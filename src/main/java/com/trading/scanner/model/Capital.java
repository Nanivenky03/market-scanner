package com.trading.scanner.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "capital")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Capital {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "trading_date", nullable = false, unique = true)
    private String tradingDate;

    @Column(name = "portfolio_mode", nullable = false)
    private String portfolioMode;

    @Column(name = "opening_capital", nullable = false)
    private Double openingCapital;

    @Column(name = "total_trades", nullable = false)
    private Integer totalTrades;

    @Column(name = "realized_gross_pnl", nullable = false)
    private Double realizedGrossPnl;

    @Column(name = "statutory_charges", nullable = false)
    private Double statutoryCharges;

    @Column(name = "statutory_taxes", nullable = false)
    private Double statutoryTaxes;

    @Column(name = "available_capital", nullable = false)
    private Double availableCapital;

    @Column(name = "net_pnl", nullable = false)
    private Double netPnl;

    @Column(name = "closing_capital")
    private Double closingCapital;

    @Column(name = "created_at", nullable = false)
    private String createdAt;

    @Column(name = "updated_at", nullable = false)
    private String updatedAt;
}
