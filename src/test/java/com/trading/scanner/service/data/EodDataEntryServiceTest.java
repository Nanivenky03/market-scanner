package com.trading.scanner.service.data;

import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.DataStatus;
import com.trading.scanner.model.EodDataEntry;
import com.trading.scanner.model.EodDataStatus;
import com.trading.scanner.model.Exchange;
import com.trading.scanner.model.StockUniverse;
import com.trading.scanner.repository.EodDataEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class EodDataEntryServiceTest {

        private EodDataEntryRepository repository;
        private TimeProvider timeProvider;
        private EodDataEntryService service;

        @BeforeEach
        void setUp() {
                repository = mock(EodDataEntryRepository.class);
                timeProvider = mock(TimeProvider.class);
                service = new EodDataEntryService(repository, timeProvider);
        }

        @Test
        void complete_shouldStoreSuccessfulEodEntry() {
                LocalDate date = LocalDate.of(2026, 8, 26);
                LocalDateTime now = date.atTime(16, 0);

                when(timeProvider.nowDateTime()).thenReturn(now);
                when(repository.findBySymbolAndExchangeAndTradingDate(
                                "ONGC", "NSE", date))
                                .thenReturn(Optional.empty());
                when(repository.save(any(EodDataEntry.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                EodDataEntry result = service.complete(
                                "ONGC",
                                "NSE",
                                date,
                                DataStatus.REPAIRED,
                                375,
                                374,
                                1,
                                0,
                                2,
                                372,
                                "EOD_RECONCILIATION",
                                "repaired");

                assertEquals(EodDataStatus.REPAIRED, result.getStatus());
                assertEquals(375, result.getExpectedMinuteCount());
                assertEquals(1, result.getNoTradeMinuteCount());
                assertEquals(0, result.getUnresolvedMinuteCount());
        }

        @Test
        void latestSuccessfulDate_shouldIgnoreFailedEntries() {
                LocalDate latest = LocalDate.of(2026, 8, 26);
                LocalDate older = LocalDate.of(2026, 8, 25);

                when(repository.findBySymbolAndExchangeOrderByTradingDateDesc(
                                "ONGC", "NSE"))
                                .thenReturn(List.of(
                                                EodDataEntry.builder()
                                                                .tradingDate(latest)
                                                                .status(EodDataStatus.FAILED)
                                                                .build(),
                                                EodDataEntry.builder()
                                                                .tradingDate(older)
                                                                .status(EodDataStatus.COMPLETE)
                                                                .build()));

                assertTrue(service.latestSuccessfulDate(
                                "ONGC", "NSE").isPresent());

                assertEquals(
                                older,
                                service.latestSuccessfulDate(
                                                "ONGC", "NSE").orElseThrow());
        }

        @Test
        void ensureInitialBaselineIfEmpty_shouldSeedWhenEntriesMissing() {
                StockUniverse stock = StockUniverse.builder()
                                .symbol("RELIANCE")
                                .exchange(Exchange.NSE)
                                .isActive(true)
                                .build();

                when(repository.findBySymbolAndExchangeAndTradingDate(anyString(), anyString(), any(LocalDate.class)))
                                .thenReturn(Optional.empty());
                when(repository.save(any(EodDataEntry.class))).thenAnswer(inv -> inv.getArgument(0));

                int seeded = service.ensureInitialBaselineIfEmpty(List.of(stock), LocalDate.of(2026, 9, 19));

                assertEquals(1, seeded); // RELIANCE only
                verify(repository, times(1)).save(any(EodDataEntry.class));
        }
}