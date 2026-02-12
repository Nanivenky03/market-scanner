package com.trading.scanner.service.indicators;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * Container for all computed indicators for a stock.
 * 
 * Design principle: Indicators are computed ONCE per scan as time series arrays.
 * Rules read from this bundle - they NEVER recompute indicators.
 * 
 * Arrays are aligned with price data (oldest → newest).
 * 
 * This structure enables:
 * - Current value lookup: bundle.getSma50().get(bundle.getSma50().size() - 1)
 * - Historical value lookup: bundle.getSma50().get(index)
 * - Future bar-by-bar backtesting without refactoring
 */
@Data
@Builder
public class IndicatorBundle {
    
    /**
     * Symbol for this indicator set
     */
    private String symbol;
    
    /**
     * Number of price bars used to compute indicators
     */
    private int barCount;
    
    /**
     * Simple Moving Average (50-period) time series
     * Aligned with price data (oldest → newest)
     */
    private List<Double> sma50;
    
    /**
     * Simple Moving Average (200-period) time series
     * Aligned with price data (oldest → newest)
     */
    private List<Double> sma200;
    
    /**
     * Average True Range (14-period) time series
     * Aligned with price data (oldest → newest)
     */
    private List<Double> atr14;
    
    /**
     * Volume Average (20-period) time series
     * Aligned with price data (oldest → newest)
     */
    private List<Double> volumeAvg20;
    
    /**
     * Get current (most recent) SMA 50 value
     */
    public Double getCurrentSma50() {
        return sma50 != null && !sma50.isEmpty() ? 
            sma50.get(sma50.size() - 1) : null;
    }
    
    /**
     * Get current (most recent) SMA 200 value
     */
    public Double getCurrentSma200() {
        return sma200 != null && !sma200.isEmpty() ? 
            sma200.get(sma200.size() - 1) : null;
    }
    
    /**
     * Get current (most recent) ATR value
     */
    public Double getCurrentAtr() {
        return atr14 != null && !atr14.isEmpty() ? 
            atr14.get(atr14.size() - 1) : null;
    }
    
    /**
     * Get current (most recent) volume average
     */
    public Double getCurrentVolumeAvg() {
        return volumeAvg20 != null && !volumeAvg20.isEmpty() ? 
            volumeAvg20.get(volumeAvg20.size() - 1) : null;
    }
    
    /**
     * Get SMA 50 value from N bars ago
     * @param barsAgo Number of bars back (0 = current)
     */
    public Double getSma50BarsAgo(int barsAgo) {
        if (sma50 == null || sma50.size() <= barsAgo) return null;
        return sma50.get(sma50.size() - 1 - barsAgo);
    }
    
    /**
     * Get ATR value from N bars ago
     * @param barsAgo Number of bars back (0 = current)
     */
    public Double getAtrBarsAgo(int barsAgo) {
        if (atr14 == null || atr14.size() <= barsAgo) return null;
        return atr14.get(atr14.size() - 1 - barsAgo);
    }
    
    /**
     * Check if bundle has sufficient data
     */
    public boolean isValid() {
        return sma50 != null && sma200 != null && 
               atr14 != null && volumeAvg20 != null &&
               getCurrentSma50() != null && getCurrentSma200() != null &&
               getCurrentAtr() != null && getCurrentVolumeAvg() != null;
    }
}
