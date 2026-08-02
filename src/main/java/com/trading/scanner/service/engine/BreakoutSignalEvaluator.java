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
    private final VolumeEngineService volumeEngineService;

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

        VolumeEngineService.VolumeState volumeState = volumeEngineService.currentVolumeState(
                current.getSymbol(),
                current.getExchange(),
                current.getCandleTime().toLocalDate());

        Double volumeRatio = volumeState != null ? volumeState.volX() : null;
        if (volumeRatio == null || volumeRatio < strategy.breakout().volumeMultiplierMatch()) {
            return null;
        }

        Double rsi = current.getRsi14();
        if (rsi == null || rsi < strategy.breakout().rsiThresholdMatch()) {
            return null;
        }

        Double vwap = current.getVwap();
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
