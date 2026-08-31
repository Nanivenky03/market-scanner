package com.trading.scanner.service.runtime;

import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.FeedHealthStatus;
import com.trading.scanner.model.LiveFeedState;
import com.trading.scanner.repository.LiveFeedStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FeedHealthServiceTest {

    private LiveFeedStateRepository repository;
    private RuntimeAlertService alertService;
    private TimeProvider timeProvider;
    private FeedHealthService service;

    @BeforeEach
    void setUp() {
        repository = mock(LiveFeedStateRepository.class);
        alertService = mock(RuntimeAlertService.class);
        timeProvider = mock(TimeProvider.class);

        service = new FeedHealthService(
                repository,
                alertService,
                timeProvider);
    }

    @Test
    void evaluate_shouldOpenOneAggregateAlertForStaleSymbols() {
        LocalDate date = LocalDate.of(2026, 8, 26);
        LocalDateTime now = date.atTime(11, 0);

        LiveFeedState state = LiveFeedState.builder()
                .symbol("ONGC")
                .exchange("NSE")
                .tradingDate(date)
                .subscriptionActive(true)
                .healthStatus(FeedHealthStatus.HEALTHY)
                .lastTickTime(date.atTime(10, 57))
                .build();

        when(timeProvider.today()).thenReturn(date);
        when(timeProvider.nowDateTime()).thenReturn(now);
        when(repository.findByTradingDateAndSubscriptionActiveTrue(date))
                .thenReturn(List.of(state));
        when(repository.save(any(LiveFeedState.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        FeedHealthService.FeedHealthEvaluationResult result = service.evaluate();

        assertEquals(1, result.monitoredSymbols());
        assertEquals(1, result.staleSymbols());
        assertEquals(FeedHealthStatus.ALERT_SENT,
                state.getHealthStatus());

        verify(alertService).evaluateFeedHealthAggregate(
                argThat(symbols -> symbols.size() == 1
                        && symbols.contains("NSE:ONGC")),
                argThat(List::isEmpty));

        verify(repository).save(state);
    }

    @Test
    void evaluate_shouldIgnoreSubscribedSymbolWithoutFirstTick() {
        LocalDate date = LocalDate.of(2026, 8, 26);

        LiveFeedState state = LiveFeedState.builder()
                .symbol("TCS")
                .exchange("NSE")
                .tradingDate(date)
                .subscriptionActive(true)
                .healthStatus(FeedHealthStatus.HEALTHY)
                .lastTickTime(null)
                .build();

        when(timeProvider.today()).thenReturn(date);
        when(timeProvider.nowDateTime())
                .thenReturn(date.atTime(11, 0));
        when(repository.findByTradingDateAndSubscriptionActiveTrue(date))
                .thenReturn(List.of(state));

        FeedHealthService.FeedHealthEvaluationResult result = service.evaluate();

        assertEquals(1, result.monitoredSymbols());
        assertEquals(0, result.staleSymbols());

        verify(alertService).evaluateFeedHealthAggregate(
                argThat(List::isEmpty),
                argThat(List::isEmpty));
    }
}
