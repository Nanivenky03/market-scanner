package com.trading.scanner.strategy.replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.DailyStockContext;
import com.trading.scanner.model.MarketCandle;

import java.io.File;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-performance fixture loader for historical market replay days.
 * Caches parsed JSON files in memory to guarantee sub-millisecond replay execution.
 */
public class MarketReplayFixtureLoader {

    public static final List<String> ALL_REPLAY_DATES = List.of(
            "2026-10-07",
            "2026-10-08",
            "2026-10-13",
            "2026-10-15",
            "2026-10-21",
            "2026-10-23",
            "2026-10-28",
            "2026-10-29"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, HistoricalMarketDay> cache = new ConcurrentHashMap<>();

    public HistoricalMarketDay loadDay(String date) {
        return cache.computeIfAbsent(date, this::parseDayFromFileOrClasspath);
    }

    public List<HistoricalMarketDay> loadAllDays() {
        List<HistoricalMarketDay> days = new ArrayList<>();
        for (String date : ALL_REPLAY_DATES) {
            days.add(loadDay(date));
        }
        return days;
    }

    private HistoricalMarketDay parseDayFromFileOrClasspath(String date) {
        try {
            JsonNode root = null;
            // 1. Try classpath resource
            InputStream is = getClass().getResourceAsStream("/replay/" + date + ".json");
            if (is != null) {
                root = objectMapper.readTree(is);
            } else {
                // 2. Try file system fallback
                File file = new File("src/test/resources/replay/" + date + ".json");
                if (file.exists()) {
                    root = objectMapper.readTree(file);
                }
            }

            if (root == null) {
                throw new IllegalArgumentException("Replay fixture not found for date: " + date);
            }

            String tradingDate = root.get("tradingDate").asText();
            String scenarioTag = root.hasNonNull("scenarioTag") ? root.get("scenarioTag").asText() : "UNKNOWN";
            String scenarioDesc = root.hasNonNull("scenarioDescription") ? root.get("scenarioDescription").asText() : "";

            // Parse dailyContexts
            Map<String, DailyStockContext> dailyContexts = new HashMap<>();
            JsonNode contextsNode = root.get("dailyContexts");
            if (contextsNode != null && contextsNode.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = contextsNode.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    dailyContexts.put(entry.getKey(), parseDailyContext(entry.getValue()));
                }
            }

            // Parse candles1m
            List<MarketCandle> candles1m = new ArrayList<>();
            JsonNode c1mNode = root.get("candles1m");
            if (c1mNode != null && c1mNode.isArray()) {
                for (JsonNode c : c1mNode) {
                    candles1m.add(parseCandle(c));
                }
            }

            // Parse candles5m
            List<MarketCandle> candles5m = new ArrayList<>();
            JsonNode c5mNode = root.get("candles5m");
            if (c5mNode != null && c5mNode.isArray()) {
                for (JsonNode c : c5mNode) {
                    candles5m.add(parseCandle(c));
                }
            }

            // Parse candles15m
            List<MarketCandle> candles15m = new ArrayList<>();
            JsonNode c15mNode = root.get("candles15m");
            if (c15mNode != null && c15mNode.isArray()) {
                for (JsonNode c : c15mNode) {
                    candles15m.add(parseCandle(c));
                }
            }

            HistoricalMarketDay day = HistoricalMarketDay.builder()
                    .tradingDate(tradingDate)
                    .scenarioTag(scenarioTag)
                    .scenarioDescription(scenarioDesc)
                    .dailyContexts(dailyContexts)
                    .candles1m(candles1m)
                    .candles5m(candles5m)
                    .candles15m(candles15m)
                    .build();
            day.initIndexes();
            return day;

        } catch (Exception e) {
            throw new RuntimeException("Failed to load replay fixture for date: " + date, e);
        }
    }

    private MarketCandle parseCandle(JsonNode node) {
        if (node == null || node.isNull()) return null;
        String symbol = node.get("symbol").asText();
        String exchange = node.hasNonNull("exchange") ? node.get("exchange").asText() : "NSE";
        String timeframeStr = node.hasNonNull("timeframe") ? node.get("timeframe").asText() : "ONE_MINUTE";
        CandleTimeframe tf = CandleTimeframe.valueOf(timeframeStr);
        String timeStr = node.get("candleTime").asText();
        LocalDateTime time = timeStr.length() == 16 ? LocalDateTime.parse(timeStr + ":00") : LocalDateTime.parse(timeStr);

        return MarketCandle.builder()
                .symbol(symbol)
                .exchange(exchange)
                .timeframe(tf)
                .candleTime(time)
                .openPrice(node.hasNonNull("openPrice") ? node.get("openPrice").asDouble() : null)
                .highPrice(node.hasNonNull("highPrice") ? node.get("highPrice").asDouble() : null)
                .lowPrice(node.hasNonNull("lowPrice") ? node.get("lowPrice").asDouble() : null)
                .closePrice(node.hasNonNull("closePrice") ? node.get("closePrice").asDouble() : null)
                .volume(node.hasNonNull("volume") ? node.get("volume").asLong() : 0L)
                .vwap(node.hasNonNull("vwap") ? node.get("vwap").asDouble() : null)
                .rsi14(node.hasNonNull("rsi14") ? node.get("rsi14").asDouble() : null)
                .atr14(node.hasNonNull("atr14") ? node.get("atr14").asDouble() : null)
                .bodyRatio(node.hasNonNull("bodyRatio") ? node.get("bodyRatio").asDouble() : null)
                .upperWickRatio(node.hasNonNull("upperWickRatio") ? node.get("upperWickRatio").asDouble() : null)
                .lowerWickRatio(node.hasNonNull("lowerWickRatio") ? node.get("lowerWickRatio").asDouble() : null)
                .rangePct(node.hasNonNull("rangePct") ? node.get("rangePct").asDouble() : null)
                .build();
    }

    private DailyStockContext parseDailyContext(JsonNode node) {
        if (node == null || node.isNull()) return null;
        String symbol = node.get("symbol").asText();
        String exchange = node.hasNonNull("exchange") ? node.get("exchange").asText() : "NSE";
        LocalDate date = LocalDate.parse(node.get("tradingDate").asText());

        return DailyStockContext.builder()
                .symbol(symbol)
                .exchange(exchange)
                .tradingDate(date)
                .firstCandleOpen(node.hasNonNull("firstCandleOpen") ? node.get("firstCandleOpen").asDouble() : null)
                .firstCandleHigh(node.hasNonNull("firstCandleHigh") ? node.get("firstCandleHigh").asDouble() : null)
                .firstCandleLow(node.hasNonNull("firstCandleLow") ? node.get("firstCandleLow").asDouble() : null)
                .firstCandleClose(node.hasNonNull("firstCandleClose") ? node.get("firstCandleClose").asDouble() : null)
                .firstCandleVolume(node.hasNonNull("firstCandleVolume") ? node.get("firstCandleVolume").asLong() : null)
                .firstCandleRange(node.hasNonNull("firstCandleRange") ? node.get("firstCandleRange").asDouble() : null)
                .firstCandleRangePct(node.hasNonNull("firstCandleRangePct") ? node.get("firstCandleRangePct").asDouble() : null)
                .firstCandleBullish(node.hasNonNull("firstCandleBullish") ? node.get("firstCandleBullish").asBoolean() : null)
                .firstCandleValid(node.hasNonNull("firstCandleValid") ? node.get("firstCandleValid").asBoolean() : null)
                .firstCandleReady(node.hasNonNull("firstCandleReady") ? node.get("firstCandleReady").asBoolean() : true)
                .prevDayHigh(node.hasNonNull("prevDayHigh") ? node.get("prevDayHigh").asDouble() : null)
                .prevDayLow(node.hasNonNull("prevDayLow") ? node.get("prevDayLow").asDouble() : null)
                .prevDayOpen(node.hasNonNull("prevDayOpen") ? node.get("prevDayOpen").asDouble() : null)
                .prevDayClose(node.hasNonNull("prevDayClose") ? node.get("prevDayClose").asDouble() : null)
                .prevDayRangePct(node.hasNonNull("prevDayRangePct") ? node.get("prevDayRangePct").asDouble() : null)
                .highestClose15d(node.hasNonNull("highestClose15d") ? node.get("highestClose15d").asDouble() : null)
                .distFromResistancePct(node.hasNonNull("distFromResistancePct") ? node.get("distFromResistancePct").asDouble() : null)
                .gapPct(node.hasNonNull("gapPct") ? node.get("gapPct").asDouble() : null)
                .skipToday(node.hasNonNull("skipToday") ? node.get("skipToday").asBoolean() : false)
                .build();
    }
}
