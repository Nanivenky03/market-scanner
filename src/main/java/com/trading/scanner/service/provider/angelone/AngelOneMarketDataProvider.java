package com.trading.scanner.service.provider.angelone;

import com.trading.scanner.config.provider.AngelOneProperties;
import com.trading.scanner.model.CandleQualityStatus;
import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.InstrumentMaster;
import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.repository.InstrumentMasterRepository;
import com.trading.scanner.service.provider.DailyBarDto;
import com.trading.scanner.service.provider.MarketDataProvider;
import com.trading.scanner.service.provider.ProviderException;
import com.trading.scanner.service.provider.ProviderType;
import com.trading.scanner.service.provider.angelone.dto.AngelOneAuthDtos;
import com.trading.scanner.service.provider.angelone.dto.AngelOneMarketDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@RequiredArgsConstructor
public class AngelOneMarketDataProvider
                implements MarketDataProvider {

        private static final String INTERVAL_ONE_DAY = "ONE_DAY";

        private static final String INTERVAL_ONE_MINUTE = "ONE_MINUTE";

        private static final DateTimeFormatter REQUEST_DATE_TIME_FORMAT = DateTimeFormatter.ofPattern(
                        "yyyy-MM-dd HH:mm");

        private static final DateTimeFormatter RESPONSE_MINUTE_FORMAT = DateTimeFormatter.ofPattern(
                        "yyyy-MM-dd HH:mm");

        private static final DateTimeFormatter RESPONSE_SECOND_FORMAT = DateTimeFormatter.ofPattern(
                        "yyyy-MM-dd HH:mm:ss");

        private final AngelOneProperties properties;
        private final InstrumentMasterRepository instrumentMasterRepository;
        private final AngelOneSessionService angelOneSessionService;
        private final AngelOneApiExecutor angelOneApiExecutor;

        @Override
        public ProviderType getProviderType() {
                return ProviderType.ANGEL_ONE;
        }

        @Override
        public boolean isAvailable() {
                return properties.enabled();
        }

        @Override
        public List<DailyBarDto> fetchDailyBars(
                        LocalDate tradingDate,
                        List<String> symbols) {

                requireProviderEnabled();

                if (tradingDate == null) {
                        throw new ProviderException(
                                        "tradingDate is required");
                }

                if (symbols == null || symbols.isEmpty()) {
                        return List.of();
                }

                List<DailyBarDto> result = new ArrayList<>();

                for (String symbol : symbols.stream()
                                .filter(this::hasText)
                                .map(this::normalizeSymbol)
                                .distinct()
                                .sorted()
                                .toList()) {

                        result.addAll(
                                        fetchHistoricalBars(
                                                        symbol,
                                                        tradingDate,
                                                        tradingDate));
                }

                return result.stream()
                                .sorted(Comparator.comparing(
                                                DailyBarDto::symbol))
                                .toList();
        }

        @Override
        public List<DailyBarDto> fetchHistoricalBars(
                        String symbol,
                        LocalDate from,
                        LocalDate to) {

                requireProviderEnabled();
                validateDateRange(symbol, from, to);

                String normalizedSymbol = normalizeSymbol(symbol);

                InstrumentMaster instrument = getRequiredInstrument(normalizedSymbol);

                try {
                        AngelOneAuthDtos.AngelOneSessionTokens sessionTokens = angelOneSessionService
                                        .createSessionTokens();

                        AngelOneMarketDtos.AngelOneCandleResponse response = callHistoricalCandleApi(
                                        sessionTokens.jwtToken(),
                                        instrument.getExchange(),
                                        instrument.getBrokerToken(),
                                        INTERVAL_ONE_DAY,
                                        from,
                                        to);

                        validateSuccessfulResponse(
                                        response,
                                        "historical daily");

                        if (response.data() == null
                                        || response.data().isEmpty()) {
                                return List.of();
                        }

                        List<DailyBarDto> bars = new ArrayList<>();

                        int rowIndex = 0;

                        for (List<Object> row : response.data()) {
                                if (row == null || row.size() < 6) {
                                        throw malformedRow(
                                                        "historical daily",
                                                        rowIndex,
                                                        "Expected at least six fields");
                                }

                                try {
                                        String timestamp = stringValue(row.get(0));

                                        Double open = doubleValue(row.get(1));

                                        Double high = doubleValue(row.get(2));

                                        Double low = doubleValue(row.get(3));

                                        Double close = doubleValue(row.get(4));

                                        Long volume = longValue(row.get(5));

                                        if (timestamp == null
                                                        || timestamp.length() < 10
                                                        || open == null
                                                        || high == null
                                                        || low == null
                                                        || close == null
                                                        || volume == null) {
                                                throw malformedRow(
                                                                "historical daily",
                                                                rowIndex,
                                                                "Required candle field is missing");
                                        }

                                        LocalDate tradingDate = LocalDate.parse(
                                                        timestamp.substring(0, 10));

                                        validateOhlcv(
                                                        open,
                                                        high,
                                                        low,
                                                        close,
                                                        volume,
                                                        "historical daily",
                                                        rowIndex);

                                        bars.add(
                                                        new DailyBarDto(
                                                                        normalizedSymbol,
                                                                        tradingDate,
                                                                        open,
                                                                        high,
                                                                        low,
                                                                        close,
                                                                        close,
                                                                        volume,
                                                                        "ANGEL_ONE"));

                                } catch (ProviderException ex) {
                                        throw ex;
                                } catch (Exception ex) {
                                        throw malformedRow(
                                                        "historical daily",
                                                        rowIndex,
                                                        ex.getMessage());
                                }

                                rowIndex++;
                        }

                        return bars.stream()
                                        .sorted(Comparator.comparing(
                                                        DailyBarDto::tradingDate))
                                        .toList();

                } catch (ProviderException ex) {
                        throw ex;
                } catch (Exception ex) {
                        throw new ProviderException(
                                        "Failed to fetch Angel One historical daily bars for symbol="
                                                        + normalizedSymbol,
                                        ex);
                }
        }

        public List<MarketCandle> fetchHistoricalOneMinuteCandles(
                        String symbol,
                        LocalDate from,
                        LocalDate to) {

                requireProviderEnabled();
                validateDateRange(symbol, from, to);

                String normalizedSymbol = normalizeSymbol(symbol);

                InstrumentMaster instrument = getRequiredInstrument(normalizedSymbol);

                try {
                        AngelOneAuthDtos.AngelOneSessionTokens sessionTokens = angelOneSessionService
                                        .createSessionTokens();

                        AngelOneMarketDtos.AngelOneCandleResponse response = callHistoricalCandleApi(
                                        sessionTokens.jwtToken(),
                                        instrument.getExchange(),
                                        instrument.getBrokerToken(),
                                        INTERVAL_ONE_MINUTE,
                                        from,
                                        to);

                        validateSuccessfulResponse(
                                        response,
                                        "historical one-minute");

                        /*
                         * An empty response is deliberately not interpreted as
                         * NO_TRADE_CONFIRMED. SmartAPI has not provided explicit
                         * no-trade evidence in this response.
                         */
                        if (response.data() == null
                                        || response.data().isEmpty()) {
                                throw new ProviderException(
                                                "Angel One historical one-minute response returned no candle rows"
                                                                + "; no-trade cannot be inferred"
                                                                + "; symbol="
                                                                + normalizedSymbol
                                                                + ", from="
                                                                + from
                                                                + ", to="
                                                                + to);
                        }

                        List<MarketCandle> candles = new ArrayList<>();

                        Set<LocalDateTime> seenTimes = new HashSet<>();

                        int rowIndex = 0;

                        for (List<Object> row : response.data()) {
                                if (row == null || row.size() < 6) {
                                        throw malformedRow(
                                                        "historical one-minute",
                                                        rowIndex,
                                                        "Expected at least six fields");
                                }

                                try {
                                        LocalDateTime candleTime = parseCandleTime(
                                                        stringValue(row.get(0)));

                                        Double open = doubleValue(row.get(1));

                                        Double high = doubleValue(row.get(2));

                                        Double low = doubleValue(row.get(3));

                                        Double close = doubleValue(row.get(4));

                                        Long volume = longValue(row.get(5));

                                        Long openInterest = row.size() > 6
                                                        ? longValue(row.get(6))
                                                        : null;

                                        if (candleTime == null
                                                        || open == null
                                                        || high == null
                                                        || low == null
                                                        || close == null
                                                        || volume == null) {
                                                throw malformedRow(
                                                                "historical one-minute",
                                                                rowIndex,
                                                                "Required candle field is missing");
                                        }

                                        if (candleTime.toLocalDate()
                                                        .isBefore(from)
                                                        || candleTime.toLocalDate()
                                                                        .isAfter(to)) {
                                                throw malformedRow(
                                                                "historical one-minute",
                                                                rowIndex,
                                                                "Candle timestamp is outside requested date range");
                                        }

                                        validateOhlcv(
                                                        open,
                                                        high,
                                                        low,
                                                        close,
                                                        volume,
                                                        "historical one-minute",
                                                        rowIndex);

                                        if (!seenTimes.add(candleTime)) {
                                                throw malformedRow(
                                                                "historical one-minute",
                                                                rowIndex,
                                                                "Duplicate candle timestamp="
                                                                                + candleTime);
                                        }

                                        candles.add(
                                                        MarketCandle.builder()
                                                                        .symbol(normalizedSymbol)
                                                                        .exchange(
                                                                                        instrument.getExchange())
                                                                        .timeframe(
                                                                                        CandleTimeframe.ONE_MINUTE)
                                                                        .candleTime(candleTime)
                                                                        .openPrice(open)
                                                                        .highPrice(high)
                                                                        .lowPrice(low)
                                                                        .closePrice(close)
                                                                        .volume(volume)
                                                                        .openInterest(openInterest)
                                                                        .source("ANGEL_ONE")
                                                                        .isFinalized(true)
                                                                        .qualityStatus(
                                                                                        CandleQualityStatus.LIVE)
                                                                        .build());

                                } catch (ProviderException ex) {
                                        throw ex;
                                } catch (Exception ex) {
                                        throw malformedRow(
                                                        "historical one-minute",
                                                        rowIndex,
                                                        ex.getMessage());
                                }

                                rowIndex++;
                        }

                        if (candles.isEmpty()) {
                                throw new ProviderException(
                                                "Angel One historical one-minute response contained no valid candles"
                                                                + "; no-trade cannot be inferred"
                                                                + "; symbol="
                                                                + normalizedSymbol);
                        }

                        return candles.stream()
                                        .sorted(Comparator.comparing(
                                                        MarketCandle::getCandleTime))
                                        .toList();

                } catch (ProviderException ex) {
                        throw ex;
                } catch (Exception ex) {
                        throw new ProviderException(
                                        "Failed to fetch Angel One 1-minute candles for symbol="
                                                        + normalizedSymbol,
                                        ex);
                }
        }

        private void requireProviderEnabled() {
                if (!properties.enabled()) {
                        throw new ProviderException(
                                        "Angel One provider is disabled. Set ANGELONE_ENABLED=true");
                }
        }

        private void validateDateRange(
                        String symbol,
                        LocalDate from,
                        LocalDate to) {

                if (!hasText(symbol)) {
                        throw new ProviderException(
                                        "symbol is required");
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

        private void validateSuccessfulResponse(
                        AngelOneMarketDtos.AngelOneCandleResponse response,
                        String operation) {

                if (response == null) {
                        throw new ProviderException(
                                        "Angel One "
                                                        + operation
                                                        + " response was null");
                }

                if (response.status() == null
                                || !response.status()) {
                        throw new ProviderException(
                                        "Angel One "
                                                        + operation
                                                        + " fetch failed. message="
                                                        + response.message()
                                                        + ", errorcode="
                                                        + response.errorcode());
                }
        }

        private void validateOhlcv(
                        Double open,
                        Double high,
                        Double low,
                        Double close,
                        Long volume,
                        String operation,
                        int rowIndex) {

                if (!isFinite(open)
                                || !isFinite(high)
                                || !isFinite(low)
                                || !isFinite(close)
                                || volume < 0L) {
                        throw malformedRow(
                                        operation,
                                        rowIndex,
                                        "Non-finite OHLC value or negative volume");
                }

                if (high < low
                                || high < Math.max(open, close)
                                || low > Math.min(open, close)) {
                        throw malformedRow(
                                        operation,
                                        rowIndex,
                                        "OHLC values are internally inconsistent");
                }
        }

        private ProviderException malformedRow(
                        String operation,
                        int rowIndex,
                        String reason) {

                return new ProviderException(
                                "Malformed Angel One "
                                                + operation
                                                + " row at index="
                                                + rowIndex
                                                + ". reason="
                                                + reason);
        }

        private InstrumentMaster getRequiredInstrument(
                        String symbol) {

                InstrumentMaster instrument = instrumentMasterRepository
                                .findBySymbolAndExchange(
                                                symbol,
                                                "NSE")
                                .orElseThrow(() -> new ProviderException(
                                                "Instrument not found in instrument_master for symbol="
                                                                + symbol
                                                                + ", exchange=NSE"));

                if (instrument.getBrokerToken() == null
                                || instrument.getBrokerToken().isBlank()) {
                        throw new ProviderException(
                                        "broker_token is missing for symbol="
                                                        + symbol);
                }

                return instrument;
        }

        public Map<String, AngelOneMarketDtos.AngelOneMarketQuoteItem> fetchMarketQuotes(
                        List<String> symbols,
                        String exchange) {

                requireProviderEnabled();

                if (symbols == null || symbols.isEmpty()) {
                        return Map.of();
                }

                String targetExchange = (exchange != null && !exchange.isBlank())
                                ? exchange.trim().toUpperCase(Locale.ROOT)
                                : "NSE";

                Map<String, String> symbolToToken = new LinkedHashMap<>();
                Map<String, String> tokenToSymbol = new LinkedHashMap<>();

                for (String s : symbols) {
                        if (!hasText(s)) {
                                continue;
                        }
                        String normalizedSymbol = normalizeSymbol(s);
                        try {
                                InstrumentMaster instrument = getRequiredInstrument(normalizedSymbol);
                                if (instrument.getBrokerToken() != null && !instrument.getBrokerToken().isBlank()) {
                                        symbolToToken.put(normalizedSymbol, instrument.getBrokerToken());
                                        tokenToSymbol.put(instrument.getBrokerToken(), normalizedSymbol);
                                }
                        } catch (Exception ex) {
                                log.debug("No broker token found for symbol={} exchange={}: {}",
                                                normalizedSymbol, targetExchange, ex.getMessage());
                        }
                }

                if (symbolToToken.isEmpty()) {
                        return Map.of();
                }

                List<String> allTokens = new ArrayList<>(symbolToToken.values());
                Map<String, AngelOneMarketDtos.AngelOneMarketQuoteItem> resultMap = new LinkedHashMap<>();

                int batchSize = 50;
                for (int i = 0; i < allTokens.size(); i += batchSize) {
                        List<String> tokenBatch = allTokens.subList(i, Math.min(i + batchSize, allTokens.size()));
                        try {
                                AngelOneAuthDtos.AngelOneSessionTokens sessionTokens = angelOneSessionService
                                                .createSessionTokens();
                                String url = properties.baseUrl() + "/rest/secure/angelbroking/market/v1/quote/";

                                Map<String, List<String>> exchangeTokens = Map.of(targetExchange, tokenBatch);
                                AngelOneMarketDtos.AngelOneMarketQuoteRequest requestBody = new AngelOneMarketDtos.AngelOneMarketQuoteRequest(
                                                "FULL", exchangeTokens);

                                Map<String, String> headers = createHeaders(sessionTokens.jwtToken());

                                AngelOneMarketDtos.AngelOneMarketQuoteResponse response = angelOneApiExecutor.postJson(
                                                AngelOneApiExecutor.ApiEndpoint.MARKET_DATA_QUOTE,
                                                url,
                                                headers,
                                                requestBody,
                                                AngelOneMarketDtos.AngelOneMarketQuoteResponse.class);

                                if (response != null
                                                && Boolean.TRUE.equals(response.status())
                                                && response.data() != null
                                                && response.data().fetched() != null) {

                                        for (AngelOneMarketDtos.AngelOneMarketQuoteItem item : response.data()
                                                        .fetched()) {
                                                if (item == null) {
                                                        continue;
                                                }
                                                String token = item.symbolToken();
                                                String matchedSymbol = tokenToSymbol.get(token);
                                                if (matchedSymbol == null && item.tradingSymbol() != null) {
                                                        matchedSymbol = normalizeSymbol(item.tradingSymbol());
                                                }
                                                if (matchedSymbol != null) {
                                                        resultMap.put(matchedSymbol, item);
                                                }
                                        }
                                } else {
                                        log.warn("Angel One market quote API response: status={}, message={}, errorCode={}",
                                                        response != null ? response.status() : "null",
                                                        response != null ? response.message() : "null",
                                                        response != null ? response.errorcode() : "null");
                                }
                        } catch (Exception ex) {
                                log.warn("Failed to fetch market quotes batch of {} tokens for exchange={}: {}",
                                                tokenBatch.size(), targetExchange, ex.getMessage(), ex);
                        }
                }

                return resultMap;
        }

        private AngelOneMarketDtos.AngelOneCandleResponse callHistoricalCandleApi(
                        String jwtToken,
                        String exchange,
                        String symbolToken,
                        String interval,
                        LocalDate from,
                        LocalDate to) {

                String url = properties.baseUrl()
                                + "/rest/secure/angelbroking/historical/v1/getCandleData";

                AngelOneMarketDtos.AngelOneCandleRequest requestBody = new AngelOneMarketDtos.AngelOneCandleRequest(
                                exchange,
                                symbolToken,
                                interval,
                                from.atTime(0, 0)
                                                .format(
                                                                REQUEST_DATE_TIME_FORMAT),
                                to.atTime(23, 59)
                                                .format(
                                                                REQUEST_DATE_TIME_FORMAT));

                Map<String, String> headers = createHeaders(jwtToken);

                return angelOneApiExecutor.postJson(
                                AngelOneApiExecutor.ApiEndpoint.HISTORICAL_CANDLE,
                                url,
                                headers,
                                requestBody,
                                AngelOneMarketDtos.AngelOneCandleResponse.class);
        }

        private Map<String, String> createHeaders(String jwtToken) {
                Map<String, String> headers = new LinkedHashMap<>();

                headers.put(
                                "Content-Type",
                                "application/json");

                headers.put(
                                "Accept",
                                "application/json");

                headers.put(
                                "Authorization",
                                "Bearer " + jwtToken);

                headers.put(
                                "X-UserType",
                                "USER");

                headers.put(
                                "X-SourceID",
                                "WEB");

                headers.put(
                                "X-ClientLocalIP",
                                properties.clientLocalIp());

                headers.put(
                                "X-ClientPublicIP",
                                properties.clientPublicIp());

                headers.put(
                                "X-MACAddress",
                                properties.macAddress());

                headers.put(
                                "X-PrivateKey",
                                properties.apiKey());

                return headers;
        }

        private LocalDateTime parseCandleTime(
                        String value) {

                if (value == null || value.isBlank()) {
                        return null;
                }

                try {
                        return OffsetDateTime.parse(value)
                                        .toLocalDateTime();
                } catch (Exception ignored) {
                }

                String normalized = value.replace('T', ' ')
                                .trim();

                try {
                        if (normalized.length() >= 19) {
                                return LocalDateTime.parse(
                                                normalized.substring(0, 19),
                                                RESPONSE_SECOND_FORMAT);
                        }
                } catch (Exception ignored) {
                }

                try {
                        if (normalized.length() >= 16) {
                                return LocalDateTime.parse(
                                                normalized.substring(0, 16),
                                                RESPONSE_MINUTE_FORMAT);
                        }
                } catch (Exception ignored) {
                }

                return null;
        }

        private String stringValue(Object value) {
                return value == null
                                ? null
                                : value.toString();
        }

        private Double doubleValue(Object value) {
                if (value == null) {
                        return null;
                }

                return Double.valueOf(value.toString());
        }

        private Long longValue(Object value) {
                if (value == null) {
                        return null;
                }

                return Long.valueOf(value.toString());
        }

        private boolean isFinite(Double value) {
                return value != null
                                && !value.isNaN()
                                && !value.isInfinite();
        }

        private boolean hasText(String value) {
                return value != null
                                && !value.isBlank();
        }

        private String normalizeSymbol(String symbol) {
                return symbol.trim()
                                .toUpperCase(Locale.ROOT);
        }
}
