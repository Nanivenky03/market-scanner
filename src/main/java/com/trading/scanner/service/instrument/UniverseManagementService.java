package com.trading.scanner.service.instrument;

import com.trading.scanner.model.Exchange;
import com.trading.scanner.model.InstrumentMaster;
import com.trading.scanner.model.StockUniverse;
import com.trading.scanner.repository.InstrumentMasterRepository;
import com.trading.scanner.repository.StockUniverseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UniverseManagementService {

    private final InstrumentMasterRepository instrumentMasterRepository;
    private final StockUniverseRepository stockUniverseRepository;

    @Transactional(readOnly = true)
    public List<InstrumentSearchResult> searchActiveInstruments(String query, String exchange) {
        String normalizedQuery = query == null ? "" : query.trim();
        String normalizedExchange = normalizeExchange(exchange);

        if (normalizedQuery.isBlank()) {
            return List.of();
        }

        return instrumentMasterRepository.searchActiveByExchangeAndQuery(normalizedExchange, normalizedQuery)
                .stream()
                .limit(20)
                .map(this::toSearchResult)
                .toList();
    }

    @Transactional
    public AddToUniverseResult addInstrumentToUniverse(Integer instrumentId) {
        InstrumentMaster instrument = instrumentMasterRepository.findById(instrumentId)
                .orElseThrow(() -> new IllegalArgumentException("Instrument not found for id=" + instrumentId));

        Exchange exchange = Exchange.valueOf(instrument.getExchange());

        StockUniverse existing = stockUniverseRepository
                .findBySymbolAndExchange(instrument.getSymbol(), exchange)
                .orElse(null);

        if (existing != null) {
            boolean reactivated = Boolean.FALSE.equals(existing.getIsActive());

            if (reactivated) {
                existing.setIsActive(true);
                stockUniverseRepository.save(existing);
            }

            return new AddToUniverseResult(
                    instrument.getId(),
                    instrument.getSymbol(),
                    false,
                    reactivated,
                    reactivated
                            ? "Instrument reactivated in stock_universe"
                            : "Instrument already exists in stock_universe"
            );
        }

        StockUniverse newUniverseRow = StockUniverse.builder()
                .symbol(instrument.getSymbol())
                .exchange(exchange)
                .companyName(instrument.getCompanyName())
                .sector(null)
                .isActive(true)
                .build();

        stockUniverseRepository.save(newUniverseRow);

        return new AddToUniverseResult(
                instrument.getId(),
                instrument.getSymbol(),
                true,
                false,
                "Instrument added to stock_universe"
        );
    }

    private InstrumentSearchResult toSearchResult(InstrumentMaster instrument) {
        Exchange exchange = Exchange.valueOf(instrument.getExchange());

        StockUniverse existing = stockUniverseRepository
                .findBySymbolAndExchange(instrument.getSymbol(), exchange)
                .orElse(null);

        boolean alreadyInUniverse = existing != null;
        boolean activeInUniverse = existing != null && Boolean.TRUE.equals(existing.getIsActive());

        return new InstrumentSearchResult(
                instrument.getId(),
                instrument.getSymbol(),
                instrument.getExchange(),
                instrument.getCompanyName(),
                instrument.getInstrumentType(),
                instrument.getSegment(),
                instrument.getBrokerToken(),
                alreadyInUniverse,
                activeInUniverse
        );
    }

    private String normalizeExchange(String exchange) {
        if (exchange == null || exchange.isBlank()) {
            return "NSE";
        }
        return exchange.trim().toUpperCase();
    }

    public record InstrumentSearchResult(
            Integer instrumentId,
            String symbol,
            String exchange,
            String companyName,
            String instrumentType,
            String segment,
            String brokerToken,
            boolean alreadyInUniverse,
            boolean activeInUniverse
    ) {
    }

    public record AddToUniverseResult(
            Integer instrumentId,
            String symbol,
            boolean created,
            boolean reactivated,
            String message
    ) {
    }
}