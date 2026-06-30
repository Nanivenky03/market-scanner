package com.trading.scanner.strategy;

import com.trading.scanner.config.BreakoutRuleProperties;

public record StrategyYamlDefinition(
                String strategyId,
                String version,
                String displayName,
                StrategyTimeframe timeframe,
                StrategyStatus status,
                Boolean nextCandleConfirmationRequired,
                BreakoutRuleProperties breakout,
                StrategyScoringModels.StrategyScoringDefinition scoring) {
}