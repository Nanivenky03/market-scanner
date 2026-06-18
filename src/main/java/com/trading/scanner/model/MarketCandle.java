package com.trading.scanner.model;

import com.trading.scanner.config.LocalDateTimeConverter;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "market_candles",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"symbol", "exchange", "timeframe", "candle_time"}
        )
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketCandle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private String exchange;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CandleTimeframe timeframe;

    @Column(name = "candle_time", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = LocalDateTimeConverter.class)
    private LocalDateTime candleTime;

    @Column(name = "open_price", nullable = false)
    private Double openPrice;

    @Column(name = "high_price", nullable = false)
    private Double highPrice;

    @Column(name = "low_price", nullable = false)
    private Double lowPrice;

    @Column(name = "close_price", nullable = false)
    private Double closePrice;

    private Long volume;

    @Column(name = "open_interest")
    private Long openInterest;

    @Column(nullable = false)
    private String source;

    @Column(name = "created_at", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = LocalDateTimeConverter.class)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = LocalDateTimeConverter.class)
    private LocalDateTime updatedAt;

    @Column(name = "is_finalized", nullable = false)
    @Builder.Default
    private Boolean isFinalized = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "quality_status", nullable = false)
    @Builder.Default
    private CandleQualityStatus qualityStatus = CandleQualityStatus.VALID;
}