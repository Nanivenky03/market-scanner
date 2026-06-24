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
@Table(name = "daily_stock_context", uniqueConstraints = @UniqueConstraint(columnNames = { "symbol", "exchange",
        "trading_date" }))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyStockContext {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private String exchange;

    @Column(name = "trading_date", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = LocalDateConverter.class)
    private LocalDate tradingDate;

    @Column(name = "first_candle_open")
    private Double firstCandleOpen;

    @Column(name = "first_candle_high")
    private Double firstCandleHigh;

    @Column(name = "first_candle_low")
    private Double firstCandleLow;

    @Column(name = "first_candle_close")
    private Double firstCandleClose;

    @Column(name = "first_candle_volume")
    private Long firstCandleVolume;

    @Column(name = "first_candle_range")
    private Double firstCandleRange;

    @Column(name = "first_candle_range_pct")
    private Double firstCandleRangePct;

    @Column(name = "first_candle_ready", nullable = false)
    private Boolean firstCandleReady;

    @Column(name = "opening_range_high")
    private Double openingRangeHigh;

    @Column(name = "opening_range_low")
    private Double openingRangeLow;

    @Column(name = "opening_range_size")
    private Double openingRangeSize;

    @Column(name = "opening_range_size_pct")
    private Double openingRangeSizePct;

    @Column(name = "opening_range_ready", nullable = false)
    private Boolean openingRangeReady;

    @Column(name = "created_at", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = LocalDateTimeConverter.class)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = LocalDateTimeConverter.class)
    private LocalDateTime updatedAt;
}