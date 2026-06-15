package com.trading.scanner.service.instrument;

import com.trading.scanner.config.HistoricalBackfillProperties;
import com.trading.scanner.model.Exchange;
import com.trading.scanner.model.InstrumentMaster;
import com.trading.scanner.model.StockUniverse;
import com.trading.scanner.repository.InstrumentMasterRepository;
import com.trading.scanner.repository.StockUniverseRepository;
import com.trading.scanner.service.data.HistoricalBackfillResult;
import com.trading.scanner.service.data.HistoricalBackfillService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UniverseManagementService {

    private final InstrumentMasterRepository instrumentMasterRepository;
    private final StockUniverseRepository stockUniverseRepository;
    private final HistoricalBackfillService historicalBackfillService;
    private final HistoricalBackfillProperties historicalBackfillProperties;

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

            HistoricalBackfillResult backfillResult = null;
            if (reactivated && historicalBackfillProperties.enabledOnUniverseAdd()) {
                backfillResult = historicalBackfillService.backfillSymbolUsingDefaultWindow(instrument.getSymbol());
            }

            return new AddToUniverseResult(
                    instrument.getId(),
                    instrument.getSymbol(),
                    false,
                    reactivated,
                    reactivated
                            ? "Instrument reactivated in stock_universe"
                            : "Instrument already exists in stock_universe",
                    backfillResult
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

        HistoricalBackfillResult backfillResult = null;
        if (historicalBackfillProperties.enabledOnUniverseAdd()) {
            backfillResult = historicalBackfillService.backfillSymbolUsingDefaultWindow(instrument.getSymbol());
        }

        return new AddToUniverseResult(
                instrument.getId(),
                instrument.getSymbol(),
                true,
                false,
                "Instrument added to stock_universe",
                backfillResult
        );
    }

    @Transactional
    public RemoveFromUniverseResult deactivateInstrumentFromUniverse(String symbol, String exchange) {
        String normalizedSymbol = symbol.trim().toUpperCase();
        Exchange normalizedExchange = Exchange.valueOf(normalizeExchange(exchange));

        StockUniverse existing = stockUniverseRepository.findBySymbolAndExchange(normalizedSymbol, normalizedExchange)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Instrument not found in stock_universe for symbol=" + normalizedSymbol + ", exchange=" + normalizedExchange));

        if (Boolean.FALSE.equals(existing.getIsActive())) {
            return new RemoveFromUniverseResult(existing.getSymbol(), false, "Instrument already inactive");
        }

        existing.setIsActive(false);
        stockUniverseRepository.save(existing);

        return new RemoveFromUniverseResult(existing.getSymbol(), true, "Instrument deactivated in stock_universe");
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
            String message,
            HistoricalBackfillResult backfill
    ) {
    }

    public record RemoveFromUniverseResult(
            String symbol,
            boolean changed,
            String message
    ) {
    }
}