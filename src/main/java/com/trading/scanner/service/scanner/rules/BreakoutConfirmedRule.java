package com.trading.scanner.service.scanner.rules;

import com.google.gson.Gson;
import com.trading.scanner.model.ScanResult;
import com.trading.scanner.model.StockPrice;
import com.trading.scanner.service.data.DataQualityService;
import com.trading.scanner.service.indicators.IndicatorBundle;
import com.trading.scanner.service.indicators.IndicatorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Confirmed Breakout Detection Rule
 * 
 * DESIGN PRINCIPLES:
 * - Reads ONLY from IndicatorBundle (no indicator recomputation)
 * - Implements professional gap filter
 * - Uses proper array indexing (no subList bugs)
 * - Stores comprehensive metadata
 * - Includes scanner version for future analytics
 * 
 * CONDITIONS (ALL must be true):
 * 1. Price breakout above 20-day high (with buffer)
 * 2. Strong close (top X% of daily range)
 * 3. Volume spike (X% above average)
 * 4. ATR compression (volatility contraction)
 * 5. Trend alignment (golden cross + uptrend)
 * 6. Liquidity filter (minimum average volume)
 * 7. Gap filter (reject news-driven gaps)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BreakoutConfirmedRule implements ScannerRule {
    
    private final IndicatorService indicatorService;
    private final DataQualityService dataQualityService;
    private final Gson gson = new Gson();
    
    // Scanner version for tracking rule evolution
    private static final String SCANNER_VERSION = "1.1.0";
    
    @Override
    public ScanResult evaluate(
            List<StockPrice> prices,  // MUST be oldest → newest
            IndicatorBundle indicators,
            Map<String, Object> config) {
        
        if (prices.size() < 250) {
            return null;  // Insufficient data
        }
        
        // Verify indicators are valid
        if (indicators == null || !indicators.isValid()) {
            log.warn("Invalid indicator bundle for {}", 
                prices.isEmpty() ? "UNKNOWN" : prices.get(0).getSymbol());
            return null;
        }
        
        // Current bar (most recent)
        int currentIndex = prices.size() - 1;
        StockPrice current = prices.get(currentIndex);
        String symbol = current.getSymbol();
        
        // Extract configuration
        double volumeThreshold = getConfigDouble(config, "volume_threshold", 1.4);
        int compressionDaysMin = getConfigInt(config, "compression_days_min", 10);
        double atrCompressionRatio = getConfigDouble(config, "atr_compression_ratio", 0.70);
        double breakoutBuffer = getConfigDouble(config, "breakout_buffer", 1.01);
        double closeInRangeTop = getConfigDouble(config, "close_in_range_top", 0.80);
        boolean requireGoldenCross = getConfigBoolean(config, "require_golden_cross", true);
        double minAvgVolume = getConfigDouble(config, "min_avg_volume", 100000);
        double highConfidenceVolume = getConfigDouble(config, "high_confidence_volume", 1.8);
        int highConfidenceCompression = getConfigInt(config, "high_confidence_compression", 15);
        double maxGapPercent = getConfigDouble(config, "max_gap_percent", 5.0);
        
        try {
            // ========================================
            // PRE-FILTER: GAP CHECK (Professional Standard)
            // ========================================
            if (!passesGapFilter(prices, currentIndex, maxGapPercent, volumeThreshold)) {
                return null;  // Reject news-driven gaps
            }
            
            // ========================================
            // CONDITION 1: Price Breakout
            // ========================================
            double highest20Day = findHighest20DayHigh(prices, currentIndex);
            double breakoutLevel = highest20Day * breakoutBuffer;
            
            if (current.getAdjClose() <= breakoutLevel) {
                return null;  // No breakout
            }
            
            // ========================================
            // CONDITION 2: Strong Close Position
            // ========================================
            if (!indicatorService.isStrongClose(current, closeInRangeTop)) {
                return null;  // Weak close
            }
            
            // ========================================
            // CONDITION 3: Volume Confirmation
            // ========================================
            Double avgVolume = indicators.getCurrentVolumeAvg();
            if (avgVolume == null || avgVolume < minAvgVolume) {
                return null;  // Low liquidity
            }
            
            double volumeRatio = current.getVolume().doubleValue() / avgVolume;
            if (volumeRatio < volumeThreshold) {
                return null;  // Insufficient volume spike
            }
            
            // ========================================
            // CONDITION 4: ATR Compression
            // ========================================
            int compressionDays = indicatorService.detectCompressionDuration(
                indicators, 30, atrCompressionRatio);
            
            if (compressionDays < compressionDaysMin) {
                return null;  // No meaningful compression
            }
            
            // ========================================
            // CONDITION 5: Trend Alignment
            // ========================================
            Double sma50 = indicators.getCurrentSma50();
            Double sma200 = indicators.getCurrentSma200();
            
            if (sma50 == null || sma200 == null) {
                return null;  // Insufficient data
            }
            
            // Price above short-term trend
            if (current.getAdjClose() <= sma50) {
                return null;
            }
            
            // Golden cross check
            if (requireGoldenCross && sma50 <= sma200) {
                return null;  // Death cross or neutral
            }
            
            // SMA 50 trending up
            if (!indicatorService.isSMATrendingUp(indicators, 50, 10)) {
                return null;
            }
            
            // ========================================
            // ALL CONDITIONS PASSED - Build Result
            // ========================================
            
            // Determine confidence level
            String confidence = determineConfidence(
                volumeRatio, compressionDays, 
                highConfidenceVolume, highConfidenceCompression);
            
            // Build comprehensive metadata
            Map<String, Object> metadata = buildMetadata(
                current, highest20Day, volumeRatio, compressionDays,
                sma50, sma200, indicators.getCurrentAtr());
            
            String metadataJson = gson.toJson(metadata);
            
            ScanResult result = ScanResult.builder()
                .scanDate(LocalDate.now())
                .symbol(symbol)
                .classification(getClassification())
                .confidence(confidence)
                .metadata(metadataJson)
                .scannerVersion(SCANNER_VERSION)
                .build();
            
            log.info("BREAKOUT: {} | Confidence: {} | Vol: {:.2f}x | Compression: {}d", 
                symbol, confidence, volumeRatio, compressionDays);
            
            return result;
            
        } catch (Exception e) {
            log.error("Error evaluating breakout for {}: {}", symbol, e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Gap filter: Reject breakouts driven by overnight news gaps.
     * 
     * Exception: Allow gap if volume is exceptional (market is confirming the gap)
     */
    private boolean passesGapFilter(
            List<StockPrice> prices, 
            int currentIndex, 
            double maxGapPercent,
            double volumeThreshold) {
        
        if (currentIndex == 0) return true;  // First bar has no gap
        
        StockPrice previous = prices.get(currentIndex - 1);
        StockPrice current = prices.get(currentIndex);
        
        double gapPercent = Math.abs((current.getOpen() - previous.getAdjClose()) / 
                                     previous.getAdjClose() * 100);
        
        if (gapPercent <= maxGapPercent) {
            return true;  // Normal gap, acceptable
        }
        
        // Large gap detected - check if volume is exceptional
        // If volume is 2x+ threshold, market is confirming the gap
        Double avgVolume = current.getVolume() > 0 ? 
            calculateVolumeAverage(prices, currentIndex, 20) : null;
        
        if (avgVolume != null && avgVolume > 0) {
            double volumeRatio = current.getVolume().doubleValue() / avgVolume;
            
            if (volumeRatio >= volumeThreshold * 2.0) {
                log.info("Large gap ({:.2f}%) on {} accepted due to exceptional volume ({:.2f}x)", 
                    gapPercent, current.getSymbol(), volumeRatio);
                return true;  // Exceptional volume confirms gap
            }
        }
        
        log.debug("Breakout rejected for {} due to {:.2f}% overnight gap", 
            current.getSymbol(), gapPercent);
        return false;  // Reject gap-driven breakout
    }
    
    /**
     * Find highest high over last 20 days (excluding current)
     */
    private double findHighest20DayHigh(List<StockPrice> prices, int currentIndex) {
        int startIndex = Math.max(0, currentIndex - 20);
        int endIndex = currentIndex - 1;  // Exclude current
        
        double highest = 0.0;
        for (int i = startIndex; i <= endIndex && i < prices.size(); i++) {
            if (i >= 0) {
                highest = Math.max(highest, prices.get(i).getHigh());
            }
        }
        
        return highest;
    }
    
    /**
     * Calculate volume average (fallback for gap filter)
     */
    private Double calculateVolumeAverage(List<StockPrice> prices, int endIndex, int period) {
        int startIndex = Math.max(0, endIndex - period + 1);
        
        if (startIndex >= endIndex) return null;
        
        long sum = 0;
        int count = 0;
        
        for (int i = startIndex; i <= endIndex && i < prices.size(); i++) {
            if (i >= 0) {
                sum += prices.get(i).getVolume();
                count++;
            }
        }
        
        return count > 0 ? (double) sum / count : null;
    }
    
    /**
     * Determine confidence level based on signal strength
     */
    private String determineConfidence(
            double volumeRatio, 
            int compressionDays,
            double highVolThreshold, 
            int highCompThreshold) {
        
        if (volumeRatio >= highVolThreshold && compressionDays >= highCompThreshold) {
            return "HIGH";
        }
        
        return "MODERATE";
    }
    
    /**
     * Build comprehensive metadata for signal
     */
    private Map<String, Object> buildMetadata(
            StockPrice current,
            double breakoutLevel,
            double volumeRatio,
            int compressionDays,
            double sma50,
            double sma200,
            Double atr) {
        
        Map<String, Object> metadata = new HashMap<>();
        
        metadata.put("price", String.format("%.2f", current.getAdjClose()));
        metadata.put("breakout_level", String.format("%.2f", breakoutLevel));
        metadata.put("volume_ratio", String.format("%.2f", volumeRatio));
        metadata.put("compression_days", compressionDays);
        metadata.put("sma_50", String.format("%.2f", sma50));
        metadata.put("sma_200", String.format("%.2f", sma200));
        
        if (atr != null) {
            metadata.put("atr_14", String.format("%.2f", atr));
        }
        
        // Close position in daily range
        double range = current.getHigh() - current.getLow();
        if (range > 0) {
            double closePosition = (current.getClose() - current.getLow()) / range;
            metadata.put("close_position", String.format("%.2f", closePosition));
        }
        
        metadata.put("date", current.getDate().toString());
        metadata.put("scanner_version", SCANNER_VERSION);
        
        return metadata;
    }
    
    @Override
    public String getClassification() {
        return "BREAKOUT_CONFIRMED";
    }
    
    // Configuration helpers
    private double getConfigDouble(Map<String, Object> config, String key, double defaultValue) {
        Object value = config.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return defaultValue;
    }
    
    private int getConfigInt(Map<String, Object> config, String key, int defaultValue) {
        Object value = config.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }
    
    private boolean getConfigBoolean(Map<String, Object> config, String key, boolean defaultValue) {
        Object value = config.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return defaultValue;
    }
}
