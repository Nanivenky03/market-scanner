package com.trading.scanner.service.engine;

import com.trading.scanner.model.MarketCandle;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class IntradayIndicatorService {

    private static final int RSI_PERIOD = 14;
    private static final int RSI_MINIMUM_CANDLES = 42;
    private static final int ATR_PERIOD = 14;
    private static final int ATR_MINIMUM_CANDLES = 15;

    public Double calculateSessionVwap(List<MarketCandle> oneMinuteSessionCandles) {
        if (oneMinuteSessionCandles == null || oneMinuteSessionCandles.isEmpty()) {
            return null;
        }

        double cumulativeTypicalPriceVolume = 0.0;
        long cumulativeVolume = 0L;

        for (int i = 0; i < oneMinuteSessionCandles.size(); i++) {
            MarketCandle candle = oneMinuteSessionCandles.get(i);
            if (candle == null || candle.getVolume() == null || candle.getVolume() <= 0L) {
                continue;
            }

            double typicalPrice = typicalPrice(candle);
            cumulativeTypicalPriceVolume += typicalPrice * candle.getVolume();
            cumulativeVolume += candle.getVolume();
        }

        if (cumulativeVolume <= 0L) {
            return null;
        }

        return cumulativeTypicalPriceVolume / cumulativeVolume;
    }

    public Double calculateRsi14Wilder(List<MarketCandle> candles) {
        if (candles == null || candles.size() < RSI_MINIMUM_CANDLES) {
            return null;
        }

        double avgGain = 0.0;
        double avgLoss = 0.0;

        for (int i = 1; i <= RSI_PERIOD; i++) {
            Double currentClose = candles.get(i).getClosePrice();
            Double previousClose = candles.get(i - 1).getClosePrice();

            if (currentClose == null || previousClose == null) {
                return null;
            }

            double change = currentClose - previousClose;
            if (change > 0.0) {
                avgGain += change;
            } else {
                avgLoss += -change;
            }
        }

        avgGain /= RSI_PERIOD;
        avgLoss /= RSI_PERIOD;

        for (int i = RSI_PERIOD + 1; i < candles.size(); i++) {
            Double currentClose = candles.get(i).getClosePrice();
            Double previousClose = candles.get(i - 1).getClosePrice();

            if (currentClose == null || previousClose == null) {
                return null;
            }

            double change = currentClose - previousClose;
            double gain = change > 0.0 ? change : 0.0;
            double loss = change < 0.0 ? -change : 0.0;

            avgGain = ((avgGain * (RSI_PERIOD - 1)) + gain) / RSI_PERIOD;
            avgLoss = ((avgLoss * (RSI_PERIOD - 1)) + loss) / RSI_PERIOD;
        }

        if (avgLoss == 0.0) {
            return 100.0;
        }

        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    public Double calculateAtr14Wilder(List<MarketCandle> candles) {
        if (candles == null || candles.size() < ATR_MINIMUM_CANDLES) {
            return null;
        }

        double atr = 0.0;

        for (int i = 1; i <= ATR_PERIOD; i++) {
            Double currentHigh = candles.get(i).getHighPrice();
            Double currentLow = candles.get(i).getLowPrice();
            Double previousClose = candles.get(i - 1).getClosePrice();

            if (currentHigh == null || currentLow == null || previousClose == null) {
                return null;
            }

            atr += trueRange(currentHigh, currentLow, previousClose);
        }

        atr /= ATR_PERIOD;

        for (int i = ATR_PERIOD + 1; i < candles.size(); i++) {
            Double currentHigh = candles.get(i).getHighPrice();
            Double currentLow = candles.get(i).getLowPrice();
            Double previousClose = candles.get(i - 1).getClosePrice();

            if (currentHigh == null || currentLow == null || previousClose == null) {
                return null;
            }

            double currentTr = trueRange(currentHigh, currentLow, previousClose);
            atr = ((atr * (ATR_PERIOD - 1)) + currentTr) / ATR_PERIOD;
        }

        return atr;
    }

    private double trueRange(double currentHigh, double currentLow, double previousClose) {
        double range = currentHigh - currentLow;
        double gapUp = Math.abs(currentHigh - previousClose);
        double gapDown = Math.abs(currentLow - previousClose);
        return Math.max(range, Math.max(gapUp, gapDown));
    }

    private double typicalPrice(MarketCandle candle) {
        return (candle.getHighPrice() + candle.getLowPrice() + candle.getClosePrice()) / 3.0;
    }
}
