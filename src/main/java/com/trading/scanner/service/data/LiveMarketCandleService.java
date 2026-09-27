package com.trading.scanner.service.data;

import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.CandleDirection;
import com.trading.scanner.model.CandleProcessingStatus;
import com.trading.scanner.model.CandleQualityStatus;
import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.LiveMinuteResolution;
import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.model.MinuteResolutionStatus;
import com.trading.scanner.model.StockPrice;
import com.trading.scanner.repository.LiveMinuteResolutionRepository;
import com.trading.scanner.repository.MarketCandleRepository;
import com.trading.scanner.repository.StockPriceRepository;
import com.trading.scanner.service.engine.DailyDataStatusService;
import com.trading.scanner.service.engine.DailyStockContextService;
import com.trading.scanner.service.engine.IntradayIndicatorService;
import com.trading.scanner.service.runtime.LiveSignalService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LiveMarketCandleService {

        private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);

        private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 29);

        private final MarketCandleRepository marketCandleRepository;

        private final TimeProvider timeProvider;
        private final LiveSignalService liveSignalService;

        private final DailyStockContextService dailyStockContextService;

        private final IntradayIndicatorService intradayIndicatorService;

        private final DailyDataStatusService dailyDataStatusService;

        private final ApplicationEventPublisher eventPublisher;

        private final LiveMinuteResolutionRepository liveMinuteResolutionRepository;

        private final StockPriceRepository stockPriceRepository;

        private final Map<String, OpenMinuteCandle> openCandles = new ConcurrentHashMap<>();

        private final Map<String, Boolean> blockedSymbols = new ConcurrentHashMap<>();

        private final Map<String, Optional<Double>> previousDayCloseCache = new ConcurrentHashMap<>();

        @Autowired
        public LiveMarketCandleService(
                        MarketCandleRepository marketCandleRepository,
                        TimeProvider timeProvider,
                        LiveSignalService liveSignalService,
                        DailyStockContextService dailyStockContextService,
                        IntradayIndicatorService intradayIndicatorService,
                        DailyDataStatusService dailyDataStatusService,
                        ApplicationEventPublisher eventPublisher,
                        LiveMinuteResolutionRepository liveMinuteResolutionRepository,
                        StockPriceRepository stockPriceRepository) {

                this.marketCandleRepository = marketCandleRepository;

                this.timeProvider = timeProvider;
                this.liveSignalService = liveSignalService;

                this.dailyStockContextService = dailyStockContextService;

                this.intradayIndicatorService = intradayIndicatorService;

                this.dailyDataStatusService = dailyDataStatusService;

                this.eventPublisher = eventPublisher;

                this.liveMinuteResolutionRepository = liveMinuteResolutionRepository;

                this.stockPriceRepository = stockPriceRepository;
        }

        /*
         * Compatibility constructor for existing tests.
         * Production Spring wiring uses the constructor above.
         */
        public LiveMarketCandleService(
                        MarketCandleRepository marketCandleRepository,
                        TimeProvider timeProvider,
                        LiveSignalService liveSignalService,
                        DailyStockContextService dailyStockContextService,
                        IntradayIndicatorService intradayIndicatorService,
                        DailyDataStatusService dailyDataStatusService,
                        ApplicationEventPublisher eventPublisher,
                        LiveMinuteResolutionRepository liveMinuteResolutionRepository) {

                this(
                                marketCandleRepository,
                                timeProvider,
                                liveSignalService,
                                dailyStockContextService,
                                intradayIndicatorService,
                                dailyDataStatusService,
                                eventPublisher,
                                liveMinuteResolutionRepository,
                                null);
        }

        @Transactional
        public IngestResult ingestTick(
                        TickInput input) {

                if (input == null
                                || input.symbol() == null
                                || input.exchange() == null
                                || input.tickTime() == null) {

                        return new IngestResult(
                                        null,
                                        null,
                                        null,
                                        false,
                                        false,
                                        "Ignored invalid tick");
                }

                String symbol = normalize(input.symbol());

                String exchange = normalize(input.exchange());

                LocalDateTime tickTime = truncateToMinute(input.tickTime());

                String key = exchange + "|" + symbol;

                synchronized (key.intern()) {
                        OpenMinuteCandle existing = openCandles.get(key);

                        if (existing == null) {
                                detectPersistedGap(
                                                symbol,
                                                exchange,
                                                tickTime);

                                openCandles.put(
                                                key,
                                                OpenMinuteCandle.start(
                                                                symbol,
                                                                exchange,
                                                                tickTime,
                                                                input.lastPrice(),
                                                                input.lastTradedQuantity()));

                                return new IngestResult(
                                                symbol,
                                                exchange,
                                                tickTime,
                                                true,
                                                false,
                                                "Started new open 1-minute candle");
                        }

                        if (tickTime.isBefore(
                                        existing.candleTime())) {

                                return new IngestResult(
                                                symbol,
                                                exchange,
                                                existing.candleTime(),
                                                false,
                                                false,
                                                "Ignored out-of-order tick");
                        }

                        if (tickTime.equals(
                                        existing.candleTime())) {

                                existing.applyTick(
                                                input.lastPrice(),
                                                input.lastTradedQuantity());

                                return new IngestResult(
                                                symbol,
                                                exchange,
                                                tickTime,
                                                true,
                                                false,
                                                "Updated open 1-minute candle");
                        }

                        boolean gap = tickTime.isAfter(
                                        existing.candleTime()
                                                        .plusMinutes(1));

                        if (gap) {
                                detectGap(
                                                symbol,
                                                exchange,
                                                tickTime.toLocalDate(),
                                                existing.candleTime()
                                                                .plusMinutes(1),
                                                tickTime.minusMinutes(1));
                        }

                        finalizeAndPersist(
                                        existing,
                                        blocked(key));

                        openCandles.put(
                                        key,
                                        OpenMinuteCandle.start(
                                                        symbol,
                                                        exchange,
                                                        tickTime,
                                                        input.lastPrice(),
                                                        input.lastTradedQuantity()));

                        return new IngestResult(
                                        symbol,
                                        exchange,
                                        tickTime,
                                        true,
                                        true,
                                        gap
                                                        ? "Gap detected; candle pipeline blocked"
                                                        : "Finalized previous 1-minute candle");
                }
        }

        @Transactional
        public boolean releaseRepairedRange(
                        String symbol,
                        String exchange,
                        LocalDateTime fromTime,
                        LocalDateTime toTime) {

                if (symbol == null
                                || exchange == null
                                || fromTime == null
                                || toTime == null) {
                        return false;
                }

                String normalizedSymbol = normalize(symbol);

                String normalizedExchange = normalize(exchange);

                LocalDateTime from = truncateToMinute(fromTime);

                LocalDateTime to = truncateToMinute(toTime);

                if (from.isAfter(to)
                                || !from.toLocalDate().equals(
                                                to.toLocalDate())) {
                        return false;
                }

                LocalDateTime releaseFrom = from.toLocalTime().equals(MARKET_OPEN)
                                ? from
                                : from.minusMinutes(1);

                List<MarketCandle> candles = marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                normalizedSymbol,
                                                normalizedExchange,
                                                CandleTimeframe.ONE_MINUTE,
                                                releaseFrom,
                                                to);

                List<LiveMinuteResolution> resolutions = liveMinuteResolutionRepository
                                .findBySymbolAndExchangeAndMinuteTimeBetweenOrderByMinuteTimeAsc(
                                                normalizedSymbol,
                                                normalizedExchange,
                                                releaseFrom,
                                                to);

                Map<LocalDateTime, MarketCandle> candlesByTime = new LinkedHashMap<>();

                for (MarketCandle candle : candles) {
                        candlesByTime.put(
                                        candle.getCandleTime(),
                                        candle);
                }

                Map<LocalDateTime, LiveMinuteResolution> resolutionsByTime = new LinkedHashMap<>();

                for (LiveMinuteResolution resolution : resolutions) {
                        resolutionsByTime.put(
                                        resolution.getMinuteTime(),
                                        resolution);
                }

                for (LocalDateTime minute = releaseFrom; !minute.isAfter(to); minute = minute.plusMinutes(1)) {

                        MarketCandle candle = candlesByTime.get(minute);

                        if (candle != null) {
                                if (!isRepairable(candle)) {
                                        return false;
                                }

                                continue;
                        }

                        LiveMinuteResolution resolution = resolutionsByTime.get(minute);

                        if (resolution == null
                                        || resolution.getStatus() != MinuteResolutionStatus.NO_TRADE_CONFIRMED) {
                                return false;
                        }
                }

                for (MarketCandle candle : candles) {
                        candle.setProcessingStatus(
                                        CandleProcessingStatus.RELEASED);

                        candle.setUpdatedAt(
                                        timeProvider.nowDateTime());

                        marketCandleRepository.save(candle);
                }

                recomputeDerivedData(
                                normalizedSymbol,
                                normalizedExchange,
                                from.toLocalDate());

                blockedSymbols.remove(
                                normalizedExchange
                                                + "|"
                                                + normalizedSymbol);

                return true;
        }

        @Transactional
        public void confirmNoTradeForUnresolvedRange(
                        String symbol,
                        String exchange,
                        LocalDateTime fromTime,
                        LocalDateTime toTime) {

                if (symbol == null
                                || exchange == null
                                || fromTime == null
                                || toTime == null) {
                        return;
                }

                String normalizedSymbol = normalize(symbol);
                String normalizedExchange = normalize(exchange);
                LocalDateTime from = truncateToMinute(fromTime);
                LocalDateTime to = truncateToMinute(toTime);

                if (from.isAfter(to) || !from.toLocalDate().equals(to.toLocalDate())) {
                        return;
                }

                LocalDateTime releaseFrom = from.toLocalTime().equals(MARKET_OPEN)
                                ? from
                                : from.minusMinutes(1);

                List<MarketCandle> candles = marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                normalizedSymbol,
                                                normalizedExchange,
                                                CandleTimeframe.ONE_MINUTE,
                                                releaseFrom,
                                                to);

                java.util.Set<LocalDateTime> candleTimes = new java.util.HashSet<>();
                for (MarketCandle candle : candles) {
                        if (candle != null && candle.getCandleTime() != null) {
                                candleTimes.add(candle.getCandleTime());
                        }
                }

                LocalDateTime now = timeProvider.nowDateTime();
                for (LocalDateTime minute = releaseFrom; !minute.isAfter(to); minute = minute.plusMinutes(1)) {
                        if (!candleTimes.contains(minute)) {
                                final LocalDateTime targetMinute = minute;
                                LiveMinuteResolution resolution = liveMinuteResolutionRepository
                                                .findBySymbolAndExchangeAndMinuteTime(normalizedSymbol,
                                                                normalizedExchange, targetMinute)
                                                .orElseGet(() -> LiveMinuteResolution.builder()
                                                                .symbol(normalizedSymbol)
                                                                .exchange(normalizedExchange)
                                                                .tradingDate(targetMinute.toLocalDate())
                                                                .minuteTime(targetMinute)
                                                                .createdAt(now)
                                                                .build());

                                if (resolution.getStatus() != MinuteResolutionStatus.REPAIRED) {
                                        resolution.setStatus(MinuteResolutionStatus.NO_TRADE_CONFIRMED);
                                        resolution.setReason(
                                                        "SmartAPI returned no candle after retries; confirmed zero-trade minute");
                                        resolution.setResolvedAt(now);
                                        resolution.setUpdatedAt(now);
                                        liveMinuteResolutionRepository.save(resolution);
                                }
                        }
                }
        }

        @Transactional
        public void recomputeDerivedData(
                        String symbol,
                        String exchange,
                        LocalDate tradingDate) {

                if (symbol == null
                                || symbol.isBlank()
                                || exchange == null
                                || exchange.isBlank()
                                || tradingDate == null) {
                        return;
                }

                recomputeFrom(
                                normalize(symbol),
                                normalize(exchange),
                                tradingDate.atTime(MARKET_OPEN));
        }

        @Transactional(readOnly = true)
        public List<OpenCandleView> openCandles() {
                return openCandles.values()
                                .stream()
                                .map(candle -> new OpenCandleView(
                                                candle.symbol(),
                                                candle.exchange(),
                                                candle.candleTime(),
                                                candle.openPrice(),
                                                candle.highPrice(),
                                                candle.lowPrice(),
                                                candle.closePrice(),
                                                candle.volume()))
                                .toList();
        }

        @Transactional
        public FlushResult flushOpenCandles() {
                int flushed = 0;

                for (OpenMinuteCandle candle : new ArrayList<>(
                                openCandles.values())) {

                        finalizeAndPersist(
                                        candle,
                                        blocked(
                                                        candle.exchange()
                                                                        + "|"
                                                                        + candle.symbol()));

                        flushed++;
                }

                openCandles.clear();

                return new FlushResult(
                                flushed,
                                "Flushed open candles");
        }

        @Transactional
        public RolloverResult rolloverCompletedMinutes() {
                LocalDateTime now = timeProvider.nowDateTime();
                LocalDateTime currentMinute = truncateToMinute(now);
                int rolledOver = 0;

                for (Map.Entry<String, OpenMinuteCandle> entry : new ArrayList<>(openCandles.entrySet())) {
                        OpenMinuteCandle candle = entry.getValue();
                        if (candle != null && candle.candleTime().isBefore(currentMinute)) {
                                String key = entry.getKey();
                                synchronized (key.intern()) {
                                        OpenMinuteCandle current = openCandles.get(key);
                                        if (current != null && current.candleTime().isBefore(currentMinute)) {
                                                finalizeAndPersist(current, blocked(key));
                                                openCandles.remove(key, current);
                                                rolledOver++;
                                        }
                                }
                        }
                }

                return new RolloverResult(rolledOver, "Rolled over completed minute candles");
        }

        @Transactional
        public void taintRangeAsProvisional(
                        String symbol,
                        String exchange,
                        LocalDateTime fromTime,
                        LocalDateTime toTime) {

                if (symbol == null
                                || exchange == null
                                || fromTime == null
                                || toTime == null) {
                        return;
                }

                String normalizedSymbol = normalize(symbol);
                String normalizedExchange = normalize(exchange);
                String key = normalizedExchange + "|" + normalizedSymbol;
                blockedSymbols.put(key, true);

                LocalDateTime from = truncateToMinute(fromTime);
                LocalDateTime to = truncateToMinute(toTime);

                List<MarketCandle> candles = marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                normalizedSymbol,
                                                normalizedExchange,
                                                CandleTimeframe.ONE_MINUTE,
                                                from,
                                                to);

                for (MarketCandle candle : candles) {
                        if (candle.getProcessingStatus() != CandleProcessingStatus.PROVISIONAL) {
                                candle.setProcessingStatus(CandleProcessingStatus.PROVISIONAL);
                                candle.setUpdatedAt(timeProvider.nowDateTime());
                                marketCandleRepository.save(candle);
                        }
                }
        }

        private void finalizeAndPersist(
                        OpenMinuteCandle candle,
                        boolean blocked) {

                MarketCandle oneMinute = upsertOneMinuteCandle(
                                candle,
                                blocked);

                if (blocked) {
                        return;
                }

                oneMinute = applyCommonIndicators(oneMinute);

                dailyDataStatusService.markLive(
                                oneMinute.getSymbol(),
                                oneMinute.getExchange(),
                                oneMinute.getCandleTime()
                                                .toLocalDate());

                dailyStockContextService
                                .processFinalizedOneMinuteCandle(
                                                oneMinute);

                rebuildDerived(
                                oneMinute,
                                CandleTimeframe.FIVE_MINUTE,
                                5,
                                true);

                rebuildDerived(
                                oneMinute,
                                CandleTimeframe.FIFTEEN_MINUTE,
                                15,
                                true);
        }

        private MarketCandle upsertOneMinuteCandle(
                        OpenMinuteCandle candle,
                        boolean blocked) {

                Optional<MarketCandle> existing = marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTime(
                                                candle.symbol(),
                                                candle.exchange(),
                                                CandleTimeframe.ONE_MINUTE,
                                                candle.candleTime());

                MarketCandle result = existing.orElseGet(() -> MarketCandle.builder()
                                .symbol(candle.symbol())
                                .exchange(candle.exchange())
                                .timeframe(
                                                CandleTimeframe.ONE_MINUTE)
                                .candleTime(
                                                candle.candleTime())
                                .createdAt(
                                                timeProvider.nowDateTime())
                                .build());

                result.setOpenPrice(
                                candle.openPrice());

                result.setHighPrice(
                                candle.highPrice());

                result.setLowPrice(
                                candle.lowPrice());

                result.setClosePrice(
                                candle.closePrice());

                result.setVolume(
                                candle.volume());

                result.setSource(
                                "LIVE_WEBSOCKET");

                result.setIsFinalized(true);

                result.setQualityStatus(
                                CandleQualityStatus.LIVE);

                result.setProcessingStatus(
                                blocked
                                                ? CandleProcessingStatus.PROVISIONAL
                                                : CandleProcessingStatus.RELEASED);

                result.setUpdatedAt(
                                timeProvider.nowDateTime());

                try {
                        return marketCandleRepository.save(result);
                } catch (org.springframework.dao.DataIntegrityViolationException ex) {
                        MarketCandle reload = marketCandleRepository
                                        .findBySymbolAndExchangeAndTimeframeAndCandleTime(
                                                        candle.symbol(),
                                                        candle.exchange(),
                                                        CandleTimeframe.ONE_MINUTE,
                                                        candle.candleTime())
                                        .orElse(result);
                        reload.setOpenPrice(candle.openPrice());
                        reload.setHighPrice(candle.highPrice());
                        reload.setLowPrice(candle.lowPrice());
                        reload.setClosePrice(candle.closePrice());
                        reload.setVolume(candle.volume());
                        reload.setSource("LIVE_WEBSOCKET");
                        reload.setIsFinalized(true);
                        reload.setQualityStatus(CandleQualityStatus.LIVE);
                        reload.setProcessingStatus(
                                        blocked
                                                        ? CandleProcessingStatus.PROVISIONAL
                                                        : CandleProcessingStatus.RELEASED);
                        reload.setUpdatedAt(timeProvider.nowDateTime());
                        return marketCandleRepository.save(reload);
                }
        }

        private void recomputeFrom(
                        String symbol,
                        String exchange,
                        LocalDateTime affectedFrom) {

                LocalDate tradingDate = affectedFrom.toLocalDate();

                LocalDateTime from = tradingDate.atTime(MARKET_OPEN);

                LocalDateTime to = tradingDate.atTime(MARKET_CLOSE);

                List<MarketCandle> candles = marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                symbol,
                                                exchange,
                                                CandleTimeframe.ONE_MINUTE,
                                                from,
                                                to)
                                .stream()
                                .filter(this::isUsable)
                                .toList();

                if (candles.isEmpty()) {
                        return;
                }

                Double previousDayClose = previousTradingDayClose(
                                symbol,
                                tradingDate);

                for (int i = 0; i < candles.size(); i++) {
                        MarketCandle candle = candles.get(i);
                        applyCandleStructure(candle);

                        List<MarketCandle> sublist = candles.subList(0, i + 1);
                        candle.setVwap(intradayIndicatorService.calculateSessionVwap(sublist));

                        int rsiStart = Math.max(0, i + 1 - 100);
                        List<MarketCandle> rsiHistory = candles.subList(rsiStart, i + 1);
                        candle.setRsi14(intradayIndicatorService.calculateRsi14Wilder(rsiHistory));

                        candle.setAtr14(intradayIndicatorService.calculateAtr14Wilder(sublist, previousDayClose));
                        candle.setUpdatedAt(timeProvider.nowDateTime());
                }

                marketCandleRepository.saveAll(candles);

                LocalDateTime firstCandleTime = candles.get(0).getCandleTime();

                LocalDateTime lastCandleTime = candles.get(candles.size() - 1)
                                .getCandleTime();

                rebuildDerivedBuckets(
                                symbol,
                                exchange,
                                firstCandleTime,
                                lastCandleTime,
                                CandleTimeframe.FIVE_MINUTE,
                                5);

                rebuildDerivedBuckets(
                                symbol,
                                exchange,
                                firstCandleTime,
                                lastCandleTime,
                                CandleTimeframe.FIFTEEN_MINUTE,
                                15);
        }

        private void rebuildDerivedBuckets(
                        String symbol,
                        String exchange,
                        LocalDateTime firstCandleTime,
                        LocalDateTime lastCandleTime,
                        CandleTimeframe timeframe,
                        int bucketMinutes) {

                LocalDateTime bucket = bucketStart(
                                firstCandleTime,
                                bucketMinutes);

                LocalDateTime lastBucket = bucketStart(
                                lastCandleTime,
                                bucketMinutes);

                while (!bucket.isAfter(lastBucket)) {
                        final LocalDateTime currentBucket = bucket;

                        MarketCandle representative = marketCandleRepository
                                        .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                        symbol,
                                                        exchange,
                                                        CandleTimeframe.ONE_MINUTE,
                                                        currentBucket,
                                                        currentBucket
                                                                        .plusMinutes(
                                                                                        bucketMinutes - 1L)
                                                                        .withSecond(59)
                                                                        .withNano(
                                                                                        999_999_999))
                                        .stream()
                                        .filter(this::isUsable)
                                        .findFirst()
                                        .orElse(null);

                        if (representative != null) {
                                rebuildDerived(
                                                representative,
                                                timeframe,
                                                bucketMinutes,
                                                false);
                        }

                        bucket = bucket.plusMinutes(
                                        bucketMinutes);
                }
        }

        private void rebuildDerived(
                        MarketCandle oneMinute,
                        CandleTimeframe timeframe,
                        int bucketMinutes,
                        boolean emitSignal) {

                MarketCandle derived = upsertDerivedCandle(
                                oneMinute,
                                timeframe,
                                bucketMinutes);

                if (derived == null
                                || !Boolean.TRUE.equals(
                                                derived.getIsFinalized())
                                || !isReleased(derived)) {
                        return;
                }

                MarketCandle calculated = applyCommonIndicators(derived);

                if (emitSignal) {
                        liveSignalService
                                        .processFinalizedDerivedCandle(
                                                        calculated);
                }
        }

        private MarketCandle upsertDerivedCandle(
                        MarketCandle oneMinute,
                        CandleTimeframe timeframe,
                        int bucketMinutes) {

                LocalDateTime bucket = bucketStart(
                                oneMinute.getCandleTime(),
                                bucketMinutes);

                LocalDateTime bucketEnd = bucket.plusMinutes(
                                bucketMinutes - 1L);

                List<MarketCandle> allCandles = marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                oneMinute.getSymbol(),
                                                oneMinute.getExchange(),
                                                CandleTimeframe.ONE_MINUTE,
                                                bucket,
                                                bucketEnd
                                                                .withSecond(59)
                                                                .withNano(
                                                                                999_999_999));

                List<LiveMinuteResolution> resolutions = liveMinuteResolutionRepository
                                .findBySymbolAndExchangeAndMinuteTimeBetweenOrderByMinuteTimeAsc(
                                                oneMinute.getSymbol(),
                                                oneMinute.getExchange(),
                                                bucket,
                                                bucketEnd);

                Map<LocalDateTime, MarketCandle> candlesByTime = new LinkedHashMap<>();

                for (MarketCandle candle : allCandles) {
                        candlesByTime.put(
                                        candle.getCandleTime(),
                                        candle);
                }

                Map<LocalDateTime, LiveMinuteResolution> resolutionsByTime = new LinkedHashMap<>();

                for (LiveMinuteResolution resolution : resolutions) {
                        resolutionsByTime.put(
                                        resolution.getMinuteTime(),
                                        resolution);
                }

                for (LocalDateTime minute = bucket; !minute.isAfter(bucketEnd); minute = minute.plusMinutes(1)) {

                        MarketCandle candle = candlesByTime.get(minute);

                        if (candle != null) {
                                if (!isUsable(candle)) {
                                        return null;
                                }

                                continue;
                        }

                        LiveMinuteResolution resolution = resolutionsByTime.get(minute);

                        if (resolution == null
                                        || resolution.getStatus() != MinuteResolutionStatus.NO_TRADE_CONFIRMED) {
                                return null;
                        }
                }

                List<MarketCandle> sourceCandles = allCandles.stream()
                                .filter(this::isUsable)
                                .filter(candle -> !candle.getCandleTime()
                                                .isBefore(bucket))
                                .filter(candle -> !candle.getCandleTime()
                                                .isAfter(bucketEnd))
                                .toList();

                if (sourceCandles.isEmpty()) {
                        return null;
                }

                MarketCandle first = sourceCandles.get(0);

                MarketCandle last = sourceCandles.get(
                                sourceCandles.size() - 1);

                double high = sourceCandles.stream()
                                .mapToDouble(
                                                MarketCandle::getHighPrice)
                                .max()
                                .orElse(
                                                first.getHighPrice());

                double low = sourceCandles.stream()
                                .mapToDouble(
                                                MarketCandle::getLowPrice)
                                .min()
                                .orElse(
                                                first.getLowPrice());

                long volume = sourceCandles.stream()
                                .map(MarketCandle::getVolume)
                                .filter(Objects::nonNull)
                                .mapToLong(Long::longValue)
                                .sum();

                Optional<MarketCandle> existing = marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTime(
                                                oneMinute.getSymbol(),
                                                oneMinute.getExchange(),
                                                timeframe,
                                                bucket);

                MarketCandle result = existing.orElseGet(() -> MarketCandle.builder()
                                .symbol(
                                                oneMinute.getSymbol())
                                .exchange(
                                                oneMinute.getExchange())
                                .timeframe(timeframe)
                                .candleTime(bucket)
                                .createdAt(
                                                timeProvider.nowDateTime())
                                .build());

                result.setOpenPrice(
                                first.getOpenPrice());

                result.setHighPrice(high);
                result.setLowPrice(low);

                result.setClosePrice(
                                last.getClosePrice());

                result.setVolume(volume);
                result.setVwap(last.getVwap());

                result.setSource(
                                "DERIVED_FROM_ONE_MINUTE");

                result.setIsFinalized(true);

                result.setQualityStatus(
                                derivedQuality(sourceCandles));

                result.setProcessingStatus(
                                CandleProcessingStatus.RELEASED);

                result.setUpdatedAt(
                                timeProvider.nowDateTime());

                return marketCandleRepository.save(result);
        }

        private MarketCandle applyCommonIndicators(
                        MarketCandle candle) {

                if (!isUsable(candle)) {
                        return candle;
                }

                applyCandleStructure(candle);

                LocalDate tradingDate = candle.getCandleTime().toLocalDate();

                if (candle.getTimeframe() == CandleTimeframe.ONE_MINUTE) {

                        List<MarketCandle> session = marketCandleRepository
                                        .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                        candle.getSymbol(),
                                                        candle.getExchange(),
                                                        CandleTimeframe.ONE_MINUTE,
                                                        tradingDate.atTime(
                                                                        MARKET_OPEN),
                                                        candle.getCandleTime())
                                        .stream()
                                        .filter(this::isUsable)
                                        .toList();

                        if (!session.isEmpty()) {
                                candle.setVwap(
                                                intradayIndicatorService
                                                                .calculateSessionVwap(session));
                        }
                }

                boolean indicatorTimeframe = candle.getTimeframe() == CandleTimeframe.ONE_MINUTE
                                || candle.getTimeframe() == CandleTimeframe.FIVE_MINUTE
                                || candle.getTimeframe() == CandleTimeframe.FIFTEEN_MINUTE;

                if (!indicatorTimeframe) {
                        candle.setUpdatedAt(
                                        timeProvider.nowDateTime());

                        return marketCandleRepository.save(candle);
                }

                /*
                 * RSI uses the recent timeframe history. The helper
                 * requires at least 42 candles and applies Wilder
                 * smoothing from the supplied chronological series.
                 */
                List<MarketCandle> rsiHistory = marketCandleRepository
                                .findTop100BySymbolAndExchangeAndTimeframeAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
                                                candle.getSymbol(),
                                                candle.getExchange(),
                                                candle.getTimeframe(),
                                                candle.getCandleTime())
                                .stream()
                                .filter(this::isUsable)
                                .sorted((left, right) -> left.getCandleTime()
                                                .compareTo(
                                                                right.getCandleTime()))
                                .toList();

                candle.setRsi14(
                                intradayIndicatorService
                                                .calculateRsi14Wilder(
                                                                rsiHistory));

                /*
                 * ATR must use the complete current-day timeframe
                 * history. This is important for 1M candles because
                 * one trading day contains 375 candles and exceeds
                 * the RSI/old-history query limit.
                 */
                LocalDateTime dayStart = tradingDate.atTime(MARKET_OPEN);

                List<MarketCandle> atrHistory = marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                candle.getSymbol(),
                                                candle.getExchange(),
                                                candle.getTimeframe(),
                                                dayStart,
                                                candle.getCandleTime())
                                .stream()
                                .filter(this::isUsable)
                                .toList();

                Double previousDayClose = previousTradingDayClose(
                                candle.getSymbol(),
                                tradingDate);

                candle.setAtr14(
                                intradayIndicatorService
                                                .calculateAtr14Wilder(
                                                                atrHistory,
                                                                previousDayClose));

                candle.setUpdatedAt(
                                timeProvider.nowDateTime());

                return marketCandleRepository.save(candle);
        }

        private Double previousTradingDayClose(
                        String symbol,
                        LocalDate tradingDate) {

                if (stockPriceRepository == null
                                || symbol == null
                                || tradingDate == null) {
                        return null;
                }

                String key = symbol
                                + "|"
                                + tradingDate;

                Optional<Double> cached = previousDayCloseCache.get(key);

                if (cached != null) {
                        return cached.orElse(null);
                }

                List<StockPrice> prices = stockPriceRepository
                                .findBySymbolAndDateLessThanEqualOrderByDateAsc(
                                                symbol,
                                                tradingDate);

                Double close = prices == null
                                ? null
                                : prices.stream()
                                                .filter(Objects::nonNull)
                                                .filter(price -> price.getDate() != null
                                                                && price.getDate()
                                                                                .isBefore(
                                                                                                tradingDate))
                                                .max((left, right) -> left.getDate()
                                                                .compareTo(
                                                                                right.getDate()))
                                                .map(StockPrice::getClosePrice)
                                                .filter(this::isFinite)
                                                .orElse(null);

                previousDayCloseCache.put(
                                key,
                                Optional.ofNullable(close));

                return close;
        }

        private boolean isFinite(Double value) {
                return value != null
                                && !value.isNaN()
                                && !value.isInfinite();
        }

        private void detectPersistedGap(
                        String symbol,
                        String exchange,
                        LocalDateTime tickTime) {

                marketCandleRepository
                                .findTopBySymbolAndExchangeAndTimeframeOrderByCandleTimeDesc(
                                                symbol,
                                                exchange,
                                                CandleTimeframe.ONE_MINUTE)
                                .map(MarketCandle::getCandleTime)
                                .filter(previous -> previous.toLocalDate()
                                                .equals(
                                                                tickTime.toLocalDate()))
                                .filter(previous -> tickTime.isAfter(
                                                previous.plusMinutes(1)))
                                .ifPresent(previous -> detectGap(
                                                symbol,
                                                exchange,
                                                tickTime.toLocalDate(),
                                                previous.plusMinutes(1),
                                                tickTime.minusMinutes(1)));
        }

        private void detectGap(
                        String symbol,
                        String exchange,
                        LocalDate date,
                        LocalDateTime from,
                        LocalDateTime to) {

                LocalDateTime marketOpen = date.atTime(MARKET_OPEN);
                LocalDateTime marketClose = date.atTime(MARKET_CLOSE);

                LocalDateTime clampedFrom = from.isBefore(marketOpen) ? marketOpen : from;
                LocalDateTime clampedTo = to.isAfter(marketClose) ? marketClose : to;

                if (clampedFrom.isAfter(clampedTo)) {
                        return;
                }

                String key = normalize(exchange)
                                + "|"
                                + normalize(symbol);

                blockedSymbols.put(key, true);

                eventPublisher.publishEvent(
                                new IntradayGapDetectedEvent(
                                                normalize(symbol),
                                                normalize(exchange),
                                                date,
                                                clampedFrom,
                                                clampedTo));
        }

        private boolean blocked(String key) {
                return Boolean.TRUE.equals(
                                blockedSymbols.get(key));
        }

        private boolean isRepairable(
                        MarketCandle candle) {

                return candle != null
                                && Boolean.TRUE.equals(
                                                candle.getIsFinalized())
                                && candle.getQualityStatus() != CandleQualityStatus.SUSPECT
                                && (candle.getProcessingStatus() == null
                                                || candle.getProcessingStatus() == CandleProcessingStatus.PROVISIONAL
                                                || candle.getProcessingStatus() == CandleProcessingStatus.RELEASED);
        }

        private boolean isUsable(
                        MarketCandle candle) {

                return candle != null
                                && Boolean.TRUE.equals(
                                                candle.getIsFinalized())
                                && candle.getQualityStatus() != CandleQualityStatus.SUSPECT
                                && isReleased(candle);
        }

        private boolean isReleased(
                        MarketCandle candle) {

                return candle.getProcessingStatus() == null
                                || candle.getProcessingStatus() == CandleProcessingStatus.RELEASED;
        }

        private CandleQualityStatus derivedQuality(
                        List<MarketCandle> candles) {

                if (candles.stream().anyMatch(
                                candle -> candle.getQualityStatus() == CandleQualityStatus.SUSPECT)) {
                        return CandleQualityStatus.SUSPECT;
                }

                if (candles.stream().anyMatch(
                                candle -> candle.getQualityStatus() == CandleQualityStatus.REPAIRED)) {
                        return CandleQualityStatus.REPAIRED;
                }

                if (!candles.isEmpty()
                                && candles.stream().allMatch(
                                                candle -> candle.getQualityStatus() == CandleQualityStatus.RECONCILED)) {
                        return CandleQualityStatus.RECONCILED;
                }

                return CandleQualityStatus.LIVE;
        }

        static void applyCandleStructure(
                        MarketCandle candle) {

                if (candle == null
                                || candle.getOpenPrice() == null
                                || candle.getHighPrice() == null
                                || candle.getLowPrice() == null
                                || candle.getClosePrice() == null) {
                        return;
                }

                double open = candle.getOpenPrice();

                double high = candle.getHighPrice();

                double low = candle.getLowPrice();

                double close = candle.getClosePrice();

                CandleDirection direction = close > open
                                ? CandleDirection.BULLISH
                                : close < open
                                                ? CandleDirection.BEARISH
                                                : CandleDirection.NEUTRAL;

                candle.setDirection(direction);

                double range = high - low;

                if (range <= 0.0 || low <= 0.0 || open <= 0.0 || high <= 0.0 || close <= 0.0) {
                        candle.setBodyRatio(null);
                        candle.setUpperWickRatio(null);
                        candle.setLowerWickRatio(null);
                        candle.setRangePct(null);
                        candle.setStrongBullish(false);
                        candle.setStrongBearish(false);
                        return;
                }

                double bodyRatio = Math.abs(close - open)
                                / range;

                double upperWickRatio = (high - Math.max(open, close))
                                / range;

                double lowerWickRatio = (Math.min(open, close) - low)
                                / range;

                Double rangePct = close == 0.0
                                ? null
                                : range / close * 100.0;

                candle.setBodyRatio(bodyRatio);
                candle.setUpperWickRatio(upperWickRatio);
                candle.setLowerWickRatio(lowerWickRatio);
                candle.setRangePct(rangePct);

                candle.setStrongBullish(
                                direction == CandleDirection.BULLISH
                                                && bodyRatio >= 0.60
                                                && upperWickRatio <= 0.25
                                                && rangePct != null
                                                && rangePct >= 0.15);

                candle.setStrongBearish(
                                direction == CandleDirection.BEARISH
                                                && bodyRatio >= 0.60
                                                && lowerWickRatio <= 0.25
                                                && rangePct != null
                                                && rangePct >= 0.15);
        }

        private LocalDateTime truncateToMinute(
                        LocalDateTime value) {

                return value
                                .withSecond(0)
                                .withNano(0);
        }

        private LocalDateTime bucketStart(
                        LocalDateTime value,
                        int minutes) {

                return value
                                .withMinute(
                                                (value.getMinute() / minutes)
                                                                * minutes)
                                .withSecond(0)
                                .withNano(0);
        }

        private String normalize(String value) {
                return value == null
                                ? null
                                : value.trim()
                                                .toUpperCase(Locale.ROOT);
        }

        public record TickInput(
                        String symbol,
                        String exchange,
                        LocalDateTime tickTime,
                        Double lastPrice,
                        Long lastTradedQuantity) {
        }

        public record IngestResult(
                        String symbol,
                        String exchange,
                        LocalDateTime candleTime,
                        boolean accepted,
                        boolean finalizedPreviousCandle,
                        String message) {
        }

        public record OpenCandleView(
                        String symbol,
                        String exchange,
                        LocalDateTime candleTime,
                        Double openPrice,
                        Double highPrice,
                        Double lowPrice,
                        Double closePrice,
                        Long volume) {
        }

        public record FlushResult(
                        int flushed,
                        String message) {
        }

        public record RolloverResult(
                        int rolledOver,
                        String message) {
        }

        private static final class OpenMinuteCandle {

                private final String symbol;
                private final String exchange;
                private final LocalDateTime candleTime;
                private final Double openPrice;

                private Double highPrice;
                private Double lowPrice;
                private Double closePrice;
                private Long volume;

                private OpenMinuteCandle(
                                String symbol,
                                String exchange,
                                LocalDateTime candleTime,
                                Double openPrice,
                                Double highPrice,
                                Double lowPrice,
                                Double closePrice,
                                Long volume) {

                        this.symbol = symbol;
                        this.exchange = exchange;
                        this.candleTime = candleTime;
                        this.openPrice = openPrice;
                        this.highPrice = highPrice;
                        this.lowPrice = lowPrice;
                        this.closePrice = closePrice;
                        this.volume = volume;
                }

                static OpenMinuteCandle start(
                                String symbol,
                                String exchange,
                                LocalDateTime time,
                                Double price,
                                Long volume) {

                        return new OpenMinuteCandle(
                                        symbol,
                                        exchange,
                                        time,
                                        price,
                                        price,
                                        price,
                                        price,
                                        volume == null
                                                        ? 0L
                                                        : volume);
                }

                void applyTick(
                                Double price,
                                Long tickVolume) {

                        if (price != null) {
                                highPrice = Math.max(
                                                highPrice,
                                                price);

                                lowPrice = Math.min(
                                                lowPrice,
                                                price);

                                closePrice = price;
                        }

                        volume = (volume == null
                                        ? 0L
                                        : volume)
                                        + (tickVolume == null
                                                        ? 0L
                                                        : tickVolume);
                }

                String symbol() {
                        return symbol;
                }

                String exchange() {
                        return exchange;
                }

                LocalDateTime candleTime() {
                        return candleTime;
                }

                Double openPrice() {
                        return openPrice;
                }

                Double highPrice() {
                        return highPrice;
                }

                Double lowPrice() {
                        return lowPrice;
                }

                Double closePrice() {
                        return closePrice;
                }

                Long volume() {
                        return volume;
                }
        }
}
