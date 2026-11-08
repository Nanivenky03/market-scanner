package com.trading.scanner.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Utility for exchange tick-size validation and price rounding.
 * In Indian equity markets (NSE/BSE), all orders and trigger prices must be
 * exact multiples of the instrument's tick size (typically ₹0.05).
 *
 * Implemented using exact BigDecimal arithmetic to eliminate IEEE-754 binary floating-point
 * truncation errors.
 */
public final class PriceTickUtil {

    public static final double DEFAULT_TICK_SIZE = 0.05;

    private PriceTickUtil() {
    }

    public static double roundToTick(double price, double tickSize) {
        double tick = tickSize > 0.0 ? tickSize : DEFAULT_TICK_SIZE;
        BigDecimal p = BigDecimal.valueOf(price);
        BigDecimal t = BigDecimal.valueOf(tick);
        BigDecimal rounded = p.divide(t, 0, RoundingMode.HALF_UP).multiply(t);
        return roundTo2Decimals(rounded.doubleValue());
    }

    public static double floorToTick(double price, double tickSize) {
        double tick = tickSize > 0.0 ? tickSize : DEFAULT_TICK_SIZE;
        BigDecimal p = BigDecimal.valueOf(price);
        BigDecimal t = BigDecimal.valueOf(tick);
        BigDecimal floored = p.divide(t, 0, RoundingMode.FLOOR).multiply(t);
        return roundTo2Decimals(floored.doubleValue());
    }

    public static double ceilToTick(double price, double tickSize) {
        double tick = tickSize > 0.0 ? tickSize : DEFAULT_TICK_SIZE;
        BigDecimal p = BigDecimal.valueOf(price);
        BigDecimal t = BigDecimal.valueOf(tick);
        BigDecimal ceiled = p.divide(t, 0, RoundingMode.CEILING).multiply(t);
        return roundTo2Decimals(ceiled.doubleValue());
    }

    /**
     * Directional rounding for Stop Loss:
     * - Long Position: Floor to tick (lower is more protective / conservative)
     * - Short Position: Ceil to tick (higher is more protective / conservative)
     */
    public static double roundStopLoss(double price, boolean isShort, double tickSize) {
        return isShort ? ceilToTick(price, tickSize) : floorToTick(price, tickSize);
    }

    /**
     * Directional rounding for Profit Target:
     * - Long Position: Ceil to tick
     * - Short Position: Floor to tick
     */
    public static double roundTarget(double price, boolean isShort, double tickSize) {
        return isShort ? floorToTick(price, tickSize) : ceilToTick(price, tickSize);
    }

    public static double roundTo2Decimals(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
