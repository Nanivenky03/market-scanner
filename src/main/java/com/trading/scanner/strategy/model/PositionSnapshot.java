package com.trading.scanner.strategy.model;

import java.time.LocalDateTime;

/**
 * Immutable snapshot of an open trading position for evaluation by strategy position handlers.
 */
public record PositionSnapshot(
        Integer positionId,
        Integer strategyId,
        String symbol,
        String exchange,
        String positionSide,
        Integer initialQuantity,
        Integer currentQuantity,
        Double entryPrice,
        Double currentStopLoss,
        Double target1Price,
        Double firstCandleHigh,
        boolean target1Booked,
        LocalDateTime entryTime
) {}
