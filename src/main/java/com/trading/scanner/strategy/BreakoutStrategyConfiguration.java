package com.trading.scanner.strategy;

import com.trading.scanner.config.BreakoutRuleProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class BreakoutStrategyConfiguration {

    public static final String BREAKOUT_V1_ID = "breakout_v1";

    @Bean
    @Primary
    public BreakoutRuleProperties breakoutRuleProperties(StrategyCatalogService strategyCatalogService) {
        StrategyYamlDefinition definition = strategyCatalogService.getRequired(BREAKOUT_V1_ID);

        if (definition.breakout() == null) {
            throw new IllegalStateException("Strategy " + BREAKOUT_V1_ID + " is missing breakout configuration");
        }

        return definition.breakout();
    }
}