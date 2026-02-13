package com.trading.scanner.service.provider;

import com.trading.scanner.model.StockPrice;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import yahoofinance.Stock;
import yahoofinance.YahooFinance;
import yahoofinance.histquotes.HistoricalQuote;
import yahoofinance.histquotes.Interval;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

@Slf4j
@Service
public class YahooFinanceProvider implements MarketDataProvider {
    
    @Override
    public List<StockPrice> fetchHistoricalData(String symbol, LocalDate startDate, LocalDate endDate) 
            throws DataProviderException {
        
        try {
            String yahooSymbol = symbol + ".NS";
            
            Calendar from = Calendar.getInstance();
            from.setTime(java.sql.Date.valueOf(startDate));
            
            Calendar to = Calendar.getInstance();
            to.setTime(java.sql.Date.valueOf(endDate));
            
            Stock stock = YahooFinance.get(yahooSymbol, from, to, Interval.DAILY);
            
            if (stock == null) {
                throw new DataProviderException("No data returned for " + symbol);
            }
            
            List<HistoricalQuote> history = stock.getHistory();
            
            if (history == null || history.isEmpty()) {
                log.debug("No historical data for {} between {} and {}", symbol, startDate, endDate);
                return new ArrayList<>();
            }
            
            List<StockPrice> prices = new ArrayList<>();
            
            for (HistoricalQuote quote : history) {
                if (quote.getDate() == null) continue;
                
                LocalDate date = quote.getDate().toInstant()
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate();
                
                StockPrice price = StockPrice.builder()
                    .symbol(symbol)
                    .date(date)
                    .openPrice(quote.getOpen() != null ? quote.getOpen().doubleValue() : null)
                    .highPrice(quote.getHigh() != null ? quote.getHigh().doubleValue() : null)
                    .lowPrice(quote.getLow() != null ? quote.getLow().doubleValue() : null)
                    .closePrice(quote.getClose() != null ? quote.getClose().doubleValue() : null)
                    .adjClose(quote.getAdjClose() != null ? quote.getAdjClose().doubleValue() : null)
                    .volume(quote.getVolume())
                    .build();
                
                prices.add(price);
            }
            
            return prices;
            
        } catch (IOException e) {
            throw new DataProviderException("Failed to fetch data for " + symbol, e);
        }
    }
    
    @Override
    public StockPrice fetchLatestData(String symbol) throws DataProviderException {
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(5);
        
        List<StockPrice> prices = fetchHistoricalData(symbol, from, today);
        
        if (prices.isEmpty()) {
            throw new DataProviderException("No recent data for " + symbol);
        }
        
        return prices.get(prices.size() - 1);
    }
    
    @Override
    public boolean isHealthy() {
        try {
            Stock stock = YahooFinance.get("RELIANCE.NS");
            return stock != null;
        } catch (IOException e) {
            log.error("Provider health check failed: {}", e.getMessage());
            return false;
        }
    }
}
