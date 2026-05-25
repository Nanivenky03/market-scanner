package com.trading.scanner.service.provider;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MockMarketDataProviderTest {

    private final MockMarketDataProvider provider = new MockMarketDataProvider();

    @Test
    void fetchDailyBars_shouldReturnDeterministicBarsForSameInputs() {
        LocalDate tradingDate = LocalDate.of(2024, 4, 5);
        List<String> symbols = List.of("TCS", "RELIANCE", "TCS");

        List<DailyBarDto> first = provider.fetchDailyBars(tradingDate, symbols);
        List<DailyBarDto> second = provider.fetchDailyBars(tradingDate, symbols);

        assertEquals(first, second);
        assertEquals(2, first.size());
        assertEquals("RELIANCE", first.get(0).symbol());
        assertEquals("TCS", first.get(1).symbol());
    }

    @Test
    void fetchHistoricalBars_shouldReturnInclusiveDateRange() {
        LocalDate from = LocalDate.of(2024, 4, 1);
        LocalDate to = LocalDate.of(2024, 4, 3);

        List<DailyBarDto> bars = provider.fetchHistoricalBars("INFY", from, to);

        assertEquals(3, bars.size());
        assertEquals(LocalDate.of(2024, 4, 1), bars.get(0).tradingDate());
        assertEquals(LocalDate.of(2024, 4, 2), bars.get(1).tradingDate());
        assertEquals(LocalDate.of(2024, 4, 3), bars.get(2).tradingDate());

        assertTrue(bars.stream().allMatch(b -> "INFY".equals(b.symbol())));
        assertTrue(bars.stream().allMatch(b -> b.close() != null));
        assertTrue(bars.stream().allMatch(b -> b.volume() != null));
    }

    @Test
    void fetchDailyBars_shouldReturnEmptyWhenSymbolsEmpty() {
        LocalDate tradingDate = LocalDate.of(2024, 4, 5);

        List<DailyBarDto> bars = provider.fetchDailyBars(tradingDate, List.of());

        assertNotNull(bars);
        assertTrue(bars.isEmpty());
    }
}