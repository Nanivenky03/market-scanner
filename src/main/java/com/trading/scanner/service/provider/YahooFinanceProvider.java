package com.trading.scanner.service.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.scanner.aop.SimulationExit;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.Exchange;
import com.trading.scanner.model.StockPrice;
import com.trading.scanner.model.StockUniverse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Yahoo Finance Provider - Direct HTTP Implementation
 *
 * Uses Yahoo Finance v8 Chart API for historical data
 *
 * API: https://query1.finance.yahoo.com/v8/finance/chart/{symbol}?period1={start}&period2={end}&interval=1d
 */
@Slf4j
@Service
@SimulationExit
public class YahooFinanceProvider implements MarketDataProvider {

    private static final String YAHOO_API_URL = "https://query1.finance.yahoo.com/v8/finance/chart/";
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final TimeProvider timeProvider;

    public YahooFinanceProvider(ObjectMapper objectMapper, TimeProvider timeProvider) {
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.objectMapper = objectMapper;
        this.timeProvider = timeProvider;
    }

    @Override
    public List<StockPrice> fetchHistoricalData(StockUniverse stock, LocalDate startDate, LocalDate endDate)
            throws DataProviderException {
        String yahooSymbol = formatTicker(stock.getSymbol(), stock.getExchange());
        try {
            // Convert dates to Unix timestamps
            long period1 = startDate.atStartOfDay(ZoneId.of("UTC")).toEpochSecond();
            long period2 = endDate.atTime(23, 59, 59).atZone(ZoneId.of("UTC")).toEpochSecond();

            // Build URL
            String url = String.format("%s%s?period1=%d&period2=%d&interval=1d&includeAdjustedClose=true",
                    YAHOO_API_URL, yahooSymbol, period1, period2);

            log.debug("Fetching data for {}: {} to {}", stock.getSymbol(), startDate, endDate);

            // Make HTTP request
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 404) {
                throw new SymbolNotFoundException(stock.getSymbol(), stock.getExchange());
            }

            if (response.statusCode() != 200) {
                throw new DataProviderException(
                        String.format("Yahoo Finance API returned status %d for %s", response.statusCode(), stock.getSymbol())
                );
            }

            // Parse JSON response
            JsonNode root = objectMapper.readTree(response.body());

            // Check for errors
            if (root.has("chart") && root.get("chart").has("error") &&
                    !root.get("chart").get("error").isNull()) {
                String error = root.get("chart").get("error").get("description").asText();
                log.error("Yahoo Finance error for {}: {}", stock.getSymbol(), error);
                throw new SymbolNotFoundException(stock.getSymbol(), stock.getExchange());
            }

            // Extract data
            JsonNode result = root.path("chart").path("result");
            if (result.isEmpty() || result.isNull() || result.get(0) == null) {
                log.warn("No data returned for {} between {} and {}", stock.getSymbol(), startDate, endDate);
                throw new SymbolNotFoundException(stock.getSymbol(), stock.getExchange());
            }

            JsonNode firstResult = result.get(0);
            JsonNode timestamps = firstResult.path("timestamp");
            JsonNode indicators = firstResult.path("indicators");
            JsonNode quote = indicators.path("quote").get(0);
            JsonNode adjclose = indicators.path("adjclose").get(0).path("adjclose");

            if (timestamps.isEmpty() || timestamps.isNull() || timestamps.size() == 0) {
                log.warn("No timestamps for {} between {} and {}", stock.getSymbol(), startDate, endDate);
                throw new SymbolNotFoundException(stock.getSymbol(), stock.getExchange());
            }

            // Build price list
            List<StockPrice> prices = new ArrayList<>();
            for (int i = 0; i < timestamps.size(); i++) {
                long timestamp = timestamps.get(i).asLong();
                LocalDate date = LocalDate.ofEpochDay(timestamp / 86400);

                // Extract OHLCV data
                Double open = getDoubleValue(quote.path("open"), i);
                Double high = getDoubleValue(quote.path("high"), i);
                Double low = getDoubleValue(quote.path("low"), i);
                Double close = getDoubleValue(quote.path("close"), i);
                Long volume = getLongValue(quote.path("volume"), i);
                Double adjClose = getDoubleValue(adjclose, i);

                // Skip if no close price (likely invalid data)
                if (close == null || close == 0.0) {
                    continue;
                }

                StockPrice price = StockPrice.builder()
                        .symbol(stock.getSymbol()) // Use original symbol
                        .date(date)
                        .openPrice(open)
                        .highPrice(high)
                        .lowPrice(low)
                        .closePrice(close)
                        .adjClose(adjClose != null ? adjClose : close) // Fallback to close if no adj_close
                        .volume(volume)
                        .build();
                prices.add(price);
            }

            log.debug("Fetched {} prices for {}", prices.size(), stock.getSymbol());
            return prices;

        } catch (IOException | InterruptedException e) {
            throw new DataProviderException("Failed to fetch data for " + stock.getSymbol() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public StockPrice fetchLatestData(StockUniverse stock) throws DataProviderException {
        LocalDate today = timeProvider.today();
        LocalDate from = today.minusDays(7); // Fetch 1 week to be safe

        List<StockPrice> prices = fetchHistoricalData(stock, from, today);

        if (prices.isEmpty()) {
            throw new SymbolNotFoundException(stock.getSymbol(), stock.getExchange());
        }

        // Return the most recent price
        return prices.get(prices.size() - 1);
    }

    @Override
    public boolean isHealthy(StockUniverse stock) {
        try {
            LocalDate today = timeProvider.today();
            List<StockPrice> prices = fetchHistoricalData(stock, today.minusDays(7), today);
            return !prices.isEmpty();
        } catch (DataProviderException e) {
            log.error("Provider health check failed for {}: {}", stock.getSymbol(), e.getMessage());
            return false;
        }
    }

    private String formatTicker(String symbol, Exchange exchange) {
        return switch (exchange) {
            case NSE -> symbol + ".NS";
        };
    }

    /**
     * Safely extract double value from JSON array at index
     */
    private Double getDoubleValue(JsonNode array, int index) {
        if (array == null || array.isNull() || index >= array.size()) {
            return null;
        }
        JsonNode node = array.get(index);
        return (node == null || node.isNull()) ? null : node.asDouble();
    }

    /**
     * Safely extract long value from JSON array at index
     */
    private Long getLongValue(JsonNode array, int index) {
        if (array == null || array.isNull() || index >= array.size()) {
            return null;
        }
        JsonNode node = array.get(index);
        return (node == null || node.isNull()) ? null : node.asLong();
    }
}