package com.trading.scanner.strategy;

import com.trading.scanner.strategy.model.MarketState;
import com.trading.scanner.strategy.model.StockContext;
import com.trading.scanner.strategy.model.StrategyEvaluation;

import java.util.Map;

/**
 * Pure strategy decision function contract.
 *
 * Constraints:
 * - Pure function: Strategies NEVER touch database records, network sockets, or broker APIs.
 * - Consumes finalized StockContext, MarketState, and runtime rule parameters.
 * - Emits an immutable StrategyEvaluation.
 */
public interface Strategy {

    /**
     * Unique integer strategy identifier corresponding to strategy_config.id.
     */
    Integer getStrategyId();

    /**
     * Strategy key matching strategy_config.strategy_key (e.g., 'FCHB', 'ORB').
     */
    String getStrategyKey();

    /**
     * Primary timeframe on which the strategy evaluates (e.g., '5M', '15M').
     */
    String getTimeframe();

    /**
     * Evaluates the stock context against the strategy's rules.
     *
     * @param context Stock facts and sliding candle windows.
     * @param marketState Macro NIFTY market filter.
     * @param parameters Strategy-specific parameter overrides.
     * @return Evaluation result containing proposal if qualified, or failure reasons.
     */
    StrategyEvaluation evaluate(StockContext context, MarketState marketState, Map<String, String> parameters);
}
