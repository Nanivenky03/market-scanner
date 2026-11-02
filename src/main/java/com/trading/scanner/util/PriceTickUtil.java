package com.trading.scanner.util;

/**
 * Utility for exchange tick-size validation and price rounding.
 * In Indian equity markets (NSE/BSE), all orders and trigger prices must be
 * exact multiples of the instrument's tick size (typically ₹0.05).
 */
public final class PriceTickUtil {

    public static final double DEFAULT_TICK_SIZE = 0.05;

    private PriceTickUtil() {
    }

    public static double roundToTick(double price, double tickSize) {
        double tick = tickSize > 0.0 ? tickSize : DEFAULT_TICK_SIZE;
        double rounded = Math.round(price / tick) * tick;
        return roundTo2Decimals(rounded);
    }

    public static double floorToTick(double price, double tickSize) {
        double tick = tickSize > 0.0 ? tickSize : DEFAULT_TICK_SIZE;
        double floored = Math.floor(price / tick) * tick;
        return roundTo2Decimals(floored);
    }

    public static double ceilToTick(double price, double tickSize) {
        double tick = tickSize > 0.0 ? tickSize : DEFAULT_TICK_SIZE;
        double ceiled = Math.ceil(price / tick) * tick;
        return roundTo2Decimals(ceiled);
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
        return Math.round(value * 100.0) / 100.0;
    }
}
