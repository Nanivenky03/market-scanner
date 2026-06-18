package com.trading.scanner.config;

import com.trading.scanner.model.Exchange;
import com.trading.scanner.model.StockUniverse;
import com.trading.scanner.repository.StockUniverseRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@Profile("simulation")
@RequiredArgsConstructor
public class SimulationUniverseSeeder {

    private final StockUniverseRepository stockUniverseRepository;

    @EventListener(ApplicationReadyEvent.class)
    public void seedOnStartup() {
        seedIfNeeded();
    }

    @Transactional
    public SeedResult seedIfNeeded() {
        long existing = stockUniverseRepository.count();
        if (existing > 0) {
            log.info("Simulation universe already seeded. Skipping.");
            return new SeedResult(0, "Simulation universe already seeded. Skipping.");
        }

        List<StockUniverse> rows = loadSeedRows();
        stockUniverseRepository.saveAll(rows);

        log.info("Seeded simulation universe with {} rows", rows.size());
        return new SeedResult(rows.size(), "Simulation universe seeded");
    }

    private List<StockUniverse> loadSeedRows() {
        try {
            ClassPathResource resource = new ClassPathResource("bootstrap/simulation-universe-nse.csv");
            List<StockUniverse> rows = new ArrayList<>();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {

                String line;
                boolean header = true;
                while ((line = reader.readLine()) != null) {
                    if (header) {
                        header = false;
                        continue;
                    }

                    if (line.isBlank()) {
                        continue;
                    }

                    String[] parts = line.split(",", 2);
                    String symbol = parts[0].trim();
                    String companyName = parts.length > 1 ? parts[1].trim() : symbol;

                    rows.add(StockUniverse.builder()
                            .symbol(symbol)
                            .exchange(Exchange.NSE)
                            .companyName(companyName)
                            .sector(null)
                            .isActive(true)
                            .build());
                }
            }

            return rows;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load simulation universe seed file", ex);
        }
    }

    public record SeedResult(
            int inserted,
            String message) {
    }
}