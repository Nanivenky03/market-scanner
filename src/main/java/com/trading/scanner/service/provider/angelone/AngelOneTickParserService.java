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
            Double lastPrice = number(root, "lastPrice");
            Long lastQty = longValue(root, "lastTradedQuantity");

            if (symbol == null || exchange == null || lastPrice == null) {
                return Optional.empty();
            }

            return Optional.of(new NormalizedTick(
                    symbol.trim().toUpperCase(Locale.ROOT),
                    exchange.trim().toUpperCase(Locale.ROOT),
                    LocalDateTime.now(INDIA_ZONE),
                    lastPrice,
                    lastQty,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    public Optional<NormalizedTick> tryParseBinary(byte[] payload) {
        if (payload == null || payload.length < 51) {
            return Optional.empty();
        }

        try {
            int subscriptionMode = unsignedByte(payload[0]);
            int exchangeType = unsignedByte(payload[1]);

            String brokerToken = readAsciiToken(payload, 2, 25);
            if (brokerToken == null || brokerToken.isBlank()) {
                return Optional.empty();
            }

            long sequenceNumber = readLittleEndianLong(payload, 27, 8);
            long exchangeTimestamp = readLittleEndianLong(payload, 35, 8);
            long lastPriceRaw = readLittleEndianLong(payload, 43, 8);

            Optional<InstrumentMaster> instrumentOpt = instrumentMasterRepository.findByBrokerToken(brokerToken);
            if (instrumentOpt.isEmpty()) {
                return Optional.empty();
            }

            InstrumentMaster instrument = instrumentOpt.get();

            LocalDateTime tickTime = exchangeTimestamp > 0
                    ? Instant.ofEpochMilli(exchangeTimestamp).atZone(INDIA_ZONE).toLocalDateTime()
                    : LocalDateTime.now(INDIA_ZONE);

            Double lastPrice = lastPriceRaw > 0 ? (lastPriceRaw / 100.0) : null;

            return Optional.of(new NormalizedTick(
                    instrument.getSymbol(),
                    instrument.getExchange(),
                    tickTime,
                    lastPrice,
                    null,
                    brokerToken,
                    subscriptionMode,
                    exchangeType,
                    sequenceNumber,
                    exchangeTimestamp,
                    lastPriceRaw));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node != null && !node.isNull() ? node.asText() : null;
    }

    private Double number(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node != null && node.isNumber() ? node.asDouble() : null;
    }

    private Long longValue(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node != null && node.isNumber() ? node.asLong() : null;
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

        for (int i = 0; i < max; i++) {
            value |= ((long) payload[offset + i] & 0xFFL) << (8 * i);
        }

        return value;
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
            Long lastPriceRaw) {
    }
}