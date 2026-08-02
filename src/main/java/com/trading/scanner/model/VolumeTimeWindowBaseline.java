package com.trading.scanner.model;

import com.trading.scanner.config.LocalDateConverter;
import com.trading.scanner.config.LocalDateTimeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "volume_time_window_baseline", uniqueConstraints = @UniqueConstraint(columnNames = { "symbol", "exchange",
        "trading_date", "session_minute" }))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VolumeTimeWindowBaseline {

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

    @Column(name = "session_minute", nullable = false)
    private Integer sessionMinute;

    @Column(name = "avg_cumulative_volume_20", nullable = false)
    private Long avgCumulativeVolume20;

    @Column(name = "sample_days", nullable = false)
    private Integer sampleDays;

    @Column(name = "computed_at", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = LocalDateTimeConverter.class)
    private LocalDateTime computedAt;
}
