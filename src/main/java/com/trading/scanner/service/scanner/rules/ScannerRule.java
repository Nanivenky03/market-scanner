package com.trading.scanner.service.scanner.rules;

import com.trading.scanner.model.ScanResult;
import com.trading.scanner.model.StockPrice;
import com.trading.scanner.service.indicators.IndicatorBundle;

import java.util.List;
import java.util.Map;

/**
 * Scanner Rule Interface
 * 
 * DESIGN PRINCIPLES:
 * 1. Rules receive NORMALIZED price data (oldest → newest)
 * 2. Rules receive PRE-COMPUTED IndicatorBundle
 * 3. Rules NEVER compute indicators themselves
 * 4. Rules are pure evaluation logic
 * 5. Rules return ScanResult or null
 * 
 * This separation enables:
 * - Clean testing
 * - Future backtesting without refactoring
 * - Performance optimization
 * - Rule composition
 */
public interface ScannerRule {
    
    /**
     * Evaluate if this rule applies to the given stock.
     * 
     * @param prices Price data (MUST be oldest → newest, system invariant)
     * @param indicators Pre-computed indicator bundle
     * @param config Rule configuration parameters
     * @return ScanResult if rule matches, null otherwise
     */
    ScanResult evaluate(
        List<StockPrice> prices, 
        IndicatorBundle indicators,
        Map<String, Object> config
    );
    
    /**
     * Get the classification name for this rule.
     * 
     * @return Classification string (e.g., "BREAKOUT_CONFIRMED")
     */
    String getClassification();
    
    /**
     * Check if rule is enabled.
     * Default implementation returns true.
     * Override to implement dynamic enabling/disabling.
     * 
     * @return true if rule should be evaluated
     */
    default boolean isEnabled() {
        return true;
    }
}
