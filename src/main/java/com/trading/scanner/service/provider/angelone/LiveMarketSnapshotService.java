package com.trading.scanner.service.provider.angelone;

import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.FeedHealthStatus;
import com.trading.scanner.model.LiveFeedState;
import com.trading.scanner.model.LiveMinuteResolution;
import com.trading.scanner.model.MarketMinuteSnapshot;
import com.trading.scanner.model.MinuteResolutionStatus;
import com.trading.scanner.model.StockUniverse;
import com.trading.scanner.repository.LiveFeedStateRepository;
import com.trading.scanner.repository.LiveMinuteResolutionRepository;
import com.trading.scanner.repository.MarketMinuteSnapshotRepository;
import com.trading.scanner.repository.StockUniverseRepository;
import com.trading.scanner.service.data.IntradayGapDetectedEvent;
import com.trading.scanner.service.engine.DailyDataStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LiveMarketSnapshotService {

        private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);

        private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 29);

        private final MarketMinuteSnapshotRepository snapshotRepository;
        private final LiveFeedStateRepository feedStateRepository;
        private final LiveMinuteResolutionRepository resolutionRepository;
        private final StockUniverseRepository stockUniverseRepository;
        private final TradingCalendar tradingCalendar;
        private final DailyDataStatusService dataStatusService;
        private final TimeProvider timeProvider;
        private final ApplicationEventPublisher eventPublisher;

        private final Map<String, SnapshotView> latestSnapshots = new ConcurrentHashMap<>();

        @Value("${runtime.feed-health.recovery-ticks:2}")
        private int recoveryTicks = 2;

        @Transactional
        public void update(
                        AngelOneTickParserService.NormalizedTick tick) {

                if (tick == null
                                || tick.symbol() == null
                                || tick.exchange() == null
                                || tick.tickTime() == null) {
                        return;
                }

                String symbol = normalize(tick.symbol());
                String exchange = normalize(tick.exchange());
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

                updateFeedState(snapshot, now);

                String key = snapshotKey(
                                snapshot.symbol(),
                                snapshot.exchange(),
                                snapshot.brokerToken());

                SnapshotView previous = latestSnapshots.get(key);

                if (previous != null
                                && !minuteBucket(previous.tickTime())
                                                .equals(minuteBucket(snapshot.tickTime()))) {

                        snapshotRepository
                                        .findBySymbolAndExchangeAndMinuteTime(
                                                        symbol,
                                                        exchange,
                                                        minuteBucket(previous.tickTime()))
                                        .ifPresent(row -> {
                                                row.setIsFinalized(true);
                                                row.setUpdatedAt(now);
                                                snapshotRepository.save(row);
                                        });
                }

                latestSnapshots.put(key, snapshot);
                upsertMinuteSnapshot(snapshot, now);
                markTickReceived(snapshot, now);
        }

        @Transactional
        public int checkForClosedMinuteGaps() {
                LocalDateTime now = timeProvider.nowDateTime()
                                .withSecond(0)
                                .withNano(0);

                LocalDate date = now.toLocalDate();

                if (!tradingCalendar.isTradingDay(date)) {
                        return 0;
                }

                LocalDateTime latestClosedMinute = now.minusMinutes(1);

                if (latestClosedMinute.toLocalTime()
                                .isAfter(MARKET_CLOSE)) {
                        latestClosedMinute = date.atTime(MARKET_CLOSE);
                }

                if (latestClosedMinute.toLocalTime()
                                .isBefore(MARKET_OPEN)) {
                        return 0;
                }

                int detected = 0;

                for (StockUniverse stock : stockUniverseRepository
                                .findByIsActiveTrueOrderBySymbolAsc()) {

                        String symbol = stock.getSymbol();
                        String exchange = stock.getExchange().name();

                        LiveFeedState feedState = feedStateRepository
                                        .findBySymbolAndExchangeAndTradingDate(
                                                        symbol,
                                                        exchange,
                                                        date)
                                        .orElseGet(() -> LiveFeedState.builder()
                                                        .symbol(symbol)
                                                        .exchange(exchange)
                                                        .tradingDate(date)
                                                        .blocked(false)
                                                        .build());

                        LocalDateTime from = feedState.getLastCheckedMinute() == null
                                        ? date.atTime(MARKET_OPEN)
                                        : feedState.getLastCheckedMinute()
                                                        .plusMinutes(1);

                        if (from.isBefore(
                                        date.atTime(MARKET_OPEN))) {
                                from = date.atTime(MARKET_OPEN);
                        }

                        if (from.isAfter(latestClosedMinute)) {
                                continue;
                        }

                        Set<LocalDateTime> tickMinutes = snapshotRepository
                                        .findBySymbolAndExchangeAndMinuteTimeBetweenOrderByMinuteTimeAsc(
                                                        symbol,
                                                        exchange,
                                                        from,
                                                        latestClosedMinute)
                                        .stream()
                                        .map(MarketMinuteSnapshot::getMinuteTime)
                                        .collect(Collectors.toSet());

                        Map<LocalDateTime, LiveMinuteResolution> resolutions = new HashMap<>();

                        for (LiveMinuteResolution resolution : resolutionRepository
                                        .findBySymbolAndExchangeAndMinuteTimeBetweenOrderByMinuteTimeAsc(
                                                        symbol,
                                                        exchange,
                                                        from,
                                                        latestClosedMinute)) {

                                resolutions.put(
                                                resolution.getMinuteTime(),
                                                resolution);
                        }

                        List<LocalDateTime> unresolved = new ArrayList<>();

                        List<LocalDateTime> newlyUnresolved = new ArrayList<>();

                        for (LocalDateTime minute = from; !minute.isAfter(latestClosedMinute); minute = minute
                                        .plusMinutes(1)) {

                                if (tickMinutes.contains(minute)) {
                                        continue;
                                }

                                LiveMinuteResolution resolution = resolutions.get(minute);

                                if (resolution != null
                                                && (resolution.getStatus() == MinuteResolutionStatus.NO_TRADE_CONFIRMED
                                                                || resolution.getStatus() == MinuteResolutionStatus.REPAIRED)) {
                                        continue;
                                }

                                unresolved.add(minute);

                                if (markUnresolved(
                                                symbol,
                                                exchange,
                                                minute,
                                                "No local tick candle; provider validation required",
                                                now)) {
                                        newlyUnresolved.add(minute);
                                }
                        }

                        if (!unresolved.isEmpty()) {
                                feedState.setBlocked(true);
                                feedState.setGapFrom(min(
                                                feedState.getGapFrom(),
                                                unresolved.get(0)));
                                feedState.setGapTo(max(
                                                feedState.getGapTo(),
                                                unresolved.get(
                                                                unresolved.size() - 1)));

                                dataStatusService.markPartial(
                                                symbol,
                                                exchange,
                                                date,
                                                "Closed minute unresolved; provider validation required");

                                publishRanges(
                                                symbol,
                                                exchange,
                                                date,
                                                newlyUnresolved);

                                detected += newlyUnresolved.isEmpty()
                                                ? 0
                                                : 1;
                        } else if (Boolean.TRUE.equals(
                                        feedState.getBlocked())) {

                                feedState.setBlocked(false);
                                feedState.setGapFrom(null);
                                feedState.setGapTo(null);
                        }

                        feedState.setLastCheckedMinute(
                                        latestClosedMinute);
                        feedState.setUpdatedAt(now);
                        feedStateRepository.save(feedState);
                }

                return detected;
        }

        @Transactional
        public void confirmNoTrade(
                        String symbol,
                        String exchange,
                        LocalDateTime minuteTime,
                        String reason) {

                String normalizedSymbol = normalize(symbol);
                String normalizedExchange = normalize(exchange);
                LocalDateTime minute = minuteBucket(minuteTime);
                LocalDateTime now = timeProvider.nowDateTime();

                LiveMinuteResolution resolution = resolutionRepository
                                .findBySymbolAndExchangeAndMinuteTime(
                                                normalizedSymbol,
                                                normalizedExchange,
                                                minute)
                                .orElseGet(() -> LiveMinuteResolution.builder()
                                                .symbol(normalizedSymbol)
                                                .exchange(normalizedExchange)
                                                .minuteTime(minute)
                                                .tradingDate(
                                                                minute.toLocalDate())
                                                .createdAt(now)
                                                .build());

                if (resolution.getStatus() != MinuteResolutionStatus.REPAIRED) {

                        resolution.setStatus(
                                        MinuteResolutionStatus.NO_TRADE_CONFIRMED);
                        resolution.setReason(reason);
                        resolution.setResolvedAt(now);
                        resolution.setUpdatedAt(now);

                        resolutionRepository.save(resolution);
                }
        }

        @Transactional(readOnly = true)
        public Optional<Long> currentCumulativeVolumeToday(
                        String symbol,
                        String exchange,
                        LocalDate tradingDate) {

                String normalizedSymbol = normalize(symbol);
                String normalizedExchange = normalize(exchange);

                Optional<LiveFeedState> state = feedStateRepository
                                .findBySymbolAndExchangeAndTradingDate(
                                                normalizedSymbol,
                                                normalizedExchange,
                                                tradingDate);

                if (state.isPresent()
                                && state.get().getCumulativeVolumeToday() != null) {
                        return Optional.of(
                                        state.get().getCumulativeVolumeToday());
                }

                return cumulativeVolumeFromSnapshots(
                                normalizedSymbol,
                                normalizedExchange,
                                tradingDate,
                                tradingDate.atTime(MARKET_CLOSE));
        }

        @Transactional(readOnly = true)
        public Optional<Long> cumulativeVolumeAt(
                        String symbol,
                        String exchange,
                        LocalDate tradingDate,
                        LocalDateTime asOf) {

                if (symbol == null
                                || exchange == null
                                || tradingDate == null
                                || asOf == null) {
                        return Optional.empty();
                }

                String normalizedSymbol = normalize(symbol);
                String normalizedExchange = normalize(exchange);

                LocalDateTime calculatedEnd = asOf.toLocalDate().equals(tradingDate)
                                ? asOf
                                : tradingDate.atTime(MARKET_CLOSE);

                if (calculatedEnd.isBefore(
                                tradingDate.atTime(MARKET_OPEN))) {
                        return Optional.empty();
                }

                if (calculatedEnd.toLocalTime()
                                .isAfter(MARKET_CLOSE)) {
                        calculatedEnd = tradingDate.atTime(MARKET_CLOSE);
                }

                final LocalDateTime effectiveEnd = calculatedEnd;

                Optional<Long> snapshotVolume = cumulativeVolumeFromSnapshots(
                                normalizedSymbol,
                                normalizedExchange,
                                tradingDate,
                                effectiveEnd);

                if (snapshotVolume.isPresent()) {
                        return snapshotVolume;
                }

                return feedStateRepository
                                .findBySymbolAndExchangeAndTradingDate(
                                                normalizedSymbol,
                                                normalizedExchange,
                                                tradingDate)
                                .filter(state -> state.getLastTickTime() == null
                                                || !state.getLastTickTime()
                                                                .isAfter(effectiveEnd))
                                .map(LiveFeedState::getCumulativeVolumeToday);
        }

        @Transactional(readOnly = true)
        public List<SnapshotView> latest(int limit) {
                return latestSnapshots.values()
                                .stream()
                                .sorted(Comparator.comparing(
                                                SnapshotView::updatedAt).reversed())
                                .limit(limit)
                                .toList();
        }

        @Transactional(readOnly = true)
        public List<MarketMinuteSnapshot> recentPersisted(
                        int limit) {

                return snapshotRepository
                                .findAllByOrderByUpdatedAtDesc(
                                                PageRequest.of(0, limit));
        }

        public ClearResult clear() {
                int removed = latestSnapshots.size();
                latestSnapshots.clear();

                return new ClearResult(
                                removed,
                                "Cleared latest live market snapshots");
        }

        private Optional<Long> cumulativeVolumeFromSnapshots(
                        String symbol,
                        String exchange,
                        LocalDate tradingDate,
                        LocalDateTime end) {

                List<MarketMinuteSnapshot> rows = snapshotRepository
                                .findBySymbolAndExchangeAndMinuteTimeBetweenOrderByMinuteTimeAsc(
                                                symbol,
                                                exchange,
                                                tradingDate.atTime(MARKET_OPEN),
                                                end);

                for (int i = rows.size() - 1; i >= 0; i--) {
                        Long volume = rows.get(i).getVolumeTradedForDay();

                        if (volume != null && volume >= 0L) {
                                return Optional.of(volume);
                        }
                }

                return Optional.empty();
        }

        private void updateFeedState(
                        SnapshotView snapshot,
                        LocalDateTime now) {

                LocalDate date = snapshot.tickTime().toLocalDate();

                LocalDateTime minute = minuteBucket(snapshot.tickTime());

                LiveFeedState state = feedStateRepository
                                .findBySymbolAndExchangeAndTradingDate(
                                                snapshot.symbol(),
                                                snapshot.exchange(),
                                                date)
                                .orElseGet(() -> LiveFeedState.builder()
                                                .symbol(snapshot.symbol())
                                                .exchange(snapshot.exchange())
                                                .tradingDate(date)
                                                .healthStatus(
                                                                FeedHealthStatus.HEALTHY)
                                                .subscriptionActive(false)
                                                .consecutiveRecoveryTicks(0)
                                                .build());

                FeedHealthStatus currentStatus = state.getHealthStatus() == null
                                ? FeedHealthStatus.HEALTHY
                                : state.getHealthStatus();

                boolean newerTick = state.getLastTickTime() == null
                                || snapshot.tickTime()
                                                .isAfter(state.getLastTickTime());

                if (Boolean.TRUE.equals(
                                state.getSubscriptionActive())) {

                        if (currentStatus == FeedHealthStatus.STALE
                                        || currentStatus == FeedHealthStatus.ALERT_SENT) {

                                state.setHealthStatus(
                                                FeedHealthStatus.RECOVERING);
                                state.setConsecutiveRecoveryTicks(1);
                                state.setRecoveredAt(null);
                                state.setLastHealthTransitionAt(now);

                        } else if (currentStatus == FeedHealthStatus.RECOVERING) {

                                int count = state.getConsecutiveRecoveryTicks() == null
                                                ? 0
                                                : state.getConsecutiveRecoveryTicks();

                                count++;
                                state.setConsecutiveRecoveryTicks(count);

                                if (count >= Math.max(
                                                1,
                                                recoveryTicks)) {

                                        state.setHealthStatus(
                                                        FeedHealthStatus.HEALTHY);
                                        state.setRecoveredAt(now);
                                        state.setStaleSince(null);
                                        state.setStaleAlertedAt(null);
                                        state.setLastHealthTransitionAt(now);
                                        state.setConsecutiveRecoveryTicks(0);
                                }
                        } else {
                                state.setHealthStatus(
                                                FeedHealthStatus.HEALTHY);
                                state.setConsecutiveRecoveryTicks(0);
                        }
                }

                if (newerTick) {
                        state.setLastTickTime(
                                        snapshot.tickTime());
                        state.setLastTickMinute(minute);
                }

                Long volume = snapshot.volumeTradedForDay();

                if (volume != null
                                && volume >= 0L
                                && (newerTick
                                                || state.getCumulativeVolumeToday() == null)) {

                        state.setCumulativeVolumeToday(volume);
                }

                state.setUpdatedAt(now);
                feedStateRepository.save(state);
        }

        private void markTickReceived(
                        SnapshotView snapshot,
                        LocalDateTime now) {

                LocalDateTime minute = minuteBucket(snapshot.tickTime());

                LiveMinuteResolution resolution = resolutionRepository
                                .findBySymbolAndExchangeAndMinuteTime(
                                                snapshot.symbol(),
                                                snapshot.exchange(),
                                                minute)
                                .orElseGet(() -> LiveMinuteResolution.builder()
                                                .symbol(snapshot.symbol())
                                                .exchange(snapshot.exchange())
                                                .minuteTime(minute)
                                                .tradingDate(
                                                                minute.toLocalDate())
                                                .createdAt(now)
                                                .build());

                if (resolution.getStatus() != MinuteResolutionStatus.REPAIRED) {

                        resolution.setStatus(
                                        MinuteResolutionStatus.TICK_RECEIVED);
                        resolution.setReason(
                                        "WebSocket tick received");
                        resolution.setUpdatedAt(now);

                        resolutionRepository.save(resolution);
                }
        }

        private void upsertMinuteSnapshot(
                        SnapshotView snapshot,
                        LocalDateTime now) {

                LocalDateTime minuteTime = minuteBucket(snapshot.tickTime());

                MarketMinuteSnapshot row = snapshotRepository
                                .findBySymbolAndExchangeAndMinuteTime(
                                                snapshot.symbol(),
                                                snapshot.exchange(),
                                                minuteTime)
                                .orElseGet(() -> MarketMinuteSnapshot.builder()
                                                .symbol(snapshot.symbol())
                                                .exchange(snapshot.exchange())
                                                .minuteTime(minuteTime)
                                                .tradingDate(
                                                                minuteTime.toLocalDate())
                                                .createdAt(now)
                                                .build());

                row.setLatestTickTime(snapshot.tickTime());
                row.setBrokerToken(snapshot.brokerToken());
                row.setSubscriptionMode(
                                snapshot.subscriptionMode());
                row.setExchangeType(
                                snapshot.exchangeType());
                row.setLastPrice(snapshot.lastPrice());
                row.setLastTradedQuantity(
                                snapshot.lastTradedQuantity());
                row.setAverageTradedPrice(
                                snapshot.averageTradedPrice());
                row.setVolumeTradedForDay(
                                snapshot.volumeTradedForDay());
                row.setTotalBuyQuantity(
                                snapshot.totalBuyQuantity());
                row.setTotalSellQuantity(
                                snapshot.totalSellQuantity());
                row.setOpenInterest(
                                snapshot.openInterest());
                row.setOpenInterestChangePercent(
                                snapshot.openInterestChangePercentRaw());
                row.setUpperCircuitLimit(
                                snapshot.upperCircuitLimit());
                row.setLowerCircuitLimit(
                                snapshot.lowerCircuitLimit());
                row.setFiftyTwoWeekHighPrice(
                                snapshot.fiftyTwoWeekHighPrice());
                row.setFiftyTwoWeekLowPrice(
                                snapshot.fiftyTwoWeekLowPrice());
                row.setLastTradedTimestampEpoch(
                                snapshot.lastTradedTimestamp());
                row.setExchangeTimestampEpoch(
                                snapshot.exchangeTimestamp());
                row.setSequenceNumber(
                                snapshot.sequenceNumber());
                row.setIsFinalized(false);
                row.setUpdatedAt(now);

                snapshotRepository.save(row);
        }

        private boolean markUnresolved(
                        String symbol,
                        String exchange,
                        LocalDateTime minute,
                        String reason,
                        LocalDateTime now) {

                LiveMinuteResolution resolution = resolutionRepository
                                .findBySymbolAndExchangeAndMinuteTime(
                                                symbol,
                                                exchange,
                                                minute)
                                .orElseGet(() -> LiveMinuteResolution.builder()
                                                .symbol(symbol)
                                                .exchange(exchange)
                                                .minuteTime(minute)
                                                .tradingDate(
                                                                minute.toLocalDate())
                                                .createdAt(now)
                                                .build());

                boolean changed = resolution.getStatus() != MinuteResolutionStatus.UNRESOLVED;

                if (changed) {
                        resolution.setStatus(
                                        MinuteResolutionStatus.UNRESOLVED);
                        resolution.setReason(reason);
                        resolution.setResolvedAt(null);
                        resolution.setUpdatedAt(now);
                        resolutionRepository.save(resolution);
                }

                return changed;
        }

        private void publishRanges(
                        String symbol,
                        String exchange,
                        LocalDate date,
                        List<LocalDateTime> minutes) {

                if (minutes.isEmpty()) {
                        return;
                }

                LocalDateTime start = minutes.get(0);
                LocalDateTime end = start;

                for (int i = 1; i < minutes.size(); i++) {
                        LocalDateTime current = minutes.get(i);

                        if (!current.equals(
                                        end.plusMinutes(1))) {

                                publishGap(
                                                symbol,
                                                exchange,
                                                date,
                                                start,
                                                end);

                                start = current;
                        }

                        end = current;
                }

                publishGap(
                                symbol,
                                exchange,
                                date,
                                start,
                                end);
        }

        private void publishGap(
                        String symbol,
                        String exchange,
                        LocalDate date,
                        LocalDateTime from,
                        LocalDateTime to) {

                eventPublisher.publishEvent(
                                new IntradayGapDetectedEvent(
                                                symbol,
                                                exchange,
                                                date,
                                                from,
                                                to));
        }

        private String snapshotKey(
                        String symbol,
                        String exchange,
                        String brokerToken) {

                return brokerToken == null
                                || brokerToken.isBlank()
                                                ? exchange + "|" + symbol
                                                : brokerToken;
        }

        private LocalDateTime minuteBucket(
                        LocalDateTime value) {

                return value
                                .withSecond(0)
                                .withNano(0);
        }

        private LocalDateTime min(
                        LocalDateTime left,
                        LocalDateTime right) {

                return left == null
                                || right.isBefore(left)
                                                ? right
                                                : left;
        }

        private LocalDateTime max(
                        LocalDateTime left,
                        LocalDateTime right) {

                return left == null
                                || right.isAfter(left)
                                                ? right
                                                : left;
        }

        private String normalize(String value) {
                return value == null
                                ? null
                                : value.trim()
                                                .toUpperCase(Locale.ROOT);
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
