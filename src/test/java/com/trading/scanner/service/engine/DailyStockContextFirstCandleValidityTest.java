package com.trading.scanner.service.engine;

import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.CandleProcessingStatus;
import com.trading.scanner.model.CandleQualityStatus;
import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.DailyStockContext;
import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.repository.DailyStockContextRepository;
import com.trading.scanner.repository.MarketCandleRepository;
import com.trading.scanner.repository.StockPriceRepository;
import com.trading.scanner.repository.VolumeTimeWindowBaselineRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DailyStockContextFirstCandleValidityTest {

    private DailyStockContextRepository contextRepository;
    private MarketCandleRepository candleRepository;
    private StockPriceRepository stockPriceRepository;
    private VolumeTimeWindowBaselineRepository baselineRepository;
    private MarketStateService marketStateService;
    private TradingCalendar tradingCalendar;
    private TimeProvider timeProvider;
    private DailyStockContextService service;

    @BeforeEach
    void setUp() {
        contextRepository = mock(DailyStockContextRepository.class);

        candleRepository = mock(MarketCandleRepository.class);

        stockPriceRepository = mock(StockPriceRepository.class);

        baselineRepository = mock(VolumeTimeWindowBaselineRepository.class);

        marketStateService = mock(MarketStateService.class);

        tradingCalendar = mock(TradingCalendar.class);

        timeProvider = mock(TimeProvider.class);

        service = new DailyStockContextService(
                contextRepository,
                candleRepository,
                stockPriceRepository,
                baselineRepository,
                marketStateService,
                tradingCalendar,
                timeProvider);
    }

    @Test
    void firstCandle_shouldBeValidWhenAllRequirementsPass() {
        DailyStockContext context = processScenario(
                100.0,
                101.2,
                99.99,
                101.0,
                80_000L);

        assertTrue(context.getFirstCandleReady());
        assertTrue(context.getFirstCandleBullish());
        assertTrue(context.getFirstCandleValid());
    }

    @Test
    void firstCandle_shouldBeInvalidWhenRangeIsTooTight() {
        DailyStockContext context = processScenario(
                100.0,
                100.10,
                100.0,
                100.05,
                80_000L);

        assertTrue(context.getFirstCandleReady());
        assertFalse(context.getFirstCandleValid());
    }

    @Test
    void firstCandle_shouldBeInvalidWhenRangeIsTooWide() {
        DailyStockContext context = processScenario(
                100.0,
                103.5,
                100.0,
                103.0,
                80_000L);

        assertTrue(context.getFirstCandleReady());
        assertFalse(context.getFirstCandleValid());
    }

    @Test
    void firstCandle_shouldBeInvalidWhenVolumeIsBelow50000() {
        DailyStockContext context = processScenario(
                100.0,
                101.2,
                99.99,
                101.0,
                49_999L);

        assertTrue(context.getFirstCandleReady());
        assertFalse(context.getFirstCandleValid());
    }

    @Test
    void firstCandle_shouldBeValidAtExactly50000Volume() {
        DailyStockContext context = processScenario(
                100.0,
                101.2,
                99.99,
                101.0,
                50_000L);

        assertTrue(context.getFirstCandleReady());
        assertTrue(context.getFirstCandleValid());
    }

    @Test
    void firstCandle_shouldBeInvalidWhenBearish() {
        DailyStockContext context = processScenario(
                100.0,
                101.2,
                99.99,
                99.0,
                80_000L);

        assertTrue(context.getFirstCandleReady());
        assertFalse(context.getFirstCandleBullish());
        assertFalse(context.getFirstCandleValid());
    }

    @Test
    void firstCandle_shouldBeInvalidWhenDoji() {
        DailyStockContext context = processScenario(
                100.0,
                101.2,
                99.99,
                100.0,
                80_000L);

        assertTrue(context.getFirstCandleReady());
        assertFalse(context.getFirstCandleBullish());
        assertFalse(context.getFirstCandleValid());
    }

    @Test
    void firstCandle_shouldBeNotReadyWhenAnyMinuteIsMissing() {
        LocalDate date = LocalDate.of(2026, 8, 24);

        List<MarketCandle> candles = firstCandles(
                date,
                100.0,
                101.2,
                99.99,
                101.0,
                80_000L)
                .subList(0, 4);

        LocalDateTime current = date.atTime(9, 18);

        stubScenario(
                date,
                current,
                candles);

        service.processFinalizedOneMinuteCandle(
                candles.get(candles.size() - 1));

        DailyStockContext context = savedContext();

        assertFalse(context.getFirstCandleReady());
        assertFalse(context.getFirstCandleValid());
    }

    private DailyStockContext processScenario(
            double open,
            double high,
            double low,
            double finalClose,
            long totalVolume) {

        LocalDate date = LocalDate.of(2026, 8, 24);

        List<MarketCandle> candles = firstCandles(
                date,
                open,
                high,
                low,
                finalClose,
                totalVolume);

        LocalDateTime current = date.atTime(9, 19);

        stubScenario(
                date,
                current,
                candles);

        service.processFinalizedOneMinuteCandle(
                candles.get(candles.size() - 1));

        return savedContext();
    }

    private void stubScenario(
            LocalDate date,
            LocalDateTime current,
            List<MarketCandle> candles) {

        when(timeProvider.nowDateTime())
                .thenReturn(date.atTime(9, 20));

        when(tradingCalendar.previousTradingDay(date))
                .thenReturn(date.minusDays(1));

        when(stockPriceRepository
                .findBySymbolAndDateLessThanEqualOrderByDateAsc(
                        "ONGC",
                        date))
                .thenReturn(List.of());

        when(candleRepository
                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                        "ONGC",
                        "NSE",
                        CandleTimeframe.ONE_MINUTE,
                        date.atTime(9, 15),
                        current))
                .thenReturn(candles);

        when(contextRepository
                .findBySymbolAndExchangeAndTradingDate(
                        "ONGC",
                        "NSE",
                        date))
                .thenReturn(Optional.empty());

        when(contextRepository.save(
                any(DailyStockContext.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private DailyStockContext savedContext() {
        ArgumentCaptor<DailyStockContext> captor = ArgumentCaptor.forClass(
                DailyStockContext.class);

        verify(contextRepository)
                .save(captor.capture());

        return captor.getValue();
    }

    private List<MarketCandle> firstCandles(
            LocalDate date,
            double open,
            double high,
            double low,
            double finalClose,
            long totalVolume) {

        List<MarketCandle> candles = new ArrayList<>();

        long baseVolume = totalVolume / 5;

        long remainder = totalVolume % 5;

        for (int i = 0; i < 5; i++) {
            long volume = baseVolume
                    + (i < remainder ? 1L : 0L);

            double close = i == 4
                    ? finalClose
                    : open;

            candles.add(
                    MarketCandle.builder()
                            .symbol("ONGC")
                            .exchange("NSE")
                            .timeframe(
                                    CandleTimeframe.ONE_MINUTE)
                            .candleTime(
                                    date.atTime(9, 15)
                                            .plusMinutes(i))
                            .openPrice(open)
                            .highPrice(high)
                            .lowPrice(low)
                            .closePrice(close)
                            .volume(volume)
                            .source("TEST")
                            .isFinalized(true)
                            .qualityStatus(
                                    CandleQualityStatus.LIVE)
                            .processingStatus(
                                    CandleProcessingStatus.RELEASED)
                            .build());
        }

        return candles;
    }
}
