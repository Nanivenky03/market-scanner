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
     * Side-aware:
     * - LONG: Sum(CurrentQuantity * max(0.0, EntryPrice - LiveTrailingStop))
     * - SHORT: Sum(CurrentQuantity * max(0.0, LiveTrailingStop - EntryPrice))
     * When trailing stop is moved to Entry (or locked into profit), risk drops to Rs. 0.0,
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
                    boolean isShort = isShortSide(pos.positionSide());
                    double downsidePerShare = isShort
                            ? Math.max(0.0, sl - entry)
                            : Math.max(0.0, entry - sl);
                    return pos.currentQuantity() * downsidePerShare;
                })
                .sum();
    }

    private static boolean isShortSide(String side) {
        return side != null && ("SHORT".equalsIgnoreCase(side) || "SELL".equalsIgnoreCase(side));
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

    /**
     * Calculates remaining available margin for new positions.
     * Formula: max(0.0, CurrentCapital - CommittedMargin)
     */
    public double calculateAvailableMargin(double leverageFactor) {
        return Math.max(0.0, this.currentCapital - calculateTotalCommittedMargin(leverageFactor));
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

    /**
     * Unlocks a symbol if an order was rejected or cancelled before execution.
     */
    public synchronized void unlockSymbol(String symbol) {
        if (symbol != null) {
            tradedSymbolsToday.remove(symbol);
            if (!openPositions.containsKey(symbol) && dailyTradeCount > 0) {
                dailyTradeCount--;
            }
        }
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

    /**
     * Syncs capital with live broker RMS cash/net balance.
     */
    public synchronized void syncBrokerCapital(double liveBrokerCapital) {
        if (liveBrokerCapital > 0.0) {
            this.currentCapital = liveBrokerCapital;
            if (this.initialCapital <= 0.0) {
                this.initialCapital = liveBrokerCapital;
            }
        }
    }

    /**
     * Rehydrates state upon application restart or mid-day crash recovery.
     */
    public synchronized void rehydrate(java.util.List<PositionSnapshot> positions, Integer tradeCount, java.util.Set<String> symbols, Double liveCapital) {
        if (liveCapital != null && liveCapital > 0.0) {
            this.currentCapital = liveCapital;
            if (this.initialCapital <= 0.0) {
                this.initialCapital = liveCapital;
            }
        }
        if (tradeCount != null && tradeCount >= 0) {
            this.dailyTradeCount = tradeCount;
        }
        if (symbols != null) {
            this.tradedSymbolsToday.addAll(symbols);
        }
        if (positions != null) {
            for (PositionSnapshot pos : positions) {
                if (pos != null && pos.symbol() != null) {
                    this.openPositions.put(pos.symbol(), pos);
                    this.tradedSymbolsToday.add(pos.symbol());
                }
            }
        }
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
