package com.trading.scanner.model;

import com.trading.scanner.config.LocalDateTimeConverter;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "market_candles", uniqueConstraints = @UniqueConstraint(columnNames = { "symbol", "exchange", "timeframe",
                "candle_time" }))
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

        private Double vwap;

        @Column(name = "rsi_14")
        private Double rsi14;

        @Column(name = "atr_14")
        private Double atr14;

        @Column(name = "body_ratio")
        private Double bodyRatio;

        @Column(name = "upper_wick_ratio")
        private Double upperWickRatio;

        @Column(name = "lower_wick_ratio")
        private Double lowerWickRatio;

        @Column(name = "range_pct")
        private Double rangePct;

        @Enumerated(EnumType.STRING)
        private CandleDirection direction;

        private Boolean strongBullish;

        private Boolean strongBearish;

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
        private CandleQualityStatus qualityStatus = CandleQualityStatus.LIVE;

        @Enumerated(EnumType.STRING)
        @Column(name = "processing_status", nullable = false)
        @Builder.Default
        private CandleProcessingStatus processingStatus = CandleProcessingStatus.RELEASED;
}
