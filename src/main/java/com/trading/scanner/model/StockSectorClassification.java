package com.trading.scanner.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "stock_sector_classification", indexes = {
        @Index(name = "idx_stock_sector_symbol", columnList = "symbol"),
        @Index(name = "idx_stock_sector_index", columnList = "sector_index_symbol")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockSectorClassification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "symbol", nullable = false, unique = true)
    private String symbol;

    @Column(name = "company_name")
    private String companyName;

    @Column(name = "macro_sector", nullable = false)
    private String macroSector;

    @Column(name = "sector_index_symbol", nullable = false)
    private String sectorIndexSymbol;

    @Column(name = "industry")
    private String industry;

    @Column(name = "isin")
    private String isin;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "source", nullable = false)
    private String source;

    @Column(name = "created_at", nullable = false)
    private String createdAt;

    @Column(name = "updated_at", nullable = false)
    private String updatedAt;

    @PrePersist
    public void onPrePersist() {
        String now = LocalDateTime.now().toString();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
        if (this.isActive == null) {
            this.isActive = true;
        }
    }

    @PreUpdate
    public void onPreUpdate() {
        this.updatedAt = LocalDateTime.now().toString();
    }
}
