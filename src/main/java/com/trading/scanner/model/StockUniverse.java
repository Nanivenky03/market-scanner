package com.trading.scanner.model;

import com.trading.scanner.config.LocalDateConverter;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Entity
@Table(name = "stock_universe", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "symbol", "exchange" })
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockUniverse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Exchange exchange;

    @Column(name = "company_name", nullable = false)
    private String companyName;

    private String sector;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "is_tradable", nullable = false)
    @Builder.Default
    private Boolean isTradable = Boolean.FALSE;

    @Column(name = "active_from", columnDefinition = "TEXT")
    @Convert(converter = LocalDateConverter.class)
    private LocalDate activeFrom;
}
