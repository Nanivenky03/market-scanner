package com.trading.scanner.service.indicators;

import com.trading.scanner.model.StockPrice;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class IndicatorService {
    
    public IndicatorBundle calculateIndicators(List<StockPrice> prices) {
        if (prices == null || prices.isEmpty()) {
            return new IndicatorBundle();
        }
        
        IndicatorBundle bundle = new IndicatorBundle();
        
        if (prices.size() >= 14) {
            bundle.setRsi(calculateRSI(prices, 14));
        }
        
        if (prices.size() >= 20) {
            bundle.setSma20(calculateSMA(prices, 20));
            bundle.setAvgVolume20(calculateAvgVolume(prices, 20));
        }
        
        if (prices.size() >= 50) {
            bundle.setSma50(calculateSMA(prices, 50));
        }
        
        if (prices.size() >= 200) {
            bundle.setSma200(calculateSMA(prices, 200));
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
