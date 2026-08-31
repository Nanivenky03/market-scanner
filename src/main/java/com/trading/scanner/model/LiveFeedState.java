package com.trading.scanner.model;

import com.trading.scanner.config.LocalDateConverter;
import com.trading.scanner.config.LocalDateTimeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "live_feed_state", uniqueConstraints = @UniqueConstraint(columnNames = {
        "symbol",
        "exchange",
        "trading_date"
}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LiveFeedState {

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

    @Column(name = "last_tick_time", columnDefinition = "TEXT")
    @Convert(converter = LocalDateTimeConverter.class)
    private LocalDateTime lastTickTime;

    @Column(name = "last_tick_minute", columnDefinition = "TEXT")
    @Convert(converter = LocalDateTimeConverter.class)
    private LocalDateTime lastTickMinute;

    @Column(name = "last_checked_minute", columnDefinition = "TEXT")
    @Convert(converter = LocalDateTimeConverter.class)
    private LocalDateTime lastCheckedMinute;

    @Column(name = "cumulative_volume_today")
    private Long cumulativeVolumeToday;

    @Column(nullable = false)
    @Builder.Default
    private Boolean blocked = false;

    @Column(name = "gap_from", columnDefinition = "TEXT")
    @Convert(converter = LocalDateTimeConverter.class)
    private LocalDateTime gapFrom;

    @Column(name = "gap_to", columnDefinition = "TEXT")
    @Convert(converter = LocalDateTimeConverter.class)
    private LocalDateTime gapTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "health_status", nullable = false)
    @Builder.Default
    private FeedHealthStatus healthStatus = FeedHealthStatus.HEALTHY;

    @Column(name = "subscription_active", nullable = false)
    @Builder.Default
    private Boolean subscriptionActive = false;

    @Column(name = "stale_since", columnDefinition = "TEXT")
    @Convert(converter = LocalDateTimeConverter.class)
    private LocalDateTime staleSince;

    @Column(name = "last_health_transition_at", columnDefinition = "TEXT")
    @Convert(converter = LocalDateTimeConverter.class)
    private LocalDateTime lastHealthTransitionAt;

    @Column(name = "stale_alerted_at", columnDefinition = "TEXT")
    @Convert(converter = LocalDateTimeConverter.class)
    private LocalDateTime staleAlertedAt;

    @Column(name = "recovered_at", columnDefinition = "TEXT")
    @Convert(converter = LocalDateTimeConverter.class)
    private LocalDateTime recoveredAt;

    @Column(name = "consecutive_recovery_ticks", nullable = false)
    @Builder.Default
    private Integer consecutiveRecoveryTicks = 0;

    @Column(name = "updated_at", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = LocalDateTimeConverter.class)
    private LocalDateTime updatedAt;
}
