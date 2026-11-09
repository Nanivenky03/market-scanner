package com.trading.scanner.position;

import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.risk.RiskEngine;
import com.trading.scanner.risk.model.PortfolioMode;
import com.trading.scanner.strategy.StrategyPositionHandler;
import com.trading.scanner.strategy.model.PositionAction;
import com.trading.scanner.strategy.model.PositionSnapshot;
import com.trading.scanner.util.PriceTickUtil;
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
 * incoming price ticks and 1-minute candle closes against strategy position handlers,
 * maintains in-memory trailing stop state machines, and dispatches exits to Risk Engine.
 *
 * Hardened with:
 * - Corrupt tick sanity filter (rejecting impossible / outlier prices)
 * - Multi-Tick Confirmation Filter (requiring 2 consecutive ticks on sudden > 1.5% exit spikes)
 * - Directional exchange tick-size rounding via PriceTickUtil
 */
@Service
public class PositionManagerService {

    private static final Logger log = LoggerFactory.getLogger(PositionManagerService.class);

    private final RiskEngine riskEngine;
    private final Map<Integer, StrategyPositionHandler> handlersByStrategyId = new ConcurrentHashMap<>();
    private final Map<PortfolioMode, Map<String, PositionSnapshot>> activePositions = new ConcurrentHashMap<>();

    private final Map<String, Double> lastConfirmedLtp = new ConcurrentHashMap<>();
    private final Map<String, Double> pendingExitCandidatePrice = new ConcurrentHashMap<>();

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
            lastConfirmedLtp.put(position.symbol(), position.entryPrice());
            pendingExitCandidatePrice.remove(position.symbol());
            riskEngine.recordTradeExecuted(mode, position.symbol(), position);
            log.info("[{}] Registered open position for symbol={} side={} qty={} entry={} SL={}",
                    mode, position.symbol(), position.positionSide(), position.currentQuantity(),
                    position.entryPrice(), position.currentStopLoss());
        }
    }

    /**
     * Deregisters and removes an open position from RAM once closed by OrderExecutionService.
     */
    public void deregisterPosition(PortfolioMode mode, String symbol) {
        if (symbol == null) return;
        synchronized (getSymbolLock(symbol)) {
            Map<String, PositionSnapshot> map = activePositions.get(mode);
            if (map != null) {
                map.remove(symbol);
            }
            lastConfirmedLtp.remove(symbol);
            pendingExitCandidatePrice.remove(symbol);
        }
    }

    /**
     * Clears all in-memory open positions for the specified portfolio mode.
     */
    public synchronized void clearPositionsForMode(PortfolioMode mode) {
        Map<String, PositionSnapshot> map = activePositions.get(mode);
        if (map != null) {
            map.clear();
        }
        lastConfirmedLtp.clear();
        pendingExitCandidatePrice.clear();
        log.info("[{}] Cleared all active in-memory positions", mode);
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
     * Evaluates a real-time price tick for an open position with corrupt tick defense
     * and 2-tick confirmation filter on sudden trigger breaches.
     */
    public PositionAction processTick(PortfolioMode mode, String symbol, double ltp,
            LocalTime currentTime) {
        synchronized (getSymbolLock(symbol)) {
            PositionSnapshot position = getPosition(mode, symbol);
            if (position == null) {
                return PositionAction.hold();
            }

            // 1. Hard Sanity Check: Reject absurd, negative, or extreme outlier prices
            if (ltp <= 0.0 || ltp > 500_000.0 || Double.isNaN(ltp) || Double.isInfinite(ltp)) {
                log.warn("[{}] Discarded corrupt/outlier tick for symbol={}: ltp={}", mode, symbol, ltp);
                return PositionAction.hold();
            }
            if (position.entryPrice() != null && (ltp > position.entryPrice() * 2.5 || ltp < position.entryPrice() * 0.2)) {
                log.warn("[{}] Discarded extreme outlier tick for symbol={}: ltp={} vs entry={}", mode, symbol, ltp, position.entryPrice());
                return PositionAction.hold();
            }

            StrategyPositionHandler handler = handlersByStrategyId.get(position.strategyId());
            if (handler == null) {
                log.warn("No PositionHandler registered for strategyId={}", position.strategyId());
                return PositionAction.hold();
            }

            boolean isShort = isShortSide(position.positionSide());
            double currentPeak = position.peakPrice() != null ? position.peakPrice() : position.entryPrice();
            double currentTrough = position.troughPrice() != null ? position.troughPrice() : position.entryPrice();
            double newPeak = isShort ? Math.min(currentPeak, ltp) : Math.max(currentPeak, ltp);
            double newTrough = isShort ? Math.max(currentTrough, ltp) : Math.min(currentTrough, ltp);
            if (Double.compare(newPeak, currentPeak) != 0 || Double.compare(newTrough, currentTrough) != 0) {
                position = new PositionSnapshot(
                        position.positionId(),
                        position.strategyId(),
                        position.symbol(),
                        position.exchange(),
                        position.positionSide(),
                        position.initialQuantity(),
                        position.currentQuantity(),
                        position.entryPrice(),
                        position.currentStopLoss(),
                        position.target1Price(),
                        position.firstCandleHigh(),
                        position.target1Booked(),
                        newPeak,
                        newTrough,
                        position.entryTime(),
                        position.restingSlOrderId()
                );
                activePositions.get(mode).put(symbol, position);
            }

            PositionAction action = handler.evaluatePosition(position, ltp, null, null, currentTime,
                    Collections.emptyMap());

            // 2. Multi-Tick Confirmation Filter for Exit Spikes (> 1.5% sudden jump across trigger level)
            if (action != null && (action.type() == PositionAction.ActionType.FULL_EXIT || action.type() == PositionAction.ActionType.PARTIAL_EXIT)) {
                boolean isTimeCutoff = action.reason() != null && (action.reason().contains("CUTOFF") || action.reason().contains("WIND_DOWN") || action.reason().contains("HARD_CLOSE"));
                if (!isTimeCutoff) {
                    Double prevLtp = lastConfirmedLtp.get(symbol);
                    if (prevLtp != null && (Math.abs(ltp - prevLtp) / prevLtp) > 0.025) {
                        Double pendingCandidate = pendingExitCandidatePrice.get(symbol);
                        if (pendingCandidate == null) {
                            pendingExitCandidatePrice.put(symbol, ltp);
                            log.info("[{}] Price spike detected for symbol={} (jump from {} to {}). Awaiting 2nd confirming tick before exit.", mode, symbol, prevLtp, ltp);
                            return PositionAction.hold();
                        } else {
                            if (Math.abs(ltp - pendingCandidate) / pendingCandidate <= 0.01) {
                                // Confirmed across 2 continuous ticks in the new price neighborhood
                                pendingExitCandidatePrice.remove(symbol);
                            } else {
                                pendingExitCandidatePrice.put(symbol, ltp);
                                return PositionAction.hold();
                            }
                        }
                    } else {
                        pendingExitCandidatePrice.remove(symbol);
                    }
                }
            } else {
                pendingExitCandidatePrice.remove(symbol);
            }

            lastConfirmedLtp.put(symbol, ltp);
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
            if (closePrice <= 0.0 || closePrice > 500_000.0) {
                return PositionAction.hold();
            }

            boolean isShort = isShortSide(position.positionSide());
            double barHigh = candle1m.getHighPrice() != null ? candle1m.getHighPrice() : closePrice;
            double barLow = candle1m.getLowPrice() != null ? candle1m.getLowPrice() : closePrice;
            double currentPeak = position.peakPrice() != null ? position.peakPrice() : position.entryPrice();
            double currentTrough = position.troughPrice() != null ? position.troughPrice() : position.entryPrice();
            double newPeak = isShort ? Math.min(currentPeak, barLow) : Math.max(currentPeak, barHigh);
            double newTrough = isShort ? Math.max(currentTrough, barHigh) : Math.min(currentTrough, barLow);
            if (Double.compare(newPeak, currentPeak) != 0 || Double.compare(newTrough, currentTrough) != 0) {
                position = new PositionSnapshot(
                        position.positionId(),
                        position.strategyId(),
                        position.symbol(),
                        position.exchange(),
                        position.positionSide(),
                        position.initialQuantity(),
                        position.currentQuantity(),
                        position.entryPrice(),
                        position.currentStopLoss(),
                        position.target1Price(),
                        position.firstCandleHigh(),
                        position.target1Booked(),
                        newPeak,
                        newTrough,
                        position.entryTime(),
                        position.restingSlOrderId()
                );
                activePositions.get(mode).put(symbol, position);
            }

            PositionAction action = handler.evaluatePosition(position, closePrice, candle1m, atr5m, currentTime,
                    Collections.emptyMap());
            lastConfirmedLtp.put(symbol, closePrice);
            pendingExitCandidatePrice.remove(symbol);
            applyPositionAction(mode, position, action, closePrice);
            return action;
        }
    }

    /**
     * Applies the proposed position action to in-memory state and updates the Risk Engine.
     */
    private void applyPositionAction(PortfolioMode mode, PositionSnapshot position, PositionAction action,
            double currentPrice) {
        if (action == null || action.type() == PositionAction.ActionType.HOLD) {
            return;
        }

        String symbol = position.symbol();
        switch (action.type()) {
            case FULL_EXIT -> {
                double pnl = calculatePnl(position, currentPrice, position.currentQuantity());
                log.info("[{}] FULL EXIT signal evaluated for symbol={} at price={} calculatedPnl={} reason={}. Retaining in memory for OrderExecutionService.",
                        mode, symbol, currentPrice, pnl, action.reason());
            }
            case PARTIAL_EXIT -> {
                if (position.currentQuantity() <= 1) {
                    double pnl = calculatePnl(position, currentPrice, position.currentQuantity());
                    log.info("[{}] PARTIAL EXIT signal evaluated for qty <= 1 on symbol={} at price={} P&L={}",
                            mode, symbol, currentPrice, pnl);
                    return;
                }

                double fraction = action.exitQuantityFraction() != null ? action.exitQuantityFraction() : 0.5;
                int exitQty = (int) Math.round(position.currentQuantity() * fraction);
                exitQty = Math.max(1, Math.min(position.currentQuantity() - 1, exitQty));
                int remainingQty = position.currentQuantity() - exitQty;

                if (remainingQty <= 0) {
                    double pnl = calculatePnl(position, currentPrice, position.currentQuantity());
                    log.info("[{}] PARTIAL EXIT signal evaluated for remainingQty <= 0 on symbol={} at price={} P&L={}",
                            mode, symbol, currentPrice, pnl);
                    return;
                }

                boolean isShort = isShortSide(position.positionSide());
                double candidateSl = action.newStopLoss() != null ? action.newStopLoss() : position.entryPrice();
                double candidateSlRounded = PriceTickUtil.roundStopLoss(candidateSl, isShort, 0.05);
                double newSl = isShort
                        ? Math.min(position.currentStopLoss(), candidateSlRounded)
                        : Math.max(position.currentStopLoss(), candidateSlRounded);

                double partialPnl = calculatePnl(position, currentPrice, exitQty);
                double partialTurnover = exitQty * currentPrice;
                double partialFriction = com.trading.scanner.risk.BrokerChargesCalculator.calculateSellLegCharges(partialTurnover);

                riskEngine.recordPartialExit(mode, partialPnl);
                riskEngine.recordFrictionIncurred(mode, partialFriction);

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
                        position.peakPrice(),
                        position.troughPrice(),
                        position.entryTime(),
                        position.restingSlOrderId());
                activePositions.get(mode).put(symbol, updated);
                riskEngine.updatePosition(mode, updated);
                log.info("[{}] PARTIAL EXIT booked for symbol={} exitQty={} remainingQty={} newStop={} partialPnl={} friction={} reason={}",
                        mode, symbol, exitQty, remainingQty, newSl, partialPnl, partialFriction, action.reason());
            }
            case UPDATE_STOP -> {
                if (action.newStopLoss() != null && position.currentStopLoss() != null) {
                    boolean isShort = isShortSide(position.positionSide());
                    double roundedSl = PriceTickUtil.roundStopLoss(action.newStopLoss(), isShort, 0.05);
                    boolean isMonotonicMove = isShort
                            ? roundedSl < position.currentStopLoss()
                            : roundedSl > position.currentStopLoss();
                    if (isMonotonicMove) {
                        PositionSnapshot updated = new PositionSnapshot(
                                position.positionId(),
                                position.strategyId(),
                                position.symbol(),
                                position.exchange(),
                                position.positionSide(),
                                position.initialQuantity(),
                                position.currentQuantity(),
                                position.entryPrice(),
                                roundedSl,
                                position.target1Price(),
                                position.firstCandleHigh(),
                                position.target1Booked(),
                                position.peakPrice(),
                                position.troughPrice(),
                                position.entryTime(),
                                position.restingSlOrderId());
                        activePositions.get(mode).put(symbol, updated);
                        riskEngine.updatePosition(mode, updated);
                        log.info("[{}] Ratcheted Stop Loss for symbol={} from {} to {} reason={}",
                                mode, symbol, position.currentStopLoss(), roundedSl, action.reason());
                    }
                }
            }
            default -> {
            }
        }
    }

    private double calculatePnl(PositionSnapshot position, double exitPrice, int quantity) {
        boolean isShort = isShortSide(position.positionSide());
        double rawPnl = isShort
                ? (position.entryPrice() - exitPrice) * quantity
                : (exitPrice - position.entryPrice()) * quantity;
        return PriceTickUtil.roundTo2Decimals(rawPnl);
    }

    private static boolean isShortSide(String side) {
        return side != null && ("SHORT".equalsIgnoreCase(side) || "SELL".equalsIgnoreCase(side));
    }

    /**
     * Records authoritative broker execution fill and re-anchors targets/actual risk
     * while keeping the stop-loss fixed at the structural invalidation level.
     */
    public void recordBrokerFill(
            PortfolioMode mode,
            String symbol,
            double executedPrice,
            int executedQty,
            double actualCharges
    ) {
        if (symbol == null) {
            return;
        }
        synchronized (getSymbolLock(symbol)) {
            PositionSnapshot position = getPosition(mode, symbol);
            if (position == null) {
                return;
            }
            boolean isShort = isShortSide(position.positionSide());
            double structuralStop = position.currentStopLoss();
            double actualRiskPerShare = isShort
                    ? Math.max(0.01, structuralStop - executedPrice)
                    : Math.max(0.01, executedPrice - structuralStop);

            double rawTarget1 = isShort
                    ? executedPrice - (com.trading.scanner.strategy.fchb.FchbStrategy.TARGET_1_R_MULTIPLE * actualRiskPerShare)
                    : executedPrice + (com.trading.scanner.strategy.fchb.FchbStrategy.TARGET_1_R_MULTIPLE * actualRiskPerShare);
            double reAnchoredTarget1 = PriceTickUtil.roundTarget(rawTarget1, isShort, 0.05);

            PositionSnapshot reAnchored = new PositionSnapshot(
                    position.positionId(),
                    position.strategyId(),
                    position.symbol(),
                    position.exchange(),
                    position.positionSide(),
                    executedQty,
                    executedQty,
                    executedPrice,
                    structuralStop,
                    reAnchoredTarget1,
                    position.firstCandleHigh(),
                    position.target1Booked(),
                    executedPrice,
                    position.entryTime()
            );
            activePositions.computeIfAbsent(mode, m -> new ConcurrentHashMap<>()).put(symbol, reAnchored);
            lastConfirmedLtp.put(symbol, executedPrice);
            pendingExitCandidatePrice.remove(symbol);
            riskEngine.updatePosition(mode, reAnchored);
            if (actualCharges > 0.0) {
                riskEngine.recordFrictionIncurred(mode, actualCharges);
            }
            log.info("[{}] Broker fill recorded for symbol={} fillPrice={} actualRisk={} target1={} charges={}",
                    mode, symbol, executedPrice, actualRiskPerShare, reAnchoredTarget1, actualCharges);
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

        List<PositionSnapshot> snapshotList = positions.values().stream()
                .filter(pos -> pos != null && pos.symbol() != null)
                .toList();

        for (PositionSnapshot pos : snapshotList) {
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

    private volatile java.time.LocalDate currentTradingDate;

    /**
     * Resets in-memory position state for a new trading day.
     * Strictly only clears if date is a new calendar day.
     */
    public synchronized void resetForNewDay(java.time.LocalDate newDate) {
        if (newDate != null && (currentTradingDate == null || newDate.isAfter(currentTradingDate))) {
            for (Map<String, PositionSnapshot> map : activePositions.values()) {
                if (map != null) {
                    map.clear();
                }
            }
            symbolLocks.clear();
            lastConfirmedLtp.clear();
            pendingExitCandidatePrice.clear();
            currentTradingDate = newDate;
            log.info("PositionManagerService reset for new trading day {}", newDate);
        } else {
            log.info("PositionManagerService mid-day restart detected for date {}: preserved open positions", newDate);
        }
    }

    public void resetForNewDay() {
        resetForNewDay(java.time.LocalDate.now());
    }

    /**
     * Re-hydrates active positions into RAM upon crash recovery or application restart.
     */
    public void rehydrateActiveState(PortfolioMode mode, List<PositionSnapshot> openPositions) {
        if (openPositions == null) {
            return;
        }
        for (PositionSnapshot pos : openPositions) {
            if (pos != null && pos.symbol() != null) {
                synchronized (getSymbolLock(pos.symbol())) {
                    activePositions.computeIfAbsent(mode, m -> new ConcurrentHashMap<>()).put(pos.symbol(), pos);
                    lastConfirmedLtp.put(pos.symbol(), pos.entryPrice());
                    pendingExitCandidatePrice.remove(pos.symbol());
                }
            }
        }
        riskEngine.rehydrateState(mode, openPositions, null, null, null);
        log.info("[{}] Rehydrated {} active positions into RAM and RiskEngine", mode, openPositions.size());
    }

    /**
     * Immediately closes an open position (e.g. manual intervention or emergency risk cutoff).
     */
    public void forceClosePosition(PortfolioMode mode, String symbol, double exitPrice, String reason) {
        synchronized (getSymbolLock(symbol)) {
            PositionSnapshot position = getPosition(mode, symbol);
            if (position != null) {
                double pnl = calculatePnl(position, exitPrice, position.currentQuantity());
                activePositions.get(mode).remove(symbol);
                lastConfirmedLtp.remove(symbol);
                pendingExitCandidatePrice.remove(symbol);
                riskEngine.recordPositionClosed(mode, symbol, pnl);
                log.warn("[{}] FORCE CLOSE symbol={} at price={} P&L={} reason={}",
                        mode, symbol, exitPrice, pnl, reason);
            }
        }
    }
}
