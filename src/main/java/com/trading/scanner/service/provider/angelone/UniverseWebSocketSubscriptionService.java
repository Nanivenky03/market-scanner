package com.trading.scanner.service.provider.angelone;

import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.config.provider.AngelOneProperties;
import com.trading.scanner.model.FeedHealthStatus;
import com.trading.scanner.model.InstrumentMaster;
import com.trading.scanner.model.LiveFeedState;
import com.trading.scanner.model.StockUniverse;
import com.trading.scanner.repository.InstrumentMasterRepository;
import com.trading.scanner.repository.LiveFeedStateRepository;
import com.trading.scanner.repository.StockUniverseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class UniverseWebSocketSubscriptionService {

    private final StockUniverseRepository stockUniverseRepository;
    private final InstrumentMasterRepository instrumentMasterRepository;
    private final LiveFeedStateRepository liveFeedStateRepository;
    private final AngelOneProperties angelOneProperties;
    private final AngelOneWebSocketService angelOneWebSocketService;
    private final TimeProvider timeProvider;

    public SubscriptionResult subscribeActiveUniverse(int mode) {
        List<SubscriptionTarget> targets = buildActiveUniverseTargets();

        int maxPerConnection = angelOneProperties.websocketMaxSubscriptionsPerConnection();

        if (maxPerConnection <= 0) {
            throw new IllegalStateException(
                    "websocketMaxSubscriptionsPerConnection must be greater than zero");
        }

        int chunks = 0;
        int subscribed = 0;

        for (int i = 0; i < targets.size(); i += maxPerConnection) {
            List<SubscriptionTarget> chunk = targets.subList(
                    i,
                    Math.min(
                            i + maxPerConnection,
                            targets.size()));

            chunks++;

            angelOneWebSocketService.subscribe(
                    "active-universe-" + chunks,
                    mode,
                    chunk.stream()
                            .map(SubscriptionTarget::tokenRef)
                            .toList());

            for (SubscriptionTarget target : chunk) {
                markSubscriptionActive(
                        target.symbol(),
                        target.exchange());
            }

            subscribed += chunk.size();
        }

        return new SubscriptionResult(
                targets.size(),
                subscribed,
                chunks,
                "Subscribed active universe");
    }

    public SubscriptionResult unsubscribeActiveUniverse(int mode) {
        List<SubscriptionTarget> targets = buildActiveUniverseTargets();

        int maxPerConnection = angelOneProperties.websocketMaxSubscriptionsPerConnection();

        if (maxPerConnection <= 0) {
            throw new IllegalStateException(
                    "websocketMaxSubscriptionsPerConnection must be greater than zero");
        }

        int chunks = 0;
        int unsubscribed = 0;

        for (int i = 0; i < targets.size(); i += maxPerConnection) {
            List<SubscriptionTarget> chunk = targets.subList(
                    i,
                    Math.min(
                            i + maxPerConnection,
                            targets.size()));

            chunks++;

            angelOneWebSocketService.unsubscribe(
                    "active-universe-" + chunks,
                    mode,
                    chunk.stream()
                            .map(SubscriptionTarget::tokenRef)
                            .toList());

            for (SubscriptionTarget target : chunk) {
                markSubscriptionInactive(
                        target.symbol(),
                        target.exchange());
            }

            unsubscribed += chunk.size();
        }

        return new SubscriptionResult(
                targets.size(),
                unsubscribed,
                chunks,
                "Unsubscribed active universe");
    }

    private List<SubscriptionTarget> buildActiveUniverseTargets() {
        List<StockUniverse> activeUniverse = stockUniverseRepository
                .findByIsActiveTrueOrderBySymbolAsc();

        List<SubscriptionTarget> targets = new ArrayList<>();

        for (StockUniverse stock : activeUniverse) {
            String symbol = stock.getSymbol()
                    .trim()
                    .toUpperCase(Locale.ROOT);

            String exchange = stock.getExchange().name();

            instrumentMasterRepository
                    .findBySymbolAndExchange(symbol, exchange)
                    .filter(instrument -> instrument.getBrokerToken() != null
                            && !instrument.getBrokerToken().isBlank())
                    .ifPresent(instrument -> targets.add(new SubscriptionTarget(
                            symbol,
                            exchange,
                            new AngelOneWebSocketService.TokenRef(
                                    instrument.getSymbol(),
                                    instrument.getBrokerToken()))));
        }

        return targets;
    }

    private void markSubscriptionActive(
            String symbol,
            String exchange) {

        LocalDateTime now = timeProvider.nowDateTime();
        LocalDate date = timeProvider.today();

        LiveFeedState state = liveFeedStateRepository
                .findBySymbolAndExchangeAndTradingDate(
                        symbol,
                        exchange,
                        date)
                .orElseGet(() -> LiveFeedState.builder()
                        .symbol(symbol)
                        .exchange(exchange)
                        .tradingDate(date)
                        .healthStatus(FeedHealthStatus.HEALTHY)
                        .consecutiveRecoveryTicks(0)
                        .build());

        state.setSubscriptionActive(true);

        if (state.getHealthStatus() == null) {
            state.setHealthStatus(FeedHealthStatus.HEALTHY);
        }

        if (state.getLastHealthTransitionAt() == null) {
            state.setLastHealthTransitionAt(now);
        }

        state.setUpdatedAt(now);
        liveFeedStateRepository.save(state);
    }

    private void markSubscriptionInactive(
            String symbol,
            String exchange) {

        LocalDateTime now = timeProvider.nowDateTime();

        liveFeedStateRepository
                .findBySymbolAndExchangeAndTradingDate(
                        symbol,
                        exchange,
                        timeProvider.today())
                .ifPresent(state -> {
                    state.setSubscriptionActive(false);
                    state.setHealthStatus(FeedHealthStatus.HEALTHY);
                    state.setStaleSince(null);
                    state.setStaleAlertedAt(null);
                    state.setRecoveredAt(null);
                    state.setConsecutiveRecoveryTicks(0);
                    state.setLastHealthTransitionAt(now);
                    state.setUpdatedAt(now);
                    liveFeedStateRepository.save(state);
                });
    }

    private record SubscriptionTarget(
            String symbol,
            String exchange,
            AngelOneWebSocketService.TokenRef tokenRef) {
    }

    public record SubscriptionResult(
            int eligibleSymbols,
            int processedSubscriptions,
            int chunks,
            String message) {
    }
}
