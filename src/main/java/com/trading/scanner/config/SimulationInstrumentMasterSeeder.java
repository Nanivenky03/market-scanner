package com.trading.scanner.config;

import com.trading.scanner.service.instrument.InstrumentMasterSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("simulation")
@Order(2)
@RequiredArgsConstructor
public class SimulationInstrumentMasterSeeder implements CommandLineRunner {

    private final InstrumentMasterSyncService instrumentMasterSyncService;

    @Override
    public void run(String... args) {
        int synced = instrumentMasterSyncService.syncFromStockUniverseIfEmpty();
        if (synced > 0) {
            log.info("Simulation instrument master seeding completed with {} rows.", synced);
        }
    }
}