package com.trading.scanner.service.provider.angelone;

import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.MarketMinuteSnapshot;
import com.trading.scanner.repository.MarketMinuteSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LiveMarketSnapshotServiceTest {

        private MarketMinuteSnapshotRepository marketMinuteSnapshotRepository;
        private TimeProvider timeProvider;
        private LiveMarketSnapshotService service;

        @BeforeEach
        void setUp() {
                marketMinuteSnapshotRepository = mock(MarketMinuteSnapshotRepository.class);
                timeProvider = mock(TimeProvider.class);
                service = new LiveMarketSnapshotService(marketMinuteSnapshotRepository, timeProvider);
        }

        @Test
        void update_shouldStoreLatestSnapshotsAndPersistMinuteRow() {
                when(timeProvider.nowDateTime()).thenReturn(LocalDateTime.of(2026, 7, 14, 13, 59, 6));
                when(marketMinuteSnapshotRepository.findBySymbolAndExchangeAndMinuteTime(
                                "WIPRO", "NSE", LocalDateTime.of(2026, 7, 14, 13, 59)))
                                .thenReturn(Optional.empty());

                service.update(new AngelOneTickParserService.NormalizedTick(
                                "WIPRO",
                                "NSE",
                                LocalDateTime.of(2026, 7, 14, 13, 59, 6),
                                1770.90,
                                25L,
                                "16675",
                                3,
                                1,
                                1L,
                                1000L,
                                177090L,
                                1750.0,
                                1780.0,
                                1740.0,
                                1760.0,
                                1768.4,
                                100000L,
                                50000L,
                                45000L,
                                900L,
                                1000L,
                                25.0,
                                1900.0,
                                1500.0,
                                2000.0,
                                1200.0));

                List<LiveMarketSnapshotService.SnapshotView> snapshots = service.latest(10);
                assertEquals(1, snapshots.size());
                assertEquals(Set.of("WIPRO"), snapshots.stream()
                                .map(LiveMarketSnapshotService.SnapshotView::symbol)
                                .collect(Collectors.toSet()));

                ArgumentCaptor<MarketMinuteSnapshot> captor = ArgumentCaptor.forClass(MarketMinuteSnapshot.class);
                verify(marketMinuteSnapshotRepository, times(1)).save(captor.capture());

                MarketMinuteSnapshot saved = captor.getValue();
                assertEquals("WIPRO", saved.getSymbol());
                assertEquals("NSE", saved.getExchange());
                assertEquals(LocalDateTime.of(2026, 7, 14, 13, 59), saved.getMinuteTime());
                assertEquals(LocalDateTime.of(2026, 7, 14, 13, 59, 6), saved.getLatestTickTime());
                assertEquals(1770.90, saved.getLastPrice());
                assertEquals(100000L, saved.getVolumeTradedForDay());
                assertEquals(false, saved.getIsFinalized());
        }

        @Test
        void update_shouldUpsertExistingMinuteRowForSameMinute() {
                when(timeProvider.nowDateTime())
                                .thenReturn(LocalDateTime.of(2026, 7, 14, 13, 59, 6))
                                .thenReturn(LocalDateTime.of(2026, 7, 14, 13, 59, 20));

                MarketMinuteSnapshot existing = MarketMinuteSnapshot.builder()
                                .symbol("WIPRO")
                                .exchange("NSE")
                                .minuteTime(LocalDateTime.of(2026, 7, 14, 13, 59))
                                .latestTickTime(LocalDateTime.of(2026, 7, 14, 13, 59, 6))
                                .tradingDate(LocalDateTime.of(2026, 7, 14, 13, 59).toLocalDate())
                                .createdAt(LocalDateTime.of(2026, 7, 14, 13, 59, 6))
                                .updatedAt(LocalDateTime.of(2026, 7, 14, 13, 59, 6))
                                .isFinalized(false)
                                .build();

                when(marketMinuteSnapshotRepository.findBySymbolAndExchangeAndMinuteTime(
                                "WIPRO", "NSE", LocalDateTime.of(2026, 7, 14, 13, 59)))
                                .thenReturn(Optional.empty())
                                .thenReturn(Optional.of(existing));

                service.update(new AngelOneTickParserService.NormalizedTick(
                                "WIPRO",
                                "NSE",
                                LocalDateTime.of(2026, 7, 14, 13, 59, 6),
                                1770.90,
                                25L,
                                "16675",
                                3,
                                1,
                                1L,
                                1000L,
                                177090L,
                                null,
                                null,
                                null,
                                null,
                                null,
                                100000L,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null));

                service.update(new AngelOneTickParserService.NormalizedTick(
                                "WIPRO",
                                "NSE",
                                LocalDateTime.of(2026, 7, 14, 13, 59, 20),
                                1775.00,
                                30L,
                                "16675",
                                3,
                                1,
                                2L,
                                2000L,
                                177500L,
                                null,
                                null,
                                null,
                                null,
                                null,
                                120000L,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null));

                ArgumentCaptor<MarketMinuteSnapshot> captor = ArgumentCaptor.forClass(MarketMinuteSnapshot.class);
                verify(marketMinuteSnapshotRepository, times(2)).save(captor.capture());

                List<MarketMinuteSnapshot> saved = captor.getAllValues();
                MarketMinuteSnapshot second = saved.get(1);

                assertEquals(LocalDateTime.of(2026, 7, 14, 13, 59), second.getMinuteTime());
                assertEquals(LocalDateTime.of(2026, 7, 14, 13, 59, 20), second.getLatestTickTime());
                assertEquals(1775.00, second.getLastPrice());
                assertEquals(30L, second.getLastTradedQuantity());
                assertEquals(120000L, second.getVolumeTradedForDay());
        }

        @Test
        void update_shouldFinalizePreviousMinuteOnRollover() {
                when(timeProvider.nowDateTime())
                                .thenReturn(LocalDateTime.of(2026, 7, 14, 13, 59, 58))
                                .thenReturn(LocalDateTime.of(2026, 7, 14, 14, 0, 3));

                MarketMinuteSnapshot previousRow = MarketMinuteSnapshot.builder()
                                .symbol("WIPRO")
                                .exchange("NSE")
                                .minuteTime(LocalDateTime.of(2026, 7, 14, 13, 59))
                                .latestTickTime(LocalDateTime.of(2026, 7, 14, 13, 59, 58))
                                .tradingDate(LocalDateTime.of(2026, 7, 14, 13, 59).toLocalDate())
                                .createdAt(LocalDateTime.of(2026, 7, 14, 13, 59, 58))
                                .updatedAt(LocalDateTime.of(2026, 7, 14, 13, 59, 58))
                                .isFinalized(false)
                                .build();

                when(marketMinuteSnapshotRepository.findBySymbolAndExchangeAndMinuteTime(
                                "WIPRO", "NSE", LocalDateTime.of(2026, 7, 14, 13, 59)))
                                .thenReturn(Optional.empty())
                                .thenReturn(Optional.of(previousRow));

                when(marketMinuteSnapshotRepository.findBySymbolAndExchangeAndMinuteTime(
                                "WIPRO", "NSE", LocalDateTime.of(2026, 7, 14, 14, 0)))
                                .thenReturn(Optional.empty());

                service.update(new AngelOneTickParserService.NormalizedTick(
                                "WIPRO",
                                "NSE",
                                LocalDateTime.of(2026, 7, 14, 13, 59, 58),
                                1770.90,
                                25L,
                                "16675",
                                3,
                                1,
                                1L,
                                1000L,
                                177090L,
                                null,
                                null,
                                null,
                                null,
                                null,
                                100000L,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null));

                service.update(new AngelOneTickParserService.NormalizedTick(
                                "WIPRO",
                                "NSE",
                                LocalDateTime.of(2026, 7, 14, 14, 0, 3),
                                1775.00,
                                30L,
                                "16675",
                                3,
                                1,
                                2L,
                                2000L,
                                177500L,
                                null,
                                null,
                                null,
                                null,
                                null,
                                120000L,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null));

                verify(marketMinuteSnapshotRepository, atLeastOnce()).save(
                                argThat(row -> row.getMinuteTime().equals(LocalDateTime.of(2026, 7, 14, 13, 59)) &&
                                                Boolean.TRUE.equals(row.getIsFinalized())));

                verify(marketMinuteSnapshotRepository, atLeastOnce())
                                .save(argThat(row -> row.getMinuteTime().equals(LocalDateTime.of(2026, 7, 14, 14, 0)) &&
                                                Boolean.FALSE.equals(row.getIsFinalized())));
        }

        @Test
        void clear_shouldRemoveOnlyInMemorySnapshots() {
                when(timeProvider.nowDateTime()).thenReturn(LocalDateTime.of(2026, 7, 14, 13, 59, 6));
                when(marketMinuteSnapshotRepository.findBySymbolAndExchangeAndMinuteTime(
                                "WIPRO", "NSE", LocalDateTime.of(2026, 7, 14, 13, 59)))
                                .thenReturn(Optional.empty());

                service.update(new AngelOneTickParserService.NormalizedTick(
                                "WIPRO",
                                "NSE",
                                LocalDateTime.of(2026, 7, 14, 13, 59, 6),
                                1770.90,
                                25L,
                                "16675",
                                3,
                                1,
                                1L,
                                1000L,
                                177090L,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null));

                LiveMarketSnapshotService.ClearResult result = service.clear();

                assertEquals(1, result.removed());
                assertEquals(0, service.latest(10).size());
                verify(marketMinuteSnapshotRepository, never()).deleteAll();
        }
}