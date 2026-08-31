package com.trading.scanner.service.provider.angelone;

import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.Exchange;
import com.trading.scanner.model.LiveFeedState;
import com.trading.scanner.model.LiveMinuteResolution;
import com.trading.scanner.model.MarketMinuteSnapshot;
import com.trading.scanner.model.MinuteResolutionStatus;
import com.trading.scanner.model.StockUniverse;
import com.trading.scanner.repository.LiveFeedStateRepository;
import com.trading.scanner.repository.LiveMinuteResolutionRepository;
import com.trading.scanner.repository.MarketMinuteSnapshotRepository;
import com.trading.scanner.repository.StockUniverseRepository;
import com.trading.scanner.service.data.IntradayGapDetectedEvent;
import com.trading.scanner.service.engine.DailyDataStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class LiveMarketSnapshotServiceTest {

        private MarketMinuteSnapshotRepository snapshotRepository;
        private LiveFeedStateRepository feedStateRepository;
        private LiveMinuteResolutionRepository resolutionRepository;
        private StockUniverseRepository stockUniverseRepository;
        private TradingCalendar tradingCalendar;
        private DailyDataStatusService dataStatusService;
        private TimeProvider timeProvider;
        private ApplicationEventPublisher eventPublisher;
        private LiveMarketSnapshotService service;

        @BeforeEach
        void setUp() {
                snapshotRepository = mock(MarketMinuteSnapshotRepository.class);
                feedStateRepository = mock(LiveFeedStateRepository.class);
                resolutionRepository = mock(LiveMinuteResolutionRepository.class);
                stockUniverseRepository = mock(StockUniverseRepository.class);
                tradingCalendar = mock(TradingCalendar.class);
                dataStatusService = mock(DailyDataStatusService.class);
                timeProvider = mock(TimeProvider.class);
                eventPublisher = mock(ApplicationEventPublisher.class);

                service = new LiveMarketSnapshotService(
                                snapshotRepository,
                                feedStateRepository,
                                resolutionRepository,
                                stockUniverseRepository,
                                tradingCalendar,
                                dataStatusService,
                                timeProvider,
                                eventPublisher);
        }

        @Test
        void update_shouldStoreLatestSnapshotAndPersistMinuteRow() {
                LocalDateTime time = LocalDateTime.of(2026, 7, 14, 13, 59, 6);

                when(timeProvider.nowDateTime()).thenReturn(time);
                when(feedStateRepository.findBySymbolAndExchangeAndTradingDate(
                                "WIPRO", "NSE", time.toLocalDate()))
                                .thenReturn(Optional.empty());
                when(snapshotRepository.findBySymbolAndExchangeAndMinuteTime(
                                "WIPRO", "NSE", time.withSecond(0)))
                                .thenReturn(Optional.empty());
                when(resolutionRepository.findBySymbolAndExchangeAndMinuteTime(
                                "WIPRO", "NSE", time.withSecond(0)))
                                .thenReturn(Optional.empty());

                service.update(tick(time, 1770.90, 25L, 1L, 100000L));

                List<LiveMarketSnapshotService.SnapshotView> snapshots = service.latest(10);

                assertEquals(1, snapshots.size());
                assertEquals(
                                Set.of("WIPRO"),
                                snapshots.stream()
                                                .map(LiveMarketSnapshotService.SnapshotView::symbol)
                                                .collect(Collectors.toSet()));

                ArgumentCaptor<MarketMinuteSnapshot> captor = ArgumentCaptor.forClass(MarketMinuteSnapshot.class);

                verify(snapshotRepository).save(captor.capture());

                MarketMinuteSnapshot saved = captor.getValue();

                assertEquals("WIPRO", saved.getSymbol());
                assertEquals("NSE", saved.getExchange());
                assertEquals(time.withSecond(0), saved.getMinuteTime());
                assertEquals(time, saved.getLatestTickTime());
                assertEquals(1770.90, saved.getLastPrice());
                assertEquals(100000L, saved.getVolumeTradedForDay());
                assertFalse(saved.getIsFinalized());

                verify(feedStateRepository).save(any(LiveFeedState.class));
                verify(resolutionRepository)
                                .save(argThat(row -> row.getStatus() == MinuteResolutionStatus.TICK_RECEIVED));
        }

        @Test
        void update_shouldUpsertExistingMinuteRowForSameMinute() {
                LocalDate date = LocalDate.of(2026, 7, 14);
                LocalDateTime first = date.atTime(13, 59, 6);
                LocalDateTime second = date.atTime(13, 59, 20);

                when(timeProvider.nowDateTime()).thenReturn(first, second);

                when(feedStateRepository.findBySymbolAndExchangeAndTradingDate(
                                "WIPRO", "NSE", date))
                                .thenReturn(Optional.empty())
                                .thenReturn(Optional.of(LiveFeedState.builder()
                                                .symbol("WIPRO")
                                                .exchange("NSE")
                                                .tradingDate(date)
                                                .lastTickTime(first)
                                                .lastTickMinute(date.atTime(13, 59))
                                                .blocked(false)
                                                .build()));

                MarketMinuteSnapshot existing = MarketMinuteSnapshot.builder()
                                .symbol("WIPRO")
                                .exchange("NSE")
                                .minuteTime(date.atTime(13, 59))
                                .latestTickTime(first)
                                .tradingDate(date)
                                .createdAt(first)
                                .updatedAt(first)
                                .isFinalized(false)
                                .build();

                when(snapshotRepository.findBySymbolAndExchangeAndMinuteTime(
                                "WIPRO", "NSE", date.atTime(13, 59)))
                                .thenReturn(Optional.empty())
                                .thenReturn(Optional.of(existing));

                when(resolutionRepository.findBySymbolAndExchangeAndMinuteTime(
                                "WIPRO", "NSE", date.atTime(13, 59)))
                                .thenReturn(Optional.empty())
                                .thenReturn(Optional.of(LiveMinuteResolution.builder()
                                                .symbol("WIPRO")
                                                .exchange("NSE")
                                                .minuteTime(date.atTime(13, 59))
                                                .tradingDate(date)
                                                .status(MinuteResolutionStatus.TICK_RECEIVED)
                                                .build()));

                service.update(tick(first, 1770.90, 25L, 1L, 100000L));
                service.update(tick(second, 1775.00, 30L, 2L, 120000L));

                ArgumentCaptor<MarketMinuteSnapshot> captor = ArgumentCaptor.forClass(MarketMinuteSnapshot.class);

                verify(snapshotRepository, times(2)).save(captor.capture());

                MarketMinuteSnapshot saved = captor.getAllValues().get(1);

                assertEquals(date.atTime(13, 59), saved.getMinuteTime());
                assertEquals(second, saved.getLatestTickTime());
                assertEquals(1775.00, saved.getLastPrice());
                assertEquals(30L, saved.getLastTradedQuantity());
                assertEquals(120000L, saved.getVolumeTradedForDay());
        }

        @Test
        void update_shouldFinalizePreviousMinuteOnRollover() {
                LocalDate date = LocalDate.of(2026, 7, 14);
                LocalDateTime previousTime = date.atTime(13, 59, 58);
                LocalDateTime currentTime = date.atTime(14, 0, 3);

                when(timeProvider.nowDateTime())
                                .thenReturn(previousTime, currentTime);

                when(feedStateRepository.findBySymbolAndExchangeAndTradingDate(
                                "WIPRO", "NSE", date))
                                .thenReturn(Optional.empty())
                                .thenReturn(Optional.of(LiveFeedState.builder()
                                                .symbol("WIPRO")
                                                .exchange("NSE")
                                                .tradingDate(date)
                                                .lastTickTime(previousTime)
                                                .lastTickMinute(date.atTime(13, 59))
                                                .blocked(false)
                                                .build()));

                MarketMinuteSnapshot previousRow = MarketMinuteSnapshot.builder()
                                .symbol("WIPRO")
                                .exchange("NSE")
                                .minuteTime(date.atTime(13, 59))
                                .latestTickTime(previousTime)
                                .tradingDate(date)
                                .createdAt(previousTime)
                                .updatedAt(previousTime)
                                .isFinalized(false)
                                .build();

                when(snapshotRepository.findBySymbolAndExchangeAndMinuteTime(
                                "WIPRO", "NSE", date.atTime(13, 59)))
                                .thenReturn(Optional.empty())
                                .thenReturn(Optional.of(previousRow));

                when(snapshotRepository.findBySymbolAndExchangeAndMinuteTime(
                                "WIPRO", "NSE", date.atTime(14, 0)))
                                .thenReturn(Optional.empty());

                when(resolutionRepository.findBySymbolAndExchangeAndMinuteTime(
                                "WIPRO", "NSE", date.atTime(13, 59)))
                                .thenReturn(Optional.empty());

                when(resolutionRepository.findBySymbolAndExchangeAndMinuteTime(
                                "WIPRO", "NSE", date.atTime(14, 0)))
                                .thenReturn(Optional.empty());

                service.update(tick(previousTime, 1770.90, 25L, 1L, 100000L));
                service.update(tick(currentTime, 1775.00, 30L, 2L, 120000L));

                verify(snapshotRepository).save(argThat(row -> row.getMinuteTime().equals(date.atTime(13, 59))
                                && Boolean.TRUE.equals(row.getIsFinalized())));

                verify(snapshotRepository).save(argThat(row -> row.getMinuteTime().equals(date.atTime(14, 0))
                                && Boolean.FALSE.equals(row.getIsFinalized())));
        }

        @Test
        void checkForClosedMinuteGaps_shouldPublishMissingMinuteRange() {
                LocalDate date = LocalDate.of(2026, 8, 17);
                LocalDateTime now = date.atTime(11, 20);

                StockUniverse stock = StockUniverse.builder()
                                .symbol("ONGC")
                                .exchange(Exchange.NSE)
                                .companyName("Oil and Natural Gas Corporation")
                                .isActive(true)
                                .build();

                LiveFeedState state = LiveFeedState.builder()
                                .symbol("ONGC")
                                .exchange("NSE")
                                .tradingDate(date)
                                .lastTickTime(date.atTime(11, 16, 20))
                                .lastTickMinute(date.atTime(11, 16))
                                .lastCheckedMinute(date.atTime(11, 16))
                                .blocked(false)
                                .build();

                when(timeProvider.nowDateTime()).thenReturn(now);
                when(tradingCalendar.isTradingDay(date)).thenReturn(true);
                when(stockUniverseRepository.findByIsActiveTrueOrderBySymbolAsc())
                                .thenReturn(List.of(stock));
                when(feedStateRepository.findBySymbolAndExchangeAndTradingDate(
                                "ONGC", "NSE", date))
                                .thenReturn(Optional.of(state));
                when(feedStateRepository.save(any(LiveFeedState.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                when(snapshotRepository
                                .findBySymbolAndExchangeAndMinuteTimeBetweenOrderByMinuteTimeAsc(
                                                "ONGC",
                                                "NSE",
                                                date.atTime(11, 17),
                                                date.atTime(11, 19)))
                                .thenReturn(List.of());

                when(resolutionRepository
                                .findBySymbolAndExchangeAndMinuteTimeBetweenOrderByMinuteTimeAsc(
                                                "ONGC",
                                                "NSE",
                                                date.atTime(11, 17),
                                                date.atTime(11, 19)))
                                .thenReturn(List.of());

                when(resolutionRepository.findBySymbolAndExchangeAndMinuteTime(
                                anyString(), anyString(), any(LocalDateTime.class)))
                                .thenReturn(Optional.empty());

                when(resolutionRepository.save(any(LiveMinuteResolution.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                int detected = service.checkForClosedMinuteGaps();

                assertEquals(1, detected);

                ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);

                verify(eventPublisher).publishEvent(eventCaptor.capture());

                IntradayGapDetectedEvent event = (IntradayGapDetectedEvent) eventCaptor.getValue();

                assertEquals("ONGC", event.symbol());
                assertEquals("NSE", event.exchange());
                assertEquals(date.atTime(11, 17), event.fromTime());
                assertEquals(date.atTime(11, 19), event.toTime());

                verify(dataStatusService).markPartial(
                                "ONGC",
                                "NSE",
                                date,
                                "Closed minute unresolved; provider validation required");

                verify(feedStateRepository).save(argThat(saved -> Boolean.TRUE.equals(saved.getBlocked())
                                && date.atTime(11, 19).equals(saved.getLastCheckedMinute())
                                && date.atTime(11, 17).equals(saved.getGapFrom())
                                && date.atTime(11, 19).equals(saved.getGapTo())));
        }

        @Test
        void checkForClosedMinuteGaps_shouldIgnoreConfirmedNoTradeMinute() {
                LocalDate date = LocalDate.of(2026, 8, 17);
                LocalDateTime now = date.atTime(11, 18);

                StockUniverse stock = StockUniverse.builder()
                                .symbol("ONGC")
                                .exchange(Exchange.NSE)
                                .companyName("Oil and Natural Gas Corporation")
                                .isActive(true)
                                .build();

                LiveFeedState state = LiveFeedState.builder()
                                .symbol("ONGC")
                                .exchange("NSE")
                                .tradingDate(date)
                                .lastCheckedMinute(date.atTime(11, 16))
                                .blocked(false)
                                .build();

                when(timeProvider.nowDateTime()).thenReturn(now);
                when(tradingCalendar.isTradingDay(date)).thenReturn(true);
                when(stockUniverseRepository.findByIsActiveTrueOrderBySymbolAsc())
                                .thenReturn(List.of(stock));
                when(feedStateRepository.findBySymbolAndExchangeAndTradingDate(
                                "ONGC", "NSE", date))
                                .thenReturn(Optional.of(state));
                when(feedStateRepository.save(any(LiveFeedState.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));
                when(snapshotRepository
                                .findBySymbolAndExchangeAndMinuteTimeBetweenOrderByMinuteTimeAsc(
                                                "ONGC",
                                                "NSE",
                                                date.atTime(11, 17),
                                                date.atTime(11, 17)))
                                .thenReturn(List.of());
                when(resolutionRepository
                                .findBySymbolAndExchangeAndMinuteTimeBetweenOrderByMinuteTimeAsc(
                                                "ONGC",
                                                "NSE",
                                                date.atTime(11, 17),
                                                date.atTime(11, 17)))
                                .thenReturn(List.of(LiveMinuteResolution.builder()
                                                .symbol("ONGC")
                                                .exchange("NSE")
                                                .minuteTime(date.atTime(11, 17))
                                                .tradingDate(date)
                                                .status(MinuteResolutionStatus.NO_TRADE_CONFIRMED)
                                                .build()));

                int detected = service.checkForClosedMinuteGaps();

                assertEquals(0, detected);
                verify(eventPublisher, never()).publishEvent(any());
                verify(dataStatusService, never()).markPartial(
                                anyString(), anyString(), any(LocalDate.class), anyString());
        }

        @Test
        void confirmNoTrade_shouldPersistExplicitResolution() {
                LocalDateTime minute = LocalDateTime.of(2026, 8, 17, 11, 17);

                when(timeProvider.nowDateTime()).thenReturn(minute);
                when(resolutionRepository.findBySymbolAndExchangeAndMinuteTime(
                                "ONGC", "NSE", minute))
                                .thenReturn(Optional.empty());
                when(resolutionRepository.save(any(LiveMinuteResolution.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                service.confirmNoTrade(
                                "ONGC",
                                "NSE",
                                minute,
                                "Broker validation confirmed no trade");

                verify(resolutionRepository)
                                .save(argThat(row -> row.getStatus() == MinuteResolutionStatus.NO_TRADE_CONFIRMED
                                                && "Broker validation confirmed no trade"
                                                                .equals(row.getReason())));
        }

        @Test
        void clear_shouldRemoveOnlyInMemorySnapshots() {
                LocalDateTime time = LocalDateTime.of(2026, 7, 14, 13, 59, 6);

                when(timeProvider.nowDateTime()).thenReturn(time);
                when(feedStateRepository.findBySymbolAndExchangeAndTradingDate(
                                "WIPRO", "NSE", time.toLocalDate()))
                                .thenReturn(Optional.empty());
                when(snapshotRepository.findBySymbolAndExchangeAndMinuteTime(
                                "WIPRO", "NSE", time.withSecond(0)))
                                .thenReturn(Optional.empty());
                when(resolutionRepository.findBySymbolAndExchangeAndMinuteTime(
                                "WIPRO", "NSE", time.withSecond(0)))
                                .thenReturn(Optional.empty());

                service.update(tick(time, 1770.90, 25L, 1L, null));

                LiveMarketSnapshotService.ClearResult result = service.clear();

                assertEquals(1, result.removed());
                assertEquals(0, service.latest(10).size());
                verify(snapshotRepository, never()).deleteAll();
        }

        private AngelOneTickParserService.NormalizedTick tick(
                        LocalDateTime time,
                        double price,
                        long lastTradedQuantity,
                        long sequence,
                        Long volume) {

                return new AngelOneTickParserService.NormalizedTick(
                                "WIPRO",
                                "NSE",
                                time,
                                price,
                                lastTradedQuantity,
                                "16675",
                                3,
                                1,
                                sequence,
                                1000L,
                                (long) (price * 100),
                                1750.0,
                                1780.0,
                                1740.0,
                                1760.0,
                                1768.4,
                                volume,
                                50000L,
                                45000L,
                                900L,
                                1000L,
                                25.0,
                                1900.0,
                                1500.0,
                                2000.0,
                                1200.0);
        }

        @Test
        void update_shouldPersistCumulativeVolumeTodayInFeedState() {
                LocalDate date = LocalDate.of(2026, 7, 14);

                LocalDateTime time = date.atTime(13, 59, 6);

                when(timeProvider.nowDateTime())
                                .thenReturn(time);

                when(feedStateRepository
                                .findBySymbolAndExchangeAndTradingDate(
                                                "WIPRO",
                                                "NSE",
                                                date))
                                .thenReturn(Optional.empty());

                when(snapshotRepository
                                .findBySymbolAndExchangeAndMinuteTime(
                                                "WIPRO",
                                                "NSE",
                                                time.withSecond(0)))
                                .thenReturn(Optional.empty());

                when(resolutionRepository
                                .findBySymbolAndExchangeAndMinuteTime(
                                                "WIPRO",
                                                "NSE",
                                                time.withSecond(0)))
                                .thenReturn(Optional.empty());

                service.update(
                                tick(
                                                time,
                                                1770.90,
                                                25L,
                                                1L,
                                                100000L));

                verify(feedStateRepository).save(argThat(state -> "WIPRO".equals(state.getSymbol())
                                && "NSE".equals(state.getExchange())
                                && date.equals(state.getTradingDate())
                                && Long.valueOf(100000L)
                                                .equals(state.getCumulativeVolumeToday())));
        }

        @Test
        void currentCumulativeVolumeToday_shouldReadPersistedFeedState() {
                LocalDate date = LocalDate.of(2026, 7, 14);

                LiveFeedState state = LiveFeedState.builder()
                                .symbol("WIPRO")
                                .exchange("NSE")
                                .tradingDate(date)
                                .cumulativeVolumeToday(120000L)
                                .build();

                when(feedStateRepository
                                .findBySymbolAndExchangeAndTradingDate(
                                                "WIPRO",
                                                "NSE",
                                                date))
                                .thenReturn(Optional.of(state));

                Optional<Long> result = service.currentCumulativeVolumeToday(
                                "WIPRO",
                                "NSE",
                                date);

                assertEquals(
                                Optional.of(120000L),
                                result);

                verifyNoInteractions(snapshotRepository);
        }

}
