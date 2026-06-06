package com.trading.scanner.config.provider;

import com.trading.scanner.service.provider.DailyBarDto;
import com.trading.scanner.service.provider.MarketDataProvider;
import com.trading.scanner.service.provider.ProviderType;
import com.trading.scanner.service.provider.angelone.AngelOneMarketDataProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

@Configuration
public class ProviderConfiguration {

    @Bean
    @Primary
    @Profile("simulation")
    public MarketDataProvider simulationMarketDataProvider() {
        return new MarketDataProvider() {
            @Override
            public ProviderType getProviderType() {
                return ProviderType.MOCK;
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public List<DailyBarDto> fetchDailyBars(LocalDate tradingDate, List<String> symbols) {
                return Collections.emptyList();
            }

            @Override
            public List<DailyBarDto> fetchHistoricalBars(String symbol, LocalDate from, LocalDate to) {
                return Collections.emptyList();
            }
        };
    }

    @Bean
    @Primary
    @Profile({"default", "production"})
    public MarketDataProvider productionMarketDataProvider(AngelOneProperties angelOneProperties) {
        return new AngelOneMarketDataProvider(angelOneProperties);
    }
}