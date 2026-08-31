package com.trading.scanner.service.instrument;

import com.trading.scanner.model.InstrumentMaster;
import com.trading.scanner.model.StockUniverse;
import com.trading.scanner.repository.InstrumentMasterRepository;
import com.trading.scanner.repository.StockUniverseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class InstrumentMasterSyncService {

    private final InstrumentMasterRepository instrumentMasterRepository;
    private final StockUniverseRepository stockUniverseRepository;

    @Transactional
    public int syncFromStockUniverseIfEmpty() {
        if (instrumentMasterRepository.count() > 0) {
            log.info("Instrument master already populated. Skipping initial sync.");
            return 0;
        }

        List<StockUniverse> stocks = stockUniverseRepository.findAll().stream()
                .sorted(Comparator.comparing(StockUniverse::getSymbol))
                .toList();

        if (stocks.isEmpty()) {
            log.warn("Stock universe is empty. Nothing to sync.");
            return 0;
        }

        List<InstrumentMaster> instruments = stocks.stream()
                .map(this::toInstrumentMaster)
                .toList();

        instrumentMasterRepository.saveAll(instruments);
        log.info("Initial instrument master sync completed. inserted={}", instruments.size());
        return instruments.size();
    }

    @Transactional
    public int syncActiveUniverse() {
        List<InstrumentMaster> changed = new ArrayList<>();

        for (StockUniverse stock : stockUniverseRepository.findByIsActiveTrueOrderBySymbolAsc()) {
            Optional<InstrumentMaster> existing = instrumentMasterRepository.findBySymbolAndExchange(
                    stock.getSymbol(),
                    stock.getExchange().name());

            if (existing.isEmpty()) {
                changed.add(toInstrumentMaster(stock));
                continue;
            }

            InstrumentMaster instrument = existing.get();
            boolean rowChanged = false;

            if (!safeEquals(instrument.getCompanyName(), stock.getCompanyName())) {
                instrument.setCompanyName(stock.getCompanyName());
                rowChanged = true;
            }

            if (!Boolean.TRUE.equals(instrument.getIsActive())) {
                instrument.setIsActive(true);
                rowChanged = true;
            }

            if (rowChanged) {
                changed.add(instrument);
            }
        }

        if (!changed.isEmpty()) {
            instrumentMasterRepository.saveAll(changed);
        }

        log.info("Active instrument universe synchronized. active={} changed={}",
                stockUniverseRepository.findByIsActiveTrueOrderBySymbolAsc().size(),
                changed.size());

        return changed.size();
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

    private boolean safeEquals(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }
}
