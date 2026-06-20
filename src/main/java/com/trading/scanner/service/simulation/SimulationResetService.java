package com.trading.scanner.service.simulation;

import com.trading.scanner.config.SimulationUniverseSeeder;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.InstrumentMaster;
import com.trading.scanner.model.SimulationState;
import com.trading.scanner.model.StockUniverse;
import com.trading.scanner.repository.*;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
@Profile("simulation")
@RequiredArgsConstructor
public class SimulationResetService {

    private final SimulationTradeRepository simulationTradeRepository;
    private final SimulationVariantRepository simulationVariantRepository;
    private final SimulationRunGroupRepository simulationRunGroupRepository;
    private final SignalOutcomeRepository signalOutcomeRepository;
    private final ScanResultRepository scanResultRepository;
    private final MarketCandleRepository marketCandleRepository;
    private final StockPriceRepository stockPriceRepository;
    private final ScannerRunRepository scannerRunRepository;
    private final ScanExecutionStateRepository scanExecutionStateRepository;
    private final InstrumentMasterRepository instrumentMasterRepository;
    private final StockUniverseRepository stockUniverseRepository;
    private final SimulationStateRepository simulationStateRepository;
    private final SimulationUniverseSeeder simulationUniverseSeeder;
    private final TimeProvider timeProvider;

    @Transactional
    public ResetResult resetAndReseed() {
        LocalDate baseDate = simulationStateRepository.findById(1)
                .map(SimulationState::getBaseDate)
                .orElse(timeProvider.today());

        simulationTradeRepository.deleteAll();
        simulationVariantRepository.deleteAll();
        simulationRunGroupRepository.deleteAll();

        signalOutcomeRepository.deleteAll();
        scanResultRepository.deleteAll();

        marketCandleRepository.deleteAll();
        stockPriceRepository.deleteAll();
        scannerRunRepository.deleteAll();
        scanExecutionStateRepository.deleteAll();

        instrumentMasterRepository.deleteAll();
        stockUniverseRepository.deleteAll();
        simulationStateRepository.deleteAll();

        SimulationUniverseSeeder.SeedResult universeSeed = simulationUniverseSeeder.seedIfNeeded();

        List<StockUniverse> stockUniverseRows = stockUniverseRepository.findAll();
        instrumentMasterRepository.saveAll(
                stockUniverseRows.stream()
                        .map(this::toInstrumentMaster)
                        .toList());

        simulationStateRepository.save(
                SimulationState.builder()
                        .id(1)
                        .version(0)
                        .baseDate(baseDate)
                        .tradingOffset(0)
                        .build());

        return new ResetResult(
                universeSeed.inserted(),
                stockUniverseRows.size(),
                baseDate,
                "Simulation data reset and reseeded");
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

    public record ResetResult(
            int universeRowsInserted,
            int instrumentMasterRowsInserted,
            LocalDate simulationBaseDate,
            String message) {
    }
}