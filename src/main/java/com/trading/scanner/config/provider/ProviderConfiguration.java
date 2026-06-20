package com.trading.scanner.config.provider;

import com.trading.scanner.repository.InstrumentMasterRepository;
import com.trading.scanner.service.provider.MarketDataProvider;
import com.trading.scanner.service.provider.angelone.AngelOneApiExecutor;
import com.trading.scanner.service.provider.angelone.AngelOneMarketDataProvider;
import com.trading.scanner.service.provider.angelone.AngelOneSessionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

@Configuration
public class ProviderConfiguration {

    @Bean
    public AngelOneMarketDataProvider angelOneMarketDataProvider(
            AngelOneProperties angelOneProperties,
            InstrumentMasterRepository instrumentMasterRepository,
            AngelOneSessionService angelOneSessionService,
            AngelOneApiExecutor angelOneApiExecutor) {
        return new AngelOneMarketDataProvider(
                angelOneProperties,
                instrumentMasterRepository,
                angelOneSessionService,
                angelOneApiExecutor);
    }

    @Bean
    @Primary
    @Profile({ "default", "production" })
    public MarketDataProvider productionMarketDataProvider(AngelOneMarketDataProvider angelOneMarketDataProvider) {
        return angelOneMarketDataProvider;
    }
}