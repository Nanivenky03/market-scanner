package com.trading.scanner.service.engine;

import com.trading.scanner.strategy.StrategyTimeframe;
import com.trading.scanner.strategy.StrategyYamlDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class BreakoutSignalGenerator implements SignalGenerator {

    private final BreakoutSignalEvaluator breakoutSignalEvaluator;

    @Override
    public StrategyTimeframe supportedTimeframe() {
        return null;
    }

    @Override
    public Optional<BreakoutSignalEvaluator.EvaluationSnapshot> onFiveMinuteCandleClose(
            StrategyYamlDefinition strategy,
            MarketContext marketContext,
            SymbolContext symbolContext) {
        if (strategy.timeframe() != StrategyTimeframe.FIVE_MINUTE) {
            return Optional.empty();
        }

        return Optional.ofNullable(
                breakoutSignalEvaluator.evaluate(strategy, symbolContext));
    }

    @Override
    public Optional<BreakoutSignalEvaluator.EvaluationSnapshot> onFifteenMinuteCandleClose(
            StrategyYamlDefinition strategy,
            MarketContext marketContext,
            SymbolContext symbolContext) {
        if (strategy.timeframe() != StrategyTimeframe.FIFTEEN_MINUTE) {
            return Optional.empty();
        }

        return Optional.ofNullable(
                breakoutSignalEvaluator.evaluate(strategy, symbolContext));
    }
}