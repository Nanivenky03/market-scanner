package com.trading.scanner.config.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "provider.angelone")
public record AngelOneProperties(
        boolean enabled,
        String apiKey,
        String clientId,
        String password,
        String totpSecret,
        String baseUrl
) {
}