package com.trading.scanner.strategy;

import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.strategy.model.PositionAction;
import com.trading.scanner.strategy.model.PositionSnapshot;

import java.time.LocalTime;
import java.util.Map;

/**
 * Interface for strategy-specific position lifecycle handlers.
 * Decouples position exit/ratchet logic from entry signal generation.
 */
public interface StrategyPositionHandler {

    int getStrategyId();

    String getStrategyKey();

    /**
     * Evaluates open position state against incoming market tick and candle events.
     *
     * @param position Current position snapshot
     * @param currentLtp Latest traded price
     * @param last1mCandle Most recently completed 1-minute candle
     * @param latest5mAtr Latest 5-minute ATR (or null if unavailable)
     * @param currentTime Current market time
     * @param parameters Strategy configuration parameters
     * @return Proposed position action (HOLD, UPDATE_STOP, PARTIAL_EXIT, FULL_EXIT)
     */
    PositionAction evaluatePosition(
            PositionSnapshot position,
            Double currentLtp,
            MarketCandle last1mCandle,
            Double latest5mAtr,
            LocalTime currentTime,
            Map<String, String> parameters
    );
}
