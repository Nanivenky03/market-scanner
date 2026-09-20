package com.trading.scanner.config;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Deprecated
public class SimulationUniverseSeeder {

    private final StockUniverseSeeder stockUniverseSeeder;

    @Transactional
    public SeedResult seedIfNeeded() {
        StockUniverseSeeder.SeedResult result = stockUniverseSeeder.seedIfNeeded();

        return new SeedResult(
                result.inserted(),
                result.message());
    }

    public record SeedResult(
            int inserted,
            String message) {
    }
}
