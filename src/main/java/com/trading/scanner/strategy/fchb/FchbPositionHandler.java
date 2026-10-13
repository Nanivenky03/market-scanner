package com.trading.scanner.strategy.fchb;

import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.strategy.StrategyPositionHandler;
import com.trading.scanner.strategy.model.PositionAction;
import com.trading.scanner.strategy.model.PositionSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.Map;

/**
 * Position lifecycle handler for FCHB (Strategy ID 2).
 * Implements priority-ordered exit, invalidation, ratchet, and trailing stop logic.
 */
@Component
public class FchbPositionHandler implements StrategyPositionHandler {

    private static final Logger log = LoggerFactory.getLogger(FchbPositionHandler.class);

    private static final LocalTime HARD_CLOSE_TIME = LocalTime.of(14, 55, 0);
    private static final LocalTime WIND_DOWN_TIME = LocalTime.of(14, 45, 0);

    @Override
    public int getStrategyId() {
        return FchbStrategy.STRATEGY_ID;
    }

    @Override
    public String getStrategyKey() {
        return FchbStrategy.STRATEGY_KEY;
    }

    @Override
    public PositionAction evaluatePosition(
            PositionSnapshot position,
            Double currentLtp,
            MarketCandle last1mCandle,
            Double latest5mAtr,
            LocalTime currentTime,
            Map<String, String> parameters
    ) {
        if (position == null || currentLtp == null || currentTime == null) {
            return PositionAction.hold();
        }

        boolean isShort = position.positionSide() != null
                && ("SHORT".equalsIgnoreCase(position.positionSide()) || "SELL".equalsIgnoreCase(position.positionSide()));

        // Priority 1: 14:55:00 Hard Close Cutoff (15 min safety buffer before broker RMS)
        if (!currentTime.isBefore(HARD_CLOSE_TIME)) {
            return PositionAction.fullExit("HARD_CLOSE_TIME_CUTOFF");
        }

        // Priority 2: 14:45:00 Wind-Down Cutoff
        if (!currentTime.isBefore(WIND_DOWN_TIME)) {
            boolean profitProtected = isShort
                    ? (position.currentStopLoss() != null && position.currentStopLoss() <= position.entryPrice())
                    : (position.currentStopLoss() != null && position.currentStopLoss() >= position.entryPrice());
            if (!profitProtected) {
                return PositionAction.fullExit("WIND_DOWN_EXIT");
            }
        }

        // Priority 3: Real-Time Tick Stop Loss Breach
        boolean stopHit = isShort
                ? (position.currentStopLoss() != null && currentLtp >= position.currentStopLoss())
                : (position.currentStopLoss() != null && currentLtp <= position.currentStopLoss());
        if (stopHit) {
            return PositionAction.fullExit("STOP_LOSS_HIT");
        }

        // Priority 4: Early Structural Invalidation (1M candle close beyond reference level)
        if (last1mCandle != null && position.firstCandleHigh() != null && last1mCandle.getClosePrice() != null) {
            boolean invalid = isShort
                    ? (last1mCandle.getClosePrice() > position.firstCandleHigh())
                    : (last1mCandle.getClosePrice() < position.firstCandleHigh());
            if (invalid) {
                return PositionAction.fullExit("STRUCTURAL_INVALIDATION");
            }
        }

        // Priority 5: Target 1 Partial Exit (50% exit at +1R) + Immediate Breakeven Stop Lock
        if (!position.target1Booked() && position.target1Price() != null) {
            boolean target1Hit = isShort
                    ? (currentLtp <= position.target1Price())
                    : (currentLtp >= position.target1Price());
            if (target1Hit) {
                double target1Stop;
                if (isShort) {
                    target1Stop = position.currentStopLoss() != null
                            ? Math.min(position.currentStopLoss(), position.entryPrice())
                            : position.entryPrice();
                } else {
                    target1Stop = position.currentStopLoss() != null
                            ? Math.max(position.currentStopLoss(), position.entryPrice())
                            : position.entryPrice();
                }
                return PositionAction.partialExit(0.5, target1Stop, "TARGET_1_HIT");
            }
        }

        // Priority 6: Breakeven Ratchet (at +0.8R before Target 1)
        if (!position.target1Booked() && position.target1Price() != null) {
            double initialRisk = Math.abs(position.target1Price() - position.entryPrice());
            if (isShort) {
                double breakevenTriggerPrice = position.entryPrice() - (0.8 * initialRisk);
                if (currentLtp <= breakevenTriggerPrice) {
                    if (position.currentStopLoss() == null || position.currentStopLoss() > position.entryPrice()) {
                        return PositionAction.updateStop(position.entryPrice(), "BREAKEVEN_0_8R_TRIGGERED");
                    }
                }
            } else {
                double breakevenTriggerPrice = position.entryPrice() + (0.8 * initialRisk);
                if (currentLtp >= breakevenTriggerPrice) {
                    if (position.currentStopLoss() == null || position.currentStopLoss() < position.entryPrice()) {
                        return PositionAction.updateStop(position.entryPrice(), "BREAKEVEN_0_8R_TRIGGERED");
                    }
                }
            }
        }

        // Priority 7: Progressive Milestone Ratchets + ATR Trailing Stop (after Target 1 booked)
        if (position.target1Booked() && position.target1Price() != null) {
            double initialRisk = Math.abs(position.target1Price() - position.entryPrice());
            if (isShort) {
                double milestoneCeiling = position.entryPrice();
                if (currentLtp <= (position.entryPrice() - (3.0 * initialRisk))) {
                    milestoneCeiling = position.entryPrice() - (2.0 * initialRisk);
                } else if (currentLtp <= (position.entryPrice() - (2.0 * initialRisk))) {
                    milestoneCeiling = position.entryPrice() - (1.0 * initialRisk);
                }

                double candidateStop = milestoneCeiling;
                if (last1mCandle != null && last1mCandle.getHighPrice() != null && latest5mAtr != null && latest5mAtr > 0.0) {
                    double atrTrail = last1mCandle.getHighPrice() + (0.5 * latest5mAtr);
                    candidateStop = Math.min(milestoneCeiling, atrTrail);
                }

                if (position.currentStopLoss() == null || candidateStop < position.currentStopLoss()) {
                    String reason = candidateStop < milestoneCeiling ? "ATR_TRAILING_STOP_RATCHET" : "MILESTONE_RATCHET";
                    return PositionAction.updateStop(candidateStop, reason);
                }
            } else {
                double milestoneFloor = position.entryPrice();
                if (currentLtp >= (position.entryPrice() + (3.0 * initialRisk))) {
                    milestoneFloor = position.entryPrice() + (2.0 * initialRisk);
                } else if (currentLtp >= (position.entryPrice() + (2.0 * initialRisk))) {
                    milestoneFloor = position.entryPrice() + (1.0 * initialRisk);
                }

                double candidateStop = milestoneFloor;
                if (last1mCandle != null && last1mCandle.getLowPrice() != null && latest5mAtr != null && latest5mAtr > 0.0) {
                    double atrTrail = last1mCandle.getLowPrice() - (0.5 * latest5mAtr);
                    candidateStop = Math.max(milestoneFloor, atrTrail);
                }

                if (position.currentStopLoss() == null || candidateStop > position.currentStopLoss()) {
                    String reason = candidateStop > milestoneFloor ? "ATR_TRAILING_STOP_RATCHET" : "MILESTONE_RATCHET";
                    return PositionAction.updateStop(candidateStop, reason);
                }
            }
        }

        return PositionAction.hold();
    }
}
