package com.trading.scanner.service.runtime;

import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.CandleProcessingStatus;
import com.trading.scanner.model.CandleQualityStatus;
import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.model.StockPrice;
import com.trading.scanner.model.StockUniverse;
import com.trading.scanner.repository.MarketCandleRepository;
import com.trading.scanner.repository.StockPriceRepository;
import com.trading.scanner.repository.StockUniverseRepository;
import com.trading.scanner.service.data.LiveMarketCandleService;
import com.trading.scanner.service.engine.DailyStockContextService;
import com.trading.scanner.service.provider.angelone.AngelOneMarketDataProvider;
import com.trading.scanner.service.provider.angelone.dto.AngelOneMarketDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Service to synchronize Angel One Market Quote data, synthesize authoritative 09:08 pre-market
 * auction candles, and update stock_prices and daily context.
 * Single-run guard guarantees zero duplicate external broker API calls once synced for the day.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MarketQuoteSyncService {

    private final MarketCandleRepository marketCandleRepository;
    private final StockPriceRepository stockPriceRepository;
    private final StockUniverseRepository stockUniverseRepository;
    private final AngelOneMarketDataProvider marketDataProvider;
    private final DailyStockContextService dailyStockContextService;
    private final TimeProvider timeProvider;

    public record QuoteSyncResult(
            boolean executed,
            int totalSymbols,
            int preMarketCandlesSaved,
            int stockPricesUpserted,
            String message) {
    }

    /**
     * Checks if today's 09:08 pre-market candles exist in DB.
     * If already present, skips external API call.
     * If missing, fetches batch quotes from Angel One SmartAPI, creates 09:08 candles,
     * upserts stock_prices, and recomputes DailyStockContext.
     */
    @Transactional
    public QuoteSyncResult syncMarketQuotesIfMissing(LocalDate targetDate) {
        if (targetDate == null) {
            return new QuoteSyncResult(false, 0, 0, 0, "Target date is null");
        }

        LocalDateTime preMarketTime = targetDate.atTime(9, 8);

        // Check if we already have 09:08 pre-market candles for today
        long existingPreMarketCount = marketCandleRepository
                .countByTimeframeAndCandleTime(CandleTimeframe.ONE_MINUTE, preMarketTime);

        if (existingPreMarketCount > 10) {
            log.info("09:08 pre-market candles already exist for date={} (count={}). Skipping Quote API call.",
                    targetDate, existingPreMarketCount);
            return new QuoteSyncResult(false, 0, 0, 0,
                    "09:08 pre-market candles already present (" + existingPreMarketCount + "). No sync needed.");
        }

        List<StockUniverse> activeUniverse = stockUniverseRepository.findByIsActiveTrue();
        if (activeUniverse == null || activeUniverse.isEmpty()) {
            return new QuoteSyncResult(false, 0, 0, 0, "Active universe is empty");
        }

        List<String> symbols = activeUniverse.stream()
                .map(StockUniverse::getSymbol)
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .toList();

        if (symbols.isEmpty()) {
            return new QuoteSyncResult(false, 0, 0, 0, "No symbols found in active universe");
        }

        log.info("Fetching Angel One market quotes for {} active symbols on date={}...", symbols.size(), targetDate);
        Map<String, AngelOneMarketDtos.AngelOneMarketQuoteItem> quotes;
        try {
            quotes = marketDataProvider.fetchMarketQuotes(symbols, "NSE");
        } catch (Exception ex) {
            log.error("Failed to fetch Angel One market quotes for date={}: {}", targetDate, ex.getMessage());
            return new QuoteSyncResult(false, symbols.size(), 0, 0, "Quote fetch failed: " + ex.getMessage());
        }

        if (quotes == null || quotes.isEmpty()) {
            log.warn("Angel One returned empty market quotes for date={}", targetDate);
            return new QuoteSyncResult(false, symbols.size(), 0, 0, "Empty quotes received from provider");
        }

        int candlesCreated = 0;
        int pricesUpserted = 0;

        for (String symbol : symbols) {
            AngelOneMarketDtos.AngelOneMarketQuoteItem quote = quotes.get(symbol);
            if (quote == null || quote.open() == null || quote.open() <= 0.0) {
                continue;
            }

            // 1. Synthesize authoritative 09:08 pre-market candle
            MarketCandle candle = marketCandleRepository
                    .findBySymbolAndExchangeAndTimeframeAndCandleTime(
                            symbol, "NSE", CandleTimeframe.ONE_MINUTE, preMarketTime)
                    .orElseGet(() -> MarketCandle.builder()
                            .symbol(symbol)
                            .exchange("NSE")
                            .timeframe(CandleTimeframe.ONE_MINUTE)
                            .candleTime(preMarketTime)
                            .createdAt(timeProvider.nowDateTime())
                            .build());

            candle.setOpenPrice(quote.open());
            candle.setHighPrice(quote.open());
            candle.setLowPrice(quote.open());
            candle.setClosePrice(quote.open());
            if (candle.getVolume() == null) {
                candle.setVolume(0L);
            }
            candle.setSource("MARKET_QUOTE_RECOVERY");
            candle.setIsFinalized(true);
            candle.setQualityStatus(CandleQualityStatus.RECONCILED);
            candle.setProcessingStatus(CandleProcessingStatus.RELEASED);
            candle.setUpdatedAt(timeProvider.nowDateTime());
            LiveMarketCandleService.applyCandleStructure(candle);
            marketCandleRepository.save(candle);
            candlesCreated++;

            // 2. Upsert stock_prices for today
            try {
                StockPrice price = stockPriceRepository.findBySymbolAndDate(symbol, targetDate)
                        .orElseGet(() -> StockPrice.builder()
                                .symbol(symbol)
                                .date(targetDate)
                                .build());

                if (quote.open() != null && quote.open() > 0.0) {
                    price.setOpenPrice(quote.open());
                }
                if (quote.high() != null && quote.high() > 0.0) {
                    price.setHighPrice(quote.high());
                }
                if (quote.low() != null && quote.low() > 0.0) {
                    price.setLowPrice(quote.low());
                }
                Double closeVal = quote.ltp() != null && quote.ltp() > 0.0
                        ? quote.ltp()
                        : (quote.close() != null && quote.close() > 0.0 ? quote.close() : null);
                if (closeVal != null) {
                    price.setClosePrice(closeVal);
                    price.setAdjClose(closeVal);
                }
                if (quote.tradeVolume() != null && quote.tradeVolume() >= 0L) {
                    price.setVolume((int) Math.min((long) Integer.MAX_VALUE, quote.tradeVolume()));
                }
                stockPriceRepository.save(price);
                pricesUpserted++;
            } catch (Exception ex) {
                log.warn("Failed to upsert stock_prices for symbol={} date={}: {}", symbol, targetDate, ex.getMessage());
            }

            // 3. Update DailyStockContext with the authoritative 09:08 candle
            try {
                dailyStockContextService.processFinalizedOneMinuteCandle(candle);
            } catch (Exception ex) {
                log.warn("Failed to update dailyStockContext for symbol={} date={}: {}", symbol, targetDate, ex.getMessage());
            }
        }

        log.info("Market quote sync complete for date={}: symbols={}, candlesCreated={}, pricesUpserted={}",
                targetDate, symbols.size(), candlesCreated, pricesUpserted);

        return new QuoteSyncResult(true, symbols.size(), candlesCreated, pricesUpserted,
                "Market quotes synchronized successfully");
    }
}
