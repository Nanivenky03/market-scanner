package com.trading.scanner.strategy.model;

/**
 * Action proposed by a StrategyPositionHandler during position lifecycle evaluation.
 */
public record PositionAction(
        ActionType type,
        Double newStopLoss,
        Double exitQuantityFraction,
        String reason
) {
    public enum ActionType {
        HOLD,
        UPDATE_STOP,
        PARTIAL_EXIT,
        FULL_EXIT
    }

    public static PositionAction hold() {
        return new PositionAction(ActionType.HOLD, null, null, "HOLD");
    }

    public static PositionAction updateStop(double newStopLoss, String reason) {
        return new PositionAction(ActionType.UPDATE_STOP, newStopLoss, null, reason);
    }

    public static PositionAction partialExit(double exitQuantityFraction, String reason) {
        return new PositionAction(ActionType.PARTIAL_EXIT, null, exitQuantityFraction, reason);
    }

    public static PositionAction partialExit(double exitQuantityFraction, double newStopLoss, String reason) {
        return new PositionAction(ActionType.PARTIAL_EXIT, newStopLoss, exitQuantityFraction, reason);
    }

    public static PositionAction fullExit(String reason) {
        return new PositionAction(ActionType.FULL_EXIT, null, 1.0, reason);
    }
}
