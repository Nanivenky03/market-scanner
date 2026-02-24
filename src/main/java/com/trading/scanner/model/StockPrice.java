package com.trading.scanner.model;

import com.trading.scanner.config.LocalDateConverter;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDate;

@Entity
@Table(name = "stock_prices",
       uniqueConstraints = @UniqueConstraint(columnNames = {"symbol", "date"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockPrice {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    
    @Column(nullable = false)
    private String symbol;
    
    @Column(nullable = false, columnDefinition = "TEXT")
    @Convert(converter = LocalDateConverter.class)
    private LocalDate date;
    
    @Column(name = "open_price")
    private Double openPrice;
    
    @Column(name = "high_price")
    private Double highPrice;
    
    @Column(name = "low_price")
    private Double lowPrice;
    
    @Column(name = "close_price")
    private Double closePrice;
    
    @Column(name = "adj_close")
    private Double adjClose;
    
    private Integer volume;
}
