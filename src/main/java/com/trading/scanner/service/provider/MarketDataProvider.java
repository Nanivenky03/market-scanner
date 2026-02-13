package com.trading.scanner.service.provider;

import com.trading.scanner.model.StockPrice;
import java.time.LocalDate;
import java.util.List;

public interface MarketDataProvider {
    
    List<StockPrice> fetchHistoricalData(String symbol, LocalDate startDate, LocalDate endDate) 
        throws DataProviderException;
    
    StockPrice fetchLatestData(String symbol) throws DataProviderException;
    
    boolean isHealthy();
}
