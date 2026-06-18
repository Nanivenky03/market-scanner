package com.trading.scanner.service.instrument;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Service
public class SymbolAliasService {

    private final Map<String, String> aliases = new LinkedHashMap<>();
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    @PostConstruct
    public void load() {
        aliases.clear();

        try {
            ClassPathResource resource = new ClassPathResource("bootstrap/symbol-aliases.yaml");
            if (!resource.exists()) {
                return;
            }

            try (InputStream in = resource.getInputStream()) {
                JsonNode root = yamlMapper.readTree(in);
                JsonNode aliasNode = root.get("aliases");
                if (aliasNode != null && aliasNode.isObject()) {
                    aliasNode.fields().forEachRemaining(entry -> aliases.put(
                            normalize(entry.getKey()),
                            normalize(entry.getValue().asText())));
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load symbol aliases", ex);
        }
    }

    public String resolveLookupSymbol(String symbol) {
        String normalized = normalize(symbol);
        return aliases.getOrDefault(normalized, normalized);
    }

    public Map<String, String> allAliases() {
        return Map.copyOf(aliases);
    }

    private String normalize(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}