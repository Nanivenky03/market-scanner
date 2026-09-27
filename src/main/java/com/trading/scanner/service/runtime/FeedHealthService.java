package com.trading.scanner.service.runtime;

import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.FeedHealthStatus;
import com.trading.scanner.model.LiveFeedState;
import com.trading.scanner.repository.LiveFeedStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class FeedHealthService {

    private static final String EXCHANGE_SEPARATOR = ":";
    private static final LocalTime FEED_WINDOW_START = LocalTime.of(8, 55);
    private static final LocalTime FEED_WINDOW_END = LocalTime.of(15, 30);

    private final LiveFeedStateRepository liveFeedStateRepository;
    private final RuntimeAlertService runtimeAlertService;
    private final TimeProvider timeProvider;
    private final TradingCalendar tradingCalendar;

    @Value("${runtime.feed-health.stale-after-seconds:90}")
    private long staleAfterSeconds = 90L;

    @Transactional
    public FeedHealthEvaluationResult evaluate() {
        LocalDate today = timeProvider.today();
        LocalDateTime now = timeProvider.nowDateTime();

        if (tradingCalendar != null && !tradingCalendar.isTradingDay(today)) {
            runtimeAlertService.evaluateFeedHealthAggregate(List.of(), List.of());
            return new FeedHealthEvaluationResult(
                    now,
                    0,
                    0,
                    0,
                    "Feed-health evaluation skipped: Non-trading day");
        }

        LocalTime currentTime = now.toLocalTime();
        if (currentTime.isBefore(FEED_WINDOW_START) || currentTime.isAfter(FEED_WINDOW_END)) {
            runtimeAlertService.evaluateFeedHealthAggregate(List.of(), List.of());
            return new FeedHealthEvaluationResult(
                    now,
                    0,
                    0,
                    0,
                    "Feed-health evaluation skipped: Outside market feed window (" + FEED_WINDOW_START + " - "
                            + FEED_WINDOW_END + ")");
        }

        List<LiveFeedState> states = liveFeedStateRepository
                .findByTradingDateAndSubscriptionActiveTrue(today);

        List<String> staleSymbols = new ArrayList<>();
        List<String> recoveringSymbols = new ArrayList<>();

        for (LiveFeedState state : states) {
            if (state.getLastTickTime() == null) {
                continue;
            }

            long idleSeconds = Math.max(
                    0L,
                    Duration.between(
                            state.getLastTickTime(),
                            now).getSeconds());

            FeedHealthStatus status = state.getHealthStatus();

            if (status == null) {
                status = FeedHealthStatus.HEALTHY;
                state.setHealthStatus(status);
            }

            if (idleSeconds > staleAfterSeconds) {
                if (status == FeedHealthStatus.HEALTHY
                        || status == FeedHealthStatus.RECOVERING) {
                    transition(
                            state,
                            FeedHealthStatus.STALE,
                            now);

                    if (state.getStaleSince() == null) {
                        state.setStaleSince(now);
                    }
                }

                staleSymbols.add(displayName(state));
            } else if (status == FeedHealthStatus.STALE
                    || status == FeedHealthStatus.ALERT_SENT
                    || status == FeedHealthStatus.RECOVERING) {
                recoveringSymbols.add(displayName(state));
            }

            state.setUpdatedAt(now);
        }

        runtimeAlertService.evaluateFeedHealthAggregate(
                staleSymbols,
                recoveringSymbols);

        for (LiveFeedState state : states) {
            if (state.getHealthStatus() == FeedHealthStatus.STALE) {
                state.setHealthStatus(FeedHealthStatus.ALERT_SENT);
                state.setStaleAlertedAt(now);
                state.setLastHealthTransitionAt(now);
            }

            liveFeedStateRepository.save(state);
        }

        return new FeedHealthEvaluationResult(
                now,
                states.size(),
                staleSymbols.size(),
                recoveringSymbols.size(),
                "Feed-health evaluation completed");
    }

    private void transition(
            LiveFeedState state,
            FeedHealthStatus next,
            LocalDateTime now) {

        state.setHealthStatus(next);
        state.setLastHealthTransitionAt(now);

        if (next == FeedHealthStatus.STALE
                && state.getStaleSince() == null) {
            state.setStaleSince(now);
        }
    }

    private String displayName(LiveFeedState state) {
        return state.getExchange()
                + EXCHANGE_SEPARATOR
                + state.getSymbol();
    }

    public record FeedHealthEvaluationResult(
            LocalDateTime evaluatedAt,
            int monitoredSymbols,
            int staleSymbols,
            int recoveringSymbols,
            String message) {
    }
}
