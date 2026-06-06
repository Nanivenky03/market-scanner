package com.trading.scanner.config.provider;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AngelOneProperties.class)
public class AngelOneProviderConfiguration {
}