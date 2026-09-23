package com.trading.scanner.service.instrument;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.scanner.config.provider.AngelOneProperties;
import com.trading.scanner.model.InstrumentMaster;
import com.trading.scanner.repository.InstrumentMasterRepository;
import com.trading.scanner.service.provider.ProviderException;
import com.trading.scanner.service.provider.angelone.AngelOneApiExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
public class AngelOneInstrumentCatalogSyncService {

    private static final String NSE = "NSE";

    private static final String SOURCE = "SMARTAPI_SCRIP_MASTER";

    private final ObjectMapper objectMapper;
    private final AngelOneApiExecutor apiExecutor;
    private final AngelOneProperties angelOneProperties;
    private final InstrumentMasterRepository instrumentMasterRepository;
    private final com.trading.scanner.service.provider.angelone.AngelOneTickParserService angelOneTickParserService;

    @Value("${provider.angelone.instrument-master-url:https://margincalculator.angelbroking.com/OpenAPI_File/files/OpenAPIScripMaster.json}")
    private String instrumentMasterUrl;

    @Autowired
    public AngelOneInstrumentCatalogSyncService(
            ObjectMapper objectMapper,
            AngelOneApiExecutor apiExecutor,
            AngelOneProperties angelOneProperties,
            InstrumentMasterRepository instrumentMasterRepository,
            @Autowired(required = false) com.trading.scanner.service.provider.angelone.AngelOneTickParserService angelOneTickParserService) {
        this.objectMapper = objectMapper;
        this.apiExecutor = apiExecutor;
        this.angelOneProperties = angelOneProperties;
        this.instrumentMasterRepository = instrumentMasterRepository;
        this.angelOneTickParserService = angelOneTickParserService;
    }

    public AngelOneInstrumentCatalogSyncService(
            ObjectMapper objectMapper,
            AngelOneApiExecutor apiExecutor,
            AngelOneProperties angelOneProperties,
            InstrumentMasterRepository instrumentMasterRepository) {
        this(objectMapper, apiExecutor, angelOneProperties, instrumentMasterRepository, null);
    }

    @Transactional
    public CatalogSyncResult syncNseCatalog() {
        if (!angelOneProperties.enabled()) {
            throw new ProviderException(
                    "Angel One provider is disabled");
        }

        String body = apiExecutor.getJsonForBody(
                AngelOneApiExecutor.ApiEndpoint.INSTRUMENT_MASTER,
                instrumentMasterUrl,
                Map.of(
                        "Accept",
                        "application/json"));

        JsonNode root;

        try {
            root = objectMapper.readTree(body);
        } catch (Exception ex) {
            throw new ProviderException(
                    "Could not parse SmartAPI instrument master JSON",
                    ex);
        }

        if (root == null
                || !root.isArray()) {
            throw new ProviderException(
                    "SmartAPI instrument master response is not an array");
        }

        Map<String, InstrumentMaster> existing = new HashMap<>();

        for (InstrumentMaster instrument : instrumentMasterRepository.findAll()) {

            if (instrument == null
                    || instrument.getSymbol() == null
                    || instrument.getExchange() == null) {
                continue;
            }

            existing.put(
                    key(
                            instrument.getSymbol(),
                            instrument.getExchange()),
                    instrument);
        }

        Map<String, CatalogInstrument> unique = new LinkedHashMap<>();

        int totalRecords = root.size();

        int skippedRecords = 0;

        Iterator<JsonNode> iterator = root.elements();

        while (iterator.hasNext()) {
            JsonNode node = iterator.next();

            CatalogInstrument instrument = parseNseInstrument(node);

            if (instrument == null) {
                skippedRecords++;
                continue;
            }

            unique.putIfAbsent(
                    key(
                            instrument.symbol(),
                            instrument.exchange()),
                    instrument);
        }

        List<InstrumentMaster> changed = new ArrayList<>();

        int inserted = 0;
        int updated = 0;

        for (CatalogInstrument catalog : unique.values()) {

            String key = key(
                    catalog.symbol(),
                    catalog.exchange());

            InstrumentMaster target = existing.get(key);

            if (target == null) {
                target = InstrumentMaster.builder()
                        .symbol(catalog.symbol())
                        .exchange(
                                catalog.exchange())
                        .build();

                inserted++;
            } else {
                updated++;
            }

            boolean wasChanged = apply(
                    target,
                    catalog);

            if (wasChanged
                    || target.getId() == null) {
                changed.add(target);
            }
        }

        if (!changed.isEmpty()) {
            instrumentMasterRepository.saveAll(changed);
        }

        if (angelOneTickParserService != null) {
            angelOneTickParserService.refreshCache();
        }

        CatalogSyncResult result = new CatalogSyncResult(
                totalRecords,
                unique.size(),
                inserted,
                updated,
                skippedRecords,
                unique.containsKey(
                        key(
                                "NIFTY",
                                NSE)),
                "SmartAPI NSE instrument catalog synchronized");

        log.info(
                "SmartAPI NSE instrument catalog synchronized: {}",
                result);

        return result;
    }

    private CatalogInstrument parseNseInstrument(
            JsonNode node) {

        if (node == null
                || !NSE.equalsIgnoreCase(
                        text(
                                node,
                                "exch_seg"))) {
            return null;
        }

        String providerSymbol = text(
                node,
                "symbol");

        String name = text(
                node,
                "name");

        String instrumentType = text(
                node,
                "instrumenttype");

        if (isBlank(providerSymbol)) {
            return null;
        }

        boolean index = "AMXIDX".equalsIgnoreCase(
                instrumentType)
                || "INDEX".equalsIgnoreCase(
                        instrumentType);

        boolean equity = "EQ".equalsIgnoreCase(
                instrumentType)
                || "EQUITY".equalsIgnoreCase(
                        instrumentType)
                || providerSymbol
                        .toUpperCase(Locale.ROOT)
                        .endsWith("-EQ");

        if (!index && !equity) {
            return null;
        }

        String applicationSymbol;

        if (index) {
            applicationSymbol = !isBlank(name)
                    ? name
                    : providerSymbol;
        } else {
            applicationSymbol = !isBlank(name)
                    ? name
                    : stripEquitySuffix(
                            providerSymbol);
        }

        applicationSymbol = normalize(applicationSymbol);

        if (isBlank(applicationSymbol)) {
            return null;
        }

        String token = text(
                node,
                "token");

        if (isBlank(token)) {
            return null;
        }

        return new CatalogInstrument(
                applicationSymbol,
                NSE,
                !isBlank(name)
                        ? name
                        : applicationSymbol,
                providerSymbol,
                token,
                index
                        ? "INDEX"
                        : "EQUITY",
                index
                        ? "INDEX"
                        : "CASH",
                text(
                        node,
                        "isin"),
                doubleValue(
                        node,
                        "tick_size"),
                integerValue(
                        node,
                        "lotsize"),
                text(
                        node,
                        "expiry"),
                doubleValue(
                        node,
                        "strike"));
    }

    private boolean apply(
            InstrumentMaster target,
            CatalogInstrument source) {

        boolean changed = false;

        changed |= set(
                target.getSymbol(),
                source.symbol(),
                target::setSymbol);

        changed |= set(
                target.getExchange(),
                source.exchange(),
                target::setExchange);

        changed |= set(
                target.getCompanyName(),
                source.companyName(),
                target::setCompanyName);

        changed |= set(
                target.getBrokerSymbol(),
                source.brokerSymbol(),
                target::setBrokerSymbol);

        changed |= set(
                target.getBrokerToken(),
                source.brokerToken(),
                target::setBrokerToken);

        changed |= set(
                target.getInstrumentType(),
                source.instrumentType(),
                target::setInstrumentType);

        changed |= set(
                target.getSegment(),
                source.segment(),
                target::setSegment);

        changed |= set(
                target.getIsin(),
                source.isin(),
                target::setIsin);

        changed |= set(
                target.getTickSize(),
                source.tickSize(),
                target::setTickSize);

        changed |= set(
                target.getLotSize(),
                source.lotSize(),
                target::setLotSize);

        changed |= set(
                target.getExpiry(),
                source.expiry(),
                target::setExpiry);

        changed |= set(
                target.getStrikePrice(),
                source.strikePrice(),
                target::setStrikePrice);

        if (!Boolean.TRUE.equals(
                target.getIsActive())) {
            target.setIsActive(true);
            changed = true;
        }

        if (!SOURCE.equals(
                target.getMetadataSource())) {
            target.setMetadataSource(SOURCE);
            changed = true;
        }

        return changed;
    }

    private boolean set(
            String current,
            String next,
            java.util.function.Consumer<String> setter) {

        if (isBlank(next)
                || next.equals(current)) {
            return false;
        }

        setter.accept(next);
        return true;
    }

    private boolean set(
            Double current,
            Double next,
            java.util.function.Consumer<Double> setter) {

        if (next == null
                || next.equals(current)) {
            return false;
        }

        setter.accept(next);
        return true;
    }

    private boolean set(
            Integer current,
            Integer next,
            java.util.function.Consumer<Integer> setter) {

        if (next == null
                || next.equals(current)) {
            return false;
        }

        setter.accept(next);
        return true;
    }

    private String text(
            JsonNode node,
            String field) {

        JsonNode value = node.get(field);

        return value == null
                || value.isNull()
                        ? null
                        : value.asText();
    }

    private Double doubleValue(
            JsonNode node,
            String field) {

        String value = text(node, field);

        try {
            return isBlank(value)
                    ? null
                    : Double.valueOf(
                            value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Integer integerValue(
            JsonNode node,
            String field) {

        String value = text(node, field);

        try {
            return isBlank(value)
                    ? null
                    : Integer.valueOf(
                            value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String stripEquitySuffix(
            String value) {

        String normalized = normalize(value);

        return normalized.endsWith("-EQ")
                ? normalized.substring(
                        0,
                        normalized.length() - 3)
                : normalized;
    }

    private String normalize(
            String value) {

        return value == null
                ? null
                : value.trim()
                        .toUpperCase(Locale.ROOT);
    }

    private String key(
            String symbol,
            String exchange) {

        return normalize(exchange)
                + "|"
                + normalize(symbol);
    }

    private boolean isBlank(
            String value) {

        return value == null
                || value.isBlank();
    }

    private record CatalogInstrument(
            String symbol,
            String exchange,
            String companyName,
            String brokerSymbol,
            String brokerToken,
            String instrumentType,
            String segment,
            String isin,
            Double tickSize,
            Integer lotSize,
            String expiry,
            Double strikePrice) {
    }

    public record CatalogSyncResult(
            int providerRecords,
            int importedRecords,
            int inserted,
            int updated,
            int skipped,
            boolean niftyImported,
            String message) {
    }
}
