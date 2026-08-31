package com.trading.scanner.model;

import com.trading.scanner.config.LocalDateConverter;
import com.trading.scanner.config.LocalDateTimeConverter;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "live_minute_resolution", uniqueConstraints = @UniqueConstraint(columnNames = { "symbol", "exchange",
        "minute_time" }))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LiveMinuteResolution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private String exchange;

    @Column(name = "minute_time", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = LocalDateTimeConverter.class)
    private LocalDateTime minuteTime;

    @Column(name = "trading_date", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = LocalDateConverter.class)
    private LocalDate tradingDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MinuteResolutionStatus status;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(name = "resolved_at", columnDefinition = "TEXT")
    @Convert(converter = LocalDateTimeConverter.class)
    private LocalDateTime resolvedAt;

    @Column(name = "created_at", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = LocalDateTimeConverter.class)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = LocalDateTimeConverter.class)
    private LocalDateTime updatedAt;
}
