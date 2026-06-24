package com.trading.scanner.service.engine;

import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.model.StockPrice;
import com.trading.scanner.strategy.ScoreDecision;
import com.trading.scanner.strategy.StrategyScoringModels;
import com.trading.scanner.strategy.StrategyScoringService;
import com.trading.scanner.strategy.StrategyYamlDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class BreakoutSignalEvaluator {

    private final StrategyScoringService strategyScoringService;

    public EvaluationSnapshot evaluate(
            StrategyYamlDefinition strategy,
            SymbolContext symbolContext) {
        return evaluate(strategy, symbolContext.recentCandles(), symbolContext.dailyPrices());
    }

    public EvaluationSnapshot evaluate(
            StrategyYamlDefinition strategy,
            List<MarketCandle> candles,
            List<StockPrice> dailyPrices) {
        if (strategy == null || candles == null || candles.size() < 20 || dailyPrices == null
                || dailyPrices.isEmpty()) {
            return null;
        }

        MarketCandle current = candles.get(candles.size() - 1);
        StockPrice previousDay = findPreviousTradingDayPrice(dailyPrices, current.getCandleTime().toLocalDate());

        if (previousDay == null || previousDay.getHighPrice() == null || previousDay.getClosePrice() == null) {
            return null;
        }

        if (previousDay.getClosePrice() <= 50.0) {
            return null;
        }

        double previousDayHigh = previousDay.getHighPrice();
        double close = current.getClosePrice();

        if (close <= previousDayHigh) {
            return null;
        }

        double breakoutRatio = (close - previousDayHigh) / previousDayHigh;
        if (breakoutRatio > strategy.breakout().maxGap()) {
            return null;
        }

        Double volumeRatio = computeVolumeRatio(candles, candles.size() - 1, 20);
        if (volumeRatio == null || volumeRatio < strategy.breakout().volumeMultiplierMatch()) {
            return null;
        }

        Double rsi = computeRsi(candles, candles.size() - 1, strategy.breakout().rsiPeriod());
        if (rsi == null || rsi < strategy.breakout().rsiThresholdMatch()) {
            return null;
        }

        Double vwap = computeIntradayVwap(candles, candles.size() - 1);
        if (vwap == null || close <= vwap) {
            return null;
        }

        double closeStrength = computeCloseStrength(current);
        if (closeStrength < 0.70) {
            return null;
        }

        double breakoutPercent = breakoutRatio * 100.0;

        StrategyScoringModels.StrategyScoreResult scoreResult = strategyScoringService.score(
                strategy,
                Map.of(
                        "breakoutPercent", breakoutPercent,
                        "volumeRatio", volumeRatio,
                        "rsi", rsi));

        if (scoreResult.decision() != ScoreDecision.INVEST) {
            return null;
        }

        return new EvaluationSnapshot(
                current,
                close,
                previousDayHigh,
                breakoutPercent,
                volumeRatio,
                rsi,
                vwap,
                closeStrength,
                scoreResult);
    }

    private StockPrice findPreviousTradingDayPrice(List<StockPrice> prices, LocalDate currentDate) {
        StockPrice previous = null;
        for (StockPrice price : prices) {
            if (price.getDate().isBefore(currentDate)) {
                previous = price;
            } else {
                break;
            }
        }
        return previous;
    }

    private Double computeVolumeRatio(List<MarketCandle> candles, int currentIndex, int window) {
        if (currentIndex < window) {
            return null;
        }

        long currentVolume = candles.get(currentIndex).getVolume() != null ? candles.get(currentIndex).getVolume() : 0L;
        if (currentVolume <= 0) {
            return null;
        }

        double total = 0.0;
        int count = 0;

        for (int i = currentIndex - window; i < currentIndex; i++) {
            Long volume = candles.get(i).getVolume();
            if (volume != null) {
                total += volume;
                count++;
            }
        }

        if (count == 0 || total <= 0.0) {
            return null;
        }

        return currentVolume / (total / count);
    }

    private Double computeRsi(List<MarketCandle> candles, int currentIndex, int period) {
        if (currentIndex < period) {
            return null;
        }

        double gain = 0.0;
        double loss = 0.0;

        for (int i = currentIndex - period + 1; i <= currentIndex; i++) {
            double change = candles.get(i).getClosePrice() - candles.get(i - 1).getClosePrice();
            if (change > 0) {
                gain += change;
            } else {
                loss += Math.abs(change);
            }
        }

        double avgGain = gain / period;
        double avgLoss = loss / period;

        if (avgLoss == 0.0) {
            return 100.0;
        }

        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    private Double computeIntradayVwap(List<MarketCandle> candles, int currentIndex) {
        LocalDate currentDate = candles.get(currentIndex).getCandleTime().toLocalDate();

        double totalPriceVolume = 0.0;
        long totalVolume = 0L;

        for (int i = 0; i <= currentIndex; i++) {
            MarketCandle candle = candles.get(i);

            if (!currentDate.equals(candle.getCandleTime().toLocalDate())) {
                continue;
            }

            if (candle.getVolume() == null || candle.getVolume() <= 0) {
                continue;
            }

            double typicalPrice = (candle.getHighPrice() + candle.getLowPrice() + candle.getClosePrice()) / 3.0;
            totalPriceVolume += typicalPrice * candle.getVolume();
            totalVolume += candle.getVolume();
        }

        if (totalVolume == 0L) {
            return null;
        }

        return totalPriceVolume / totalVolume;
    }

    private double computeCloseStrength(MarketCandle candle) {
        double range = candle.getHighPrice() - candle.getLowPrice();
        if (range <= 0.0) {
            return 0.5;
        }
        return (candle.getClosePrice() - candle.getLowPrice()) / range;
    }

    public record EvaluationSnapshot(
            MarketCandle currentCandle,
            double closePrice,
            double previousDayHigh,
            double breakoutPercent,
            double volumeRatio,
            double rsi,
            double vwap,
            double closeStrength,
            StrategyScoringModels.StrategyScoreResult scoreResult) {
    }
}