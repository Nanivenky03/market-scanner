package com.trading.scanner.service.provider;

import com.trading.scanner.model.StockPrice;
import com.trading.scanner.model.StockUniverse;
import java.time.LocalDate;
import java.util.List;

public interface MarketDataProvider {
    
    List<StockPrice> fetchHistoricalData(StockUniverse stock, LocalDate startDate, LocalDate endDate) 
        throws DataProviderException;
    
    StockPrice fetchLatestData(StockUniverse stock) throws DataProviderException;
    
    boolean isHealthy(StockUniverse stock);
}
