package com.trading.scanner.strategy.model;

import java.util.Map;

/**
 * Immutable trade candidate emitted when a strategy's evaluation qualifies.
 */
public record StrategyProposal(
        String symbol,
        String exchange,
        Integer strategyId,
        String strategyKey,
        String direction,
        Double entryPrice,
        Double initialStopLoss,
        Double initialTarget,
        Double structuralInvalidationLevel,
        Double confidenceScore,
        Map<String, Object> ruleContext
) {
}
