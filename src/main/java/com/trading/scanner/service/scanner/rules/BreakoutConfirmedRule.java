package com.trading.scanner.service.scanner.rules;

import com.trading.scanner.model.StockPrice;
import com.trading.scanner.service.indicators.IndicatorBundle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class BreakoutConfirmedRule implements ScannerRule {
    
    @Value("${rules.breakout.maxGap:0.05}")
    private double maxGap;
    
    @Override
    public String getRuleName() {
        return "Breakout Confirmed";
    }
    
    @Override
    public boolean matches(String symbol, List<StockPrice> prices, IndicatorBundle indicators) {
        if (prices.size() < 21) {
            return false;
        }
        
        StockPrice today = prices.get(prices.size() - 1);
        
        if (today.getAdjClose() == null || today.getVolume() == null) {
            return false;
        }
        
        if (!indicators.hasRsi() || !indicators.hasSma20() || !indicators.hasAvgVolume()) {
            return false;
        }
        
        double recentHigh = Double.MIN_VALUE;
        for (int i = prices.size() - 21; i < prices.size() - 1; i++) {
            Double high = prices.get(i).getHighPrice();
            if (high != null && high > recentHigh) {
                recentHigh = high;
            }
        }
        
        if (recentHigh == Double.MIN_VALUE) {
            return false;
        }
        
        boolean priceBreakout = today.getAdjClose() > recentHigh;
        
        double gapPercent = (today.getAdjClose() - recentHigh) / recentHigh;
        boolean reasonableGap = gapPercent < maxGap;
        
        boolean volumeConfirmation = today.getVolume() > indicators.getAvgVolume20() * 1.5;
        
        boolean rsiSupport = indicators.getRsi() > 50;
        
        boolean aboveSma20 = indicators.getAboveSma20() != null && indicators.getAboveSma20();
        
        return priceBreakout && reasonableGap && volumeConfirmation && rsiSupport && aboveSma20;
    }
    
    @Override
    public Double getConfidence(String symbol, List<StockPrice> prices, IndicatorBundle indicators) {
        if (!matches(symbol, prices, indicators)) {
            return 0.0;
        }
        
        double confidence = 0.5;
        
        if (indicators.getRsi() > 60) {
            confidence += 0.1;
        }
        
        if (indicators.getAboveSma50() != null && indicators.getAboveSma50()) {
            confidence += 0.1;
        }
        
        StockPrice today = prices.get(prices.size() - 1);
        if (today.getVolume() > indicators.getAvgVolume20() * 2.0) {
            confidence += 0.1;
        }
        
        if (indicators.getAboveSma200() != null && indicators.getAboveSma200()) {
            confidence += 0.1;
        }
        
        return Math.min(confidence, 1.0);
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
