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
@Table(name = "market_minute_snapshot", uniqueConstraints = @UniqueConstraint(columnNames = { "symbol", "exchange",
        "minute_time" }))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketMinuteSnapshot {

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

    @Column(name = "latest_tick_time", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = LocalDateTimeConverter.class)
    private LocalDateTime latestTickTime;

    @Column(name = "trading_date", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = LocalDateConverter.class)
    private LocalDate tradingDate;

    @Column(name = "broker_token", columnDefinition = "TEXT")
    private String brokerToken;

    @Column(name = "subscription_mode")
    private Integer subscriptionMode;

    @Column(name = "exchange_type")
    private Integer exchangeType;

    @Column(name = "last_price")
    private Double lastPrice;

    @Column(name = "last_traded_quantity")
    private Long lastTradedQuantity;

    @Column(name = "average_traded_price")
    private Double averageTradedPrice;

    @Column(name = "volume_traded_for_day")
    private Long volumeTradedForDay;

    @Column(name = "total_buy_quantity")
    private Long totalBuyQuantity;

    @Column(name = "total_sell_quantity")
    private Long totalSellQuantity;

    @Column(name = "open_interest")
    private Long openInterest;

    @Column(name = "open_interest_change_percent")
    private Double openInterestChangePercent;

    @Column(name = "upper_circuit_limit")
    private Double upperCircuitLimit;

    @Column(name = "lower_circuit_limit")
    private Double lowerCircuitLimit;

    @Column(name = "fifty_two_week_high_price")
    private Double fiftyTwoWeekHighPrice;

    @Column(name = "fifty_two_week_low_price")
    private Double fiftyTwoWeekLowPrice;

    @Column(name = "last_traded_timestamp_epoch")
    private Long lastTradedTimestampEpoch;

    @Column(name = "exchange_timestamp_epoch")
    private Long exchangeTimestampEpoch;

    @Column(name = "sequence_number")
    private Long sequenceNumber;

    @Column(name = "is_finalized", nullable = false)
    private Boolean isFinalized;

    @Column(name = "created_at", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = LocalDateTimeConverter.class)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = LocalDateTimeConverter.class)
    private LocalDateTime updatedAt;
}