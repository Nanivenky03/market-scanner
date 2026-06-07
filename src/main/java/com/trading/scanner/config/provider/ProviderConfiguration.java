package com.trading.scanner.config.provider;

import com.trading.scanner.service.provider.MarketDataProvider;
import com.trading.scanner.service.provider.angelone.AngelOneMarketDataProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

@Configuration
public class ProviderConfiguration {

    @Bean
    @Primary
    @Profile({"default", "production"})
    public MarketDataProvider productionMarketDataProvider(AngelOneProperties angelOneProperties) {
        return new AngelOneMarketDataProvider(angelOneProperties);
    }
}