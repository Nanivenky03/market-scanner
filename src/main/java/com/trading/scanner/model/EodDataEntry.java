package com.trading.scanner.model;

import com.trading.scanner.config.LocalDateConverter;
import com.trading.scanner.config.LocalDateTimeConverter;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "eod_data_entry", uniqueConstraints = @UniqueConstraint(columnNames = { "symbol", "exchange",
        "trading_date" }))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EodDataEntry {

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EodDataStatus status;

    @Column(name = "expected_minute_count")
    private Integer expectedMinuteCount;

    @Column(name = "actual_minute_count")
    private Integer actualMinuteCount;

    @Column(name = "no_trade_minute_count")
    private Integer noTradeMinuteCount;

    @Column(name = "unresolved_minute_count")
    private Integer unresolvedMinuteCount;

    @Column(name = "repaired_minute_count")
    private Integer repairedMinuteCount;

    @Column(name = "reconciled_minute_count")
    private Integer reconciledMinuteCount;

    @Column(nullable = false)
    private String source;

    @Column(name = "pipeline_version", nullable = false)
    private String pipelineVersion;

    @Column(name = "started_at", columnDefinition = "TEXT")
    @Convert(converter = LocalDateTimeConverter.class)
    private LocalDateTime startedAt;

    @Column(name = "completed_at", columnDefinition = "TEXT")
    @Convert(converter = LocalDateTimeConverter.class)
    private LocalDateTime completedAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "updated_at", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = LocalDateTimeConverter.class)
    private LocalDateTime updatedAt;
}
