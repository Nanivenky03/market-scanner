package com.trading.scanner.service.provider.angelone;

import java.security.ProviderException;
import java.time.LocalDate;
import java.util.List;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.trading.scanner.service.provider.DailyBarDto;
import com.trading.scanner.service.provider.MarketDataProvider;
import com.trading.scanner.service.provider.ProviderType;

@Component
@Primary
@Profile({"default", "Production"})
public class AngelOneMarketDataProvider implements MarketDataProvider {
    @Override
    public ProviderType getProviderType() {
        return ProviderType.ANGEL_ONE;
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public List<DailyBarDto> fetchDailyBars(LocalDate tradingDate, List<String> symbols) {
        throw new ProviderException("Angel One API integration is not implemented yet.");
    }

    @Override
    public List<DailyBarDto> fetchHistoricalBars(String symbol, LocalDate from, LocalDate to) {
        throw new ProviderException("Angel One API integration is not implemented yet.");
    }
    
}
