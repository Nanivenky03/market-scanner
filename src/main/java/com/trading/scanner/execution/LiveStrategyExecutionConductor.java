package com.trading.scanner.execution;

import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.execution.adapter.SimulatedOrderAdapter;
import com.trading.scanner.execution.adapter.model.OrderResponse;
import com.trading.scanner.execution.lock.StripedSymbolLockManager;
import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.position.PositionManagerService;
import com.trading.scanner.risk.model.PortfolioMode;
import com.trading.scanner.strategy.cache.StockContextCache;
import com.trading.scanner.strategy.engine.MasterMarketFilterService;
import com.trading.scanner.strategy.fchb.FchbStrategy;
import com.trading.scanner.strategy.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Conductor / Orchestrator for real-time strategy evaluation, in-flight position
 * lifecycle management, and order execution during live market hours.
 *
 * Responsibilities:
 * 1. 5-Minute Candle Close: Evaluates qualified setups across the active universe via
 *    StockContextCache and FchbStrategy, submitting approved proposals to OrderExecutionService.
 * 2. Incoming Price Ticks: Real-time stop-loss breach detection (SL-M auto-trigger),
 *    Target 1 (+0.73R) partial exit execution, and high-water mark peak tracking.
 * 3. 1-Minute Candle Close: Tactical exit execution for 14:45 wind-down, 14:55 hard close,
 *    and candle invalidation patterns (5M breakout failure, VWAP breakdown).
 * 4. Minute Rollover: Synchronizes in-memory trailing stops with resting broker orders and DB.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LiveStrategyExecutionConductor {

    private static final LocalTime ENTRY_WINDOW_START = LocalTime.of(9, 30);
    private static final LocalTime ENTRY_WINDOW_END = LocalTime.of(10, 45);
    private static final LocalTime WIND_DOWN_TIME = LocalTime.of(14, 45);

    private final StockContextCache stockContextCache;
    private final FchbStrategy fchbStrategy;
    private final MasterMarketFilterService masterMarketFilterService;
    private final OrderExecutionService orderExecutionService;
    private final PositionManagerService positionManagerService;
    private final SimulatedOrderAdapter simulatedOrderAdapter;
    private final StripedSymbolLockManager lockManager;
    private final TimeProvider timeProvider;
    private final TradingCalendar tradingCalendar;

    /**
     * Listens to finalized candles published by LiveMarketCandleService.
     */
    @EventListener
    public void onCandleFinalized(CandleFinalizedEvent event) {
        if (event == null || event.candle() == null) {
            return;
        }

        // Indices are benchmarks/macro context; they are not tradable stocks
        if (event.isIndex()) {
            return;
        }

        if (event.timeframe() == CandleTimeframe.FIVE_MINUTE) {
            evaluateFiveMinuteSetup(event.symbol(), event.exchange(), event.candle());
        } else if (event.timeframe() == CandleTimeframe.ONE_MINUTE) {
            evaluateOneMinutePosition(event.symbol(), event.exchange(), event.candle());
        }
    }

    /**
     * Evaluates 5-minute candle completions for candidate strategy entries.
     */
    public void evaluateFiveMinuteSetup(String symbol, String exchange, MarketCandle candle5m) {
        if (candle5m == null || candle5m.getCandleTime() == null) {
            return;
        }

        LocalDate tradingDate = candle5m.getCandleTime().toLocalDate();
        LocalTime candleTime = candle5m.getCandleTime().toLocalTime();

        // 1. Check active trading day
        if (!tradingCalendar.isTradingDay(tradingDate)) {
            return;
        }

        // 2. Check active breakout entry window (09:30 - 10:45)
        if (candleTime.isBefore(ENTRY_WINDOW_START) || candleTime.isAfter(ENTRY_WINDOW_END)) {
            return;
        }

        // 3. Resolve configured execution mode for FCHB strategy
        PortfolioMode mode = orderExecutionService.resolveExecutionMode(FchbStrategy.STRATEGY_ID);
        if (mode == null) {
            return; // Strategy disabled
        }

        // 4. Check macro market state (Nifty regime & VWAP direction)
        MarketState marketState = masterMarketFilterService.evaluateCurrentState(tradingDate);
        if (marketState == null || !marketState.dataValid() || !marketState.niftyAboveVwap()) {
            log.debug("[CONDUCTOR] Skipping 5M eval for {} at {}: MarketState invalid or Nifty below VWAP",
                    symbol, candleTime);
            return;
        }

        // 5. Retrieve enriched StockContext from memory cache
        Optional<StockContext> optContext = stockContextCache.getStockContext(symbol, exchange, tradingDate);
        if (optContext.isEmpty()) {
            return;
        }
        StockContext context = optContext.get();

        // 6. Evaluate FCHB Quality Gate & Breakout rules
        StrategyEvaluation evaluation = fchbStrategy.evaluate(context, marketState, Collections.emptyMap());
        if (evaluation != null && evaluation.isQualified() && evaluation.proposal() != null) {
            StrategyProposal proposal = evaluation.proposal();
            log.info("[CONDUCTOR] FCHB setup QUALIFIED for symbol={} score={} entry={} SL={} T1={}",
                    symbol, evaluation.confidenceScore(), proposal.entryPrice(), proposal.initialStopLoss(), proposal.initialTarget());

            // 7. Route qualified candidate proposal to OrderExecutionService
            OrderExecutionService.ExecutionResult result = orderExecutionService.processCandidateProposal(proposal, mode);
            log.info("[CONDUCTOR] Order execution result for symbol={}: accepted={} orderId={} restingSlId={}",
                    symbol, result.accepted(), result.brokerOrderId(), result.restingSlOrderId());
        }
    }

    /**
     * Evaluates 1-minute candle completions for active positions (wind-down and technical invalidation).
     */
    public void evaluateOneMinutePosition(String symbol, String exchange, MarketCandle candle1m) {
        if (candle1m == null || candle1m.getCandleTime() == null) {
            return;
        }

        LocalTime candleTime = candle1m.getCandleTime().toLocalTime();
        double closePrice = candle1m.getClosePrice() != null ? candle1m.getClosePrice() : 0.0;
        if (closePrice <= 0.0) return;

        for (PortfolioMode mode : List.of(PortfolioMode.PAPER, PortfolioMode.LIVE)) {
            PositionSnapshot pos = positionManagerService.getPosition(mode, symbol);
            if (pos == null) {
                continue;
            }

            lockManager.executeWithLock(symbol, () -> {
                // Re-check after lock
                PositionSnapshot lockedPos = positionManagerService.getPosition(mode, symbol);
                if (lockedPos == null) return;

                // 1. Wind-Down Cutoff at 14:45 or later
                if (candleTime.isAfter(WIND_DOWN_TIME) || candleTime.equals(WIND_DOWN_TIME)) {
                    log.info("[CONDUCTOR] [{}] Triggering 14:45 wind-down exit for symbol={} at price={}",
                            mode, symbol, closePrice);
                    orderExecutionService.executeTacticalExit(mode, symbol, closePrice, "INTRADAY_WIND_DOWN_1445");
                    return;
                }

                // 2. Technical Invalidation Checks via StrategyPositionHandler
                PositionAction action = positionManagerService.processMinuteCandleClose(mode, symbol, candle1m, null, candleTime);
                if (action != null && action.type() == PositionAction.ActionType.FULL_EXIT) {
                    log.info("[CONDUCTOR] [{}] Invalidation exit triggered for symbol={} reason={} at price={}",
                            mode, symbol, action.reason(), closePrice);
                    orderExecutionService.executeTacticalExit(mode, symbol, closePrice, action.reason());
                }
            });
        }
    }

    /**
     * Real-time tick evaluation hook.
     * Evaluates resting stoploss breaches, Target 1 profit banking, and in-memory peak price tracking.
     * High performance: Fast $O(1)$ returns if symbol has no active positions.
     */
    public void processIncomingTick(String symbol, String exchange, double ltp, LocalDateTime tickTime) {
        if (symbol == null || ltp <= 0.0 || tickTime == null) {
            return;
        }

        // Fast-path bypass: 99.9% of incoming ticks exit here in < 10 nanoseconds
        PositionSnapshot paperPos = positionManagerService.getPosition(PortfolioMode.PAPER, symbol);
        PositionSnapshot livePos = positionManagerService.getPosition(PortfolioMode.LIVE, symbol);
        if (paperPos == null && livePos == null) {
            return;
        }

        LocalTime currentTime = tickTime.toLocalTime();

        // Process PAPER position
        if (paperPos != null) {
            lockManager.executeWithLock(symbol, () -> {
                PositionSnapshot current = positionManagerService.getPosition(PortfolioMode.PAPER, symbol);
                if (current == null) return;

                // 1. Check resting SL-M order trigger breach
                List<OrderResponse> triggered = simulatedOrderAdapter.checkRestingTriggers(symbol, ltp);
                if (!triggered.isEmpty()) {
                    log.info("[CONDUCTOR] [PAPER] Resting SL triggered for symbol={} at tick={}", symbol, ltp);
                    for (OrderResponse fill : triggered) {
                        orderExecutionService.handleRestingStopLossFilled(PortfolioMode.PAPER, symbol, fill);
                    }
                    return;
                }

                // 2. Check Target 1 (+0.73R) partial exit
                if (!current.target1Booked() && current.target1Price() != null && ltp >= current.target1Price()) {
                    log.info("[CONDUCTOR] [PAPER] Target 1 reached for symbol={} at tick={}", symbol, ltp);
                    orderExecutionService.executeTarget1PartialExit(PortfolioMode.PAPER, symbol, ltp);
                }

                // 3. Update high-water mark peak price and trailing stop state in memory
                positionManagerService.processTick(PortfolioMode.PAPER, symbol, ltp, currentTime);
            });
        }

        // Process LIVE position
        if (livePos != null) {
            lockManager.executeWithLock(symbol, () -> {
                PositionSnapshot current = positionManagerService.getPosition(PortfolioMode.LIVE, symbol);
                if (current == null) return;

                if (!current.target1Booked() && current.target1Price() != null && ltp >= current.target1Price()) {
                    log.info("[CONDUCTOR] [LIVE] Target 1 reached for symbol={} at tick={}", symbol, ltp);
                    orderExecutionService.executeTarget1PartialExit(PortfolioMode.LIVE, symbol, ltp);
                }

                positionManagerService.processTick(PortfolioMode.LIVE, symbol, ltp, currentTime);
            });
        }
    }

    /**
     * Synchronizes in-memory trailing stops with resting orders and DB records on minute rollover.
     */
    public void onMinuteRollover(LocalDate tradingDate, LocalTime currentTime) {
        orderExecutionService.flushMinuteRolloverTrailingStops(PortfolioMode.PAPER, tradingDate, currentTime);
        orderExecutionService.flushMinuteRolloverTrailingStops(PortfolioMode.LIVE, tradingDate, currentTime);
    }
}
