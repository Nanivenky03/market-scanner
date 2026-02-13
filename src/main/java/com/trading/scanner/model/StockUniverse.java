package com.trading.scanner.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "stock_universe")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockUniverse {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(unique = true, nullable = false)
    private String symbol;
    
    @Column(name = "company_name", nullable = false)
    private String companyName;
    
    private String sector;
    
    @Column(name = "is_active")
    private Boolean isActive;
}
