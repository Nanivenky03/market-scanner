package com.trading.scanner.model;

import com.trading.scanner.config.LocalDateConverter;
import com.trading.scanner.config.LocalDateTimeConverter;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "daily_candle_summary", uniqueConstraints = @UniqueConstraint(columnNames = { "symbol", "exchange",
        "trading_date" }))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyCandleSummary {

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

    @Column(name = "expected_count", nullable = false)
    private Integer expectedCount;

    @Column(name = "actual_count", nullable = false)
    private Integer actualCount;

    @Column(name = "missing_count", nullable = false)
    private Integer missingCount;

    @Column(name = "no_trade_count", nullable = false)
    @Builder.Default
    private Integer noTradeCount = 0;

    @Column(name = "duplicate_detected", nullable = false)
    private Boolean duplicateDetected;

    @Column(name = "unfinalized_detected", nullable = false)
    private Boolean unfinalizedDetected;

    @Column(name = "live_count", nullable = false)
    @Builder.Default
    private Integer liveCount = 0;

    @Column(name = "repaired_count", nullable = false)
    @Builder.Default
    private Integer repairedCount = 0;

    @Column(name = "reconciled_count", nullable = false)
    @Builder.Default
    private Integer reconciledCount = 0;

    @Column(name = "suspect_count", nullable = false)
    @Builder.Default
    private Integer suspectCount = 0;

    @Column(name = "data_status", nullable = false)
    @Enumerated(EnumType.STRING)
    private DataStatus dataStatus;

    @Column(name = "updated_at", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = LocalDateTimeConverter.class)
    private LocalDateTime updatedAt;
}
