package com.trading.scanner.service.engine;

import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.CandleProcessingStatus;
import com.trading.scanner.model.CandleQualityStatus;
import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.DailyStockContext;
import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.model.VolumeTimeWindowBaseline;
import com.trading.scanner.repository.DailyStockContextRepository;
import com.trading.scanner.repository.MarketCandleRepository;
import com.trading.scanner.repository.StockPriceRepository;
import com.trading.scanner.repository.VolumeTimeWindowBaselineRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DailyStockContextOpeningRangeParticipationTest {

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
    void openingRange_shouldBeValidWithMinute14Baseline() {
        LocalDate date = LocalDate.of(2026, 8, 24);

        List<MarketCandle> candles = openingRangeCandles(date);

        when(timeProvider.nowDateTime())
                .thenReturn(date.atTime(9, 30));

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
                        date.atTime(9, 29)))
                .thenReturn(candles);

        when(baselineRepository
                .findBySymbolAndExchangeAndTradingDateAndSessionMinute(
                        "ONGC",
                        "NSE",
                        date,
                        14))
                .thenReturn(Optional.of(
                        VolumeTimeWindowBaseline.builder()
                                .avgCumulativeVolume20(30_000L)
                                .build()));

        when(contextRepository
                .findBySymbolAndExchangeAndTradingDate(
                        "ONGC",
                        "NSE",
                        date))
                .thenReturn(Optional.empty());

        when(contextRepository.save(
                any(DailyStockContext.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.processFinalizedOneMinuteCandle(
                candles.get(candles.size() - 1));

        verify(contextRepository)
                .save(argThat(context -> Boolean.TRUE.equals(
                        context.getOpeningRangeReady())
                        && Boolean.TRUE.equals(
                                context.getOpeningRangeValid())
                        && context.getOpeningRangeVolume() == 15_000L));
    }

    @Test
    void openingRange_shouldBeInvalidWhenMinute14BaselineIsMissing() {
        DailyStockContext saved = processWithBaseline(
                Optional.empty());

        assertTrue(saved.getOpeningRangeReady());
        assertFalse(saved.getOpeningRangeValid());
    }

    @Test
    void openingRange_shouldBeInvalidWhenMinute14BaselineIsZero() {
        DailyStockContext saved = processWithBaseline(
                Optional.of(
                        VolumeTimeWindowBaseline.builder()
                                .avgCumulativeVolume20(0L)
                                .build()));

        assertTrue(saved.getOpeningRangeReady());
        assertFalse(saved.getOpeningRangeValid());
    }

    @Test
    void openingRange_shouldNotEvaluateUntilAll15CandlesExist() {
        LocalDate date = LocalDate.of(2026, 8, 24);

        List<MarketCandle> incomplete = openingRangeCandles(date)
                .subList(0, 14);

        when(timeProvider.nowDateTime())
                .thenReturn(date.atTime(9, 29));

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
                        date.atTime(9, 29)))
                .thenReturn(incomplete);

        when(contextRepository
                .findBySymbolAndExchangeAndTradingDate(
                        "ONGC",
                        "NSE",
                        date))
                .thenReturn(Optional.empty());

        when(contextRepository.save(
                any(DailyStockContext.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.processFinalizedOneMinuteCandle(
                incomplete.get(incomplete.size() - 1));

        verify(contextRepository)
                .save(argThat(context -> !Boolean.TRUE.equals(
                        context.getOpeningRangeReady())
                        && !Boolean.TRUE.equals(
                                context.getOpeningRangeValid())));
    }

    private DailyStockContext processWithBaseline(
            Optional<VolumeTimeWindowBaseline> baseline) {

        LocalDate date = LocalDate.of(2026, 8, 24);

        List<MarketCandle> candles = openingRangeCandles(date);

        when(timeProvider.nowDateTime())
                .thenReturn(date.atTime(9, 30));

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
                        date.atTime(9, 29)))
                .thenReturn(candles);

        when(baselineRepository
                .findBySymbolAndExchangeAndTradingDateAndSessionMinute(
                        "ONGC",
                        "NSE",
                        date,
                        14))
                .thenReturn(baseline);

        when(contextRepository
                .findBySymbolAndExchangeAndTradingDate(
                        "ONGC",
                        "NSE",
                        date))
                .thenReturn(Optional.empty());

        when(contextRepository.save(
                any(DailyStockContext.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.processFinalizedOneMinuteCandle(
                candles.get(candles.size() - 1));

        var captor = org.mockito.ArgumentCaptor
                .forClass(DailyStockContext.class);

        verify(contextRepository).save(captor.capture());

        return captor.getValue();
    }

    private List<MarketCandle> openingRangeCandles(
            LocalDate date) {

        List<MarketCandle> candles = new ArrayList<>();

        for (int i = 0; i < 15; i++) {
            candles.add(
                    MarketCandle.builder()
                            .symbol("ONGC")
                            .exchange("NSE")
                            .timeframe(
                                    CandleTimeframe.ONE_MINUTE)
                            .candleTime(
                                    date.atTime(9, 15)
                                            .plusMinutes(i))
                            .openPrice(100.4)
                            .highPrice(101.0)
                            .lowPrice(100.0)
                            .closePrice(100.6)
                            .volume(1_000L)
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
