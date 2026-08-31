package com.trading.scanner.service.engine;

import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.model.VolumeDirection;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class IntradayIndicatorService {

    private static final int RSI_PERIOD = 14;
    private static final int RSI_MINIMUM_CANDLES = 42;

    private static final int ATR_PERIOD = 14;
    private static final int ATR_MINIMUM_CANDLES = ATR_PERIOD;

    public Double calculateSessionVwap(
            List<MarketCandle> oneMinuteSessionCandles) {

        if (oneMinuteSessionCandles == null
                || oneMinuteSessionCandles.isEmpty()) {
            return null;
        }

        LocalDate sessionDate = null;
        double cumulativeTypicalPriceVolume = 0.0;
        long cumulativeVolume = 0L;

        for (MarketCandle candle : oneMinuteSessionCandles) {
            if (candle == null
                    || candle.getCandleTime() == null
                    || !hasValidPrices(candle)) {
                return null;
            }

            LocalDate candleDate = candle.getCandleTime().toLocalDate();

            if (sessionDate == null) {
                sessionDate = candleDate;
            } else if (!sessionDate.equals(candleDate)) {
                return null;
            }

            Long volume = candle.getVolume();

            if (volume == null || volume <= 0L) {
                continue;
            }

            cumulativeTypicalPriceVolume += typicalPrice(candle) * volume;

            cumulativeVolume += volume;
        }

        if (cumulativeVolume <= 0L) {
            return null;
        }

        return cumulativeTypicalPriceVolume
                / cumulativeVolume;
    }

    public Double calculateRsi14Wilder(
            List<MarketCandle> candles) {

        if (candles == null
                || candles.size() < RSI_MINIMUM_CANDLES
                || !hasValidCloseSeries(candles)) {
            return null;
        }

        double averageGain = 0.0;
        double averageLoss = 0.0;

        for (int i = 1; i <= RSI_PERIOD; i++) {
            double change = candles.get(i).getClosePrice()
                    - candles.get(i - 1).getClosePrice();

            if (change > 0.0) {
                averageGain += change;
            } else {
                averageLoss += -change;
            }
        }

        averageGain /= RSI_PERIOD;
        averageLoss /= RSI_PERIOD;

        for (int i = RSI_PERIOD + 1; i < candles.size(); i++) {

            double change = candles.get(i).getClosePrice()
                    - candles.get(i - 1).getClosePrice();

            double gain = change > 0.0 ? change : 0.0;

            double loss = change < 0.0 ? -change : 0.0;

            averageGain = ((averageGain * (RSI_PERIOD - 1))
                    + gain)
                    / RSI_PERIOD;

            averageLoss = ((averageLoss * (RSI_PERIOD - 1))
                    + loss)
                    / RSI_PERIOD;
        }

        if (averageLoss == 0.0) {
            return averageGain == 0.0
                    ? 50.0
                    : 100.0;
        }

        if (averageGain == 0.0) {
            return 0.0;
        }

        double relativeStrength = averageGain / averageLoss;

        return 100.0
                - (100.0
                        / (1.0 + relativeStrength));
    }

    /**
     * Calculates ATR using the previous trading day's
     * authoritative daily close for the first candle.
     *
     * The supplied candle list must represent one timeframe
     * from one trading day in chronological order.
     */
    public Double calculateAtr14Wilder(
            List<MarketCandle> candles,
            Double previousTradingDayClose) {

        if (candles == null
                || candles.size() < ATR_MINIMUM_CANDLES
                || !isFinite(previousTradingDayClose)
                || !hasValidAtrSeries(candles)) {
            return null;
        }

        LocalDate sessionDate = candles.get(0)
                .getCandleTime()
                .toLocalDate();

        LocalDateTime previousTime = null;

        for (MarketCandle candle : candles) {
            LocalDateTime candleTime = candle.getCandleTime();

            if (!sessionDate.equals(
                    candleTime.toLocalDate())) {
                return null;
            }

            if (previousTime != null
                    && candleTime.isBefore(previousTime)) {
                return null;
            }

            previousTime = candleTime;
        }

        double atr = 0.0;
        double previousClose = previousTradingDayClose;

        /*
         * The first 14 true ranges include:
         * - candle 0 versus previous daily close;
         * - candles 1 through 13 versus the prior intraday close.
         */
        for (int i = 0; i < ATR_PERIOD; i++) {

            MarketCandle current = candles.get(i);

            atr += trueRange(
                    current,
                    previousClose);

            previousClose = current.getClosePrice();
        }

        atr /= ATR_PERIOD;

        for (int i = ATR_PERIOD; i < candles.size(); i++) {

            MarketCandle current = candles.get(i);

            double currentTrueRange = trueRange(
                    current,
                    previousClose);

            atr = ((atr * (ATR_PERIOD - 1))
                    + currentTrueRange)
                    / ATR_PERIOD;

            previousClose = current.getClosePrice();
        }

        return atr;
    }

    public long calculateCumulativeVolume(
            List<MarketCandle> candles) {

        if (candles == null
                || candles.isEmpty()) {
            return 0L;
        }

        long cumulativeVolume = 0L;

        for (MarketCandle candle : candles) {
            if (candle == null
                    || candle.getVolume() == null
                    || candle.getVolume() <= 0L) {
                continue;
            }

            cumulativeVolume += candle.getVolume();
        }

        return cumulativeVolume;
    }

    public VolumeDirection calculateVolumeDirection(
            List<MarketCandle> candles) {

        if (candles == null
                || candles.size() < 3) {
            return VolumeDirection.UNKNOWN;
        }

        MarketCandle first = candles.get(candles.size() - 3);

        MarketCandle second = candles.get(candles.size() - 2);

        MarketCandle third = candles.get(candles.size() - 1);

        if (!hasPositiveVolume(first)
                || !hasPositiveVolume(second)
                || !hasPositiveVolume(third)) {
            return VolumeDirection.UNKNOWN;
        }

        long firstVolume = first.getVolume();

        long secondVolume = second.getVolume();

        long thirdVolume = third.getVolume();

        if (firstVolume < secondVolume
                && secondVolume < thirdVolume) {
            return VolumeDirection.INCREASING;
        }

        if (firstVolume > secondVolume
                && secondVolume > thirdVolume) {
            return VolumeDirection.DECREASING;
        }

        return VolumeDirection.FLAT;
    }

    public Double calculateVolX(
            Long cumulativeVolumeToday,
            Long averageCumulativeVolume) {

        if (cumulativeVolumeToday == null
                || averageCumulativeVolume == null
                || cumulativeVolumeToday < 0L
                || averageCumulativeVolume <= 0L) {
            return null;
        }

        return cumulativeVolumeToday
                / (double) averageCumulativeVolume;
    }

    private boolean hasValidCloseSeries(
            List<MarketCandle> candles) {

        for (MarketCandle candle : candles) {
            if (candle == null
                    || candle.getCandleTime() == null
                    || !isFinite(candle.getClosePrice())) {
                return false;
            }
        }

        return true;
    }

    private boolean hasValidAtrSeries(
            List<MarketCandle> candles) {

        for (MarketCandle candle : candles) {
            if (!hasValidPrices(candle)) {
                return false;
            }
        }

        return true;
    }

    private boolean hasValidPrices(
            MarketCandle candle) {

        return candle != null
                && candle.getCandleTime() != null
                && isFinite(candle.getHighPrice())
                && isFinite(candle.getLowPrice())
                && isFinite(candle.getClosePrice())
                && candle.getHighPrice() >= candle.getLowPrice();
    }

    private boolean hasPositiveVolume(
            MarketCandle candle) {

        return candle != null
                && candle.getVolume() != null
                && candle.getVolume() > 0L;
    }

    private boolean isFinite(Double value) {
        return value != null
                && !value.isNaN()
                && !value.isInfinite();
    }

    private double trueRange(
            MarketCandle current,
            double previousClose) {

        double currentHigh = current.getHighPrice();

        double currentLow = current.getLowPrice();

        double range = currentHigh - currentLow;

        double gapUp = Math.abs(currentHigh - previousClose);

        double gapDown = Math.abs(currentLow - previousClose);

        return Math.max(
                range,
                Math.max(gapUp, gapDown));
    }

    private double typicalPrice(
            MarketCandle candle) {

        return (candle.getHighPrice()
                + candle.getLowPrice()
                + candle.getClosePrice())
                / 3.0;
    }
}
