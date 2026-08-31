package com.trading.scanner.service.instrument;

import com.trading.scanner.config.provider.AngelOneProperties;
import com.trading.scanner.model.InstrumentMaster;
import com.trading.scanner.repository.InstrumentMasterRepository;
import com.trading.scanner.service.provider.ProviderException;
import com.trading.scanner.service.provider.angelone.AngelOneApiExecutor;
import com.trading.scanner.service.provider.angelone.AngelOneSessionService;
import com.trading.scanner.service.provider.angelone.dto.AngelOneAuthDtos;
import com.trading.scanner.service.provider.angelone.dto.AngelOneMarketDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class InstrumentTokenSyncService {

    private final InstrumentMasterRepository instrumentMasterRepository;
    private final AngelOneProperties angelOneProperties;
    private final AngelOneSessionService angelOneSessionService;
    private final AngelOneApiExecutor angelOneApiExecutor;
    private final SymbolAliasService symbolAliasService;

    @Transactional
    public TokenSyncResult syncActiveInstruments() {
        if (!angelOneProperties.enabled()) {
            return new TokenSyncResult(
                    0, 0, 0, 0, List.of(),
                    "Angel One provider is disabled");
        }

        return syncAngelOneTokens(false);
    }

    @Transactional
    public TokenSyncResult syncAngelOneTokens(boolean onlyMissing) {
        if (!angelOneProperties.enabled()) {
            throw new ProviderException(
                    "Angel One provider is disabled. Set ANGELONE_ENABLED=true");
        }

        List<InstrumentMaster> allActive = instrumentMasterRepository.findByIsActiveTrueOrderBySymbolAsc();

        List<InstrumentMaster> targets = allActive.stream()
                .filter(instrument -> !onlyMissing
                        || isBlank(instrument.getBrokerToken()))
                .toList();

        if (targets.isEmpty()) {
            return new TokenSyncResult(
                    allActive.size(), 0, 0, 0, List.of(),
                    "No instruments need token sync");
        }

        String jwt = angelOneSessionService
                .createSessionTokens()
                .jwtToken();

        int mapped = 0;
        int failed = 0;
        int unchanged = 0;
        List<String> unmapped = new ArrayList<>();

        for (InstrumentMaster instrument : targets) {
            try {
                SearchResult resolved = resolveWithRetry(jwt, instrument);

                if (resolved == null) {
                    failed++;
                    unmapped.add(instrument.getSymbol());
                    continue;
                }

                if (apply(instrument, resolved)) {
                    instrumentMasterRepository.save(instrument);
                    mapped++;
                } else {
                    unchanged++;
                }

                sleepQuietly(200L);
            } catch (Exception ex) {
                failed++;
                unmapped.add(instrument.getSymbol());
                log.warn("Token sync failed for symbol={} exchange={}: {}",
                        instrument.getSymbol(),
                        instrument.getExchange(),
                        ex.getMessage());
            }
        }

        log.info("Angel One token sync completed. total={} attempted={} mapped={} unchanged={} failed={}",
                allActive.size(), targets.size(), mapped, unchanged, failed);

        return new TokenSyncResult(
                allActive.size(),
                targets.size(),
                mapped,
                failed,
                unmapped,
                "Token sync completed");
    }

    private SearchResult resolveWithRetry(
            String jwt,
            InstrumentMaster instrument) {

        for (int attempt = 1; attempt <= 3; attempt++) {
            SearchResult result = searchAndResolve(jwt, instrument);
            if (result != null) {
                return result;
            }
            sleepQuietly(250L * attempt);
        }

        return null;
    }

    private SearchResult searchAndResolve(
            String jwt,
            InstrumentMaster instrument) {

        String lookup = symbolAliasService
                .resolveLookupSymbol(instrument.getSymbol());

        AngelOneMarketDtos.AngelOneSearchScripResponse response = callSearchScrip(jwt, instrument.getExchange(),
                lookup);

        if (response.status() == null
                || !response.status()
                || response.data() == null) {
            return null;
        }

        String expected = lookup.trim().toUpperCase(Locale.ROOT);

        return response.data().stream()
                .filter(item -> instrument.getExchange()
                        .equalsIgnoreCase(item.exchange()))
                .filter(item -> {
                    String tradingSymbol = item.tradingsymbol();
                    return (expected + "-EQ").equalsIgnoreCase(tradingSymbol)
                            || expected.equalsIgnoreCase(tradingSymbol);
                })
                .findFirst()
                .map(SearchResult::new)
                .orElse(null);
    }

    private boolean apply(
            InstrumentMaster instrument,
            SearchResult result) {

        var item = result.item();
        boolean changed = false;

        changed |= set(instrument.getBrokerSymbol(), item.tradingsymbol(),
                instrument::setBrokerSymbol);
        changed |= set(instrument.getBrokerToken(), item.symboltoken(),
                instrument::setBrokerToken);

        if (!blank(item.instrumentType())
                && !item.instrumentType().equals(instrument.getInstrumentType())) {
            instrument.setInstrumentType(item.instrumentType());
            changed = true;
        }

        Integer lotSize = integerValue(item.lotSize());
        if (lotSize != null && !lotSize.equals(instrument.getLotSize())) {
            instrument.setLotSize(lotSize);
            changed = true;
        }

        Double tickSize = doubleValue(item.tickSize());
        if (tickSize != null && !tickSize.equals(instrument.getTickSize())) {
            instrument.setTickSize(tickSize);
            changed = true;
        }

        Double strike = doubleValue(item.strike());
        if (strike != null && !strike.equals(instrument.getStrikePrice())) {
            instrument.setStrikePrice(strike);
            changed = true;
        }

        if (!blank(item.expiry()) && !item.expiry().equals(instrument.getExpiry())) {
            instrument.setExpiry(item.expiry());
            changed = true;
        }

        if (!"ANGEL_ONE_SEARCH_SCRIP".equals(instrument.getMetadataSource())) {
            instrument.setMetadataSource("ANGEL_ONE_SEARCH_SCRIP");
            changed = true;
        }

        return changed;
    }

    private AngelOneMarketDtos.AngelOneSearchScripResponse callSearchScrip(
            String jwtToken,
            String exchange,
            String symbol) {

        String url = angelOneProperties.baseUrl()
                + "/rest/secure/angelbroking/order/v1/searchScrip";

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        headers.put("Authorization", "Bearer " + jwtToken);
        headers.put("X-UserType", "USER");
        headers.put("X-SourceID", "WEB");
        headers.put("X-ClientLocalIP", angelOneProperties.clientLocalIp());
        headers.put("X-ClientPublicIP", angelOneProperties.clientPublicIp());
        headers.put("X-MACAddress", angelOneProperties.macAddress());
        headers.put("X-PrivateKey", angelOneProperties.apiKey());

        return angelOneApiExecutor.postJson(
                AngelOneApiExecutor.ApiEndpoint.SEARCH_SCRIP,
                url,
                headers,
                new AngelOneMarketDtos.AngelOneSearchScripRequest(exchange, symbol),
                AngelOneMarketDtos.AngelOneSearchScripResponse.class);
    }

    @Transactional(readOnly = true)
    public String debugSearchScrip(String exchange, String symbol) {
        if (!angelOneProperties.enabled()) {
            throw new ProviderException(
                    "Angel One provider is disabled. Set ANGELONE_ENABLED=true");
        }

        String jwt = angelOneSessionService.createSessionTokens().jwtToken();
        String lookup = symbolAliasService.resolveLookupSymbol(symbol);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        headers.put("Authorization", "Bearer " + jwt);
        headers.put("X-UserType", "USER");
        headers.put("X-SourceID", "WEB");
        headers.put("X-ClientLocalIP", angelOneProperties.clientLocalIp());
        headers.put("X-ClientPublicIP", angelOneProperties.clientPublicIp());
        headers.put("X-MACAddress", angelOneProperties.macAddress());
        headers.put("X-PrivateKey", angelOneProperties.apiKey());

        return angelOneApiExecutor.postJsonForBody(
                AngelOneApiExecutor.ApiEndpoint.SEARCH_SCRIP,
                angelOneProperties.baseUrl()
                        + "/rest/secure/angelbroking/order/v1/searchScrip",
                headers,
                new AngelOneMarketDtos.AngelOneSearchScripRequest(exchange, lookup));
    }

    private boolean set(
            String current,
            String next,
            java.util.function.Consumer<String> setter) {

        if (blank(next) || next.equals(current)) {
            return false;
        }

        setter.accept(next);
        return true;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private boolean isBlank(String value) {
        return blank(value);
    }

    private Integer integerValue(String value) {
        try {
            return blank(value) ? null : Integer.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Double doubleValue(String value) {
        try {
            return blank(value) ? null : Double.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ProviderException(
                    "Interrupted during Angel One token sync delay", ex);
        }
    }

    private record SearchResult(
            AngelOneMarketDtos.AngelOneSearchScripResponse.AngelOneSearchScripItem item) {
    }

    public record TokenSyncResult(
            int totalActiveInstruments,
            int attempted,
            int mapped,
            int failed,
            List<String> unmappedSymbols,
            String message) {
    }
}
