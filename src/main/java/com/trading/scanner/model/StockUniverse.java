package com.trading.scanner.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "stock_universe")
public class StockUniverse {
    
    @Id
    @Column(name = "symbol")
    private String symbol;
    
    @Column(name = "company_name", nullable = false)
    private String companyName;
    
    @Column(name = "index_name", nullable = false)
    private String indexName;
    
    @Column
    private String sector;
    
    @Column(name = "is_active")
    private Boolean isActive;
    
    @Column(name = "added_at")
    private LocalDateTime addedAt;
    
    @PrePersist
    protected void onCreate() {
        if (addedAt == null) {
            addedAt = LocalDateTime.now();
        }
        if (isActive == null) {
            isActive = true;
        }
    }
}
