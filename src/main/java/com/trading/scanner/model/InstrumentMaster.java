package com.trading.scanner.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "instrument_master",
        uniqueConstraints = @UniqueConstraint(columnNames = {"symbol", "exchange"})
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InstrumentMaster {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private String exchange;

    @Column(name = "company_name", nullable = false)
    private String companyName;

    @Column(name = "instrument_type", nullable = false)
    @Builder.Default
    private String instrumentType = "EQUITY";

    @Column(nullable = false)
    @Builder.Default
    private String segment = "CASH";

    @Column(name = "broker_symbol")
    private String brokerSymbol;

    @Column(name = "broker_token")
    private String brokerToken;

    private String isin;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;
}