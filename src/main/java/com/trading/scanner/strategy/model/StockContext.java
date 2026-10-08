package com.trading.scanner.strategy.model;

import com.trading.scanner.model.DailyStockContext;
import com.trading.scanner.model.MarketCandle;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

/**
 * In-memory micro stock snapshot containing:
 * - Pre-market context and opening 5M/15M reference bars from daily_stock_context
 * - Sliding windows of finalized 1M, 5M, and 15M candles for today
 * - Current market indicators (VWAP, RSI, ATR)
 */
public record StockContext(
        String symbol,
        String exchange,
        LocalDate tradingDate,
        DailyStockContext dailyContext,
        List<MarketCandle> candles1m,
        List<MarketCandle> candles5m,
        List<MarketCandle> candles15m,
        boolean backfillActive
) {

    public StockContext {
        candles1m = candles1m == null ? Collections.emptyList() : Collections.unmodifiableList(candles1m);
        candles5m = candles5m == null ? Collections.emptyList() : Collections.unmodifiableList(candles5m);
        candles15m = candles15m == null ? Collections.emptyList() : Collections.unmodifiableList(candles15m);
    }

    public MarketCandle latest1mCandle() {
        return candles1m.isEmpty() ? null : candles1m.get(candles1m.size() - 1);
    }

    public MarketCandle latest5mCandle() {
        return candles5m.isEmpty() ? null : candles5m.get(candles5m.size() - 1);
    }

    public MarketCandle latest15mCandle() {
        return candles15m.isEmpty() ? null : candles15m.get(candles15m.size() - 1);
    }
}
