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

    // First candle (9:15–9:19 aggregated 5M)
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

    @Column(name = "first_candle_bullish")
    private Boolean firstCandleBullish;

    @Column(name = "first_candle_valid")
    private Boolean firstCandleValid;

    @Column(name = "first_candle_ready", nullable = false)
    private Boolean firstCandleReady;

    // Opening range (9:15–9:29 1M)
    @Column(name = "opening_range_high")
    private Double openingRangeHigh;

    @Column(name = "opening_range_low")
    private Double openingRangeLow;

    @Column(name = "opening_range_size")
    private Double openingRangeSize;

    @Column(name = "opening_range_size_pct")
    private Double openingRangeSizePct;

    @Column(name = "opening_range_volume")
    private Long openingRangeVolume;

    @Column(name = "opening_range_skew")
    private Double openingRangeSkew;

    @Column(name = "opening_range_valid")
    private Boolean openingRangeValid;

    @Column(name = "opening_range_ready", nullable = false)
    private Boolean openingRangeReady;

    // Previous day facts
    @Column(name = "prev_day_high")
    private Double prevDayHigh;

    @Column(name = "prev_day_low")
    private Double prevDayLow;

    @Column(name = "prev_day_open")
    private Double prevDayOpen;

    @Column(name = "prev_day_close")
    private Double prevDayClose;

    @Column(name = "prev_day_range_pct")
    private Double prevDayRangePct;

    @Column(name = "consecutive_red_days")
    private Integer consecutiveRedDays;

    @Column(name = "consecutive_green_days")
    private Integer consecutiveGreenDays;

    @Column(name = "highest_close_15d")
    private Double highestClose15d;

    @Column(name = "dist_from_resistance_pct")
    private Double distFromResistancePct;

    // Today gap
    @Column(name = "gap_pct")
    private Double gapPct;

    // Exclusion flags
    @Column(name = "corporate_action_flag")
    private Boolean corporateActionFlag;

    @Column(name = "fo_ban_flag")
    private Boolean foBanFlag;

    @Column(name = "results_last_3d_flag")
    private Boolean resultsLast3dFlag;

    @Column(name = "skip_today")
    private Boolean skipToday;

    // Breakout reference for today
    @Column(name = "breakout_reference_price")
    private Double breakoutReferencePrice;

    @Column(name = "created_at", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = LocalDateTimeConverter.class)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = LocalDateTimeConverter.class)
    private LocalDateTime updatedAt;
}
