package com.trading.scanner.service.engine;

import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.model.StockPrice;

import java.util.List;

public record SymbolContext(
        String symbol,
        String exchange,
        String timeframe,
        MarketCandle triggerCandle,
        List<MarketCandle> recentCandles,
        List<StockPrice> dailyPrices) {
}