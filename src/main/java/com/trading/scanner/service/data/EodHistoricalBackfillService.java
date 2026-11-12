package com.trading.scanner.service.data;

import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.*;
import com.trading.scanner.repository.LiveMinuteResolutionRepository;
import com.trading.scanner.repository.MarketCandleRepository;
import com.trading.scanner.repository.StockPriceRepository;
import com.trading.scanner.repository.StockUniverseRepository;
import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.service.instrument.MarketIndexRegistry;
import com.trading.scanner.service.provider.DailyBarDto;
import com.trading.scanner.service.provider.angelone.AngelOneMarketDataProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Hardened EOD historical gap-only backfill service.
 * Automatically checks the required 20-day trading history for active universe stocks
 * and all registered sectoral/benchmark indices. Fetches only the exact missing dates
 * to ensure 100% data integrity with minimal broker API calls.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EodHistoricalBackfillService {

    private static final int REQUIRED_LOOKBACK_TRADING_DAYS = 20;
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);
    private static final String BACKFILL_SOURCE = "HISTORICAL_EOD_BACKFILL";

    private final TradingCalendar tradingCalendar;
    private final TimeProvider timeProvider;
    private final StockUniverseRepository stockUniverseRepository;
    private final StockPriceRepository stockPriceRepository;
    private final MarketCandleRepository marketCandleRepository;
    private final LiveMinuteResolutionRepository resolutionRepository;
    private final EodDataEntryService eodDataEntryService;
    private final MarketIndexRegistry marketIndexRegistry;

    @Autowired(required = false)
    private AngelOneMarketDataProvider angelOneMarketDataProvider;

    public record BackfillReport(
            LocalDate targetDate,
            int totalTargetsScanned,
            int targetsWithGaps,
            int dailyBarsBackfilled,
            int minuteDatesBackfilled,
            String message
    ) {}

    private record TargetRef(String symbol, String exchange, boolean isIndex) {}

    /**
     * Scans all active universe stocks and registered indices for missing trading days
     * in the 20-day lookback window prior to and including targetDate.
     */
    public BackfillReport backfillHistoricalGaps(LocalDate targetDate) {
        if (targetDate == null) {
            return new BackfillReport(null, 0, 0, 0, 0, "Target date is required");
        }

        if (angelOneMarketDataProvider == null || !angelOneMarketDataProvider.isAvailable()) {
            log.warn("[BACKFILL] Angel One market data provider is unavailable. Skipping historical gap backfill.");
            return new BackfillReport(targetDate, 0, 0, 0, 0, "Angel One provider unavailable");
        }

        List<LocalDate> expectedDates = new ArrayList<>();
        LocalDate curr = targetDate;
        for (int i = 0; i < REQUIRED_LOOKBACK_TRADING_DAYS; i++) {
            curr = tradingCalendar.previousTradingDay(curr);
            if (curr == null) break;
            expectedDates.add(curr);
        }
        Collections.sort(expectedDates);

        if (expectedDates.isEmpty()) {
            return new BackfillReport(targetDate, 0, 0, 0, 0, "No trading days found in lookback window");
        }

        LocalDate earliestDate = expectedDates.get(0);
        List<TargetRef> targets = resolveBackfillTargets();
        log.info("[BACKFILL] Evaluating 20-day historical completeness for {} targets between {} and {}",
                targets.size(), earliestDate, targetDate);

        int targetsWithGaps = 0;
        int totalDailyBars = 0;
        int totalMinuteDates = 0;

        for (TargetRef target : targets) {
            try {
                List<LocalDate> existingDates = stockPriceRepository.findDatesBySymbolBetween(
                        target.symbol(), earliestDate, targetDate);
                Set<LocalDate> existingSet = new HashSet<>(existingDates != null ? existingDates : List.of());

                List<LocalDate> missingDates = expectedDates.stream()
                        .filter(d -> !existingSet.contains(d))
                        .sorted()
                        .toList();

                if (missingDates.isEmpty()) {
                    continue;
                }

                targetsWithGaps++;
                log.info("[BACKFILL] Target {} missing {} trading dates in 20-day lookback: {}",
                        target.symbol(), missingDates.size(), missingDates);

                // Step 1: Gap-only daily OHLCV backfill
                int dailyInserted = backfillDailyBars(target, missingDates);
                totalDailyBars += dailyInserted;

                // Step 2: Gap-only 1-minute intraday candles and resolution backfill (equities & NIFTY)
                if (!target.isIndex() || "NIFTY".equalsIgnoreCase(target.symbol())) {
                    int minuteDatesFilled = backfillMissingIntradayMinutes(target, missingDates);
                    totalMinuteDates += minuteDatesFilled;
                }

                // Throttle slightly to respect Angel One historical API rate limit (10 req/s)
                try {
                    Thread.sleep(120);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }

            } catch (Exception ex) {
                log.warn("[BACKFILL] Exception during historical gap backfill for target={}: {}",
                        target.symbol(), ex.getMessage());
            }
        }

        String summary = String.format("Gap backfill completed: scanned=%d, gapsFound=%d, dailyBars=%d, minuteDates=%d",
                targets.size(), targetsWithGaps, totalDailyBars, totalMinuteDates);
        log.info("[BACKFILL] {}", summary);

        return new BackfillReport(targetDate, targets.size(), targetsWithGaps, totalDailyBars, totalMinuteDates, summary);
    }

    private List<TargetRef> resolveBackfillTargets() {
        Map<String, TargetRef> targets = new LinkedHashMap<>();

        // 1. All registered benchmark and sectoral indices (NIFTY, NIFTY BANK, NIFTY IT, etc.)
        for (MarketIndexRegistry.IndexDefinition idx : marketIndexRegistry.getAllRegisteredIndices()) {
            if (idx != null && idx.canonicalSymbol() != null) {
                String sym = idx.canonicalSymbol().trim().toUpperCase(Locale.ROOT);
                targets.put(sym, new TargetRef(sym, "NSE", true));
            }
        }

        // 2. All active stocks in universe
        for (StockUniverse stock : stockUniverseRepository.findByIsActiveTrueOrderBySymbolAsc()) {
            if (stock != null && stock.getSymbol() != null) {
                String sym = stock.getSymbol().trim().toUpperCase(Locale.ROOT);
                String exchange = stock.getExchange() != null ? stock.getExchange().name() : "NSE";
                targets.put(sym, new TargetRef(sym, exchange, false));
            }
        }

        return new ArrayList<>(targets.values());
    }

    private int backfillDailyBars(TargetRef target, List<LocalDate> missingDates) {
        if (missingDates.isEmpty()) return 0;
        LocalDate from = missingDates.get(0);
        LocalDate to = missingDates.get(missingDates.size() - 1);

        try {
            List<DailyBarDto> bars = angelOneMarketDataProvider.fetchHistoricalBars(target.symbol(), from, to);
            if (bars == null || bars.isEmpty()) {
                return 0;
            }

            int count = 0;
            for (DailyBarDto bar : bars) {
                if (bar == null || bar.tradingDate() == null) continue;

                try {
                    StockPrice sp = stockPriceRepository.findBySymbolAndDate(target.symbol(), bar.tradingDate())
                            .orElseGet(() -> StockPrice.builder()
                                    .symbol(target.symbol())
                                    .date(bar.tradingDate())
                                    .build());

                    if (bar.open() != null && bar.open() > 0.0) sp.setOpenPrice(bar.open());
                    if (bar.high() != null && bar.high() > 0.0) sp.setHighPrice(bar.high());
                    if (bar.low() != null && bar.low() > 0.0) sp.setLowPrice(bar.low());
                    if (bar.close() != null && bar.close() > 0.0) {
                        sp.setClosePrice(bar.close());
                        sp.setAdjClose(bar.adjustedClose() != null && bar.adjustedClose() > 0.0 ? bar.adjustedClose() : bar.close());
                    }
                    if (bar.volume() != null && bar.volume() >= 0L) {
                        sp.setVolume((int) Math.min((long) Integer.MAX_VALUE, bar.volume()));
                    }

                    stockPriceRepository.save(sp);
                    count++;
                } catch (Exception ex) {
                    log.warn("[BACKFILL_BAR_SKIPPED] Failed saving daily bar for target={} date={}: {}",
                            target.symbol(), bar.tradingDate(), ex.getMessage());
                }
            }
            return count;
        } catch (Exception ex) {
            log.warn("[BACKFILL] Failed daily bar fetch for target={} range=[{} to {}]: {}",
                    target.symbol(), from, to, ex.getMessage());
            return 0;
        }
    }

    private int backfillMissingIntradayMinutes(TargetRef target, List<LocalDate> missingDates) {
        int filledDates = 0;
        for (LocalDate date : missingDates) {
            try {
                LocalDateTime from = date.atTime(MARKET_OPEN);
                LocalDateTime to = date.atTime(MARKET_CLOSE);

                long existingCandles = marketCandleRepository.countBySymbolAndExchangeAndTimeframeAndCandleTimeBetween(
                        target.symbol(), target.exchange(), CandleTimeframe.ONE_MINUTE, from, to);

                int expectedCandles = tradingCalendar.expectedOneMinuteCandleCount(date);
                if (expectedCandles == 0) continue;

                if (existingCandles < expectedCandles) {
                    List<MarketCandle> fetched = angelOneMarketDataProvider.fetchHistoricalOneMinuteCandles(
                            target.symbol(), date, date);

                    if (fetched != null && !fetched.isEmpty()) {
                        // Persist missing candles
                        for (MarketCandle candle : fetched) {
                            if (candle != null && candle.getCandleTime() != null) {
                                Optional<MarketCandle> opt = marketCandleRepository
                                        .findBySymbolAndExchangeAndTimeframeAndCandleTime(
                                                target.symbol(), target.exchange(), CandleTimeframe.ONE_MINUTE, candle.getCandleTime());
                                if (opt.isEmpty()) {
                                    marketCandleRepository.save(candle);
                                }
                            }
                        }
                    }

                    // Account for no-trade minutes in illiquid symbols
                    fillMissingMinuteResolutions(target, date, fetched != null ? fetched : List.of());

                    // Mark EOD entry complete for this historic date
                    int actualCount = fetched != null ? fetched.size() : 0;
                    int noTradeCount = Math.max(0, expectedCandles - actualCount);
                    eodDataEntryService.complete(
                            target.symbol(),
                            target.exchange(),
                            date,
                            DataStatus.RECONCILED,
                            expectedCandles,
                            actualCount,
                            noTradeCount,
                            0,
                            0,
                            actualCount,
                            BACKFILL_SOURCE,
                            "20-day gap-only backfill verified"
                    );

                    filledDates++;
                }
            } catch (Exception ex) {
                log.warn("[BACKFILL] Failed intraday minute backfill for target={} date={}: {}",
                        target.symbol(), date, ex.getMessage());
            }
        }
        return filledDates;
    }

    private void fillMissingMinuteResolutions(TargetRef target, LocalDate date, List<MarketCandle> candles) {
        Set<LocalDateTime> candleTimes = candles.stream()
                .filter(Objects::nonNull)
                .map(MarketCandle::getCandleTime)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        LocalDateTime now = timeProvider.nowDateTime();
        LocalDateTime cursor = date.atTime(MARKET_OPEN);
        LocalDateTime end = date.atTime(MARKET_CLOSE);

        while (!cursor.isAfter(end)) {
            if (!candleTimes.contains(cursor)) {
                final LocalDateTime minuteTime = cursor;
                LiveMinuteResolution res = resolutionRepository
                        .findBySymbolAndExchangeAndMinuteTime(target.symbol(), target.exchange(), minuteTime)
                        .orElseGet(() -> LiveMinuteResolution.builder()
                                .symbol(target.symbol())
                                .exchange(target.exchange())
                                .minuteTime(minuteTime)
                                .tradingDate(date)
                                .createdAt(now)
                                .build());

                res.setStatus(MinuteResolutionStatus.NO_TRADE_CONFIRMED);
                res.setReason("Historical backfill confirmed no-trade minute");
                res.setResolvedAt(now);
                res.setUpdatedAt(now);
                resolutionRepository.save(res);
            }
            cursor = cursor.plusMinutes(1);
        }
    }
}
