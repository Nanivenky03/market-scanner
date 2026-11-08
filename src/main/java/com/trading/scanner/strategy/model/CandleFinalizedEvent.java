package com.trading.scanner.strategy.model;

import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.MarketCandle;

import java.time.LocalDateTime;

/**
 * Event published when an intraday candle (1M, 5M, 15M) has completed,
 * finalized in database, and indicators (VWAP, RSI, ATR) calculated.
 * Enables reactive event-driven strategy execution and position tracking.
 */
public record CandleFinalizedEvent(
        String symbol,
        String exchange,
        CandleTimeframe timeframe,
        LocalDateTime candleTime,
        MarketCandle candle,
        boolean isIndex
) {}
