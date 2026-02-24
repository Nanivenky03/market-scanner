package com.trading.scanner.service.indicators.parameters;

/**
 * An immutable, pure data carrier for indicator calculation window sizes.
 * This object is constructed by the ScannerEngine and passed to the IndicatorService
 * to ensure that indicator calculations are explicitly parameterized and deterministic.
 *
 * @param rsiPeriod The lookback period for RSI calculations.
 * @param smaShortPeriod The lookback period for the short-term SMA.
 * @param smaMediumPeriod The lookback period for the medium-term SMA.
 * @param smaLongPeriod The lookback period for the long-term SMA.
 */
public record IndicatorParameters(
    int rsiPeriod,
    int smaShortPeriod,
    int smaMediumPeriod,
    int smaLongPeriod
) {}
