package com.trading.scanner.service.provider.angelone;

import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.MarketMinuteSnapshot;
import com.trading.scanner.repository.MarketMinuteSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class LiveMarketSnapshotService {

    private final MarketMinuteSnapshotRepository marketMinuteSnapshotRepository;
    private final TimeProvider timeProvider;

    private final Map<String, SnapshotView> latestSnapshots = new ConcurrentHashMap<>();

    @Transactional
    public void update(AngelOneTickParserService.NormalizedTick tick) {
        if (tick == null || tick.symbol() == null || tick.exchange() == null || tick.tickTime() == null) {
            return;
        }

        String symbol = normalize(tick.symbol());
        String exchange = normalize(tick.exchange());
        String snapshotKey = snapshotKey(symbol, exchange, tick.brokerToken());

        SnapshotView previous = latestSnapshots.get(snapshotKey);
        LocalDateTime now = timeProvider.nowDateTime();

        SnapshotView snapshot = new SnapshotView(
                symbol,
                exchange,
                tick.tickTime(),
                tick.lastPrice(),
                tick.lastTradedQuantity(),
                tick.brokerToken(),
                tick.subscriptionMode(),
                tick.exchangeType(),
                tick.sequenceNumber(),
                tick.exchangeTimestamp(),
                tick.lastPriceRaw(),
                tick.openPrice(),
                tick.highPrice(),
                tick.lowPrice(),
                tick.closePrice(),
                tick.averageTradedPrice(),
                tick.volumeTradedForDay(),
                tick.totalBuyQuantity(),
                tick.totalSellQuantity(),
                tick.lastTradedTimestamp(),
                tick.openInterest(),
                tick.openInterestChangePercentRaw(),
                tick.upperCircuitLimit(),
                tick.lowerCircuitLimit(),
                tick.fiftyTwoWeekHighPrice(),
                tick.fiftyTwoWeekLowPrice(),
                now);

        if (previous != null) {
            LocalDateTime previousMinute = minuteBucket(previous.tickTime());
            LocalDateTime currentMinute = minuteBucket(snapshot.tickTime());

            if (!previousMinute.isEqual(currentMinute)) {
                marketMinuteSnapshotRepository
                        .findBySymbolAndExchangeAndMinuteTime(symbol, exchange, previousMinute)
                        .ifPresent(existing -> {
                            existing.setIsFinalized(true);
                            existing.setUpdatedAt(now);
                            marketMinuteSnapshotRepository.save(existing);
                        });
            }
        }

        latestSnapshots.put(snapshotKey, snapshot);
        upsertMinuteSnapshot(snapshot, now);
    }

    @Transactional(readOnly = true)
    public List<SnapshotView> latest(int limit) {
        return latestSnapshots.values().stream()
                .sorted(Comparator.comparing(SnapshotView::updatedAt).reversed())
                .limit(limit)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MarketMinuteSnapshot> recentPersisted(int limit) {
        return marketMinuteSnapshotRepository.findAllByOrderByUpdatedAtDesc(PageRequest.of(0, limit));
    }

    public ClearResult clear() {
        int removed = latestSnapshots.size();
        latestSnapshots.clear();
        return new ClearResult(removed, "Cleared latest live market snapshots");
    }

    private void upsertMinuteSnapshot(SnapshotView snapshot, LocalDateTime now) {
        LocalDateTime minuteTime = minuteBucket(snapshot.tickTime());
        LocalDate tradingDate = minuteTime.toLocalDate();

        MarketMinuteSnapshot row = marketMinuteSnapshotRepository
                .findBySymbolAndExchangeAndMinuteTime(snapshot.symbol(), snapshot.exchange(), minuteTime)
                .orElseGet(() -> MarketMinuteSnapshot.builder()
                        .symbol(snapshot.symbol())
                        .exchange(snapshot.exchange())
                        .minuteTime(minuteTime)
                        .tradingDate(tradingDate)
                        .createdAt(now)
                        .build());

        row.setLatestTickTime(snapshot.tickTime());
        row.setBrokerToken(snapshot.brokerToken());
        row.setSubscriptionMode(snapshot.subscriptionMode());
        row.setExchangeType(snapshot.exchangeType());
        row.setLastPrice(snapshot.lastPrice());
        row.setLastTradedQuantity(snapshot.lastTradedQuantity());
        row.setAverageTradedPrice(snapshot.averageTradedPrice());
        row.setVolumeTradedForDay(snapshot.volumeTradedForDay());
        row.setTotalBuyQuantity(snapshot.totalBuyQuantity());
        row.setTotalSellQuantity(snapshot.totalSellQuantity());
        row.setOpenInterest(snapshot.openInterest());
        row.setOpenInterestChangePercent(snapshot.openInterestChangePercentRaw());
        row.setUpperCircuitLimit(snapshot.upperCircuitLimit());
        row.setLowerCircuitLimit(snapshot.lowerCircuitLimit());
        row.setFiftyTwoWeekHighPrice(snapshot.fiftyTwoWeekHighPrice());
        row.setFiftyTwoWeekLowPrice(snapshot.fiftyTwoWeekLowPrice());
        row.setLastTradedTimestampEpoch(snapshot.lastTradedTimestamp());
        row.setExchangeTimestampEpoch(snapshot.exchangeTimestamp());
        row.setSequenceNumber(snapshot.sequenceNumber());
        row.setIsFinalized(false);
        row.setUpdatedAt(now);

        marketMinuteSnapshotRepository.save(row);
    }

    private String snapshotKey(String symbol, String exchange, String brokerToken) {
        if (brokerToken != null && !brokerToken.isBlank()) {
            return brokerToken;
        }
        return exchange + "|" + symbol;
    }

    private LocalDateTime minuteBucket(LocalDateTime tickTime) {
        return tickTime.withSecond(0).withNano(0);
    }

    private String normalize(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    public record SnapshotView(
            String symbol,
            String exchange,
            LocalDateTime tickTime,
            Double lastPrice,
            Long lastTradedQuantity,
            String brokerToken,
            Integer subscriptionMode,
            Integer exchangeType,
            Long sequenceNumber,
            Long exchangeTimestamp,
            Long lastPriceRaw,
            Double openPrice,
            Double highPrice,
            Double lowPrice,
            Double closePrice,
            Double averageTradedPrice,
            Long volumeTradedForDay,
            Long totalBuyQuantity,
            Long totalSellQuantity,
            Long lastTradedTimestamp,
            Long openInterest,
            Double openInterestChangePercentRaw,
            Double upperCircuitLimit,
            Double lowerCircuitLimit,
            Double fiftyTwoWeekHighPrice,
            Double fiftyTwoWeekLowPrice,
            LocalDateTime updatedAt) {
    }

    public record ClearResult(
            int removed,
            String message) {
    }
}