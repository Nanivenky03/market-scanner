package com.trading.scanner.service.provider.angelone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;

@Service
public class AngelOneTickParserService {

    private final ObjectMapper objectMapper = new ObjectMapper();

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
                    LocalDateTime.now(),
                    lastPrice,
                    lastQty));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    public Optional<NormalizedTick> tryParseBinary(byte[] payload) {
        return Optional.empty();
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

    public record NormalizedTick(
            String symbol,
            String exchange,
            LocalDateTime tickTime,
            Double lastPrice,
            Long lastTradedQuantity) {
    }
}