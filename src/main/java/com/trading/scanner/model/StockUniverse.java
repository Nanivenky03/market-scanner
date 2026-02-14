package com.trading.scanner.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "stock_universe",
    uniqueConstraints = {
        @UniqueConstraint(columnNames = {"symbol", "exchange"})
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockUniverse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Exchange exchange;

    @Column(name = "company_name", nullable = false)
    private String companyName;

    private String sector;

    @Column(name = "is_active")
    private Boolean isActive;
}
