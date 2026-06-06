package com.trading.scanner.service.instrument;

import com.trading.scanner.model.InstrumentMaster;
import com.trading.scanner.model.StockUniverse;
import com.trading.scanner.repository.InstrumentMasterRepository;
import com.trading.scanner.repository.StockUniverseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class InstrumentMasterSyncService {

    private final InstrumentMasterRepository instrumentMasterRepository;
    private final StockUniverseRepository stockUniverseRepository;

    @Transactional
    public int syncFromStockUniverseIfEmpty() {
        long existingCount = instrumentMasterRepository.count();
        if (existingCount > 0) {
            log.info("Instrument master already populated. Skipping sync.");
            return 0;
        }

        List<StockUniverse> universeRows = stockUniverseRepository.findAll().stream()
                .sorted(Comparator.comparing(StockUniverse::getSymbol))
                .toList();

        if (universeRows.isEmpty()) {
            log.warn("Stock universe is empty. Nothing to sync into instrument_master.");
            return 0;
        }

        List<InstrumentMaster> instruments = universeRows.stream()
                .map(this::toInstrumentMaster)
                .toList();

        instrumentMasterRepository.saveAll(instruments);

        log.info("Instrument master synced successfully with {} instruments.", instruments.size());
        return instruments.size();
    }

    private InstrumentMaster toInstrumentMaster(StockUniverse stock) {
        return InstrumentMaster.builder()
                .symbol(stock.getSymbol())
                .exchange(stock.getExchange().name())
                .companyName(stock.getCompanyName())
                .instrumentType("EQUITY")
                .segment("CASH")
                .brokerSymbol(stock.getSymbol())
                .brokerToken(null)
                .isin(null)
                .isActive(stock.getIsActive())
                .build();
    }
}