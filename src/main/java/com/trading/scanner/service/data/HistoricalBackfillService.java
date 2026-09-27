package com.trading.scanner.service.data;

import com.trading.scanner.config.HistoricalBackfillProperties;
import com.trading.scanner.model.StockPrice;
import com.trading.scanner.repository.StockPriceRepository;
import com.trading.scanner.service.provider.DailyBarDto;
import com.trading.scanner.service.provider.angelone.AngelOneMarketDataProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * @deprecated Legacy daily bar backfill service. Intraday candle backfill and EOD reconciliation
 * handle candle and daily price management.
 */
@Deprecated
@Slf4j
@Service
@RequiredArgsConstructor
public class HistoricalBackfillService {

    private final AngelOneMarketDataProvider angelOneMarketDataProvider;
    private final StockPriceRepository stockPriceRepository;
    private final HistoricalBackfillProperties historicalBackfillProperties;

    @Transactional
    public HistoricalBackfillResult backfillSymbol(String symbol, LocalDate from, LocalDate to) {
        String normalizedSymbol = normalizeSymbol(symbol);

        List<DailyBarDto> bars = angelOneMarketDataProvider.fetchHistoricalBars(normalizedSymbol, from, to);

        int inserted = 0;
        int updated = 0;

        for (DailyBarDto bar : bars) {
            Optional<StockPrice> existingOpt = stockPriceRepository.findBySymbolAndDate(bar.symbol(),
                    bar.tradingDate());

            if (existingOpt.isPresent()) {
                StockPrice existing = existingOpt.get();
                existing.setOpenPrice(bar.open());
                existing.setHighPrice(bar.high());
                existing.setLowPrice(bar.low());
                existing.setClosePrice(bar.close());
                existing.setAdjClose(bar.adjustedClose());
                existing.setVolume(bar.volume() != null ? bar.volume().intValue() : null);
                stockPriceRepository.save(existing);
                updated++;
            } else {
                StockPrice newRow = StockPrice.builder()
                        .symbol(bar.symbol())
                        .date(bar.tradingDate())
                        .openPrice(bar.open())
                        .highPrice(bar.high())
                        .lowPrice(bar.low())
                        .closePrice(bar.close())
                        .adjClose(bar.adjustedClose())
                        .volume(bar.volume() != null ? bar.volume().intValue() : null)
                        .build();
                stockPriceRepository.save(newRow);
                inserted++;
            }
        }

        log.info("Historical backfill completed for symbol={} requestedBars={} inserted={} updated={}",
                normalizedSymbol, bars.size(), inserted, updated);

        return new HistoricalBackfillResult(
                normalizedSymbol,
                bars.size(),
                inserted,
                updated,
                "Historical backfill completed");
    }

    @Transactional
    public List<HistoricalBackfillResult> backfillSymbols(List<String> symbols, LocalDate from, LocalDate to) {
        return symbols.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(this::normalizeSymbol)
                .distinct()
                .sorted()
                .map(symbol -> backfillSymbol(symbol, from, to))
                .toList();
    }

    @Transactional
    public HistoricalBackfillResult backfillSymbolUsingDefaultWindow(String symbol) {
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusMonths(historicalBackfillProperties.months());
        return backfillSymbol(symbol, from, to);
    }

    private String normalizeSymbol(String symbol) {
        return symbol.trim().toUpperCase(Locale.ROOT);
    }
}