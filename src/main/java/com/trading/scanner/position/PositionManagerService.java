package com.trading.scanner.position;

import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.risk.RiskEngine;
import com.trading.scanner.risk.model.PortfolioMode;
import com.trading.scanner.strategy.StrategyPositionHandler;
import com.trading.scanner.strategy.model.PositionAction;
import com.trading.scanner.strategy.model.PositionSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Layer 4/5: In-Memory Position Manager Service.
 * Coordinates active positions across PAPER and LIVE portfolios, evaluates
 * incoming
 * price ticks and 1-minute candle closes against strategy position handlers,
 * maintains in-memory trailing stop state machines, and dispatches exits to
 * Risk Engine.
 */
@Service
public class PositionManagerService {

    private static final Logger log = LoggerFactory.getLogger(PositionManagerService.class);

    private final RiskEngine riskEngine;
    private final Map<Integer, StrategyPositionHandler> handlersByStrategyId = new ConcurrentHashMap<>();
    private final Map<PortfolioMode, Map<String, PositionSnapshot>> activePositions = new ConcurrentHashMap<>();

    public PositionManagerService(RiskEngine riskEngine, List<StrategyPositionHandler> handlers) {
        this.riskEngine = riskEngine;
        if (handlers != null) {
            for (StrategyPositionHandler handler : handlers) {
                handlersByStrategyId.put(handler.getStrategyId(), handler);
            }
        }
        activePositions.put(PortfolioMode.PAPER, new ConcurrentHashMap<>());
        activePositions.put(PortfolioMode.LIVE, new ConcurrentHashMap<>());
    }

    private final Map<String, Object> symbolLocks = new ConcurrentHashMap<>();

    private Object getSymbolLock(String symbol) {
        return symbolLocks.computeIfAbsent(symbol != null ? symbol : "DEFAULT", s -> new Object());
    }

    /**
     * Registers a new open position snapshot into RAM and Risk Engine.
     */
    public void registerPosition(PortfolioMode mode, PositionSnapshot position) {
        if (position == null || position.symbol() == null) {
            return;
        }
        synchronized (getSymbolLock(position.symbol())) {
            activePositions.computeIfAbsent(mode, m -> new ConcurrentHashMap<>()).put(position.symbol(), position);
            riskEngine.recordTradeExecuted(mode, position.symbol(), position);
            log.info("[{}] Registered open position for symbol={} side={} qty={} entry={} SL={}",
                    mode, position.symbol(), position.positionSide(), position.currentQuantity(),
                    position.entryPrice(), position.currentStopLoss());
        }
    }

    public Map<String, PositionSnapshot> getActivePositions(PortfolioMode mode) {
        Map<String, PositionSnapshot> map = activePositions.get(mode);
        return map != null ? Collections.unmodifiableMap(map) : Collections.emptyMap();
    }

    public PositionSnapshot getPosition(PortfolioMode mode, String symbol) {
        Map<String, PositionSnapshot> map = activePositions.get(mode);
        return map != null ? map.get(symbol) : null;
    }

    /**
     * Evaluates a real-time price tick for an open position.
     */
    public PositionAction processTick(PortfolioMode mode, String symbol, double ltp,
            LocalTime currentTime) {
        synchronized (getSymbolLock(symbol)) {
            PositionSnapshot position = getPosition(mode, symbol);
            if (position == null) {
                return PositionAction.hold();
            }

            StrategyPositionHandler handler = handlersByStrategyId.get(position.strategyId());
            if (handler == null) {
                log.warn("No PositionHandler registered for strategyId={}", position.strategyId());
                return PositionAction.hold();
            }

            PositionAction action = handler.evaluatePosition(position, ltp, null, null, currentTime,
                    Collections.emptyMap());
            applyPositionAction(mode, position, action, ltp);
            return action;
        }
    }

    /**
     * Evaluates a completed 1-minute candle close for an open position (structural
     * invalidation and ATR trailing).
     */
    public PositionAction processMinuteCandleClose(
            PortfolioMode mode,
            String symbol,
            MarketCandle candle1m,
            Double atr5m,
            LocalTime currentTime) {
        synchronized (getSymbolLock(symbol)) {
            PositionSnapshot position = getPosition(mode, symbol);
            if (position == null || candle1m == null) {
                return PositionAction.hold();
            }

            StrategyPositionHandler handler = handlersByStrategyId.get(position.strategyId());
            if (handler == null) {
                log.warn("No PositionHandler registered for strategyId={}", position.strategyId());
                return PositionAction.hold();
            }

            double closePrice = candle1m.getClosePrice() != null ? candle1m.getClosePrice() : 0.0;
            PositionAction action = handler.evaluatePosition(position, closePrice, candle1m, atr5m, currentTime,
                    Collections.emptyMap());
            applyPositionAction(mode, position, action, closePrice);
            return action;
        }
    }

    /**
     * Applies the proposed position action to in-memory state and updates the Risk
     * Engine.
     */
    private void applyPositionAction(PortfolioMode mode, PositionSnapshot position, PositionAction action,
            double currentPrice) {
        if (action == null || action.type() == PositionAction.ActionType.HOLD) {
            return;
        }

        String symbol = position.symbol();
        switch (action.type()) {
            case FULL_EXIT -> {
                double pnl = (currentPrice - position.entryPrice()) * position.currentQuantity();
                activePositions.get(mode).remove(symbol);
                riskEngine.recordPositionClosed(mode, symbol, pnl);
                log.info("[{}] FULL EXIT executed for symbol={} at price={} P&L={} reason={}",
                        mode, symbol, currentPrice, pnl, action.reason());
            }
            case PARTIAL_EXIT -> {
                double fraction = action.exitQuantityFraction() != null ? action.exitQuantityFraction() : 0.5;
                int exitQty = (int) Math.round(position.currentQuantity() * fraction);
                int remainingQty = position.currentQuantity() - exitQty;
                double newSl = action.newStopLoss() != null ? action.newStopLoss() : position.entryPrice();

                double partialPnl = (currentPrice - position.entryPrice()) * exitQty;
                riskEngine.recordPartialExit(mode, partialPnl);

                PositionSnapshot updated = new PositionSnapshot(
                        position.positionId(),
                        position.strategyId(),
                        position.symbol(),
                        position.exchange(),
                        position.positionSide(),
                        position.initialQuantity(),
                        remainingQty,
                        position.entryPrice(),
                        newSl,
                        position.target1Price(),
                        position.firstCandleHigh(),
                        true,
                        position.entryTime());
                activePositions.get(mode).put(symbol, updated);
                riskEngine.updatePosition(mode, updated);
                log.info("[{}] PARTIAL EXIT booked for symbol={} exitQty={} remainingQty={} newStop={} partialPnl={} reason={}",
                        mode, symbol, exitQty, remainingQty, newSl, partialPnl, action.reason());
            }
            case UPDATE_STOP -> {
                if (action.newStopLoss() != null && action.newStopLoss() > position.currentStopLoss()) {
                    PositionSnapshot updated = new PositionSnapshot(
                            position.positionId(),
                            position.strategyId(),
                            position.symbol(),
                            position.exchange(),
                            position.positionSide(),
                            position.initialQuantity(),
                            position.currentQuantity(),
                            position.entryPrice(),
                            action.newStopLoss(),
                            position.target1Price(),
                            position.firstCandleHigh(),
                            position.target1Booked(),
                            position.entryTime());
                    activePositions.get(mode).put(symbol, updated);
                    riskEngine.updatePosition(mode, updated);
                    log.info("[{}] Ratcheted Stop Loss for symbol={} from {} to {} reason={}",
                            mode, symbol, position.currentStopLoss(), action.newStopLoss(), action.reason());
                }
            }
            default -> {
            }
        }
    }

    /**
     * Active timer sweep to force-close any remaining open positions after cutoff time.
     * Guarantees illiquid stocks with zero ticks do not leak past square-off.
     */
    public void closeExpiredPositions(PortfolioMode mode, LocalTime cutoffTime, Map<String, Double> lastKnownPrices) {
        Map<String, PositionSnapshot> positions = activePositions.get(mode);
        if (positions == null || positions.isEmpty()) {
            return;
        }

        for (PositionSnapshot pos : List.copyOf(positions.values())) {
            synchronized (getSymbolLock(pos.symbol())) {
                if (activePositions.get(mode).containsKey(pos.symbol())) {
                    double exitPrice = (lastKnownPrices != null && lastKnownPrices.containsKey(pos.symbol()))
                            ? lastKnownPrices.get(pos.symbol())
                            : pos.entryPrice();
                    forceClosePosition(mode, pos.symbol(), exitPrice, "SCHEDULED_EOD_HARD_CLOSE_SWEEP");
                }
            }
        }
    }

    /**
     * Resets in-memory position state for a new trading day.
     */
    public void resetForNewDay() {
        for (Map<String, PositionSnapshot> map : activePositions.values()) {
            if (map != null) {
                map.clear();
            }
        }
        symbolLocks.clear();
    }

    /**
     * Re-hydrates active positions into RAM upon crash recovery or application restart.
     */
    public void rehydrateActiveState(PortfolioMode mode, List<PositionSnapshot> openPositions) {
        if (openPositions == null) {
            return;
        }
        for (PositionSnapshot pos : openPositions) {
            registerPosition(mode, pos);
        }
    }

    /**
     * Immediately closes an open position (e.g. manual intervention or emergency
     * risk cutoff).
     */
    public void forceClosePosition(PortfolioMode mode, String symbol, double exitPrice, String reason) {
        synchronized (getSymbolLock(symbol)) {
            PositionSnapshot position = getPosition(mode, symbol);
            if (position != null) {
                double pnl = (exitPrice - position.entryPrice()) * position.currentQuantity();
                activePositions.get(mode).remove(symbol);
                riskEngine.recordPositionClosed(mode, symbol, pnl);
                log.warn("[{}] FORCE CLOSE symbol={} at price={} P&L={} reason={}",
                        mode, symbol, exitPrice, pnl, reason);
            }
        }
    }
}
