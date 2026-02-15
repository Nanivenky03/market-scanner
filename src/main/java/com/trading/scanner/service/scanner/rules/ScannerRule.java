package com.trading.scanner.service.scanner.rules;

import com.trading.scanner.model.StockPrice;
import com.trading.scanner.service.indicators.IndicatorBundle;

import java.util.List;

public interface ScannerRule {
    
    String getRuleName();
    
    String getRuleVersion();
    
    String getParameterSnapshot();
    
    boolean matches(String symbol, List<StockPrice> prices, IndicatorBundle indicators);
    
    Double getConfidence(String symbol, List<StockPrice> prices, IndicatorBundle indicators);
    
    String getMetadata(String symbol, List<StockPrice> prices, IndicatorBundle indicators);
}
