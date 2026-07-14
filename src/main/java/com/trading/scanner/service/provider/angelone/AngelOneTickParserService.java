package com.trading.scanner.service.provider.angelone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.scanner.model.InstrumentMaster;
import com.trading.scanner.repository.InstrumentMasterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AngelOneTickParserService {

    private static final ZoneId INDIA_ZONE = ZoneId.of("Asia/Kolkata");

    private static final int OFFSET_SUBSCRIPTION_MODE = 0;
    private static final int OFFSET_EXCHANGE_TYPE = 1;
    private static final int OFFSET_BROKER_TOKEN = 2;
    private static final int LENGTH_BROKER_TOKEN = 25;
    private static final int OFFSET_SEQUENCE_NUMBER = 27;
    private static final int OFFSET_EXCHANGE_TIMESTAMP = 35;
    private static final int OFFSET_LAST_PRICE = 43;

    private static final int OFFSET_LAST_TRADED_QUANTITY = 51;
    private static final int OFFSET_AVERAGE_TRADED_PRICE = 59;
    private static final int OFFSET_VOLUME_TRADED_FOR_DAY = 67;
    private static final int OFFSET_TOTAL_BUY_QUANTITY = 75;
    private static final int OFFSET_TOTAL_SELL_QUANTITY = 83;
    private static final int OFFSET_OPEN_PRICE = 91;
    private static final int OFFSET_HIGH_PRICE = 99;
    private static final int OFFSET_LOW_PRICE = 107;
    private static final int OFFSET_CLOSE_PRICE = 115;

    private static final int OFFSET_LAST_TRADED_TIMESTAMP = 123;
    private static final int OFFSET_OPEN_INTEREST = 131;
    private static final int OFFSET_OPEN_INTEREST_CHANGE_PERCENT = 139;

    private static final int OFFSET_UPPER_CIRCUIT_LIMIT = 347;
    private static final int OFFSET_LOWER_CIRCUIT_LIMIT = 355;
    private static final int OFFSET_FIFTY_TWO_WEEK_HIGH = 363;
    private static final int OFFSET_FIFTY_TWO_WEEK_LOW = 371;

    private static final int LENGTH_LONG = 8;
    private static final int MIN_LTP_BINARY_LENGTH = 51;
    private static final int MIN_QUOTE_BINARY_LENGTH = 123;
    private static final int MIN_SNAP_QUOTE_BINARY_LENGTH = 379;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final InstrumentMasterRepository instrumentMasterRepository;

    public Optional<NormalizedTick> tryParseText(String payload) {
        if (payload == null || payload.isBlank()) {
            return Optional.empty();
        }

        try {
            JsonNode root = objectMapper.readTree(payload);

            String symbol = text(root, "symbol");
            String exchange = text(root, "exchange");
            Double lastPrice = number(root, "lastPrice", "lastTradedPrice", "ltp");
            Long lastQty = longValue(root, "lastTradedQuantity", "lastTradedQty");
            String brokerToken = text(root, "token", "brokerToken");
            Integer subscriptionMode = intValue(root, "subscriptionMode", "mode");
            Integer exchangeType = intValue(root, "exchangeType");

            Double openPrice = number(root, "openPrice", "open");
            Double highPrice = number(root, "highPrice", "high");
            Double lowPrice = number(root, "lowPrice", "low");
            Double closePrice = number(root, "closePrice", "close");
            Double averageTradedPrice = number(root, "averageTradedPrice", "averagePrice", "atp");
            Long volumeTradedForDay = longValue(root, "volumeTradedForDay", "volumeTradeForTheDay",
                    "volumeTradedToday");
            Long totalBuyQuantity = longValue(root, "totalBuyQuantity", "totalBuyQty");
            Long totalSellQuantity = longValue(root, "totalSellQuantity", "totalSellQty");
            Long lastTradedTimestamp = longValue(root, "lastTradedTimestamp", "lastTradeTime", "lastTradedTime");
            Long openInterest = longValue(root, "openInterest", "openInterestQty");
            Double openInterestChangePercentRaw = number(root, "openInterestChangePercentRaw",
                    "openInterestChangePercentage");
            Double upperCircuitLimit = number(root, "upperCircuitLimit", "upperCircuit");
            Double lowerCircuitLimit = number(root, "lowerCircuitLimit", "lowerCircuit");
            Double fiftyTwoWeekHighPrice = number(root, "fiftyTwoWeekHighPrice", "weekHigh52", "yearlyHigh");
            Double fiftyTwoWeekLowPrice = number(root, "fiftyTwoWeekLowPrice", "weekLow52", "yearlyLow");

            if (symbol == null || exchange == null || lastPrice == null) {
                return Optional.empty();
            }

            return Optional.of(new NormalizedTick(
                    symbol.trim().toUpperCase(Locale.ROOT),
                    exchange.trim().toUpperCase(Locale.ROOT),
                    LocalDateTime.now(INDIA_ZONE),
                    lastPrice,
                    lastQty,
                    brokerToken,
                    subscriptionMode,
                    exchangeType,
                    null,
                    null,
                    null,
                    openPrice,
                    highPrice,
                    lowPrice,
                    closePrice,
                    averageTradedPrice,
                    volumeTradedForDay,
                    totalBuyQuantity,
                    totalSellQuantity,
                    lastTradedTimestamp,
                    openInterest,
                    openInterestChangePercentRaw,
                    upperCircuitLimit,
                    lowerCircuitLimit,
                    fiftyTwoWeekHighPrice,
                    fiftyTwoWeekLowPrice));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    public Optional<NormalizedTick> tryParseBinary(byte[] payload) {
        if (payload == null || payload.length < MIN_LTP_BINARY_LENGTH) {
            return Optional.empty();
        }

        try {
            int subscriptionMode = unsignedByte(payload[OFFSET_SUBSCRIPTION_MODE]);
            int exchangeType = unsignedByte(payload[OFFSET_EXCHANGE_TYPE]);

            String brokerToken = readAsciiToken(payload, OFFSET_BROKER_TOKEN, LENGTH_BROKER_TOKEN);
            if (brokerToken == null || brokerToken.isBlank()) {
                return Optional.empty();
            }

            long sequenceNumber = readLittleEndianLong(payload, OFFSET_SEQUENCE_NUMBER, LENGTH_LONG);
            long exchangeTimestamp = readLittleEndianLong(payload, OFFSET_EXCHANGE_TIMESTAMP, LENGTH_LONG);
            long lastPriceRaw = readLittleEndianLong(payload, OFFSET_LAST_PRICE, LENGTH_LONG);

            Optional<InstrumentMaster> instrumentOpt = instrumentMasterRepository.findByBrokerToken(brokerToken);
            if (instrumentOpt.isEmpty()) {
                return Optional.empty();
            }

            InstrumentMaster instrument = instrumentOpt.get();

            LocalDateTime tickTime = exchangeTimestamp > 0
                    ? Instant.ofEpochMilli(exchangeTimestamp).atZone(INDIA_ZONE).toLocalDateTime()
                    : LocalDateTime.now(INDIA_ZONE);

            Double lastPrice = scaledPrice(lastPriceRaw);

            Long lastTradedQuantity = readOptionalLong(payload, OFFSET_LAST_TRADED_QUANTITY, MIN_QUOTE_BINARY_LENGTH);
            Double averageTradedPrice = scaledPrice(
                    readOptionalLong(payload, OFFSET_AVERAGE_TRADED_PRICE, MIN_QUOTE_BINARY_LENGTH));
            Long volumeTradedForDay = readOptionalLong(payload, OFFSET_VOLUME_TRADED_FOR_DAY, MIN_QUOTE_BINARY_LENGTH);

            Long totalBuyQuantity = roundedQuantity(
                    readOptionalDouble(payload, OFFSET_TOTAL_BUY_QUANTITY, MIN_QUOTE_BINARY_LENGTH));
            Long totalSellQuantity = roundedQuantity(
                    readOptionalDouble(payload, OFFSET_TOTAL_SELL_QUANTITY, MIN_QUOTE_BINARY_LENGTH));

            Double openPrice = scaledPrice(readOptionalLong(payload, OFFSET_OPEN_PRICE, MIN_QUOTE_BINARY_LENGTH));
            Double highPrice = scaledPrice(readOptionalLong(payload, OFFSET_HIGH_PRICE, MIN_QUOTE_BINARY_LENGTH));
            Double lowPrice = scaledPrice(readOptionalLong(payload, OFFSET_LOW_PRICE, MIN_QUOTE_BINARY_LENGTH));
            Double closePrice = scaledPrice(readOptionalLong(payload, OFFSET_CLOSE_PRICE, MIN_QUOTE_BINARY_LENGTH));

            Long lastTradedTimestamp = readOptionalLong(payload, OFFSET_LAST_TRADED_TIMESTAMP,
                    MIN_SNAP_QUOTE_BINARY_LENGTH);
            Long openInterest = readOptionalLong(payload, OFFSET_OPEN_INTEREST, MIN_SNAP_QUOTE_BINARY_LENGTH);
            Double openInterestChangePercentRaw = readOptionalDouble(payload, OFFSET_OPEN_INTEREST_CHANGE_PERCENT,
                    MIN_SNAP_QUOTE_BINARY_LENGTH);

            Double upperCircuitLimit = scaledPrice(
                    readOptionalLong(payload, OFFSET_UPPER_CIRCUIT_LIMIT, MIN_SNAP_QUOTE_BINARY_LENGTH));
            Double lowerCircuitLimit = scaledPrice(
                    readOptionalLong(payload, OFFSET_LOWER_CIRCUIT_LIMIT, MIN_SNAP_QUOTE_BINARY_LENGTH));
            Double fiftyTwoWeekHighPrice = scaledPrice(
                    readOptionalLong(payload, OFFSET_FIFTY_TWO_WEEK_HIGH, MIN_SNAP_QUOTE_BINARY_LENGTH));
            Double fiftyTwoWeekLowPrice = scaledPrice(
                    readOptionalLong(payload, OFFSET_FIFTY_TWO_WEEK_LOW, MIN_SNAP_QUOTE_BINARY_LENGTH));

            return Optional.of(new NormalizedTick(
                    instrument.getSymbol(),
                    instrument.getExchange(),
                    tickTime,
                    lastPrice,
                    lastTradedQuantity,
                    brokerToken,
                    subscriptionMode,
                    exchangeType,
                    sequenceNumber,
                    exchangeTimestamp,
                    lastPriceRaw,
                    openPrice,
                    highPrice,
                    lowPrice,
                    closePrice,
                    averageTradedPrice,
                    volumeTradedForDay,
                    totalBuyQuantity,
                    totalSellQuantity,
                    lastTradedTimestamp,
                    openInterest,
                    openInterestChangePercentRaw,
                    upperCircuitLimit,
                    lowerCircuitLimit,
                    fiftyTwoWeekHighPrice,
                    fiftyTwoWeekLowPrice));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private String text(JsonNode root, String... fields) {
        for (String field : fields) {
            JsonNode node = root.get(field);
            if (node != null && !node.isNull()) {
                return node.asText();
            }
        }
        return null;
    }

    private Double number(JsonNode root, String... fields) {
        for (String field : fields) {
            JsonNode node = root.get(field);
            if (node != null && node.isNumber()) {
                return node.asDouble();
            }
        }
        return null;
    }

    private Long longValue(JsonNode root, String... fields) {
        for (String field : fields) {
            JsonNode node = root.get(field);
            if (node != null && node.isNumber()) {
                return node.asLong();
            }
        }
        return null;
    }

    private Integer intValue(JsonNode root, String... fields) {
        for (String field : fields) {
            JsonNode node = root.get(field);
            if (node != null && node.isNumber()) {
                return node.asInt();
            }
        }
        return null;
    }

    private int unsignedByte(byte value) {
        return value & 0xFF;
    }

    private String readAsciiToken(byte[] payload, int offset, int length) {
        int end = offset;
        int max = Math.min(payload.length, offset + length);

        while (end < max && payload[end] != 0) {
            end++;
        }

        return new String(payload, offset, end - offset, StandardCharsets.US_ASCII).trim();
    }

    private long readLittleEndianLong(byte[] payload, int offset, int length) {
        long value = 0L;
        int max = Math.min(length, 8);

        for (int i = 0; i < max && offset + i < payload.length; i++) {
            value |= ((long) payload[offset + i] & 0xFFL) << (8 * i);
        }

        return value;
    }

    private Double readLittleEndianDouble(byte[] payload, int offset) {
        long bits = readLittleEndianLong(payload, offset, LENGTH_LONG);
        return Double.longBitsToDouble(bits);
    }

    private Long readOptionalLong(byte[] payload, int offset, int minimumPayloadLength) {
        if (payload.length < minimumPayloadLength) {
            return null;
        }
        return readLittleEndianLong(payload, offset, LENGTH_LONG);
    }

    private Double readOptionalDouble(byte[] payload, int offset, int minimumPayloadLength) {
        if (payload.length < minimumPayloadLength) {
            return null;
        }
        return readLittleEndianDouble(payload, offset);
    }

    private Double scaledPrice(Long rawValue) {
        if (rawValue == null || rawValue <= 0L) {
            return null;
        }
        return rawValue / 100.0;
    }

    private Long roundedQuantity(Double value) {
        if (value == null || value.isNaN() || value.isInfinite() || value < 0) {
            return null;
        }
        return Math.round(value);
    }

    public record NormalizedTick(
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
            Double fiftyTwoWeekLowPrice) {
    }
}