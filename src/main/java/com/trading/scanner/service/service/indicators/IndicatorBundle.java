package com.trading.scanner.service.indicators;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndicatorBundle {
    
    private Double rsi;
    private Double sma20;
    private Double sma50;
    private Double sma200;
    private Long avgVolume20;
    private Double atr;
    private Boolean aboveSma20;
    private Boolean aboveSma50;
    private Boolean aboveSma200;
    
    public boolean hasRsi() {
        return rsi != null;
    }
    
    public boolean hasSma20() {
        return sma20 != null;
    }
    
    public boolean hasSma50() {
        return sma50 != null;
    }
    
    public boolean hasAvgVolume() {
        return avgVolume20 != null;
    }
}
