package com.trading.scanner.service.indicators;

import com.trading.scanner.model.StockPrice;
import com.trading.scanner.service.indicators.parameters.IndicatorParameters;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class IndicatorService {
    
    public IndicatorBundle calculateIndicators(List<StockPrice> prices, IndicatorParameters params) {
        if (prices == null || prices.isEmpty()) {
            return new IndicatorBundle();
        }
        
        IndicatorBundle bundle = new IndicatorBundle();
        
        if (prices.size() >= params.rsiPeriod()) {
            bundle.setRsi(calculateRSI(prices, params.rsiPeriod()));
        }
        
        if (prices.size() >= params.smaShortPeriod()) {
            bundle.setSma20(calculateSMA(prices, params.smaShortPeriod()));
            bundle.setAvgVolume20(calculateAvgVolume(prices, params.smaShortPeriod()));
        }
        
        if (prices.size() >= params.smaMediumPeriod()) {
            bundle.setSma50(calculateSMA(prices, params.smaMediumPeriod()));
        }
        
        if (prices.size() >= params.smaLongPeriod()) {
            bundle.setSma200(calculateSMA(prices, params.smaLongPeriod()));
        }
        
        if (!prices.isEmpty()) {
            Double currentPrice = prices.get(prices.size() - 1).getAdjClose();
            
            if (currentPrice != null && bundle.getSma20() != null) {
                bundle.setAboveSma20(currentPrice > bundle.getSma20());
            }
            
            if (currentPrice != null && bundle.getSma50() != null) {
                bundle.setAboveSma50(currentPrice > bundle.getSma50());
            }
            
            if (currentPrice != null && bundle.getSma200() != null) {
                bundle.setAboveSma200(currentPrice > bundle.getSma200());
            }
        }
        
        return bundle;
    }
    
    public Double calculateRSI(List<StockPrice> prices, int period) {
        if (prices.size() < period + 1) {
            return null;
        }
        
        double gainSum = 0;
        double lossSum = 0;
        
        for (int i = prices.size() - period; i < prices.size(); i++) {
            if (i == 0) continue;
            
            Double currentClose = prices.get(i).getAdjClose();
            Double prevClose = prices.get(i - 1).getAdjClose();
            
            if (currentClose == null || prevClose == null) continue;
            
            double change = currentClose - prevClose;
            
            if (change > 0) {
                gainSum += change;
            } else {
                lossSum += Math.abs(change);
            }
        }
        
        double avgGain = gainSum / period;
        double avgLoss = lossSum / period;
        
        if (avgLoss == 0) {
            return 100.0;
        }
        
        double rs = avgGain / avgLoss;
        double rsi = 100 - (100 / (1 + rs));
        
        return rsi;
    }
    
    public Double calculateSMA(List<StockPrice> prices, int period) {
        if (prices.size() < period) {
            return null;
        }
        
        double sum = 0;
        int count = 0;
        
        for (int i = prices.size() - period; i < prices.size(); i++) {
            Double adjClose = prices.get(i).getAdjClose();
            if (adjClose != null) {
                sum += adjClose;
                count++;
            }
        }
        
        if (count == 0) {
            return null;
        }
        
        return sum / count;
    }
    
    public Long calculateAvgVolume(List<StockPrice> prices, int period) {
        if (prices.size() < period) {
            return null;
        }
        
        long sum = 0;
        int count = 0;
        
        for (int i = prices.size() - period; i < prices.size(); i++) {
            Long volume = prices.get(i).getVolume();
            if (volume != null && volume > 0) {
                sum += volume;
                count++;
            }
        }
        
        if (count == 0) {
            return null;
        }
        
        return sum / count;
    }
    
    public Double calculateATR(List<StockPrice> prices, int period) {
        if (prices.size() < period + 1) {
            return null;
        }
        
        double trSum = 0;
        
        for (int i = prices.size() - period; i < prices.size(); i++) {
            if (i == 0) continue;
            
            Double high = prices.get(i).getHighPrice();
            Double low = prices.get(i).getLowPrice();
            Double prevClose = prices.get(i - 1).getAdjClose();
            
            if (high == null || low == null || prevClose == null) continue;
            
            double tr1 = high - low;
            double tr2 = Math.abs(high - prevClose);
            double tr3 = Math.abs(low - prevClose);
            
            double tr = Math.max(tr1, Math.max(tr2, tr3));
            trSum += tr;
        }
        
        return trSum / period;
    }
}
