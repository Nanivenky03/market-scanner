package com.trading.scanner.service.data;

import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.config.TimeProvider;
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
import com.trading.scanner.service.provider.ProviderException;
import com.trading.scanner.service.provider.angelone.AngelOneMarketDataProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
public class IntradayCandleBackfillService {

        private static final int EXPECTED_ONE_MINUTE_CANDLES_PER_DAY = 375;

        private static final int MAX_TRADING_DAYS_PER_REQUEST = 5;

        private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);

        private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 29);

        private final AngelOneMarketDataProvider angelOneMarketDataProvider;

        private final MarketCandleRepository marketCandleRepository;

        private final StockPriceRepository stockPriceRepository;

        private final TimeProvider timeProvider;

        private final PlatformTransactionManager transactionManager;

        private final LiveMinuteResolutionRepository liveMinuteResolutionRepository;

        private final TradingCalendar tradingCalendar;

        private final LiveMarketCandleService liveMarketCandleService;

        @Autowired
        public IntradayCandleBackfillService(
                        AngelOneMarketDataProvider angelOneMarketDataProvider,
                        MarketCandleRepository marketCandleRepository,
                        StockPriceRepository stockPriceRepository,
                        TimeProvider timeProvider,
                        PlatformTransactionManager transactionManager,
                        LiveMinuteResolutionRepository liveMinuteResolutionRepository,
                        TradingCalendar tradingCalendar,
                        LiveMarketCandleService liveMarketCandleService) {

                this.angelOneMarketDataProvider = angelOneMarketDataProvider;
                this.marketCandleRepository = marketCandleRepository;
                this.stockPriceRepository = stockPriceRepository;
                this.timeProvider = timeProvider;
                this.transactionManager = transactionManager;
                this.liveMinuteResolutionRepository = liveMinuteResolutionRepository;
                this.tradingCalendar = tradingCalendar;
                this.liveMarketCandleService = liveMarketCandleService;
        }

        public IntradayCandleBackfillService(
                        AngelOneMarketDataProvider angelOneMarketDataProvider,
                        MarketCandleRepository marketCandleRepository,
                        StockPriceRepository stockPriceRepository,
                        TimeProvider timeProvider,
                        PlatformTransactionManager transactionManager,
                        LiveMinuteResolutionRepository liveMinuteResolutionRepository,
                        TradingCalendar tradingCalendar) {

                this(
                                angelOneMarketDataProvider,
                                marketCandleRepository,
                                stockPriceRepository,
                                timeProvider,
                                transactionManager,
                                liveMinuteResolutionRepository,
                                tradingCalendar,
                                null);
        }

        public IntradayCandleBackfillService(
                        AngelOneMarketDataProvider angelOneMarketDataProvider,
                        MarketCandleRepository marketCandleRepository,
                        StockPriceRepository stockPriceRepository,
                        TimeProvider timeProvider,
                        PlatformTransactionManager transactionManager,
                        LiveMinuteResolutionRepository liveMinuteResolutionRepository) {

                this(
                                angelOneMarketDataProvider,
                                marketCandleRepository,
                                stockPriceRepository,
                                timeProvider,
                                transactionManager,
                                liveMinuteResolutionRepository,
                                null,
                                null);
        }

        public OneMinuteBackfillResult backfillOneMinuteCandles(
                        String symbol,
                        LocalDate from,
                        LocalDate to) {

                validateDateRange(symbol, from, to);

                String normalizedSymbol = normalizeSymbol(symbol);

                List<LocalDate> tradingDays = resolveTradingDays(normalizedSymbol, from, to);

                if (tradingDays.isEmpty()) {
                        return new OneMinuteBackfillResult(
                                        normalizedSymbol,
                                        from,
                                        to,
                                        0,
                                        0,
                                        0,
                                        "No trading days found in requested range");
                }

                List<LocalDate> missingDays = tradingDays.stream()
                                .filter(day -> !isCompleteOneMinuteDay(
                                                normalizedSymbol,
                                                day))
                                .toList();

                if (missingDays.isEmpty()) {
                        return new OneMinuteBackfillResult(
                                        normalizedSymbol,
                                        from,
                                        to,
                                        0,
                                        0,
                                        0,
                                        "All requested trading days already complete");
                }

                int requestedCandles = 0;
                int inserted = 0;
                int updated = 0;
                int failedChunks = 0;

                List<String> failures = new ArrayList<>();

                for (DateChunk chunk : buildChunks(missingDays)) {
                        try {
                                List<MarketCandle> fetched = angelOneMarketDataProvider
                                                .fetchHistoricalOneMinuteCandles(
                                                                normalizedSymbol,
                                                                chunk.from(),
                                                                chunk.to());

                                if (fetched == null || fetched.isEmpty()) {
                                        throw new ProviderException(
                                                        "Provider returned no candles; "
                                                                        + "no-trade cannot be inferred"
                                                                        + "; symbol="
                                                                        + normalizedSymbol
                                                                        + ", from="
                                                                        + chunk.from()
                                                                        + ", to="
                                                                        + chunk.to());
                                }

                                validateCompleteHistoricalChunk(
                                                normalizedSymbol,
                                                chunk,
                                                fetched);

                                requestedCandles += fetched.size();

                                ChunkWriteResult writeResult = persistChunk(fetched);

                                inserted += writeResult.inserted();
                                updated += writeResult.updated();

                                log.info(
                                                "1-minute backfill completed symbol={} from={} to={} fetched={} inserted={} updated={}",
                                                normalizedSymbol,
                                                chunk.from(),
                                                chunk.to(),
                                                fetched.size(),
                                                writeResult.inserted(),
                                                writeResult.updated());

                        } catch (Exception ex) {
                                failedChunks++;

                                String failure = chunk.from()
                                                + " to "
                                                + chunk.to()
                                                + ": "
                                                + (ex.getMessage() == null
                                                                ? ex.getClass()
                                                                                .getSimpleName()
                                                                : ex.getMessage());

                                failures.add(failure);

                                log.warn(
                                                "1-minute backfill failed symbol={} from={} to={}: {}",
                                                normalizedSymbol,
                                                chunk.from(),
                                                chunk.to(),
                                                ex.getMessage(),
                                                ex);
                        }
                }

                if (failedChunks > 0) {
                        throw new ProviderException(
                                        "1-minute backfill incomplete for symbol="
                                                        + normalizedSymbol
                                                        + "; failedChunks="
                                                        + failedChunks
                                                        + "; failures="
                                                        + failures);
                }

                return new OneMinuteBackfillResult(
                                normalizedSymbol,
                                from,
                                to,
                                requestedCandles,
                                inserted,
                                updated,
                                "Backfill completed");
        }

        public OneMinuteBackfillResult backfillOneMinuteCandles(
                        String symbol,
                        LocalDate tradingDate,
                        LocalDateTime fromTime,
                        LocalDateTime toTime) {

                if (tradingDate == null
                                || fromTime == null
                                || toTime == null) {
                        throw new ProviderException(
                                        "tradingDate, fromTime and toTime are required");
                }

                if (fromTime.isAfter(toTime)) {
                        throw new ProviderException(
                                        "fromTime cannot be after toTime");
                }

                String normalizedSymbol = normalizeSymbol(symbol);

                LocalDateTime from = fromTime
                                .withSecond(0)
                                .withNano(0);

                LocalDateTime to = toTime
                                .withSecond(0)
                                .withNano(0);

                List<MarketCandle> fetched = angelOneMarketDataProvider
                                .fetchHistoricalOneMinuteCandles(
                                                normalizedSymbol,
                                                tradingDate,
                                                tradingDate);

                if (fetched == null || fetched.isEmpty()) {
                        throw new ProviderException(
                                        "Provider returned no candles for intraday repair"
                                                        + "; no-trade cannot be inferred"
                                                        + "; symbol="
                                                        + normalizedSymbol
                                                        + ", tradingDate="
                                                        + tradingDate);
                }

                List<MarketCandle> range = fetched.stream()
                                .filter(Objects::nonNull)
                                .filter(candle -> candle.getCandleTime() != null)
                                .filter(candle -> !candle.getCandleTime()
                                                .isBefore(from))
                                .filter(candle -> !candle.getCandleTime()
                                                .isAfter(to))
                                .toList();

                if (range.isEmpty()) {
                        throw new ProviderException(
                                        "Provider returned no candles inside requested repair range"
                                                        + "; no-trade cannot be inferred"
                                                        + "; symbol="
                                                        + normalizedSymbol
                                                        + ", from="
                                                        + from
                                                        + ", to="
                                                        + to);
                }

                ChunkWriteResult result = persistChunk(range);

                return new OneMinuteBackfillResult(
                                normalizedSymbol,
                                tradingDate,
                                tradingDate,
                                range.size(),
                                result.inserted(),
                                result.updated(),
                                "Intraday range repair completed");
        }

        public List<OneMinuteBackfillResult> backfillOneMinuteCandles(
                        List<String> symbols,
                        LocalDate from,
                        LocalDate to) {

                if (symbols == null || symbols.isEmpty()) {
                        return List.of();
                }

                return symbols.stream()
                                .filter(this::hasText)
                                .map(this::normalizeSymbol)
                                .distinct()
                                .sorted()
                                .map(symbol -> backfillOneMinuteCandles(
                                                symbol,
                                                from,
                                                to))
                                .toList();
        }

        public FifteenMinuteMaterializationResult materializeFifteenMinuteCandles(
                        String symbol,
                        LocalDate from,
                        LocalDate to) {

                validateDateRange(
                                symbol,
                                from,
                                to);

                String normalizedSymbol = normalizeSymbol(symbol);

                List<MarketCandle> sourceCandles = marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                normalizedSymbol,
                                                "NSE",
                                                CandleTimeframe.ONE_MINUTE,
                                                from.atStartOfDay(),
                                                to.atTime(23, 59))
                                .stream()
                                .filter(this::isUsableForMaterialization)
                                .toList();

                if (sourceCandles.isEmpty()) {
                        return new FifteenMinuteMaterializationResult(
                                        normalizedSymbol,
                                        from,
                                        to,
                                        0,
                                        0,
                                        0,
                                        0,
                                        "No usable 1-minute candles found");
                }

                LinkedHashMap<LocalDateTime, List<MarketCandle>> grouped = new LinkedHashMap<>();

                for (MarketCandle candle : sourceCandles) {
                        grouped.computeIfAbsent(
                                        bucketToFifteenMinute(
                                                        candle.getCandleTime()),
                                        ignored -> new ArrayList<>())
                                        .add(candle);
                }

                int inserted = 0;
                int updated = 0;

                for (var entry : grouped.entrySet()) {

                        LocalDateTime bucketTime = entry.getKey();

                        List<MarketCandle> candles = entry.getValue();

                        MarketCandle first = candles.get(0);

                        MarketCandle last = candles.get(
                                        candles.size() - 1);

                        double high = candles.stream()
                                        .map(MarketCandle::getHighPrice)
                                        .filter(Objects::nonNull)
                                        .max(Double::compareTo)
                                        .orElse(first.getHighPrice());

                        double low = candles.stream()
                                        .map(MarketCandle::getLowPrice)
                                        .filter(Objects::nonNull)
                                        .min(Double::compareTo)
                                        .orElse(first.getLowPrice());

                        long volume = candles.stream()
                                        .map(MarketCandle::getVolume)
                                        .filter(Objects::nonNull)
                                        .mapToLong(Long::longValue)
                                        .sum();

                        CandleProcessingStatus processingStatus = candles.stream()
                                        .allMatch(this::isReleased)
                                                        ? CandleProcessingStatus.RELEASED
                                                        : CandleProcessingStatus.PROVISIONAL;

                        Optional<MarketCandle> existing = marketCandleRepository
                                        .findBySymbolAndExchangeAndTimeframeAndCandleTime(
                                                        first.getSymbol(),
                                                        first.getExchange(),
                                                        CandleTimeframe.FIFTEEN_MINUTE,
                                                        bucketTime);

                        MarketCandle target = existing.orElseGet(() -> MarketCandle.builder()
                                        .symbol(first.getSymbol())
                                        .exchange(first.getExchange())
                                        .timeframe(
                                                        CandleTimeframe.FIFTEEN_MINUTE)
                                        .candleTime(bucketTime)
                                        .createdAt(
                                                        timeProvider.nowDateTime())
                                        .build());

                        target.setOpenPrice(first.getOpenPrice());
                        target.setHighPrice(high);
                        target.setLowPrice(low);
                        target.setClosePrice(last.getClosePrice());
                        target.setVolume(volume);
                        target.setOpenInterest(last.getOpenInterest());
                        target.setSource("DERIVED_FROM_ONE_MINUTE");
                        target.setIsFinalized(true);
                        target.setQualityStatus(derivedQuality(candles));
                        target.setProcessingStatus(processingStatus);
                        target.setUpdatedAt(timeProvider.nowDateTime());

                        marketCandleRepository.save(target);

                        if (existing.isPresent()) {
                                updated++;
                        } else {
                                inserted++;
                        }
                }

                if (liveMarketCandleService != null) {
                        sourceCandles.stream()
                                        .map(candle -> candle.getCandleTime()
                                                        .toLocalDate())
                                        .distinct()
                                        .forEach(date -> liveMarketCandleService
                                                        .recomputeDerivedData(
                                                                        normalizedSymbol,
                                                                        "NSE",
                                                                        date));
                }

                return new FifteenMinuteMaterializationResult(
                                normalizedSymbol,
                                from,
                                to,
                                sourceCandles.size(),
                                grouped.size(),
                                inserted,
                                updated,
                                "15-minute materialization completed");
        }

        public List<FifteenMinuteMaterializationResult> materializeFifteenMinuteCandles(
                        List<String> symbols,
                        LocalDate from,
                        LocalDate to) {

                if (symbols == null || symbols.isEmpty()) {
                        return List.of();
                }

                return symbols.stream()
                                .filter(this::hasText)
                                .map(this::normalizeSymbol)
                                .distinct()
                                .sorted()
                                .map(symbol -> materializeFifteenMinuteCandles(
                                                symbol,
                                                from,
                                                to))
                                .toList();
        }

        private void validateCompleteHistoricalChunk(
                        String symbol,
                        DateChunk chunk,
                        List<MarketCandle> fetched) {

                Map<LocalDate, Set<LocalDateTime>> timestampsByDate = new LinkedHashMap<>();

                Map<LocalDate, Integer> validRowsByDate = new LinkedHashMap<>();

                for (LocalDate date : chunk.days()) {
                        timestampsByDate.put(date, new HashSet<>());
                        validRowsByDate.put(date, 0);
                }

                List<String> invalidCandles = new ArrayList<>();

                for (MarketCandle candle : fetched) {
                        if (candle == null
                                        || candle.getCandleTime() == null) {
                                invalidCandles.add(
                                                "null or timestamp-less candle");
                                continue;
                        }

                        if (!symbol.equals(
                                        normalizeSymbol(candle.getSymbol()))) {
                                invalidCandles.add(
                                                "unexpected symbol="
                                                                + candle.getSymbol());
                                continue;
                        }

                        if (!"NSE".equalsIgnoreCase(
                                        candle.getExchange())) {
                                invalidCandles.add(
                                                "unexpected exchange="
                                                                + candle.getExchange());
                                continue;
                        }

                        if (candle.getTimeframe() != CandleTimeframe.ONE_MINUTE) {
                                invalidCandles.add(
                                                "unexpected timeframe="
                                                                + candle.getTimeframe());
                                continue;
                        }

                        LocalDateTime candleTime = candle.getCandleTime();

                        if (candleTime.getSecond() != 0
                                        || candleTime.getNano() != 0) {
                                invalidCandles.add(
                                                "non-minute timestamp="
                                                                + candleTime);
                                continue;
                        }

                        LocalDate date = candleTime.toLocalDate();

                        if (!timestampsByDate.containsKey(date)) {
                                invalidCandles.add(
                                                "unexpected date="
                                                                + date);
                                continue;
                        }

                        LocalTime time = candleTime.toLocalTime();

                        if (time.isBefore(MARKET_OPEN)
                                        || time.isAfter(MARKET_CLOSE)) {
                                invalidCandles.add(
                                                "timestamp outside market session="
                                                                + candleTime);
                                continue;
                        }

                        validRowsByDate.merge(
                                        date,
                                        1,
                                        Integer::sum);

                        timestampsByDate
                                        .get(date)
                                        .add(candleTime);
                }

                List<String> problems = new ArrayList<>();

                for (Map.Entry<LocalDate, Set<LocalDateTime>> entry : timestampsByDate.entrySet()) {

                        LocalDate date = entry.getKey();

                        Set<LocalDateTime> actual = entry.getValue();

                        Set<LocalDateTime> expected = expectedSessionTimestamps(date);

                        int validRows = validRowsByDate.get(date);

                        if (validRows != actual.size()) {
                                problems.add(
                                                date
                                                                + " contains duplicate timestamps; rows="
                                                                + validRows
                                                                + ", unique="
                                                                + actual.size());
                        }

                        if (!actual.equals(expected)) {
                                Set<LocalDateTime> missing = new HashSet<>(expected);

                                missing.removeAll(actual);

                                Set<LocalDateTime> unexpected = new HashSet<>(actual);

                                unexpected.removeAll(expected);

                                problems.add(
                                                date
                                                                + " incomplete; actual="
                                                                + actual.size()
                                                                + "/"
                                                                + expected.size()
                                                                + ", missing="
                                                                + missing
                                                                + ", unexpected="
                                                                + unexpected);
                        }
                }

                if (!invalidCandles.isEmpty()) {
                        problems.add("invalid=" + invalidCandles);
                }

                if (!problems.isEmpty()) {
                        throw new ProviderException(
                                        "Incomplete historical candle response"
                                                        + "; symbol="
                                                        + symbol
                                                        + "; chunk="
                                                        + chunk.from()
                                                        + " to "
                                                        + chunk.to()
                                                        + "; "
                                                        + String.join(
                                                                        ", ",
                                                                        problems));
                }
        }

        private Set<LocalDateTime> expectedSessionTimestamps(
                        LocalDate date) {

                Set<LocalDateTime> expected = new HashSet<>();

                LocalDateTime timestamp = date.atTime(MARKET_OPEN);

                LocalDateTime end = date.atTime(MARKET_CLOSE);

                while (!timestamp.isAfter(end)) {
                        expected.add(timestamp);
                        timestamp = timestamp.plusMinutes(1);
                }

                return expected;
        }

        private ChunkWriteResult persistChunk(
                        List<MarketCandle> fetched) {

                TransactionTemplate template = new TransactionTemplate(transactionManager);

                return template.execute(status -> {
                        int inserted = 0;
                        int updated = 0;

                        for (MarketCandle candle : fetched) {
                                if (candle == null
                                                || candle.getCandleTime() == null) {
                                        throw new ProviderException(
                                                        "Provider returned a null or timestamp-less candle");
                                }

                                Optional<MarketCandle> existing = marketCandleRepository
                                                .findBySymbolAndExchangeAndTimeframeAndCandleTime(
                                                                candle.getSymbol(),
                                                                candle.getExchange(),
                                                                candle.getTimeframe(),
                                                                candle.getCandleTime());

                                MarketCandle target;

                                if (existing.isPresent()) {
                                        target = existing.get();

                                        target.setOpenPrice(
                                                        candle.getOpenPrice());
                                        target.setHighPrice(
                                                        candle.getHighPrice());
                                        target.setLowPrice(
                                                        candle.getLowPrice());
                                        target.setClosePrice(
                                                        candle.getClosePrice());
                                        target.setVolume(
                                                        candle.getVolume());
                                        target.setOpenInterest(
                                                        candle.getOpenInterest());

                                        updated++;
                                } else {
                                        target = candle;

                                        target.setCreatedAt(
                                                        timeProvider.nowDateTime());

                                        inserted++;
                                }

                                target.setSource("SMARTAPI_BACKFILL");
                                target.setIsFinalized(true);
                                target.setQualityStatus(
                                                CandleQualityStatus.REPAIRED);
                                target.setProcessingStatus(
                                                CandleProcessingStatus.PROVISIONAL);
                                target.setUpdatedAt(
                                                timeProvider.nowDateTime());

                                marketCandleRepository.save(target);

                                markResolution(
                                                target.getSymbol(),
                                                target.getExchange(),
                                                target.getCandleTime(),
                                                MinuteResolutionStatus.REPAIRED,
                                                "SmartAPI returned the minute candle");
                        }

                        return new ChunkWriteResult(inserted, updated);
                });
        }

        private void markResolution(
                        String symbol,
                        String exchange,
                        LocalDateTime minuteTime,
                        MinuteResolutionStatus status,
                        String reason) {

                LocalDateTime now = timeProvider.nowDateTime();

                LiveMinuteResolution resolution = liveMinuteResolutionRepository
                                .findBySymbolAndExchangeAndMinuteTime(
                                                symbol,
                                                exchange,
                                                minuteTime)
                                .orElseGet(() -> LiveMinuteResolution
                                                .builder()
                                                .symbol(symbol)
                                                .exchange(exchange)
                                                .minuteTime(minuteTime)
                                                .tradingDate(
                                                                minuteTime.toLocalDate())
                                                .createdAt(now)
                                                .build());

                resolution.setStatus(status);
                resolution.setReason(reason);
                resolution.setResolvedAt(now);
                resolution.setUpdatedAt(now);

                liveMinuteResolutionRepository.save(resolution);
        }

        private List<LocalDate> resolveTradingDays(
                        String symbol,
                        LocalDate from,
                        LocalDate to) {

                List<StockPrice> dailyRows = stockPriceRepository
                                .findBySymbolAndDateBetweenOrderByDateAsc(
                                                symbol,
                                                from,
                                                to);

                if (dailyRows == null) {
                        dailyRows = List.of();
                }

                List<LocalDate> rows = dailyRows.stream()
                                .map(StockPrice::getDate)
                                .filter(Objects::nonNull)
                                .distinct()
                                .sorted()
                                .toList();

                if (!rows.isEmpty()) {
                        return rows;
                }

                List<LocalDate> fallback = new ArrayList<>();

                for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {

                        boolean tradingDay = tradingCalendar == null
                                        ? date.getDayOfWeek().getValue() < 6
                                        : tradingCalendar.isTradingDay(date);

                        if (tradingDay) {
                                fallback.add(date);
                        }
                }

                return fallback;
        }

        private boolean isCompleteOneMinuteDay(
                        String symbol,
                        LocalDate date) {

                List<MarketCandle> candles = marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                symbol,
                                                "NSE",
                                                CandleTimeframe.ONE_MINUTE,
                                                date.atStartOfDay(),
                                                date.atTime(23, 59, 59));

                if (candles == null || candles.isEmpty()) {
                        return false;
                }

                Set<LocalDateTime> actualTimestamps = new HashSet<>();

                int validRows = 0;

                for (MarketCandle candle : candles) {
                        if (candle == null
                                        || candle.getCandleTime() == null) {
                                continue;
                        }

                        LocalDateTime timestamp = candle.getCandleTime();

                        if (!timestamp.toLocalDate().equals(date)
                                        || timestamp.getSecond() != 0
                                        || timestamp.getNano() != 0
                                        || timestamp.toLocalTime()
                                                        .isBefore(MARKET_OPEN)
                                        || timestamp.toLocalTime()
                                                        .isAfter(MARKET_CLOSE)) {
                                continue;
                        }

                        validRows++;
                        actualTimestamps.add(timestamp);
                }

                return validRows == EXPECTED_ONE_MINUTE_CANDLES_PER_DAY
                                && actualTimestamps.size() == EXPECTED_ONE_MINUTE_CANDLES_PER_DAY
                                && actualTimestamps.equals(
                                                expectedSessionTimestamps(date));
        }

        private List<DateChunk> buildChunks(
                        List<LocalDate> days) {

                List<DateChunk> chunks = new ArrayList<>();

                for (int start = 0; start < days.size(); start += MAX_TRADING_DAYS_PER_REQUEST) {

                        int end = Math.min(
                                        start + MAX_TRADING_DAYS_PER_REQUEST,
                                        days.size());

                        chunks.add(
                                        new DateChunk(
                                                        List.copyOf(
                                                                        days.subList(
                                                                                        start,
                                                                                        end))));
                }

                return chunks;
        }

        private boolean isReleased(
                        MarketCandle candle) {

                return candle.getProcessingStatus() == null
                                || candle.getProcessingStatus() == CandleProcessingStatus.RELEASED;
        }

        private boolean isUsableForMaterialization(
                        MarketCandle candle) {

                return candle != null
                                && Boolean.TRUE.equals(
                                                candle.getIsFinalized())
                                && candle.getQualityStatus() != CandleQualityStatus.SUSPECT
                                && isReleased(candle);
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

        private LocalDateTime bucketToFifteenMinute(
                        LocalDateTime time) {

                return time
                                .withMinute(
                                                (time.getMinute() / 15) * 15)
                                .withSecond(0)
                                .withNano(0);
        }

        private void validateDateRange(
                        String symbol,
                        LocalDate from,
                        LocalDate to) {

                if (!hasText(symbol)) {
                        throw new ProviderException("symbol is required");
                }

                if (from == null || to == null) {
                        throw new ProviderException(
                                        "from and to dates are required");
                }

                if (from.isAfter(to)) {
                        throw new ProviderException(
                                        "from date cannot be after to date");
                }
        }

        private boolean hasText(String value) {
                return value != null && !value.isBlank();
        }

        private String normalizeSymbol(String symbol) {
                return symbol == null
                                ? null
                                : symbol.trim()
                                                .toUpperCase(Locale.ROOT);
        }

        private record DateChunk(List<LocalDate> days) {

                private LocalDate from() {
                        return days.get(0);
                }

                private LocalDate to() {
                        return days.get(days.size() - 1);
                }
        }

        private record ChunkWriteResult(
                        int inserted,
                        int updated) {
        }
}
