package com.trading.scanner.service.runtime;

import com.trading.scanner.config.RuntimeAutomationProperties;
import com.trading.scanner.config.SimulationUniverseSeeder;
import com.trading.scanner.config.provider.AngelOneProperties;
import com.trading.scanner.model.InstrumentMaster;
import com.trading.scanner.model.StockUniverse;
import com.trading.scanner.repository.InstrumentMasterRepository;
import com.trading.scanner.repository.StockUniverseRepository;
import com.trading.scanner.service.instrument.InstrumentTokenSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class RuntimeBootstrapService {

    private final RuntimeAutomationProperties runtimeAutomationProperties;
    private final SimulationUniverseSeeder simulationUniverseSeeder;
    private final StockUniverseRepository stockUniverseRepository;
    private final InstrumentMasterRepository instrumentMasterRepository;
    private final InstrumentTokenSyncService instrumentTokenSyncService;
    private final AngelOneProperties angelOneProperties;

    @Transactional
    public BootstrapResult bootstrapIfNeeded() {
        int universeInserted = 0;
        int instrumentMasterInserted = 0;
        int tokenMapped = 0;
        boolean tokenSyncAttempted = false;

        if (runtimeAutomationProperties.getBootstrap().isAutoSeedUniverse() && stockUniverseRepository.count() == 0) {
            SimulationUniverseSeeder.SeedResult seedResult = simulationUniverseSeeder.seedIfNeeded();
            universeInserted = seedResult.inserted();
            log.info("Runtime bootstrap seeded stock universe. inserted={}", universeInserted);
        }

        if (runtimeAutomationProperties.getBootstrap().isAutoBuildInstrumentMaster()) {
            List<StockUniverse> activeStocks = stockUniverseRepository.findByIsActiveTrueOrderBySymbolAsc();
            List<InstrumentMaster> missing = new ArrayList<>();

            for (int i = 0; i < activeStocks.size(); i++) {
                StockUniverse stock = activeStocks.get(i);
                boolean exists = instrumentMasterRepository
                        .findBySymbolAndExchange(stock.getSymbol(), stock.getExchange().name())
                        .isPresent();

                if (!exists) {
                    missing.add(toInstrumentMaster(stock));
                }
            }

            if (!missing.isEmpty()) {
                instrumentMasterRepository.saveAll(missing);
                instrumentMasterInserted = missing.size();
                log.info("Runtime bootstrap created missing instrument_master rows. inserted={}",
                        instrumentMasterInserted);
            }
        }

        if (runtimeAutomationProperties.getBootstrap().isAutoSyncMissingTokens() && angelOneProperties.enabled()) {
            tokenSyncAttempted = true;
            InstrumentTokenSyncService.TokenSyncResult tokenSyncResult = instrumentTokenSyncService
                    .syncAngelOneTokens(true);
            tokenMapped = tokenSyncResult.mapped();
            log.info("Runtime bootstrap synced missing broker tokens. mapped={} failed={}",
                    tokenSyncResult.mapped(), tokenSyncResult.failed());
        }

        return new BootstrapResult(
                universeInserted,
                instrumentMasterInserted,
                tokenMapped,
                tokenSyncAttempted,
                "Runtime bootstrap completed");
    }

    private InstrumentMaster toInstrumentMaster(StockUniverse stock) {
        return InstrumentMaster.builder()
                .symbol(stock.getSymbol())
                .exchange(stock.getExchange().name())
                .companyName(stock.getCompanyName())
                .instrumentType("EQUITY")
                .segment("CASH")
                .brokerSymbol(null)
                .brokerToken(null)
                .isin(null)
                .isActive(true)
                .build();
    }

    public record BootstrapResult(
            int universeInserted,
            int instrumentMasterInserted,
            int tokenMapped,
            boolean tokenSyncAttempted,
            String message) {
    }
}