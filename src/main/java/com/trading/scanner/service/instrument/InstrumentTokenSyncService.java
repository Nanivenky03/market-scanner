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
    public TokenSyncResult syncAngelOneTokens(boolean onlyMissing) {
        if (!angelOneProperties.enabled()) {
            throw new ProviderException("Angel One provider is disabled. Set ANGELONE_ENABLED=true");
        }

        List<InstrumentMaster> allActive = instrumentMasterRepository.findByIsActiveTrueOrderBySymbolAsc();
        if (allActive.isEmpty()) {
            return new TokenSyncResult(0, 0, 0, 0, List.of(), "No active instruments found");
        }

        List<InstrumentMaster> targetList = allActive.stream()
                .filter(im -> !onlyMissing || isBlank(im.getBrokerToken()))
                .toList();

        if (targetList.isEmpty()) {
            return new TokenSyncResult(allActive.size(), 0, 0, 0, List.of(), "No instruments need token sync");
        }

        AngelOneAuthDtos.AngelOneSessionTokens sessionTokens = angelOneSessionService.createSessionTokens();

        int mapped = 0;
        int failed = 0;
        int unchanged = 0;
        List<String> unmappedSymbols = new ArrayList<>();

        for (InstrumentMaster instrument : targetList) {
            try {
                SearchResult resolved = null;
                Exception lastException = null;

                for (int attempt = 1; attempt <= 3; attempt++) {
                    try {
                        resolved = searchAndResolve(sessionTokens.jwtToken(), instrument);
                        if (resolved != null) {
                            break;
                        }
                        sleepQuietly(250L * attempt);
                    } catch (Exception ex) {
                        lastException = ex;
                        log.debug("searchScrip attempt {} failed for {}: {}", attempt, instrument.getSymbol(),
                                ex.getMessage());
                        sleepQuietly(400L * attempt);
                    }
                }

                if (resolved == null) {
                    failed++;
                    unmappedSymbols.add(instrument.getSymbol());

                    if (lastException != null) {
                        log.warn("Instrument token sync failed for symbol={} exchange={} after retries: {}",
                                instrument.getSymbol(), instrument.getExchange(), lastException.getMessage());
                    } else {
                        log.warn("Instrument token sync did not find exact match for symbol={} exchange={}",
                                instrument.getSymbol(), instrument.getExchange());
                    }
                    continue;
                }

                boolean changed = false;

                if (!safeEquals(instrument.getBrokerSymbol(), resolved.tradingSymbol())) {
                    instrument.setBrokerSymbol(resolved.tradingSymbol());
                    changed = true;
                }

                if (!safeEquals(instrument.getBrokerToken(), resolved.symbolToken())) {
                    instrument.setBrokerToken(resolved.symbolToken());
                    changed = true;
                }

                if (changed) {
                    instrumentMasterRepository.save(instrument);
                    mapped++;
                } else {
                    unchanged++;
                }

                sleepQuietly(200L);

            } catch (Exception ex) {
                log.warn("Instrument token sync failed for symbol={} exchange={}: {}",
                        instrument.getSymbol(), instrument.getExchange(), ex.getMessage());
                failed++;
                unmappedSymbols.add(instrument.getSymbol());
            }
        }

        log.info("Angel One token sync completed. totalActive={}, attempted={}, mapped={}, unchanged={}, failed={}",
                allActive.size(), targetList.size(), mapped, unchanged, failed);

        return new TokenSyncResult(
                allActive.size(),
                targetList.size(),
                mapped,
                failed,
                unmappedSymbols,
                "Token sync completed");
    }

    private SearchResult searchAndResolve(String jwtToken, InstrumentMaster instrument) {
        String lookupSymbol = symbolAliasService.resolveLookupSymbol(instrument.getSymbol());

        AngelOneMarketDtos.AngelOneSearchScripResponse searchResponse = callSearchScrip(jwtToken,
                instrument.getExchange(), lookupSymbol);

        if (searchResponse.status() == null
                || !searchResponse.status()
                || searchResponse.data() == null
                || searchResponse.data().isEmpty()) {
            return null;
        }

        String expectedSymbol = lookupSymbol.trim().toUpperCase(Locale.ROOT);
        String expectedEqSymbol = expectedSymbol + "-EQ";

        AngelOneMarketDtos.AngelOneSearchScripResponse.AngelOneSearchScripItem exactEqMatch = searchResponse.data()
                .stream()
                .filter(item -> instrument.getExchange().equalsIgnoreCase(item.exchange()))
                .filter(item -> expectedEqSymbol.equalsIgnoreCase(item.tradingsymbol()))
                .findFirst()
                .orElse(null);

        if (exactEqMatch != null) {
            return new SearchResult(exactEqMatch.tradingsymbol(), exactEqMatch.symboltoken());
        }

        AngelOneMarketDtos.AngelOneSearchScripResponse.AngelOneSearchScripItem exactSymbolMatch = searchResponse.data()
                .stream()
                .filter(item -> instrument.getExchange().equalsIgnoreCase(item.exchange()))
                .filter(item -> expectedSymbol.equalsIgnoreCase(item.tradingsymbol()))
                .findFirst()
                .orElse(null);

        if (exactSymbolMatch != null) {
            return new SearchResult(exactSymbolMatch.tradingsymbol(), exactSymbolMatch.symboltoken());
        }

        return null;
    }

    private AngelOneMarketDtos.AngelOneSearchScripResponse callSearchScrip(
            String jwtToken,
            String exchange,
            String symbol) {
        String url = angelOneProperties.baseUrl() + "/rest/secure/angelbroking/order/v1/searchScrip";

        AngelOneMarketDtos.AngelOneSearchScripRequest requestBody = new AngelOneMarketDtos.AngelOneSearchScripRequest(
                exchange, symbol);

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
                requestBody,
                AngelOneMarketDtos.AngelOneSearchScripResponse.class);
    }

    @Transactional(readOnly = true)
    public String debugSearchScrip(String exchange, String symbol) {
        if (!angelOneProperties.enabled()) {
            throw new ProviderException("Angel One provider is disabled. Set ANGELONE_ENABLED=true");
        }

        AngelOneAuthDtos.AngelOneSessionTokens sessionTokens = angelOneSessionService.createSessionTokens();
        String url = angelOneProperties.baseUrl() + "/rest/secure/angelbroking/order/v1/searchScrip";

        String lookupSymbol = symbolAliasService.resolveLookupSymbol(symbol);
        AngelOneMarketDtos.AngelOneSearchScripRequest requestBody = new AngelOneMarketDtos.AngelOneSearchScripRequest(
                exchange, lookupSymbol);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        headers.put("Authorization", "Bearer " + sessionTokens.jwtToken());
        headers.put("X-UserType", "USER");
        headers.put("X-SourceID", "WEB");
        headers.put("X-ClientLocalIP", angelOneProperties.clientLocalIp());
        headers.put("X-ClientPublicIP", angelOneProperties.clientPublicIp());
        headers.put("X-MACAddress", angelOneProperties.macAddress());
        headers.put("X-PrivateKey", angelOneProperties.apiKey());

        return angelOneApiExecutor.postJsonForBody(
                AngelOneApiExecutor.ApiEndpoint.SEARCH_SCRIP,
                url,
                headers,
                requestBody);
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ProviderException("Thread interrupted during Angel One token sync delay", ex);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean safeEquals(String left, String right) {
        if (left == null && right == null) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return left.equals(right);
    }

    private record SearchResult(String tradingSymbol, String symbolToken) {
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