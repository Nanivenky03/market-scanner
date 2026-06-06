package com.trading.scanner.service.provider.angelone;

import com.trading.scanner.config.provider.AngelOneProperties;
import com.trading.scanner.service.provider.DailyBarDto;
import com.trading.scanner.service.provider.MarketDataProvider;
import com.trading.scanner.service.provider.ProviderException;
import com.trading.scanner.service.provider.ProviderType;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@RequiredArgsConstructor
public class AngelOneMarketDataProvider implements MarketDataProvider {

    private final AngelOneProperties properties;

    @Override
    public ProviderType getProviderType() {
        return ProviderType.ANGEL_ONE;
    }

    @Override
    public boolean isAvailable() {
        return properties.enabled();
    }

    @Override
    public List<DailyBarDto> fetchDailyBars(LocalDate tradingDate, List<String> symbols) {
        throw new ProviderException("Angel One daily-bar fetch is not implemented yet");
    }

    @Override
    public List<DailyBarDto> fetchHistoricalBars(String symbol, LocalDate from, LocalDate to) {
        throw new ProviderException("Angel One historical fetch is not implemented yet");
    }
}