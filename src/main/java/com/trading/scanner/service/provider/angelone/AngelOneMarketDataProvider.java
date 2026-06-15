package com.trading.scanner.service.provider.angelone;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.scanner.config.provider.AngelOneProperties;
import com.trading.scanner.model.InstrumentMaster;
import com.trading.scanner.repository.InstrumentMasterRepository;
import com.trading.scanner.service.provider.DailyBarDto;
import com.trading.scanner.service.provider.MarketDataProvider;
import com.trading.scanner.service.provider.ProviderException;
import com.trading.scanner.service.provider.ProviderType;
import com.trading.scanner.service.provider.angelone.dto.AngelOneCandleRequest;
import com.trading.scanner.service.provider.angelone.dto.AngelOneCandleResponse;
import com.trading.scanner.service.provider.angelone.dto.AngelOneSessionTokens;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Slf4j
@RequiredArgsConstructor
public class AngelOneMarketDataProvider implements MarketDataProvider {

    private static final String INTERVAL_ONE_DAY = "ONE_DAY";
    private static final DateTimeFormatter REQUEST_DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AngelOneProperties properties;
    private final InstrumentMasterRepository instrumentMasterRepository;
    private final AngelOneSessionService angelOneSessionService;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    @Override
    public ProviderType getProviderType() {
        return ProviderType.ANGEL_ONE;
    }

    @Override
    public boolean isAvailable() {
        return properties.enabled();
    }

    @Override
    public List<DailyBarDto> fetchDailyBars(LocalDate tradingDate, List<String> symbols) {
        if (!properties.enabled()) {
            throw new ProviderException("Angel One provider is disabled. Set ANGELONE_ENABLED=true");
        }

        if (tradingDate == null) {
            throw new ProviderException("tradingDate is required");
        }

        if (symbols == null || symbols.isEmpty()) {
            return List.of();
        }

        List<DailyBarDto> result = new ArrayList<>();

        for (String symbol : symbols.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .sorted()
                .toList()) {

            List<DailyBarDto> bars = fetchHistoricalBars(symbol, tradingDate, tradingDate);
            result.addAll(bars);
        }

        return result.stream()
                .sorted(Comparator.comparing(DailyBarDto::symbol))
                .toList();
    }

    @Override
    public List<DailyBarDto> fetchHistoricalBars(String symbol, LocalDate from, LocalDate to) {
        if (!properties.enabled()) {
            throw new ProviderException("Angel One provider is disabled. Set ANGELONE_ENABLED=true");
        }

        if (symbol == null || symbol.isBlank()) {
            throw new ProviderException("symbol is required");
        }
        if (from == null || to == null) {
            throw new ProviderException("from and to dates are required");
        }
        if (from.isAfter(to)) {
            throw new ProviderException("from date cannot be after to date");
        }

        String normalizedSymbol = symbol.trim().toUpperCase(Locale.ROOT);

        InstrumentMaster instrument = instrumentMasterRepository
                .findBySymbolAndExchange(normalizedSymbol, "NSE")
                .orElseThrow(() -> new ProviderException(
                        "Instrument not found in instrument_master for symbol=" + normalizedSymbol + ", exchange=NSE"));

        if (instrument.getBrokerToken() == null || instrument.getBrokerToken().isBlank()) {
            throw new ProviderException("broker_token is missing for symbol=" + normalizedSymbol);
        }

        try {
            AngelOneSessionTokens sessionTokens = angelOneSessionService.createSessionTokens();

            AngelOneCandleResponse response = callHistoricalCandleApi(
                    sessionTokens.jwtToken(),
                    instrument.getExchange(),
                    instrument.getBrokerToken(),
                    from,
                    to
            );

            if (response.status() == null || !response.status()) {
                throw new ProviderException("Angel One historical fetch failed. message="
                        + response.message() + ", errorcode=" + response.errorcode());
            }

            if (response.data() == null || response.data().isEmpty()) {
                return List.of();
            }

            List<DailyBarDto> bars = new ArrayList<>();

            for (List<Object> row : response.data()) {
                if (row == null || row.size() < 6) {
                    continue;
                }

                String timestamp = stringValue(row.get(0));
                Double open = doubleValue(row.get(1));
                Double high = doubleValue(row.get(2));
                Double low = doubleValue(row.get(3));
                Double close = doubleValue(row.get(4));
                Long volume = longValue(row.get(5));

                if (timestamp == null || timestamp.length() < 10) {
                    continue;
                }

                LocalDate tradingDate = LocalDate.parse(timestamp.substring(0, 10));

                bars.add(new DailyBarDto(
                        normalizedSymbol,
                        tradingDate,
                        open,
                        high,
                        low,
                        close,
                        close,
                        volume,
                        "ANGEL_ONE"
                ));
            }

            return bars.stream()
                    .sorted(Comparator.comparing(DailyBarDto::tradingDate))
                    .toList();

        } catch (ProviderException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ProviderException("Failed to fetch Angel One historical bars for symbol=" + normalizedSymbol, ex);
        }
    }

    private AngelOneCandleResponse callHistoricalCandleApi(
            String jwtToken,
            String exchange,
            String symbolToken,
            LocalDate from,
            LocalDate to
    ) throws Exception {

        String url = properties.baseUrl() + "/rest/secure/angelbroking/historical/v1/getCandleData";

        AngelOneCandleRequest requestBody = new AngelOneCandleRequest(
                exchange,
                symbolToken,
                INTERVAL_ONE_DAY,
                from.atTime(0, 0).format(REQUEST_DATE_TIME_FORMAT),
                to.atTime(23, 59).format(REQUEST_DATE_TIME_FORMAT)
        );

        String jsonBody = objectMapper.writeValueAsString(requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + jwtToken)
                .header("X-UserType", "USER")
                .header("X-SourceID", "WEB")
                .header("X-ClientLocalIP", properties.clientLocalIp())
                .header("X-ClientPublicIP", properties.clientPublicIp())
                .header("X-MACAddress", properties.macAddress())
                .header("X-PrivateKey", properties.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        return objectMapper.readValue(response.body(), AngelOneCandleResponse.class);
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
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
}