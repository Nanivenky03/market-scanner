package com.trading.scanner.service.engine;

import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.DailyStockContext;
import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.model.StockPrice;
import com.trading.scanner.model.VolumeDailyBaseline;
import com.trading.scanner.model.VolumeTimeWindowBaseline;
import com.trading.scanner.repository.DailyStockContextRepository;
import com.trading.scanner.repository.MarketCandleRepository;
import com.trading.scanner.repository.StockPriceRepository;
import com.trading.scanner.repository.VolumeDailyBaselineRepository;
import com.trading.scanner.repository.VolumeTimeWindowBaselineRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class VolumeBaselineServiceTest {

    private DailyStockContextRepository dailyStockContextRepository;
    private StockPriceRepository stockPriceRepository;
    private MarketCandleRepository marketCandleRepository;
    private VolumeDailyBaselineRepository volumeDailyBaselineRepository;
    private VolumeTimeWindowBaselineRepository volumeTimeWindowBaselineRepository;
    private TradingCalendar tradingCalendar;
    private TimeProvider timeProvider;
    private VolumeBaselineService service;

    @BeforeEach
    void setUp() {
        dailyStockContextRepository = mock(DailyStockContextRepository.class);
        stockPriceRepository = mock(StockPriceRepository.class);
        marketCandleRepository = mock(MarketCandleRepository.class);
        volumeDailyBaselineRepository = mock(VolumeDailyBaselineRepository.class);
        volumeTimeWindowBaselineRepository = mock(VolumeTimeWindowBaselineRepository.class);
        tradingCalendar = mock(TradingCalendar.class);
        timeProvider = mock(TimeProvider.class);

        service = new VolumeBaselineService(
                dailyStockContextRepository,
                stockPriceRepository,
                marketCandleRepository,
                volumeDailyBaselineRepository,
                volumeTimeWindowBaselineRepository,
                tradingCalendar,
                timeProvider);
    }

    @Test
    void preCalculateForCompletedTradingDay_shouldComputeDailyAndTimeWindowBaselines() {
        LocalDate completedTradingDate = LocalDate.of(2026, 7, 14);
        LocalDate effectiveTradingDate = LocalDate.of(2026, 7, 15);
        LocalDateTime computedAt = LocalDateTime.of(2026, 7, 14, 16, 5);

        when(tradingCalendar.isTradingDay(completedTradingDate)).thenReturn(true);
        when(tradingCalendar.nextTradingDay(completedTradingDate)).thenReturn(effectiveTradingDate);
        when(timeProvider.nowDateTime()).thenReturn(computedAt);

        when(dailyStockContextRepository.findByTradingDateOrderBySymbolAsc(completedTradingDate)).thenReturn(List.of(
                DailyStockContext.builder()
                        .symbol("ONGC")
                        .exchange("NSE")
                        .tradingDate(completedTradingDate)
                        .build()));

        when(stockPriceRepository.findBySymbolAndDateLessThanEqualOrderByDateAsc("ONGC", completedTradingDate))
                .thenReturn(List.of(
                        StockPrice.builder().symbol("ONGC").date(LocalDate.of(2026, 7, 10)).volume(1000).build(),
                        StockPrice.builder().symbol("ONGC").date(LocalDate.of(2026, 7, 11)).volume(2000).build(),
                        StockPrice.builder().symbol("ONGC").date(LocalDate.of(2026, 7, 14)).volume(3000).build()));

        when(volumeDailyBaselineRepository.findBySymbolAndExchangeAndTradingDate("ONGC", "NSE", effectiveTradingDate))
                .thenReturn(Optional.empty());

        when(marketCandleRepository.findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                "ONGC",
                "NSE",
                CandleTimeframe.ONE_MINUTE,
                LocalDate.of(2026, 7, 10).atTime(9, 15),
                completedTradingDate.plusDays(1).atStartOfDay().minusNanos(1))).thenReturn(List.of(
                        candle(LocalDate.of(2026, 7, 10), 9, 15, 100L),
                        candle(LocalDate.of(2026, 7, 10), 9, 16, 150L),
                        candle(LocalDate.of(2026, 7, 11), 9, 15, 200L),
                        candle(LocalDate.of(2026, 7, 11), 9, 16, 60L),
                        candle(LocalDate.of(2026, 7, 14), 9, 15, 300L),
                        candle(LocalDate.of(2026, 7, 14), 9, 16, 90L)));

        when(volumeTimeWindowBaselineRepository.findBySymbolAndExchangeAndTradingDateAndSessionMinute(
                "ONGC", "NSE", effectiveTradingDate, 0)).thenReturn(Optional.empty());
        when(volumeTimeWindowBaselineRepository.findBySymbolAndExchangeAndTradingDateAndSessionMinute(
                "ONGC", "NSE", effectiveTradingDate, 1)).thenReturn(Optional.empty());

        VolumeBaselineService.PreCalculationResult result = service
                .preCalculateForCompletedTradingDay(completedTradingDate);

        assertEquals(completedTradingDate, result.completedTradingDate());
        assertEquals(effectiveTradingDate, result.effectiveTradingDate());
        assertEquals(1, result.processedSymbols());
        assertEquals(1, result.upsertedDailyBaselines());
        assertEquals(2, result.upsertedTimeWindowBaselines());
        assertEquals(0, result.skippedSymbols());

        ArgumentCaptor<VolumeDailyBaseline> dailyCaptor = ArgumentCaptor.forClass(VolumeDailyBaseline.class);
        verify(volumeDailyBaselineRepository, times(1)).save(dailyCaptor.capture());
        assertEquals(2000L, dailyCaptor.getValue().getAvgDailyVolume20());
        assertEquals(3, dailyCaptor.getValue().getSampleDays());

        ArgumentCaptor<VolumeTimeWindowBaseline> timeCaptor = ArgumentCaptor.forClass(VolumeTimeWindowBaseline.class);
        verify(volumeTimeWindowBaselineRepository, times(2)).save(timeCaptor.capture());

        List<VolumeTimeWindowBaseline> savedRows = timeCaptor.getAllValues();
        VolumeTimeWindowBaseline minuteZero = savedRows.stream()
                .filter(row -> row.getSessionMinute() == 0)
                .findFirst()
                .orElseThrow();
        VolumeTimeWindowBaseline minuteOne = savedRows.stream()
                .filter(row -> row.getSessionMinute() == 1)
                .findFirst()
                .orElseThrow();

        assertEquals(200L, minuteZero.getAvgCumulativeVolume20());
        assertEquals(3, minuteZero.getSampleDays());
        assertEquals(300L, minuteOne.getAvgCumulativeVolume20());
        assertEquals(3, minuteOne.getSampleDays());
    }

    private MarketCandle candle(LocalDate tradingDate, int hour, int minute, long volume) {
        LocalDateTime candleTime = tradingDate.atTime(hour, minute);
        return MarketCandle.builder()
                .symbol("ONGC")
                .exchange("NSE")
                .timeframe(CandleTimeframe.ONE_MINUTE)
                .candleTime(candleTime)
                .openPrice(100.0)
                .highPrice(101.0)
                .lowPrice(99.0)
                .closePrice(100.0)
                .volume(volume)
                .isFinalized(true)
                .build();
    }
}
