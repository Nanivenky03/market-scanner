package com.trading.scanner.service.runtime;

import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.Exchange;
import com.trading.scanner.model.StockUniverse;
import com.trading.scanner.repository.MarketCandleRepository;
import com.trading.scanner.repository.StockPriceRepository;
import com.trading.scanner.repository.StockUniverseRepository;
import com.trading.scanner.service.engine.DailyStockContextService;
import com.trading.scanner.service.provider.angelone.AngelOneMarketDataProvider;
import com.trading.scanner.service.provider.angelone.dto.AngelOneMarketDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MarketQuoteSyncServiceTest {

    private MarketCandleRepository marketCandleRepository;
    private StockPriceRepository stockPriceRepository;
    private StockUniverseRepository stockUniverseRepository;
    private AngelOneMarketDataProvider marketDataProvider;
    private DailyStockContextService dailyStockContextService;
    private TimeProvider timeProvider;

    private MarketQuoteSyncService service;

    @BeforeEach
    void setUp() {
        marketCandleRepository = mock(MarketCandleRepository.class);
        stockPriceRepository = mock(StockPriceRepository.class);
        stockUniverseRepository = mock(StockUniverseRepository.class);
        marketDataProvider = mock(AngelOneMarketDataProvider.class);
        dailyStockContextService = mock(DailyStockContextService.class);
        timeProvider = mock(TimeProvider.class);

        when(timeProvider.nowDateTime()).thenReturn(LocalDateTime.of(2026, 10, 12, 11, 0));

        service = new MarketQuoteSyncService(
                marketCandleRepository,
                stockPriceRepository,
                stockUniverseRepository,
                marketDataProvider,
                dailyStockContextService,
                timeProvider
        );
    }

    @Test
    @DisplayName("Should return failure when requested date is null")
    void syncMarketQuotesIfMissing_nullDate_returnsFailure() {
        MarketQuoteSyncService.QuoteSyncResult result = service.syncMarketQuotesIfMissing(null);
        assertFalse(result.executed());
        assertEquals("Target date is null", result.message());
        verifyNoInteractions(marketDataProvider);
    }

    @Test
    @DisplayName("Should skip API call when 09:08 premarket candles already exist (Single-run guard)")
    void syncMarketQuotesIfMissing_alreadyPresent_skipsApiCall() {
        LocalDate date = LocalDate.of(2026, 10, 12);
        when(marketCandleRepository.countByTimeframeAndCandleTime(
                eq(CandleTimeframe.ONE_MINUTE), eq(date.atTime(9, 8)))).thenReturn(50L);

        MarketQuoteSyncService.QuoteSyncResult result = service.syncMarketQuotesIfMissing(date);

        assertFalse(result.executed());
        assertTrue(result.message().contains("already present"));
        verifyNoInteractions(marketDataProvider);
        verifyNoInteractions(stockUniverseRepository);
    }

    @Test
    @DisplayName("Should fetch quotes and synthesize 09:08 candles when missing")
    void syncMarketQuotesIfMissing_whenMissing_synthesizesCandlesAndUpsertsPrices() {
        LocalDate date = LocalDate.of(2026, 10, 12);
        LocalDateTime preMarketTime = date.atTime(9, 8);
        when(marketCandleRepository.countByTimeframeAndCandleTime(
                eq(CandleTimeframe.ONE_MINUTE), eq(preMarketTime))).thenReturn(0L);

        StockUniverse stock1 = StockUniverse.builder()
                .symbol("TCS")
                .exchange(Exchange.NSE)
                .isActive(true)
                .build();
        StockUniverse stock2 = StockUniverse.builder()
                .symbol("INFY")
                .exchange(Exchange.NSE)
                .isActive(true)
                .build();
        when(stockUniverseRepository.findByIsActiveTrue()).thenReturn(List.of(stock1, stock2));

        AngelOneMarketDtos.AngelOneMarketQuoteItem quote1 = new AngelOneMarketDtos.AngelOneMarketQuoteItem(
                "NSE", "TCS", "11536", 3500.0, 3550.0, 3490.0, 3520.0, 3520.0, 3505.0, 100000L, 50000.0, 100000.0);
        AngelOneMarketDtos.AngelOneMarketQuoteItem quote2 = new AngelOneMarketDtos.AngelOneMarketQuoteItem(
                "NSE", "INFY", "1594", 1600.0, 1620.0, 1595.0, 1610.0, 1610.0, 1605.0, 200000L, 75000.0, 200000.0);

        when(marketDataProvider.fetchMarketQuotes(List.of("TCS", "INFY"), "NSE"))
                .thenReturn(Map.of("TCS", quote1, "INFY", quote2));

        when(marketCandleRepository.findBySymbolAndExchangeAndTimeframeAndCandleTime(anyString(), anyString(), any(), any()))
                .thenReturn(Optional.empty());

        MarketQuoteSyncService.QuoteSyncResult result = service.syncMarketQuotesIfMissing(date);

        assertTrue(result.executed());
        assertEquals(2, result.totalSymbols());
        assertEquals(2, result.preMarketCandlesSaved());
        assertEquals(2, result.stockPricesUpserted());

        // Verify candles saved with 09:08 time and open price
        verify(marketCandleRepository, times(2)).save(argThat(c ->
                c.getCandleTime().equals(preMarketTime) &&
                c.getOpenPrice() > 0.0 &&
                c.getHighPrice().equals(c.getOpenPrice()) &&
                c.getLowPrice().equals(c.getOpenPrice()) &&
                c.getClosePrice().equals(c.getOpenPrice()) &&
                "MARKET_QUOTE_RECOVERY".equals(c.getSource())
        ));

        // Verify stock prices upserted
        verify(stockPriceRepository, times(2)).save(any());
    }
}
