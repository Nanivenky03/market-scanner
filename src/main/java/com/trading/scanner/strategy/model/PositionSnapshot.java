package com.trading.scanner.strategy.model;

import java.time.LocalDateTime;

/**
 * Immutable snapshot of an open trading position for evaluation by strategy position handlers.
 * Includes peakPrice (high-water mark for LONG, trough for SHORT) for continuous dynamic trailing stops.
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
        Double peakPrice,
        LocalDateTime entryTime,
        String restingSlOrderId
) {
    public PositionSnapshot(
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
            Double peakPrice,
            LocalDateTime entryTime
    ) {
        this(positionId, strategyId, symbol, exchange, positionSide, initialQuantity, currentQuantity,
                entryPrice, currentStopLoss, target1Price, firstCandleHigh, target1Booked, peakPrice, entryTime, null);
    }

    /**
     * Backwards-compatible constructor defaulting peakPrice to entryPrice and restingSlOrderId to null.
     */
    public PositionSnapshot(
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
    ) {
        this(positionId, strategyId, symbol, exchange, positionSide, initialQuantity, currentQuantity,
                entryPrice, currentStopLoss, target1Price, firstCandleHigh, target1Booked, entryPrice, entryTime, null);
    }
}
