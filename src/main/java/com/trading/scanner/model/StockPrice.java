package com.trading.scanner.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "stock_prices", 
       uniqueConstraints = @UniqueConstraint(columnNames = {"symbol", "date"}))
public class StockPrice {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private String symbol;
    
    @Column(nullable = false)
    private LocalDate date;
    
    @Column(nullable = false)
    private Double open;
    
    @Column(nullable = false)
    private Double high;
    
    @Column(nullable = false)
    private Double low;
    
    @Column(nullable = false)
    private Double close;
    
    @Column(nullable = false)
    private Long volume;
    
    @Column(name = "adj_close", nullable = false)
    private Double adjClose;
    
    @Column(name = "data_source")
    private String dataSource;
    
    @Column(name = "ingested_at")
    private LocalDateTime ingestedAt;
    
    @PrePersist
    protected void onCreate() {
        if (ingestedAt == null) {
            ingestedAt = LocalDateTime.now();
        }
        if (dataSource == null) {
            dataSource = "yahoo";
        }
    }
}
