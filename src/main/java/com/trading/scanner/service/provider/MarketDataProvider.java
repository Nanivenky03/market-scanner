package com.trading.scanner.service.provider;

import java.time.LocalDate;
import java.util.List;

public interface MarketDataProvider {
    
    ProviderType getProviderType();

    boolean isAvailable();

    List<DailyBarDto> fetchDailyBars(LocalDate tradingDate, List<String> symbols);
    List<DailyBarDto> fetchHistoricalBars(String symbol, LocalDate from, LocalDate to);
}
