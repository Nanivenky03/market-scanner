package com.trading.scanner.strategy.fchb;

import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.strategy.StrategyPositionHandler;
import com.trading.scanner.strategy.model.PositionAction;
import com.trading.scanner.strategy.model.PositionSnapshot;
import com.trading.scanner.util.PriceTickUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.Map;

/**
 * Position lifecycle handler for FCHB (Strategy ID 2).
 * Implements decoupled, priority-ordered exit, invalidation, continuous trailing stop, and milestone logic:
 * - Priority 1: 14:55:00 Hard Close Cutoff (broker square-off shield).
 * - Priority 2: 14:45:00 Wind-Down Cutoff (exit if not profit-protected with 1-tick tolerance).
 * - Priority 3: Stop Loss Breach (real-time tick breach).
 * - Priority 4: Target 1 Partial Exit (50% exit at +0.87R) + Immediate Breakeven Stop Lock.
 * - Priority 5: Continuous Monotonic Dynamic Trailing Stop + Milestone Floors (+1.87R -> SL +0.87R, +2.87R -> SL +1.87R).
 */
@Component
public class FchbPositionHandler implements StrategyPositionHandler {

    private static final Logger log = LoggerFactory.getLogger(FchbPositionHandler.class);

    private static final LocalTime HARD_CLOSE_TIME = LocalTime.of(14, 55, 0);
    private static final LocalTime WIND_DOWN_TIME = LocalTime.of(14, 45, 0);

    public static final double TARGET_1_R_MULTIPLE = FchbStrategy.TARGET_1_R_MULTIPLE;
    public static final double RUNNER_TRAIL_R_MULTIPLE = 0.73;

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

        // Priority 2: 14:45:00 Wind-Down Cutoff (with 1-tick ₹0.05 tolerance for breakeven validation)
        if (!currentTime.isBefore(WIND_DOWN_TIME)) {
            boolean profitProtected = isShort
                    ? (position.currentStopLoss() != null && position.currentStopLoss() <= (position.entryPrice() + 0.05))
                    : (position.currentStopLoss() != null && position.currentStopLoss() >= (position.entryPrice() - 0.05));
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

        // Priority 4: Target 1 Partial Exit (50% exit at +0.73R) + Immediate Breakeven Stop Lock
        if (!position.target1Booked() && position.target1Price() != null) {
            boolean target1Hit = isShort
                    ? (currentLtp <= position.target1Price())
                    : (currentLtp >= position.target1Price());
            if (target1Hit) {
                double target1Stop = PriceTickUtil.roundStopLoss(position.entryPrice(), isShort, 0.05);
                return PositionAction.partialExit(0.5, target1Stop, "TARGET_1_HIT");
            }
        }

        // Calculate riskPerShare based on initial Target 1 calibration (target1Price = entry + 0.73 * R)
        double riskPerShare = position.target1Price() != null
                ? (Math.abs(position.target1Price() - position.entryPrice()) / TARGET_1_R_MULTIPLE)
                : Math.abs(position.entryPrice() - (position.currentStopLoss() != null ? position.currentStopLoss() : position.entryPrice()));
        if (riskPerShare <= 0.01) {
            riskPerShare = 5.0; // fallback safety
        }

        // Priority 5: Completed Candle Invalidation Rules (Evaluated strictly on candle closes)
        if (last1mCandle != null) {
            boolean is5mClose = (currentTime.getMinute() % 5 == 0)
                    || (parameters != null && "true".equalsIgnoreCase(parameters.get("is5mClose")));

            if (!isShort && is5mClose) {
                // Rule 5A: 5M Failed Breakout Cut (Drops below FCH with significant drawdown >= 0.50R)
                if (position.firstCandleHigh() != null && currentLtp < position.firstCandleHigh()
                        && currentLtp <= position.entryPrice() - (0.50 * riskPerShare)) {
                    return PositionAction.fullExit("FAILED_BREAKOUT_EXIT");
                }

                // Rule 5B: 5M Stock VWAP Breakdown Cut (closes below stock VWAP with drawdown >= 0.50R)
                Double vwap = last1mCandle.getVwap();
                if (parameters != null && parameters.containsKey("stock5mVwap")) {
                    try {
                        vwap = Double.parseDouble(parameters.get("stock5mVwap"));
                    } catch (NumberFormatException ignored) {
                    }
                }
                if (vwap != null && vwap > 0.0 && currentLtp < vwap
                        && currentLtp <= position.entryPrice() - (0.50 * riskPerShare)) {
                    return PositionAction.fullExit("VWAP_BREAKDOWN_EXIT");
                }

                // Rule 5C: 5M EMA 9 < 21 Momentum Exhaustion Cut
                if (parameters != null && parameters.containsKey("ema9") && parameters.containsKey("ema21")) {
                    try {
                        double ema9 = Double.parseDouble(parameters.get("ema9"));
                        double ema21 = Double.parseDouble(parameters.get("ema21"));
                        if (ema9 < ema21 && currentLtp < position.entryPrice()) {
                            return PositionAction.fullExit("MOMENTUM_EXHAUSTION_EXIT");
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }

        // Priority 6: Continuous Monotonic Dynamic Trailing Stop (Trails before & after Target 1)
        double peak = position.peakPrice() != null ? position.peakPrice() : position.entryPrice();
        if (last1mCandle != null) {
            double barExtreme = isShort
                    ? (last1mCandle.getLowPrice() != null ? last1mCandle.getLowPrice() : currentLtp)
                    : (last1mCandle.getHighPrice() != null ? last1mCandle.getHighPrice() : currentLtp);
            peak = isShort ? Math.min(peak, barExtreme) : Math.max(peak, barExtreme);
        }
        peak = isShort ? Math.min(peak, currentLtp) : Math.max(peak, currentLtp);

        double candidateStop;
        if (isShort) {
            if (position.target1Booked()) {
                // Post-Target 1: Trailing runner by 0.73R distance from peak, capped at entryPrice (breakeven)
                double dynamicTrail = peak + (RUNNER_TRAIL_R_MULTIPLE * riskPerShare);
                candidateStop = Math.min(position.entryPrice(), dynamicTrail);
                candidateStop = PriceTickUtil.roundStopLoss(candidateStop, isShort, 0.05);
                if (position.currentStopLoss() == null || candidateStop < position.currentStopLoss()) {
                    return PositionAction.updateStop(candidateStop, "RUNNER_PROFIT_LOCK_RATCHET");
                }
            } else {
                // Pre-Target 1: Monotonic trail with 1.0R leash as peak advances beyond entry
                if (peak < position.entryPrice()) {
                    double dynamicTrail = peak + riskPerShare;
                    candidateStop = PriceTickUtil.roundStopLoss(dynamicTrail, isShort, 0.05);
                    if (position.currentStopLoss() == null || candidateStop < position.currentStopLoss()) {
                        return PositionAction.updateStop(candidateStop, "DYNAMIC_TRAILING_STOP_RATCHET");
                    }
                }
            }
        } else {
            if (position.target1Booked()) {
                // Post-Target 1: Trailing runner by 0.73R distance from peak, floored at entryPrice (breakeven)
                double dynamicTrail = peak - (RUNNER_TRAIL_R_MULTIPLE * riskPerShare);
                candidateStop = Math.max(position.entryPrice(), dynamicTrail);
                candidateStop = PriceTickUtil.roundStopLoss(candidateStop, isShort, 0.05);
                if (position.currentStopLoss() == null || candidateStop > position.currentStopLoss()) {
                    return PositionAction.updateStop(candidateStop, "RUNNER_PROFIT_LOCK_RATCHET");
                }
            } else {
                // Pre-Target 1: Monotonic trail with 1.0R leash as peak advances beyond entry
                if (peak > position.entryPrice()) {
                    double dynamicTrail = peak - riskPerShare;
                    candidateStop = PriceTickUtil.roundStopLoss(dynamicTrail, isShort, 0.05);
                    if (position.currentStopLoss() == null || candidateStop > position.currentStopLoss()) {
                        return PositionAction.updateStop(candidateStop, "DYNAMIC_TRAILING_STOP_RATCHET");
                    }
                }
            }
        }

        return PositionAction.hold();
    }
}
