package com.trading.scanner.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "historical.backfill")
public record HistoricalBackfillProperties(
        int months,
        boolean enabledOnUniverseAdd
) {
}