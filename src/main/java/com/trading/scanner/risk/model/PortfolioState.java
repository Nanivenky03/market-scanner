package com.trading.scanner.risk.model;

import com.trading.scanner.strategy.model.PositionSnapshot;

import java.time.LocalDate;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks intraday state and risk utilization for a specific portfolio mode
 * (PAPER or LIVE).
 */
public class PortfolioState {

    private final PortfolioMode mode;
    private LocalDate tradingDate;
    private double initialCapital;
    private double currentCapital;
    private double realizedPnl;
    private int dailyTradeCount;

    private final Map<String, PositionSnapshot> openPositions = new ConcurrentHashMap<>();
    private final Set<String> tradedSymbolsToday = ConcurrentHashMap.newKeySet();

    public PortfolioState(PortfolioMode mode, double initialCapital, LocalDate tradingDate) {
        this.mode = mode;
        this.initialCapital = initialCapital;
        this.currentCapital = initialCapital;
        this.tradingDate = tradingDate;
        this.realizedPnl = 0.0;
        this.dailyTradeCount = 0;
    }

    /**
     * Calculates the total open downside risk across all currently open positions.
     * Formula: Sum(CurrentQuantity * max(0.0, EntryPrice - LiveTrailingStop))
     * When trailing stop is moved to Entry (or above), risk drops to Rs. 0.0,
     * freeing up risk capacity.
     */
    public double calculateTotalOpenDownsideRisk() {
        return openPositions.values().stream()
                .mapToDouble(pos -> {
                    if (pos == null || pos.entryPrice() == null || pos.currentStopLoss() == null
                            || pos.currentQuantity() == null) {
                        return 0.0;
                    }
                    double entry = pos.entryPrice();
                    double sl = pos.currentStopLoss();
                    double downsidePerShare = Math.max(0.0, entry - sl);
                    return pos.currentQuantity() * downsidePerShare;
                })
                .sum();
    }

    /**
     * Calculates total margin already committed by all active positions.
     * Formula: Sum((CurrentQuantity * EntryPrice) / LeverageFactor)
     */
    public double calculateTotalCommittedMargin(double leverageFactor) {
        double leverage = leverageFactor > 0.0 ? leverageFactor : 5.0;
        return openPositions.values().stream()
                .mapToDouble(pos -> {
                    if (pos == null || pos.currentQuantity() == null || pos.entryPrice() == null) {
                        return 0.0;
                    }
                    return (pos.currentQuantity() * pos.entryPrice()) / leverage;
                })
                .sum();
    }

    public synchronized void recordPartialExit(double pnl) {
        this.realizedPnl += pnl;
        this.currentCapital = this.initialCapital + this.realizedPnl;
    }

    public synchronized void recordTradeExecuted(String symbol, PositionSnapshot position) {
        tradedSymbolsToday.add(symbol);
        dailyTradeCount++;
        openPositions.put(symbol, position);
    }

    public synchronized void updatePosition(PositionSnapshot updatedPosition) {
        if (openPositions.containsKey(updatedPosition.symbol())) {
            openPositions.put(updatedPosition.symbol(), updatedPosition);
        }
    }

    public synchronized void recordPositionClosed(String symbol, double pnl) {
        openPositions.remove(symbol);
        this.realizedPnl += pnl;
        this.currentCapital = this.initialCapital + this.realizedPnl;
    }

    public boolean hasTradedSymbolToday(String symbol) {
        return tradedSymbolsToday.contains(symbol);
    }

    public synchronized void resetForNewDay(LocalDate newDate, double startingCapital) {
        this.tradingDate = newDate;
        this.initialCapital = startingCapital;
        this.currentCapital = startingCapital;
        this.realizedPnl = 0.0;
        this.dailyTradeCount = 0;
        this.openPositions.clear();
        this.tradedSymbolsToday.clear();
    }

    public PortfolioMode getMode() {
        return mode;
    }

    public LocalDate getTradingDate() {
        return tradingDate;
    }

    public double getInitialCapital() {
        return initialCapital;
    }

    public double getCurrentCapital() {
        return currentCapital;
    }

    public double getRealizedPnl() {
        return realizedPnl;
    }

    public int getDailyTradeCount() {
        return dailyTradeCount;
    }

    public Map<String, PositionSnapshot> getOpenPositions() {
        return Collections.unmodifiableMap(openPositions);
    }

    public Set<String> getTradedSymbolsToday() {
        return Collections.unmodifiableSet(tradedSymbolsToday);
    }
}
