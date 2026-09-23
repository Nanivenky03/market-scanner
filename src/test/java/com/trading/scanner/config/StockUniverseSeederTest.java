package com.trading.scanner.config;

import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.InstrumentMaster;
import com.trading.scanner.model.StockUniverse;
import com.trading.scanner.repository.InstrumentMasterRepository;
import com.trading.scanner.repository.StockUniverseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StockUniverseSeederTest {

    private StockUniverseRepository stockUniverseRepository;
    private InstrumentMasterRepository instrumentMasterRepository;
    private TimeProvider timeProvider;
    private TradingCalendar tradingCalendar;
    private StockUniverseSeeder seeder;

    @BeforeEach
    void setUp() {
        stockUniverseRepository = mock(StockUniverseRepository.class);
        instrumentMasterRepository = mock(InstrumentMasterRepository.class);
        timeProvider = mock(TimeProvider.class);
        tradingCalendar = mock(TradingCalendar.class);

        when(timeProvider.today()).thenReturn(LocalDate.of(2026, 9, 23));
        when(timeProvider.nowDateTime()).thenReturn(LocalDateTime.of(2026, 9, 23, 6, 30));
        when(tradingCalendar.nextTradingDay(any(LocalDate.class))).thenReturn(LocalDate.of(2026, 9, 24));

        seeder = new StockUniverseSeeder(
                stockUniverseRepository,
                instrumentMasterRepository,
                timeProvider,
                tradingCalendar);
    }

    @Test
    void seedIfNeeded_shouldSkipWhenUniverseAlreadyExists() {
        when(stockUniverseRepository.count()).thenReturn(1L);

        StockUniverseSeeder.SeedResult result = seeder.seedIfNeeded();

        assertEquals(0, result.inserted());
        verify(instrumentMasterRepository, never())
                .findBySymbolAndExchange(anyString(), anyString());
        verify(stockUniverseRepository, never())
                .saveAll(anyList());
    }

    @Test
    void seedIfNeeded_shouldResolveConfiguredSymbolsFromInstrumentMaster() {
        when(stockUniverseRepository.count()).thenReturn(0L);
        when(instrumentMasterRepository.findBySymbolAndExchange(
                anyString(),
                eq("NSE")))
                .thenAnswer(invocation -> {
                    String symbol = invocation.getArgument(0);
                    return Optional.of(activeEquity(
                            symbol,
                            "Master " + symbol));
                });

        doAnswer(invocation -> invocation.getArgument(0))
                .when(stockUniverseRepository)
                .saveAll(anyList());

        StockUniverseSeeder.SeedResult result = seeder.seedIfNeeded();

        assertEquals(50, result.inserted());

        verify(stockUniverseRepository)
                .saveAll(anyList());
    }

    @Test
    void seedIfNeeded_shouldUseInstrumentMasterCompanyNameAndNonTradableDefault() {
        when(stockUniverseRepository.count()).thenReturn(0L);
        when(instrumentMasterRepository.findBySymbolAndExchange(
                eq("ABB"),
                eq("NSE")))
                .thenReturn(Optional.of(activeEquity(
                        "ABB",
                        "ABB From Instrument Master")));
        when(instrumentMasterRepository.findBySymbolAndExchange(
                anyString(),
                eq("NSE")))
                .thenReturn(Optional.of(activeEquity(
                        "ABB",
                        "ABB From Instrument Master")));

        List<StockUniverse>[] savedRows = new List[1];
        doAnswer(invocation -> {
            savedRows[0] = invocation.getArgument(0);
            return savedRows[0];
        }).when(stockUniverseRepository).saveAll(anyList());

        seeder.seedIfNeeded();

        StockUniverse abb = savedRows[0].stream()
                .filter(row -> "ABB".equals(row.getSymbol()))
                .findFirst()
                .orElseThrow();

        assertEquals(
                "ABB From Instrument Master",
                abb.getCompanyName());
        assertEquals(Boolean.FALSE, abb.getIsTradable());
    }

    @Test
    void seedIfNeeded_shouldFailWhenConfiguredSymbolIsMissing() {
        when(stockUniverseRepository.count()).thenReturn(0L);
        when(instrumentMasterRepository.findBySymbolAndExchange(
                anyString(),
                eq("NSE")))
                .thenReturn(Optional.empty());

        assertThrows(
                IllegalStateException.class,
                () -> seeder.seedIfNeeded());

        verify(stockUniverseRepository, never())
                .saveAll(anyList());
    }

    @Test
    void seedIfNeeded_shouldFailWhenInstrumentIsInactive() {
        when(stockUniverseRepository.count()).thenReturn(0L);
        when(instrumentMasterRepository.findBySymbolAndExchange(
                anyString(),
                eq("NSE")))
                .thenReturn(Optional.of(
                        InstrumentMaster.builder()
                                .symbol("ABB")
                                .exchange("NSE")
                                .companyName("ABB")
                                .instrumentType("EQUITY")
                                .isActive(false)
                                .build()));

        assertThrows(
                IllegalStateException.class,
                () -> seeder.seedIfNeeded());

        verify(stockUniverseRepository, never())
                .saveAll(anyList());
    }

    @Test
    void seedIfNeeded_shouldFailWhenInstrumentIsNotEquity() {
        when(stockUniverseRepository.count()).thenReturn(0L);
        when(instrumentMasterRepository.findBySymbolAndExchange(
                anyString(),
                eq("NSE")))
                .thenReturn(Optional.of(
                        InstrumentMaster.builder()
                                .symbol("ABB")
                                .exchange("NSE")
                                .companyName("ABB")
                                .instrumentType("INDEX")
                                .isActive(true)
                                .build()));

        assertThrows(
                IllegalStateException.class,
                () -> seeder.seedIfNeeded());

        verify(stockUniverseRepository, never())
                .saveAll(anyList());
    }

    @Test
    void seedIfNeeded_shouldSetActiveFromToToday_whenBefore7AM() {
        when(stockUniverseRepository.count()).thenReturn(0L);
        when(timeProvider.nowDateTime()).thenReturn(LocalDateTime.of(2026, 9, 23, 6, 45));
        when(timeProvider.today()).thenReturn(LocalDate.of(2026, 9, 23));
        when(instrumentMasterRepository.findBySymbolAndExchange(anyString(), eq("NSE")))
                .thenAnswer(inv -> Optional.of(activeEquity(inv.getArgument(0), "Company")));

        List<StockUniverse>[] saved = new List[1];
        doAnswer(inv -> {
            saved[0] = inv.getArgument(0);
            return saved[0];
        }).when(stockUniverseRepository).saveAll(anyList());

        seeder.seedIfNeeded();

        assertNotNull(saved[0]);
        assertEquals(LocalDate.of(2026, 9, 23), saved[0].get(0).getActiveFrom());
    }

    @Test
    void seedIfNeeded_shouldSetActiveFromToNextTradingDay_whenAtOrAfter7AM() {
        when(stockUniverseRepository.count()).thenReturn(0L);
        when(timeProvider.nowDateTime()).thenReturn(LocalDateTime.of(2026, 9, 23, 7, 0));
        when(timeProvider.today()).thenReturn(LocalDate.of(2026, 9, 23));
        when(tradingCalendar.nextTradingDay(LocalDate.of(2026, 9, 23))).thenReturn(LocalDate.of(2026, 9, 24));
        when(instrumentMasterRepository.findBySymbolAndExchange(anyString(), eq("NSE")))
                .thenAnswer(inv -> Optional.of(activeEquity(inv.getArgument(0), "Company")));

        List<StockUniverse>[] saved = new List[1];
        doAnswer(inv -> {
            saved[0] = inv.getArgument(0);
            return saved[0];
        }).when(stockUniverseRepository).saveAll(anyList());

        seeder.seedIfNeeded();

        assertNotNull(saved[0]);
        assertEquals(LocalDate.of(2026, 9, 24), saved[0].get(0).getActiveFrom());
    }

    private InstrumentMaster activeEquity(
            String symbol,
            String companyName) {

        return InstrumentMaster.builder()
                .symbol(symbol)
                .exchange("NSE")
                .companyName(companyName)
                .instrumentType("EQUITY")
                .isActive(true)
                .build();
    }
}
