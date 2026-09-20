package com.trading.scanner.service.provider.angelone;

import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.config.provider.AngelOneProperties;
import com.trading.scanner.model.Exchange;
import com.trading.scanner.model.FeedHealthStatus;
import com.trading.scanner.model.InstrumentMaster;
import com.trading.scanner.model.LiveFeedState;
import com.trading.scanner.model.StockUniverse;
import com.trading.scanner.repository.InstrumentMasterRepository;
import com.trading.scanner.repository.LiveFeedStateRepository;
import com.trading.scanner.repository.StockUniverseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class UniverseWebSocketSubscriptionServiceTest {

    private StockUniverseRepository stockUniverseRepository;
    private InstrumentMasterRepository instrumentMasterRepository;
    private LiveFeedStateRepository liveFeedStateRepository;
    private AngelOneProperties angelOneProperties;
    private AngelOneWebSocketService angelOneWebSocketService;
    private TimeProvider timeProvider;

    private UniverseWebSocketSubscriptionService service;

    @BeforeEach
    void setUp() {
        stockUniverseRepository = mock(StockUniverseRepository.class);

        instrumentMasterRepository = mock(InstrumentMasterRepository.class);

        liveFeedStateRepository = mock(LiveFeedStateRepository.class);

        angelOneProperties = mock(AngelOneProperties.class);

        angelOneWebSocketService = mock(AngelOneWebSocketService.class);

        timeProvider = mock(TimeProvider.class);

        service = new UniverseWebSocketSubscriptionService(
                stockUniverseRepository,
                instrumentMasterRepository,
                liveFeedStateRepository,
                angelOneProperties,
                angelOneWebSocketService,
                timeProvider);
    }

    @Test
    void subscribeActiveUniverse_shouldIncludeNiftyWithoutAddingItToUniverse() {
        LocalDate date = LocalDate.of(2026, 9, 2);

        LocalDateTime now = date.atTime(9, 15);

        StockUniverse wipro = StockUniverse.builder()
                .symbol("WIPRO")
                .exchange(Exchange.NSE)
                .companyName("WIPRO")
                .isActive(true)
                .build();

        InstrumentMaster wiproInstrument = InstrumentMaster.builder()
                .symbol("WIPRO")
                .exchange("NSE")
                .companyName("WIPRO")
                .instrumentType("EQUITY")
                .segment("CASH")
                .brokerToken("1001")
                .isActive(true)
                .build();

        InstrumentMaster niftyInstrument = InstrumentMaster.builder()
                .symbol("NIFTY")
                .exchange("NSE")
                .companyName("NIFTY 50")
                .instrumentType("INDEX")
                .segment("INDEX")
                .brokerToken("99926000")
                .isActive(true)
                .build();

        when(stockUniverseRepository
                .findByIsActiveTrueOrderBySymbolAsc())
                .thenReturn(List.of(wipro));

        when(instrumentMasterRepository
                .findBySymbolAndExchange(
                        "WIPRO",
                        "NSE"))
                .thenReturn(Optional.of(wiproInstrument));

        when(instrumentMasterRepository
                .findBySymbolAndExchange(
                        "NIFTY",
                        "NSE"))
                .thenReturn(Optional.of(niftyInstrument));

        when(angelOneProperties
                .websocketMaxSubscriptionsPerConnection())
                .thenReturn(1000);

        when(timeProvider.nowDateTime())
                .thenReturn(now);

        when(timeProvider.today())
                .thenReturn(date);

        LiveFeedState state = LiveFeedState.builder()
                .symbol("WIPRO")
                .exchange("NSE")
                .tradingDate(date)
                .blocked(false)
                .healthStatus(
                        FeedHealthStatus.HEALTHY)
                .subscriptionActive(false)
                .consecutiveRecoveryTicks(0)
                .build();

        when(liveFeedStateRepository
                .findBySymbolAndExchangeAndTradingDate(
                        anyString(),
                        anyString(),
                        eq(date)))
                .thenReturn(Optional.of(state));

        when(liveFeedStateRepository
                .ensureExists(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString()))
                .thenReturn(0);

        when(liveFeedStateRepository.save(
                any(LiveFeedState.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UniverseWebSocketSubscriptionService.SubscriptionResult result = service.subscribeActiveUniverse(1);

        assertEquals(
                2,
                result.eligibleSymbols());

        assertEquals(
                2,
                result.processedSubscriptions());

        assertEquals(
                1,
                result.chunks());

        verify(angelOneWebSocketService)
                .subscribe(
                        eq("active-universe-1"),
                        eq(1),
                        argThat(tokens -> tokens != null
                                && tokens.size() == 2));

        verify(liveFeedStateRepository)
                .ensureExists(
                        "WIPRO",
                        "NSE",
                        date.toString(),
                        now.toString());

        verify(liveFeedStateRepository)
                .ensureExists(
                        "NIFTY",
                        "NSE",
                        date.toString(),
                        now.toString());

        verify(liveFeedStateRepository, times(2))
                .save(any(LiveFeedState.class));

        verify(stockUniverseRepository)
                .findByIsActiveTrueOrderBySymbolAsc();

        verifyNoMoreInteractions(stockUniverseRepository);
    }

    @Test
    void subscribeActiveUniverse_shouldFailWhenNiftyTokenIsMissing() {
        LocalDate date = LocalDate.of(2026, 9, 2);

        StockUniverse wipro = StockUniverse.builder()
                .symbol("WIPRO")
                .exchange(Exchange.NSE)
                .companyName("WIPRO")
                .isActive(true)
                .build();

        InstrumentMaster wiproInstrument = InstrumentMaster.builder()
                .symbol("WIPRO")
                .exchange("NSE")
                .companyName("WIPRO")
                .instrumentType("EQUITY")
                .segment("CASH")
                .brokerToken("1001")
                .isActive(true)
                .build();

        when(stockUniverseRepository
                .findByIsActiveTrueOrderBySymbolAsc())
                .thenReturn(List.of(wipro));

        when(instrumentMasterRepository
                .findBySymbolAndExchange(
                        "WIPRO",
                        "NSE"))
                .thenReturn(Optional.of(wiproInstrument));

        when(instrumentMasterRepository
                .findBySymbolAndExchange(
                        "NIFTY",
                        "NSE"))
                .thenReturn(Optional.empty());

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service
                        .subscribeActiveUniverse(1));

        assertEquals(
                "Required NIFTY/NSE instrument is missing or has no broker token",
                exception.getMessage());

        verify(angelOneWebSocketService, never())
                .subscribe(
                        anyString(),
                        anyInt(),
                        anyList());

        verifyNoInteractions(
                liveFeedStateRepository,
                timeProvider);
    }

    @Test
    void subscribeActiveUniverse_shouldSkipUniverseInstrumentWithoutToken() {
        InstrumentMaster wiproWithoutToken = InstrumentMaster.builder()
                .symbol("WIPRO")
                .exchange("NSE")
                .companyName("WIPRO")
                .instrumentType("EQUITY")
                .segment("CASH")
                .brokerToken(null)
                .isActive(true)
                .build();

        InstrumentMaster nifty = InstrumentMaster.builder()
                .symbol("NIFTY")
                .exchange("NSE")
                .companyName("NIFTY 50")
                .instrumentType("INDEX")
                .segment("INDEX")
                .brokerToken("99926000")
                .isActive(true)
                .build();

        when(stockUniverseRepository
                .findByIsActiveTrueOrderBySymbolAsc())
                .thenReturn(List.of(
                        StockUniverse.builder()
                                .symbol("WIPRO")
                                .exchange(Exchange.NSE)
                                .companyName("WIPRO")
                                .isActive(true)
                                .build()));

        when(instrumentMasterRepository
                .findBySymbolAndExchange(
                        "WIPRO",
                        "NSE"))
                .thenReturn(Optional.of(
                        wiproWithoutToken));

        when(instrumentMasterRepository
                .findBySymbolAndExchange(
                        "NIFTY",
                        "NSE"))
                .thenReturn(Optional.of(nifty));

        when(angelOneProperties
                .websocketMaxSubscriptionsPerConnection())
                .thenReturn(1000);

        LocalDate date = LocalDate.of(2026, 9, 2);

        LocalDateTime now = date.atTime(9, 15);

        when(timeProvider.today())
                .thenReturn(date);

        when(timeProvider.nowDateTime())
                .thenReturn(now);

        when(liveFeedStateRepository
                .findBySymbolAndExchangeAndTradingDate(
                        "NIFTY",
                        "NSE",
                        date))
                .thenReturn(Optional.of(
                        LiveFeedState.builder()
                                .symbol("NIFTY")
                                .exchange("NSE")
                                .tradingDate(date)
                                .healthStatus(
                                        FeedHealthStatus.HEALTHY)
                                .consecutiveRecoveryTicks(0)
                                .build()));

        when(liveFeedStateRepository
                .ensureExists(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString()))
                .thenReturn(0);

        when(liveFeedStateRepository.save(
                any(LiveFeedState.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UniverseWebSocketSubscriptionService.SubscriptionResult result = service.subscribeActiveUniverse(1);

        assertEquals(
                1,
                result.eligibleSymbols());

        assertEquals(
                1,
                result.processedSubscriptions());

        verify(angelOneWebSocketService)
                .subscribe(
                        eq("active-universe-1"),
                        eq(1),
                        argThat(tokens -> tokens != null
                                && tokens.size() == 1));

        verify(liveFeedStateRepository)
                .ensureExists(
                        "NIFTY",
                        "NSE",
                        date.toString(),
                        now.toString());

        verify(liveFeedStateRepository, times(1))
                .save(any(LiveFeedState.class));
    }
}
