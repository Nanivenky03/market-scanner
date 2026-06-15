package com.trading.scanner.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(HistoricalBackfillProperties.class)
public class HistoricalBackfillConfiguration {
}