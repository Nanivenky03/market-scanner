package com.trading.scanner.service.data;

import com.trading.scanner.config.HistoricalBackfillProperties;
import com.trading.scanner.model.StockPrice;
import com.trading.scanner.repository.StockPriceRepository;
import com.trading.scanner.service.provider.DailyBarDto;
import com.trading.scanner.service.provider.angelone.AngelOneMarketDataProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HistoricalBackfillServiceTest {

    @Mock
    private AngelOneMarketDataProvider angelOneMarketDataProvider;

    @Mock
    private StockPriceRepository stockPriceRepository;

    @Mock
    private HistoricalBackfillProperties historicalBackfillProperties;

    @InjectMocks
    private HistoricalBackfillService historicalBackfillService;

    @Test
    void backfillSymbol_shouldInsertNewRowsWhenNoExistingRows() {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 1, 2);

        List<DailyBarDto> bars = List.of(
                new DailyBarDto("ONGC", LocalDate.of(2024, 1, 1), 100.0, 105.0, 99.0, 104.0, 104.0, 1_000_000L,
                        "ANGEL_ONE"),
                new DailyBarDto("ONGC", LocalDate.of(2024, 1, 2), 104.0, 107.0, 103.0, 106.0, 106.0, 1_100_000L,
                        "ANGEL_ONE"));

        when(angelOneMarketDataProvider.fetchHistoricalBars("ONGC", from, to)).thenReturn(bars);
        when(stockPriceRepository.findBySymbolAndDate("ONGC", LocalDate.of(2024, 1, 1))).thenReturn(Optional.empty());
        when(stockPriceRepository.findBySymbolAndDate("ONGC", LocalDate.of(2024, 1, 2))).thenReturn(Optional.empty());
        when(stockPriceRepository.save(any(StockPrice.class))).thenAnswer(invocation -> invocation.getArgument(0));

        HistoricalBackfillResult result = historicalBackfillService.backfillSymbol("ONGC", from, to);

        assertNotNull(result);
        assertEquals("ONGC", result.symbol());
        assertEquals(2, result.requestedBars());
        assertEquals(2, result.inserted());
        assertEquals(0, result.updated());

        ArgumentCaptor<StockPrice> captor = ArgumentCaptor.forClass(StockPrice.class);
        verify(stockPriceRepository, times(2)).save(captor.capture());

        List<StockPrice> savedRows = captor.getAllValues();
        assertEquals(2, savedRows.size());

        StockPrice first = savedRows.get(0);
        assertEquals("ONGC", first.getSymbol());
        assertEquals(LocalDate.of(2024, 1, 1), first.getDate());
        assertEquals(100.0, first.getOpenPrice());
        assertEquals(105.0, first.getHighPrice());
        assertEquals(99.0, first.getLowPrice());
        assertEquals(104.0, first.getClosePrice());
        assertEquals(104.0, first.getAdjClose());
        assertEquals(1_000_000, first.getVolume());

        StockPrice second = savedRows.get(1);
        assertEquals("ONGC", second.getSymbol());
        assertEquals(LocalDate.of(2024, 1, 2), second.getDate());
        assertEquals(104.0, second.getOpenPrice());
        assertEquals(107.0, second.getHighPrice());
        assertEquals(103.0, second.getLowPrice());
        assertEquals(106.0, second.getClosePrice());
        assertEquals(106.0, second.getAdjClose());
        assertEquals(1_100_000, second.getVolume());
    }

    @Test
    void backfillSymbol_shouldUpdateExistingRowsInsteadOfCreatingDuplicates() {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 1, 1);

        DailyBarDto updatedBar = new DailyBarDto(
                "ONGC",
                LocalDate.of(2024, 1, 1),
                100.0,
                105.0,
                99.0,
                104.0,
                104.0,
                1_000_000L,
                "ANGEL_ONE");

        StockPrice existing = StockPrice.builder()
                .symbol("ONGC")
                .date(LocalDate.of(2024, 1, 1))
                .openPrice(90.0)
                .highPrice(91.0)
                .lowPrice(89.0)
                .closePrice(90.5)
                .adjClose(90.5)
                .volume(500_000)
                .build();

        when(angelOneMarketDataProvider.fetchHistoricalBars("ONGC", from, to)).thenReturn(List.of(updatedBar));
        when(stockPriceRepository.findBySymbolAndDate("ONGC", LocalDate.of(2024, 1, 1)))
                .thenReturn(Optional.of(existing));
        when(stockPriceRepository.save(any(StockPrice.class))).thenAnswer(invocation -> invocation.getArgument(0));

        HistoricalBackfillResult result = historicalBackfillService.backfillSymbol("ONGC", from, to);

        assertNotNull(result);
        assertEquals("ONGC", result.symbol());
        assertEquals(1, result.requestedBars());
        assertEquals(0, result.inserted());
        assertEquals(1, result.updated());

        ArgumentCaptor<StockPrice> captor = ArgumentCaptor.forClass(StockPrice.class);
        verify(stockPriceRepository, times(1)).save(captor.capture());

        StockPrice saved = captor.getValue();
        assertEquals("ONGC", saved.getSymbol());
        assertEquals(LocalDate.of(2024, 1, 1), saved.getDate());
        assertEquals(100.0, saved.getOpenPrice());
        assertEquals(105.0, saved.getHighPrice());
        assertEquals(99.0, saved.getLowPrice());
        assertEquals(104.0, saved.getClosePrice());
        assertEquals(104.0, saved.getAdjClose());
        assertEquals(1_000_000, saved.getVolume());
    }

    @Test
    void backfillSymbolUsingDefaultWindow_shouldUseConfiguredMonths() {
        LocalDate expectedTo = LocalDate.now();
        LocalDate expectedFrom = expectedTo.minusMonths(36);

        when(historicalBackfillProperties.months()).thenReturn(36);
        when(angelOneMarketDataProvider.fetchHistoricalBars("ONGC", expectedFrom, expectedTo)).thenReturn(List.of());

        HistoricalBackfillResult result = historicalBackfillService.backfillSymbolUsingDefaultWindow("ONGC");

        assertNotNull(result);
        assertEquals("ONGC", result.symbol());
        assertEquals(0, result.requestedBars());
        assertEquals(0, result.inserted());
        assertEquals(0, result.updated());

        verify(angelOneMarketDataProvider).fetchHistoricalBars(eq("ONGC"), eq(expectedFrom), eq(expectedTo));
        verify(stockPriceRepository, never()).save(any(StockPrice.class));
    }

    @Test
    void backfillSymbols_shouldNormalizeDeduplicateAndSortSymbols() {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 1, 2);

        when(angelOneMarketDataProvider.fetchHistoricalBars("ITC", from, to)).thenReturn(List.of());
        when(angelOneMarketDataProvider.fetchHistoricalBars("ONGC", from, to)).thenReturn(List.of());
        when(angelOneMarketDataProvider.fetchHistoricalBars("WIPRO", from, to)).thenReturn(List.of());

        List<HistoricalBackfillResult> results = historicalBackfillService.backfillSymbols(
                java.util.Arrays.asList(" wipro ", "ongc", "ITC", "ONGC", "", null),
                from,
                to);

        assertEquals(3, results.size());
        assertEquals("ITC", results.get(0).symbol());
        assertEquals("ONGC", results.get(1).symbol());
        assertEquals("WIPRO", results.get(2).symbol());

        verify(angelOneMarketDataProvider, times(1)).fetchHistoricalBars("ITC", from, to);
        verify(angelOneMarketDataProvider, times(1)).fetchHistoricalBars("ONGC", from, to);
        verify(angelOneMarketDataProvider, times(1)).fetchHistoricalBars("WIPRO", from, to);
        verify(stockPriceRepository, never()).save(any(StockPrice.class));
    }
}