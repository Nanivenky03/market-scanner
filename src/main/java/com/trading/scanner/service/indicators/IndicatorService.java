package com.trading.scanner.service.indicators;

import com.trading.scanner.model.StockPrice;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Indicator computation service.
 * 
 * CRITICAL DESIGN PRINCIPLES:
 * 1. Input prices MUST be normalized (oldest → newest) before calling
 * 2. Returns full time series arrays, not single values
 * 3. No subList bugs - uses indexed iteration
 * 4. Deterministic - same input always produces same output
 * 5. No side effects - pure computation
 * 
 * This enables future bar-by-bar backtesting without refactoring.
 */
@Slf4j
@Service
public class IndicatorService {
    
    /**
     * Compute all indicators for a stock.
     * 
     * @param prices Price data (MUST be oldest → newest)
     * @return IndicatorBundle with all computed time series
     */
    public IndicatorBundle computeIndicators(List<StockPrice> prices) {
        
        if (prices == null || prices.isEmpty()) {
            log.warn("Empty price data provided to indicator service");
            return null;
        }
        
        // CRITICAL: Verify price ordering (oldest → newest)
        if (!isPriceOrderingValid(prices)) {
            log.error("Price data not properly ordered (must be oldest → newest)");
            return null;
        }
        
        String symbol = prices.get(0).getSymbol();
        
        try {
            // Compute indicator time series
            List<Double> sma50Series = computeSMASeries(prices, 50);
            List<Double> sma200Series = computeSMASeries(prices, 200);
            List<Double> atr14Series = computeATRSeries(prices, 14);
            List<Double> volumeAvg20Series = computeVolumeAvgSeries(prices, 20);
            
            return IndicatorBundle.builder()
                .symbol(symbol)
                .barCount(prices.size())
                .sma50(sma50Series)
                .sma200(sma200Series)
                .atr14(atr14Series)
                .volumeAvg20(volumeAvg20Series)
                .build();
                
        } catch (Exception e) {
            log.error("Error computing indicators for {}: {}", symbol, e.getMessage());
            return null;
        }
    }
    
    /**
     * Compute SMA time series.
     * Returns array aligned with input prices (oldest → newest).
     * First (period-1) elements will be null.
     */
    private List<Double> computeSMASeries(List<StockPrice> prices, int period) {
        List<Double> sma = new ArrayList<>(Collections.nCopies(prices.size(), null));
        
        for (int i = period - 1; i < prices.size(); i++) {
            double sum = 0.0;
            
            // Sum last 'period' adjusted closes
            for (int j = i - period + 1; j <= i; j++) {
                sum += prices.get(j).getAdjClose();
            }
            
            sma.set(i, sum / period);
        }
        
        return sma;
    }
    
    /**
     * Compute ATR time series.
     * Returns array aligned with input prices (oldest → newest).
     * First 'period' elements will be null (need previous close for TR).
     */
    private List<Double> computeATRSeries(List<StockPrice> prices, int period) {
        List<Double> atr = new ArrayList<>(Collections.nCopies(prices.size(), null));
        
        // First compute True Range series
        List<Double> trSeries = new ArrayList<>();
        
        for (int i = 1; i < prices.size(); i++) {
            StockPrice current = prices.get(i);
            StockPrice previous = prices.get(i - 1);
            
            double highLow = current.getHigh() - current.getLow();
            double highPrevClose = Math.abs(current.getHigh() - previous.getAdjClose());
            double lowPrevClose = Math.abs(current.getLow() - previous.getAdjClose());
            
            double tr = Math.max(highLow, Math.max(highPrevClose, lowPrevClose));
            trSeries.add(tr);
        }
        
        // Now compute ATR as SMA of True Range
        for (int i = period; i < prices.size(); i++) {
            // For price index i, TR index is i-1 (since TR starts from index 1)
            int trEndIndex = i - 1;
            int trStartIndex = trEndIndex - period + 1;
            
            if (trStartIndex < 0) continue;
            
            double sum = 0.0;
            for (int j = trStartIndex; j <= trEndIndex; j++) {
                sum += trSeries.get(j);
            }
            
            atr.set(i, sum / period);
        }
        
        return atr;
    }
    
    /**
     * Compute Volume Average time series.
     * Returns array aligned with input prices (oldest → newest).
     * First (period-1) elements will be null.
     */
    private List<Double> computeVolumeAvgSeries(List<StockPrice> prices, int period) {
        List<Double> volumeAvg = new ArrayList<>(Collections.nCopies(prices.size(), null));
        
        for (int i = period - 1; i < prices.size(); i++) {
            double sum = 0.0;
            
            for (int j = i - period + 1; j <= i; j++) {
                sum += prices.get(j).getVolume();
            }
            
            volumeAvg.set(i, sum / period);
        }
        
        return volumeAvg;
    }
    
    /**
     * Verify price data is properly ordered (oldest → newest).
     * This is a system invariant that must be enforced.
     */
    private boolean isPriceOrderingValid(List<StockPrice> prices) {
        if (prices.size() < 2) return true;
        
        // Check first and last dates
        return prices.get(0).getDate().isBefore(prices.get(prices.size() - 1).getDate()) ||
               prices.get(0).getDate().isEqual(prices.get(prices.size() - 1).getDate());
    }
    
    /**
     * Detect ATR compression.
     * Returns number of consecutive days compressed.
     * 
     * @param bundle Pre-computed indicator bundle
     * @param lookback How far back to compare (e.g., 30 days)
     * @param threshold Compression ratio threshold (e.g., 0.70)
     */
    public int detectCompressionDuration(IndicatorBundle bundle, int lookback, double threshold) {
        
        if (bundle == null || bundle.getAtr14() == null) {
            return 0;
        }
        
        List<Double> atrSeries = bundle.getAtr14();
        int currentIndex = atrSeries.size() - 1;
        int baselineIndex = currentIndex - lookback;
        
        if (baselineIndex < 0 || atrSeries.get(baselineIndex) == null) {
            return 0;
        }
        
        Double currentATR = atrSeries.get(currentIndex);
        Double baselineATR = atrSeries.get(baselineIndex);
        
        if (currentATR == null || baselineATR == null || baselineATR == 0) {
            return 0;
        }
        
        double compressionRatio = currentATR / baselineATR;
        
        if (compressionRatio >= threshold) {
            return 0;  // Not compressed
        }
        
        // Count consecutive compression days
        int compressionDays = 0;
        
        for (int i = currentIndex; i > baselineIndex && i >= 0; i--) {
            Double atr = atrSeries.get(i);
            
            if (atr != null && atr < baselineATR) {
                compressionDays++;
            } else {
                break;  // Streak broken
            }
        }
        
        return compressionDays;
    }
    
    /**
     * Check if price closed in top X% of daily range.
     * 
     * @param candle Stock price bar
     * @param threshold Minimum close position (0.80 = top 20%)
     */
    public boolean isStrongClose(StockPrice candle, double threshold) {
        double range = candle.getHigh() - candle.getLow();
        
        if (range == 0) {
            return true;  // Flat day, consider neutral/acceptable
        }
        
        double closePosition = (candle.getClose() - candle.getLow()) / range;
        return closePosition >= threshold;
    }
    
    /**
     * Check if SMA is trending up over lookback period.
     * 
     * @param bundle Pre-computed indicator bundle
     * @param smaPeriod Which SMA to check (50 or 200)
     * @param lookback How many bars back to compare
     */
    public boolean isSMATrendingUp(IndicatorBundle bundle, int smaPeriod, int lookback) {
        
        List<Double> smaSeries = (smaPeriod == 50) ? bundle.getSma50() : bundle.getSma200();
        
        if (smaSeries == null || smaSeries.size() <= lookback) {
            return false;
        }
        
        Double currentSMA = smaSeries.get(smaSeries.size() - 1);
        Double previousSMA = smaSeries.get(smaSeries.size() - 1 - lookback);
        
        if (currentSMA == null || previousSMA == null) {
            return false;
        }
        
        return currentSMA > previousSMA;
    }
}
