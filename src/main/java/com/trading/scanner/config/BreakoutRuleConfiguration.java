package com.trading.scanner.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BreakoutRuleConfiguration {

    @Bean
    public BreakoutRuleProperties breakoutRuleProperties() {
        return new BreakoutRuleProperties(
                20,
                14,
                20,
                50,
                200,
                60.0,
                1.5,
                65.0,
                2.0,
                50.0,
                10.0,
                100.0,
                2.0,
                1.5,
                0.10,
                "UP");
    }
}
