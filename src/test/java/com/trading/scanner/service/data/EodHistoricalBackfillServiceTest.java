package com.trading.scanner.service.data;

import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.Exchange;
import com.trading.scanner.model.StockUniverse;
import com.trading.scanner.repository.LiveMinuteResolutionRepository;
import com.trading.scanner.repository.MarketCandleRepository;
import com.trading.scanner.repository.StockPriceRepository;
import com.trading.scanner.repository.StockUniverseRepository;
import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.service.instrument.MarketIndexRegistry;
import com.trading.scanner.service.provider.DailyBarDto;
import com.trading.scanner.service.provider.angelone.AngelOneMarketDataProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EodHistoricalBackfillServiceTest {

    private TradingCalendar tradingCalendar;
    private TimeProvider timeProvider;
    private StockUniverseRepository stockUniverseRepository;
    private StockPriceRepository stockPriceRepository;
    private MarketCandleRepository marketCandleRepository;
    private LiveMinuteResolutionRepository resolutionRepository;
    private EodDataEntryService eodDataEntryService;
    private MarketIndexRegistry marketIndexRegistry;
    private AngelOneMarketDataProvider angelOneMarketDataProvider;

    private EodHistoricalBackfillService service;

    @BeforeEach
    void setUp() {
        tradingCalendar = mock(TradingCalendar.class);
        timeProvider = mock(TimeProvider.class);
        stockUniverseRepository = mock(StockUniverseRepository.class);
        stockPriceRepository = mock(StockPriceRepository.class);
        marketCandleRepository = mock(MarketCandleRepository.class);
        resolutionRepository = mock(LiveMinuteResolutionRepository.class);
        eodDataEntryService = mock(EodDataEntryService.class);
        marketIndexRegistry = mock(MarketIndexRegistry.class);
        angelOneMarketDataProvider = mock(AngelOneMarketDataProvider.class);

        when(timeProvider.nowDateTime()).thenReturn(LocalDateTime.of(2026, 11, 10, 17, 30));
        when(angelOneMarketDataProvider.isAvailable()).thenReturn(true);

        service = new EodHistoricalBackfillService(
                tradingCalendar,
                timeProvider,
                stockUniverseRepository,
                stockPriceRepository,
                marketCandleRepository,
                resolutionRepository,
                eodDataEntryService,
                marketIndexRegistry
        );
        ReflectionTestUtils.setField(service, "angelOneMarketDataProvider", angelOneMarketDataProvider);
    }

    @Test
    @DisplayName("GIVEN all 20 historical daily bars exist WHEN backfillHistoricalGaps THEN zero API calls made")
    void testNoGaps_zeroApiCalls() {
        LocalDate targetDate = LocalDate.of(2026, 11, 10);
        LocalDate d1 = LocalDate.of(2026, 11, 9);
        LocalDate d2 = LocalDate.of(2026, 11, 6);
        when(tradingCalendar.previousTradingDay(targetDate)).thenReturn(d1);
        when(tradingCalendar.previousTradingDay(d1)).thenReturn(d2);
        when(tradingCalendar.previousTradingDay(d2)).thenReturn(null);

        StockUniverse stock = StockUniverse.builder().symbol("RELIANCE").exchange(Exchange.NSE).isActive(true).build();
        when(stockUniverseRepository.findByIsActiveTrueOrderBySymbolAsc()).thenReturn(List.of(stock));
        when(marketIndexRegistry.getAllRegisteredIndices()).thenReturn(List.of());

        // Both dates already exist in stock_prices
        when(stockPriceRepository.findDatesBySymbolBetween("RELIANCE", d2, targetDate))
                .thenReturn(List.of(d2, d1));

        EodHistoricalBackfillService.BackfillReport report = service.backfillHistoricalGaps(targetDate);

        assertEquals(0, report.targetsWithGaps());
        assertEquals(0, report.dailyBarsBackfilled());
        verify(angelOneMarketDataProvider, never()).fetchHistoricalBars(anyString(), any(), any());
    }

    @Test
    @DisplayName("GIVEN missing dates in 20-day window WHEN backfillHistoricalGaps THEN fetches gap dates only")
    void testMissingDates_fetchesGapOnly() {
        LocalDate targetDate = LocalDate.of(2026, 11, 10);
        LocalDate d1 = LocalDate.of(2026, 11, 9);
        LocalDate d2 = LocalDate.of(2026, 11, 6);
        when(tradingCalendar.previousTradingDay(targetDate)).thenReturn(d1);
        when(tradingCalendar.previousTradingDay(d1)).thenReturn(d2);
        when(tradingCalendar.previousTradingDay(d2)).thenReturn(null);

        StockUniverse stock = StockUniverse.builder().symbol("NEWSTOCK").exchange(Exchange.NSE).isActive(true).build();
        when(stockUniverseRepository.findByIsActiveTrueOrderBySymbolAsc()).thenReturn(List.of(stock));
        when(marketIndexRegistry.getAllRegisteredIndices()).thenReturn(List.of());

        // Only d2 exists; d1 is missing!
        when(stockPriceRepository.findDatesBySymbolBetween("NEWSTOCK", d2, targetDate))
                .thenReturn(List.of(d2));

        // Mock Angel One response for missing bar d1
        DailyBarDto bar = new DailyBarDto("NEWSTOCK", d1, 100.0, 105.0, 99.0, 102.0, 102.0, 50000L, "ANGEL_ONE");
        when(angelOneMarketDataProvider.fetchHistoricalBars("NEWSTOCK", d1, d1)).thenReturn(List.of(bar));
        when(tradingCalendar.expectedOneMinuteCandleCount(d1)).thenReturn(375);
        when(angelOneMarketDataProvider.fetchHistoricalOneMinuteCandles("NEWSTOCK", d1, d1)).thenReturn(List.of());

        EodHistoricalBackfillService.BackfillReport report = service.backfillHistoricalGaps(targetDate);

        assertEquals(1, report.targetsWithGaps());
        assertEquals(1, report.dailyBarsBackfilled());
        verify(angelOneMarketDataProvider).fetchHistoricalBars("NEWSTOCK", d1, d1);
        verify(stockPriceRepository).save(any());
    }
}
