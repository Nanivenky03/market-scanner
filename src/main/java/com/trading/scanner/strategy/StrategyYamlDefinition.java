package com.trading.scanner.strategy;

import com.trading.scanner.config.BreakoutRuleProperties;

public record StrategyYamlDefinition(
        String strategyId,
        String version,
        String displayName,
        StrategyTimeframe timeframe,
        Boolean simulationEnabled,
        Boolean liveEnabled,
        Boolean nextCandleConfirmationRequired,
        StrategyStatus status,
        BreakoutRuleProperties breakout,
        StrategyScoringModels.StrategyScoringDefinition scoring) {
}