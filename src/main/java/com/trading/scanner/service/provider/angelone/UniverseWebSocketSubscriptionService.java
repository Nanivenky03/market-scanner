package com.trading.scanner.service.provider.angelone;

import com.trading.scanner.config.provider.AngelOneProperties;
import com.trading.scanner.model.StockUniverse;
import com.trading.scanner.repository.InstrumentMasterRepository;
import com.trading.scanner.repository.StockUniverseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class UniverseWebSocketSubscriptionService {

    private final StockUniverseRepository stockUniverseRepository;
    private final InstrumentMasterRepository instrumentMasterRepository;
    private final AngelOneProperties angelOneProperties;
    private final AngelOneWebSocketService angelOneWebSocketService;

    public SubscriptionResult subscribeActiveUniverse(int mode) {
        List<AngelOneWebSocketService.TokenRef> tokenRefs = buildActiveUniverseTokenRefs();

        int maxPerConnection = angelOneProperties.websocketMaxSubscriptionsPerConnection();
        int chunks = 0;
        int subscribed = 0;

        for (int i = 0; i < tokenRefs.size(); i += maxPerConnection) {
            List<AngelOneWebSocketService.TokenRef> chunk = tokenRefs.subList(i,
                    Math.min(i + maxPerConnection, tokenRefs.size()));

            chunks++;
            angelOneWebSocketService.subscribe("active-universe-" + chunks, mode, chunk);
            subscribed += chunk.size();
        }

        return new SubscriptionResult(tokenRefs.size(), subscribed, chunks, "Subscribed active universe");
    }

    public SubscriptionResult unsubscribeActiveUniverse(int mode) {
        List<AngelOneWebSocketService.TokenRef> tokenRefs = buildActiveUniverseTokenRefs();

        int maxPerConnection = angelOneProperties.websocketMaxSubscriptionsPerConnection();
        int chunks = 0;
        int unsubscribed = 0;

        for (int i = 0; i < tokenRefs.size(); i += maxPerConnection) {
            List<AngelOneWebSocketService.TokenRef> chunk = tokenRefs.subList(i,
                    Math.min(i + maxPerConnection, tokenRefs.size()));

            chunks++;
            angelOneWebSocketService.unsubscribe("active-universe-" + chunks, mode, chunk);
            unsubscribed += chunk.size();
        }

        return new SubscriptionResult(tokenRefs.size(), unsubscribed, chunks, "Unsubscribed active universe");
    }

    private List<AngelOneWebSocketService.TokenRef> buildActiveUniverseTokenRefs() {
        List<StockUniverse> activeUniverse = stockUniverseRepository.findByIsActiveTrueOrderBySymbolAsc();
        List<AngelOneWebSocketService.TokenRef> tokenRefs = new ArrayList<>();

        for (StockUniverse stock : activeUniverse) {
            instrumentMasterRepository.findBySymbolAndExchange(
                    stock.getSymbol().trim().toUpperCase(Locale.ROOT),
                    stock.getExchange().name())
                    .filter(im -> im.getBrokerToken() != null && !im.getBrokerToken().isBlank())
                    .ifPresent(im -> tokenRefs.add(
                            new AngelOneWebSocketService.TokenRef(im.getSymbol(), im.getBrokerToken())));
        }

        return tokenRefs;
    }

    public record SubscriptionResult(
            int eligibleSymbols,
            int processedSubscriptions,
            int chunks,
            String message) {
    }
}