package com.trading.scanner.service.engine;

import com.trading.scanner.strategy.StrategyTimeframe;
import com.trading.scanner.strategy.StrategyYamlDefinition;

import java.util.Optional;

public interface SignalGenerator {

    StrategyTimeframe supportedTimeframe();

    default Optional<BreakoutSignalEvaluator.EvaluationSnapshot> onOneMinuteCandleClose(
            StrategyYamlDefinition strategy,
            MarketContext marketContext,
            SymbolContext symbolContext) {
        return Optional.empty();
    }

    default Optional<BreakoutSignalEvaluator.EvaluationSnapshot> onFiveMinuteCandleClose(
            StrategyYamlDefinition strategy,
            MarketContext marketContext,
            SymbolContext symbolContext) {
        return Optional.empty();
    }

    default Optional<BreakoutSignalEvaluator.EvaluationSnapshot> onFifteenMinuteCandleClose(
            StrategyYamlDefinition strategy,
            MarketContext marketContext,
            SymbolContext symbolContext) {
        return Optional.empty();
    }
}