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
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class UniverseWebSocketSubscriptionService {

        private static final String NIFTY_SYMBOL = "NIFTY";
        private static final String NIFTY_EXCHANGE = "NSE";

        private final StockUniverseRepository stockUniverseRepository;
        private final InstrumentMasterRepository instrumentMasterRepository;
        private final LiveFeedStateRepository liveFeedStateRepository;
        private final AngelOneProperties angelOneProperties;
        private final AngelOneWebSocketService angelOneWebSocketService;
        private final TimeProvider timeProvider;

        @Transactional
        public SubscriptionResult subscribeActiveUniverse(
                        int mode) {

                List<SubscriptionTarget> targets = buildSubscriptionTargets();

                int maxPerConnection = angelOneProperties
                                .websocketMaxSubscriptionsPerConnection();

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
                                "Subscribed active stock universe and required market references");
        }

        @Transactional
        public SubscriptionResult unsubscribeActiveUniverse(
                        int mode) {

                List<SubscriptionTarget> targets = buildSubscriptionTargets();

                int maxPerConnection = angelOneProperties
                                .websocketMaxSubscriptionsPerConnection();

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
                                "Unsubscribed active stock universe and required market references");
        }

        private List<SubscriptionTarget> buildSubscriptionTargets() {
                Map<String, SubscriptionTarget> targets = new LinkedHashMap<>();

                List<StockUniverse> activeUniverse = stockUniverseRepository
                                .findByIsActiveTrueOrderBySymbolAsc();

                if (activeUniverse != null) {
                        for (StockUniverse stock : activeUniverse) {
                                if (stock == null
                                                || stock.getSymbol() == null
                                                || stock.getExchange() == null) {
                                        continue;
                                }

                                String symbol = normalize(stock.getSymbol());

                                String exchange = normalize(stock.getExchange().name());

                                instrumentMasterRepository
                                                .findBySymbolAndExchange(
                                                                symbol,
                                                                exchange)
                                                .filter(this::hasValidToken)
                                                .ifPresent(instrument -> {
                                                        String token = instrument.getBrokerToken();

                                                        targets.putIfAbsent(
                                                                        token,
                                                                        new SubscriptionTarget(
                                                                                        symbol,
                                                                                        exchange,
                                                                                        new AngelOneWebSocketService.TokenRef(
                                                                                                        symbol,
                                                                                                        token)));
                                                });
                        }
                }

                InstrumentMaster nifty = instrumentMasterRepository
                                .findBySymbolAndExchange(
                                                NIFTY_SYMBOL,
                                                NIFTY_EXCHANGE)
                                .filter(this::hasValidToken)
                                .orElseThrow(() -> new IllegalStateException(
                                                "Required NIFTY/NSE instrument is missing or has no broker token"));

                String niftyToken = nifty.getBrokerToken();

                targets.putIfAbsent(
                                niftyToken,
                                new SubscriptionTarget(
                                                NIFTY_SYMBOL,
                                                NIFTY_EXCHANGE,
                                                new AngelOneWebSocketService.TokenRef(
                                                                NIFTY_SYMBOL,
                                                                niftyToken)));

                return new ArrayList<>(
                                targets.values());
        }

        private boolean hasValidToken(
                        InstrumentMaster instrument) {

                return instrument != null
                                && Boolean.TRUE.equals(
                                                instrument.getIsActive())
                                && instrument.getBrokerToken() != null
                                && !instrument.getBrokerToken()
                                                .isBlank();
        }

        private void markSubscriptionActive(
                        String symbol,
                        String exchange) {

                LocalDateTime now = timeProvider.nowDateTime();

                LocalDate date = timeProvider.today();

                liveFeedStateRepository.ensureExists(
                                symbol,
                                exchange,
                                date.toString(),
                                now.toString());

                LiveFeedState state = liveFeedStateRepository
                                .findBySymbolAndExchangeAndTradingDate(
                                                symbol,
                                                exchange,
                                                date)
                                .orElseThrow(() -> new IllegalStateException(
                                                "Live feed state was not available after atomic creation: "
                                                                + symbol
                                                                + "|"
                                                                + exchange
                                                                + "|"
                                                                + date));

                state.setSubscriptionActive(true);

                if (state.getHealthStatus() == null) {
                        state.setHealthStatus(
                                        FeedHealthStatus.HEALTHY);
                }

                if (state.getConsecutiveRecoveryTicks() == null) {
                        state.setConsecutiveRecoveryTicks(0);
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
                                        state.setHealthStatus(
                                                        FeedHealthStatus.HEALTHY);
                                        state.setStaleSince(null);
                                        state.setStaleAlertedAt(null);
                                        state.setRecoveredAt(null);
                                        state.setConsecutiveRecoveryTicks(0);
                                        state.setLastHealthTransitionAt(now);
                                        state.setUpdatedAt(now);

                                        liveFeedStateRepository.save(state);
                                });
        }

        private String normalize(
                        String value) {

                return value == null
                                ? null
                                : value.trim()
                                                .toUpperCase(Locale.ROOT);
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
