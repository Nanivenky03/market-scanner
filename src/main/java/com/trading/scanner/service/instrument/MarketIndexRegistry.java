package com.trading.scanner.service.instrument;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Authoritative registry of active market, sector, thematic, and volatility indices.
 * Provides canonical symbol normalization, token lookups, and aliases to ensure 100%
 * sync between Angel One SmartAPI, NSE official indices, WebSocket subscriptions,
 * and database models.
 */
@Component
public class MarketIndexRegistry {

    public enum IndexCategory {
        BENCHMARK,
        SECTORAL,
        THEMATIC,
        VOLATILITY
    }

    public record IndexDefinition(
            String canonicalSymbol,
            String brokerToken,
            String providerSymbol,
            String exchange,
            IndexCategory category,
            List<String> aliases
    ) {
        public IndexDefinition(String canonicalSymbol, String brokerToken, String providerSymbol,
                               IndexCategory category, String... aliases) {
            this(canonicalSymbol, brokerToken, providerSymbol, "NSE", category,
                    aliases != null ? Arrays.asList(aliases) : Collections.emptyList());
        }
    }

    private final Map<String, IndexDefinition> byCanonicalSymbol = new LinkedHashMap<>();
    private final Map<String, IndexDefinition> byToken = new HashMap<>();
    private final Map<String, IndexDefinition> lookupByAnyName = new HashMap<>();

    public MarketIndexRegistry() {
        registerAllIndices();
    }

    private void register(IndexDefinition def) {
        byCanonicalSymbol.put(def.canonicalSymbol().toUpperCase(Locale.ROOT), def);
        if (def.brokerToken() != null && !def.brokerToken().isBlank()) {
            byToken.put(def.brokerToken().trim(), def);
        }
        lookupByAnyName.put(normalize(def.canonicalSymbol()), def);
        lookupByAnyName.put(normalize(def.providerSymbol()), def);
        for (String alias : def.aliases()) {
            lookupByAnyName.put(normalize(alias), def);
        }
    }

    private void registerAllIndices() {
        // 1. Broad Market Benchmarks
        register(new IndexDefinition("NIFTY", "99926000", "Nifty 50", IndexCategory.BENCHMARK, "NIFTY 50", "Nifty 50"));
        register(new IndexDefinition("NIFTY NEXT 50", "99926013", "Nifty Next 50", IndexCategory.BENCHMARK, "NIFTYNXT50"));
        register(new IndexDefinition("NIFTY 100", "99926012", "Nifty 100", IndexCategory.BENCHMARK));
        register(new IndexDefinition("NIFTY 200", "99926033", "Nifty 200", IndexCategory.BENCHMARK));
        register(new IndexDefinition("NIFTY 500", "99926004", "Nifty 500", IndexCategory.BENCHMARK));
        register(new IndexDefinition("NIFTY MIDCAP 50", "99926014", "Nifty Midcap 50", IndexCategory.BENCHMARK));
        register(new IndexDefinition("NIFTY MIDCAP 100", "99926011", "NIFTY MIDCAP 100", IndexCategory.BENCHMARK));
        register(new IndexDefinition("NIFTY SMLCAP 100", "99926032", "NIFTY SMLCAP 100", IndexCategory.BENCHMARK, "NIFTY SMALLCAP 100"));
        register(new IndexDefinition("MIDCPNIFTY", "99926074", "NIFTY MID SELECT", IndexCategory.BENCHMARK, "NIFTY MID SELECT"));

        // 2. Core Sectoral Indices
        register(new IndexDefinition("NIFTY BANK", "99926009", "Nifty Bank", IndexCategory.SECTORAL, "BANKNIFTY"));
        register(new IndexDefinition("NIFTY IT", "99926008", "Nifty IT", IndexCategory.SECTORAL, "NIFTYIT"));
        register(new IndexDefinition("NIFTY AUTO", "99926029", "Nifty Auto", IndexCategory.SECTORAL, "NIFTYAUTO"));
        register(new IndexDefinition("NIFTY METAL", "99926030", "Nifty Metal", IndexCategory.SECTORAL, "NIFTYMETAL"));
        register(new IndexDefinition("NIFTY PHARMA", "99926023", "Nifty Pharma", IndexCategory.SECTORAL, "NIFTYPHARMA"));
        register(new IndexDefinition("NIFTY FMCG", "99926021", "Nifty FMCG", IndexCategory.SECTORAL, "NIFTYFMCG"));
        register(new IndexDefinition("NIFTY ENERGY", "99926020", "Nifty Energy", IndexCategory.SECTORAL, "NIFTYENERGY"));
        register(new IndexDefinition("NIFTY INFRA", "99926019", "Nifty Infra", IndexCategory.SECTORAL, "NIFTYINFRA"));
        register(new IndexDefinition("NIFTY REALTY", "99926018", "Nifty Realty", IndexCategory.SECTORAL, "NIFTYREALTY"));
        register(new IndexDefinition("NIFTY MEDIA", "99926031", "Nifty Media", IndexCategory.SECTORAL, "NIFTYMEDIA"));
        register(new IndexDefinition("NIFTY FIN SERVICE", "99926037", "Nifty Fin Service", IndexCategory.SECTORAL, "FINNIFTY", "NIFTY FINANCIAL SERVICES"));
        register(new IndexDefinition("NIFTY PSU BANK", "99926025", "Nifty PSU Bank", IndexCategory.SECTORAL, "NIFTYPSUBANK"));
        register(new IndexDefinition("NIFTY PVT BANK", "99926047", "Nifty Pvt Bank", IndexCategory.SECTORAL, "NIFTYPVTBANK", "NIFTY PRIVATE BANK"));

        // 3. Thematic Indices
        register(new IndexDefinition("NIFTY COMMODITIES", "99926035", "Nifty Commodities", IndexCategory.THEMATIC));
        register(new IndexDefinition("NIFTY CONSUMPTION", "99926036", "Nifty Consumption", IndexCategory.THEMATIC));
        register(new IndexDefinition("NIFTY SERV SECTOR", "99926026", "Nifty Serv Sector", IndexCategory.THEMATIC, "NIFTY SERVICES"));
        register(new IndexDefinition("NIFTY PSE", "99926024", "Nifty PSE", IndexCategory.THEMATIC));
        register(new IndexDefinition("NIFTY CPSE", "99926041", "Nifty CPSE", IndexCategory.THEMATIC));
        register(new IndexDefinition("NIFTY MNC", "99926022", "Nifty MNC", IndexCategory.THEMATIC));

        // 4. Market Volatility Index
        register(new IndexDefinition("INDIA VIX", "99926017", "India VIX", IndexCategory.VOLATILITY, "INDIAVIX"));
    }

    public List<IndexDefinition> getAllRegisteredIndices() {
        return new ArrayList<>(byCanonicalSymbol.values());
    }

    public Optional<IndexDefinition> getByCanonicalSymbol(String symbol) {
        if (symbol == null) return Optional.empty();
        return Optional.ofNullable(byCanonicalSymbol.get(symbol.toUpperCase(Locale.ROOT)));
    }

    public Optional<IndexDefinition> getByToken(String token) {
        if (token == null) return Optional.empty();
        return Optional.ofNullable(byToken.get(token.trim()));
    }

    public Optional<IndexDefinition> resolve(String rawSymbolOrName) {
        if (rawSymbolOrName == null) return Optional.empty();
        return Optional.ofNullable(lookupByAnyName.get(normalize(rawSymbolOrName)));
    }

    public String resolveCanonicalSymbol(String rawSymbolOrName, String defaultSymbol) {
        return resolve(rawSymbolOrName)
                .map(IndexDefinition::canonicalSymbol)
                .orElse(defaultSymbol);
    }

    public boolean isRegisteredIndex(String symbol) {
        if (symbol == null) return false;
        return byCanonicalSymbol.containsKey(symbol.toUpperCase(Locale.ROOT))
                || lookupByAnyName.containsKey(normalize(symbol));
    }

    private String normalize(String value) {
        if (value == null) return "";
        return value.trim()
                .replaceAll("\\s+", " ")
                .toUpperCase(Locale.ROOT);
    }
}
