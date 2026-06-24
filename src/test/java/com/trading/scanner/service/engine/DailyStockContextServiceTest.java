package com.trading.scanner.service.engine;

import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.DailyStockContext;
import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.repository.DailyStockContextRepository;
import com.trading.scanner.repository.MarketCandleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DailyStockContextServiceTest {

    @Mock
    private DailyStockContextRepository dailyStockContextRepository;

    @Mock
    private MarketCandleRepository marketCandleRepository;

    @Mock
    private TimeProvider timeProvider;

    @InjectMocks
    private DailyStockContextService dailyStockContextService;

    @Test
    void processFinalizedOneMinuteCandle_shouldBuildFirstCandleContext() {
        LocalDate tradingDate = LocalDate.of(2026, 6, 24);
        LocalDateTime current = LocalDateTime.of(2026, 6, 24, 9, 19);
        when(timeProvider.nowDateTime()).thenReturn(LocalDateTime.of(2026, 6, 24, 9, 20));

        MarketCandle trigger = candle("ONGC", current, 100, 102, 99, 101, 100L);

        List<MarketCandle> firstFive = List.of(
                candle("ONGC", LocalDateTime.of(2026, 6, 24, 9, 15), 100, 101, 99, 100.5, 10L),
                candle("ONGC", LocalDateTime.of(2026, 6, 24, 9, 16), 100.5, 101.5, 100, 101, 20L),
                candle("ONGC", LocalDateTime.of(2026, 6, 24, 9, 17), 101, 101.8, 100.6, 101.2, 30L),
                candle("ONGC", LocalDateTime.of(2026, 6, 24, 9, 18), 101.2, 101.9, 100.8, 101.1, 40L),
                candle("ONGC", LocalDateTime.of(2026, 6, 24, 9, 19), 101.1, 102, 100.9, 101.5, 50L));

        when(marketCandleRepository.findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                "ONGC",
                "NSE",
                CandleTimeframe.ONE_MINUTE,
                tradingDate.atTime(9, 15),
                tradingDate.atTime(9, 19))).thenReturn(firstFive);

        when(dailyStockContextRepository.findBySymbolAndExchangeAndTradingDate("ONGC", "NSE", tradingDate))
                .thenReturn(Optional.empty());

        when(dailyStockContextRepository.save(any(DailyStockContext.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        dailyStockContextService.processFinalizedOneMinuteCandle(trigger);

        ArgumentCaptor<DailyStockContext> captor = ArgumentCaptor.forClass(DailyStockContext.class);
        verify(dailyStockContextRepository).save(captor.capture());

        DailyStockContext saved = captor.getValue();
        assertEquals("ONGC", saved.getSymbol());
        assertEquals(tradingDate, saved.getTradingDate());
        assertEquals(100.0, saved.getFirstCandleOpen());
        assertEquals(102.0, saved.getFirstCandleHigh());
        assertEquals(99.0, saved.getFirstCandleLow());
        assertEquals(101.5, saved.getFirstCandleClose());
        assertEquals(150L, saved.getFirstCandleVolume());
        assertEquals(3.0, saved.getFirstCandleRange());
        assertEquals(true, saved.getFirstCandleReady());
        assertEquals(false, saved.getOpeningRangeReady());
    }

    @Test
    void processFinalizedOneMinuteCandle_shouldBuildOpeningRangeContext() {
        LocalDate tradingDate = LocalDate.of(2026, 6, 24);
        LocalDateTime current = LocalDateTime.of(2026, 6, 24, 9, 29);
        when(timeProvider.nowDateTime()).thenReturn(LocalDateTime.of(2026, 6, 24, 9, 30));

        MarketCandle trigger = candle("ONGC", current, 100, 103, 98, 102, 100L);

        List<MarketCandle> fifteen = List.of(
                candle("ONGC", LocalDateTime.of(2026, 6, 24, 9, 15), 100, 101, 99, 100.5, 10L),
                candle("ONGC", LocalDateTime.of(2026, 6, 24, 9, 16), 100.5, 101.5, 100, 101, 20L),
                candle("ONGC", LocalDateTime.of(2026, 6, 24, 9, 17), 101, 101.8, 100.6, 101.2, 30L),
                candle("ONGC", LocalDateTime.of(2026, 6, 24, 9, 18), 101.2, 101.9, 100.8, 101.1, 40L),
                candle("ONGC", LocalDateTime.of(2026, 6, 24, 9, 19), 101.1, 102, 100.9, 101.5, 50L),
                candle("ONGC", LocalDateTime.of(2026, 6, 24, 9, 20), 101.5, 102.2, 101, 101.8, 60L),
                candle("ONGC", LocalDateTime.of(2026, 6, 24, 9, 21), 101.8, 102.5, 101.4, 102.2, 70L),
                candle("ONGC", LocalDateTime.of(2026, 6, 24, 9, 22), 102.2, 102.8, 101.7, 102.4, 80L),
                candle("ONGC", LocalDateTime.of(2026, 6, 24, 9, 23), 102.4, 103, 101.9, 102.7, 90L),
                candle("ONGC", LocalDateTime.of(2026, 6, 24, 9, 24), 102.7, 102.9, 101.8, 102.0, 100L),
                candle("ONGC", LocalDateTime.of(2026, 6, 24, 9, 25), 102.0, 102.3, 100.7, 101.0, 110L),
                candle("ONGC", LocalDateTime.of(2026, 6, 24, 9, 26), 101.0, 101.2, 99.8, 100.0, 120L),
                candle("ONGC", LocalDateTime.of(2026, 6, 24, 9, 27), 100.0, 100.5, 99.0, 99.5, 130L),
                candle("ONGC", LocalDateTime.of(2026, 6, 24, 9, 28), 99.5, 100.0, 98.0, 98.8, 140L),
                candle("ONGC", LocalDateTime.of(2026, 6, 24, 9, 29), 98.8, 99.0, 98.0, 98.5, 150L));

        when(marketCandleRepository.findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                "ONGC",
                "NSE",
                CandleTimeframe.ONE_MINUTE,
                tradingDate.atTime(9, 15),
                tradingDate.atTime(9, 29))).thenReturn(fifteen);

        when(dailyStockContextRepository.findBySymbolAndExchangeAndTradingDate("ONGC", "NSE", tradingDate))
                .thenReturn(Optional.empty());

        when(dailyStockContextRepository.save(any(DailyStockContext.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        dailyStockContextService.processFinalizedOneMinuteCandle(trigger);

        ArgumentCaptor<DailyStockContext> captor = ArgumentCaptor.forClass(DailyStockContext.class);
        verify(dailyStockContextRepository).save(captor.capture());

        DailyStockContext saved = captor.getValue();
        assertEquals(true, saved.getOpeningRangeReady());
        assertEquals(103.0, saved.getOpeningRangeHigh());
        assertEquals(98.0, saved.getOpeningRangeLow());
        assertEquals(5.0, saved.getOpeningRangeSize());
    }

    private MarketCandle candle(
            String symbol,
            LocalDateTime candleTime,
            double open,
            double high,
            double low,
            double close,
            long volume) {
        return MarketCandle.builder()
                .symbol(symbol)
                .exchange("NSE")
                .timeframe(CandleTimeframe.ONE_MINUTE)
                .candleTime(candleTime)
                .openPrice(open)
                .highPrice(high)
                .lowPrice(low)
                .closePrice(close)
                .volume(volume)
                .isFinalized(true)
                .build();
    }
}