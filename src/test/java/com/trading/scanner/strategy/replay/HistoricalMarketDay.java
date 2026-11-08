package com.trading.scanner.strategy.replay;

import com.trading.scanner.model.DailyStockContext;
import com.trading.scanner.model.MarketCandle;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Immutable in-memory container holding all market data for a single replay day.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HistoricalMarketDay {
    private String tradingDate;
    private String scenarioTag;
    private String scenarioDescription;

    @Builder.Default
    private Map<String, DailyStockContext> dailyContexts = new HashMap<>();

    @Builder.Default
    private List<MarketCandle> candles1m = new ArrayList<>();

    @Builder.Default
    private List<MarketCandle> candles5m = new ArrayList<>();

    @Builder.Default
    private List<MarketCandle> candles15m = new ArrayList<>();

    // Cached indexes for fast lookup during replay
    private transient Map<String, List<MarketCandle>> candles1mBySymbol;
    private transient Map<String, List<MarketCandle>> candles5mBySymbol;
    private transient Map<String, List<MarketCandle>> candles15mBySymbol;
    private transient List<LocalTime> distinct1mTimes;

    public void initIndexes() {
        candles1mBySymbol = candles1m.stream().collect(Collectors.groupingBy(MarketCandle::getSymbol));
        candles5mBySymbol = candles5m.stream().collect(Collectors.groupingBy(MarketCandle::getSymbol));
        candles15mBySymbol = candles15m.stream().collect(Collectors.groupingBy(MarketCandle::getSymbol));

        distinct1mTimes = candles1m.stream()
                .filter(c -> "NIFTY".equalsIgnoreCase(c.getSymbol()))
                .map(c -> c.getCandleTime().toLocalTime())
                .sorted()
                .distinct()
                .collect(Collectors.toList());
    }

    public List<LocalTime> getDistinct1mTimes() {
        if (distinct1mTimes == null) {
            initIndexes();
        }
        return distinct1mTimes;
    }

    public DailyStockContext getDailyContext(String symbol) {
        return dailyContexts.get(symbol);
    }

    public MarketCandle get1mCandle(String symbol, LocalTime time) {
        if (candles1mBySymbol == null) {
            initIndexes();
        }
        List<MarketCandle> list = candles1mBySymbol.get(symbol);
        if (list == null) return null;
        for (MarketCandle c : list) {
            if (c.getCandleTime().toLocalTime().equals(time)) {
                return c;
            }
        }
        return null;
    }

    public List<MarketCandle> get5mCandlesUpTo(String symbol, LocalTime time) {
        if (candles5mBySymbol == null) {
            initIndexes();
        }
        List<MarketCandle> list = candles5mBySymbol.get(symbol);
        if (list == null) return Collections.emptyList();
        return list.stream()
                .filter(c -> c.getCandleTime().toLocalTime().isBefore(time))
                .collect(Collectors.toList());
    }

    public List<MarketCandle> get15mCandlesUpTo(String symbol, LocalTime time) {
        if (candles15mBySymbol == null) {
            initIndexes();
        }
        List<MarketCandle> list = candles15mBySymbol.get(symbol);
        if (list == null) return Collections.emptyList();
        return list.stream()
                .filter(c -> c.getCandleTime().toLocalTime().isBefore(time))
                .collect(Collectors.toList());
    }

    public MarketCandle getNiftyCandle1m(LocalTime time) {
        return get1mCandle("NIFTY", time);
    }
}
