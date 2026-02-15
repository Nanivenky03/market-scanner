package com.trading.scanner.service.scanner.rules;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.scanner.config.BreakoutRuleProperties;
import com.trading.scanner.model.StockPrice;
import com.trading.scanner.service.indicators.IndicatorBundle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Slf4j
@Component
public class BreakoutConfirmedRule implements ScannerRule {

    private static final String RULE_VERSION = "1.1";
    private final ObjectMapper objectMapper;
    private final BreakoutRuleProperties properties;

    public BreakoutConfirmedRule(ObjectMapper objectMapper, BreakoutRuleProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public String getRuleName() {
        return "Breakout Confirmed";
    }

    @Override
    public String getRuleVersion() {
        return RULE_VERSION;
    }

    @Override
    public String getParameterSnapshot() {
        // FIX 1: Convert properties to a TreeMap to guarantee alphabetical key order
        Map<String, Object> params = new TreeMap<>();
        params.put("lookbackWindow", properties.lookbackWindow());
        params.put("rsiPeriod", properties.rsiPeriod());
        params.put("smaShortPeriod", properties.smaShortPeriod());
        params.put("smaMediumPeriod", properties.smaMediumPeriod());
        params.put("smaLongPeriod", properties.smaLongPeriod());
        params.put("rsiThresholdMatch", properties.rsiThresholdMatch());
        params.put("volumeMultiplierMatch", properties.volumeMultiplierMatch());
        params.put("rsiThresholdConfidence", properties.rsiThresholdConfidence());
        params.put("volumeMultiplierConfidence", properties.volumeMultiplierConfidence());
        params.put("baseConfidence", properties.baseConfidence());
        params.put("confidenceIncrement", properties.confidenceIncrement());
        params.put("maxConfidenceCap", properties.maxConfidenceCap());
        params.put("maxGap", properties.maxGap());

        try {
            return objectMapper.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize rule parameters for {}", getRuleName(), e);
            return "{\"error\":\"serialization failed\"}";
        }
    }

    @Override
    public boolean matches(String symbol, List<StockPrice> prices, IndicatorBundle indicators) {
        // FIX 2: Restore layering - use pre-calculated indicators from the bundle
        if (prices.size() < properties.lookbackWindow()) {
            return false;
        }
        
        StockPrice today = prices.get(prices.size() - 1);
        if (today.getAdjClose() == null || today.getVolume() == null) {
            return false;
        }

        // The indicators are pre-calculated by the engine. Check if they exist.
        // This implicitly checks if there was enough data (e.g., 14 days for RSI, 20 for SMA20).
        if (!indicators.hasRsi() || !indicators.hasSma20() || !indicators.hasAvgVolume()) {
            return false;
        }

        double recentHigh = Double.MIN_VALUE;
        // The lookback window for finding the recent high is a rule parameter.
        for (int i = prices.size() - properties.lookbackWindow(); i < prices.size() - 1; i++) {
            Double high = prices.get(i).getHighPrice();
            if (high != null && high > recentHigh) {
                recentHigh = high;
            }
        }
        
        if (recentHigh == Double.MIN_VALUE) {
            return false;
        }

        // All "magic numbers" are now from the properties object.
        boolean priceBreakout = today.getAdjClose() > recentHigh;
        double gapPercent = (today.getAdjClose() - recentHigh) / recentHigh;
        boolean reasonableGap = gapPercent < properties.maxGap();
        boolean volumeConfirmation = today.getVolume() > (indicators.getAvgVolume20() * properties.volumeMultiplierMatch());
        boolean rsiSupport = indicators.getRsi() > properties.rsiThresholdMatch();
        boolean aboveSma20 = indicators.getAboveSma20() != null && indicators.getAboveSma20();
        
        return priceBreakout && reasonableGap && volumeConfirmation && rsiSupport && aboveSma20;
    }

    @Override
    public Double getConfidence(String symbol, List<StockPrice> prices, IndicatorBundle indicators) {
        // FIX 2: Logic restored to use the pre-calculated bundle
        if (!matches(symbol, prices, indicators)) {
            return 0.0;
        }
        
        double confidence = properties.baseConfidence();
        StockPrice today = prices.get(prices.size() - 1);

        if (indicators.hasRsi() && indicators.getRsi() > properties.rsiThresholdConfidence()) {
            confidence += properties.confidenceIncrement();
        }
        
        if (indicators.getAboveSma50() != null && indicators.getAboveSma50()) {
            confidence += properties.confidenceIncrement();
        }
        
        if (indicators.hasAvgVolume() && today.getVolume() > (indicators.getAvgVolume20() * properties.volumeMultiplierConfidence())) {
            confidence += properties.confidenceIncrement();
        }
        
        if (indicators.getAboveSma200() != null && indicators.getAboveSma200()) {
            confidence += properties.confidenceIncrement();
        }
        
        return Math.min(confidence, properties.maxConfidenceCap());
    }

    @Override
    public String getMetadata(String symbol, List<StockPrice> prices, IndicatorBundle indicators) {
        if (prices.isEmpty()) {
            return "{}";
        }
        
        StockPrice today = prices.get(prices.size() - 1);
        
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("close", today.getAdjClose());
        metadata.put("volume", today.getVolume());
        metadata.put("rsi", indicators.getRsi());
        metadata.put("sma20", indicators.getSma20());
        metadata.put("avgVolume20", indicators.getAvgVolume20());
        
        return metadata.toString();
    }
}
