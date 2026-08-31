package com.trading.scanner.service.engine;

import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.CandleProcessingStatus;
import com.trading.scanner.model.CandleQualityStatus;
import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.ContextStatus;
import com.trading.scanner.model.DailyStockContext;
import com.trading.scanner.model.DayType;
import com.trading.scanner.model.ExpiryType;
import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.model.MarketSession;
import com.trading.scanner.model.NiftyVwapDirection;
import com.trading.scanner.model.StockPrice;
import com.trading.scanner.model.VolumeTimeWindowBaseline;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DailyStockContextServiceTest {

        private DailyStockContextRepository dailyStockContextRepository;

        private MarketCandleRepository marketCandleRepository;

        private StockPriceRepository stockPriceRepository;

        private VolumeTimeWindowBaselineRepository baselineRepository;

        private MarketStateService marketStateService;
        private TradingCalendar tradingCalendar;
        private TimeProvider timeProvider;
        private DailyStockContextService service;

        @BeforeEach
        void setUp() {
                dailyStockContextRepository = mock(DailyStockContextRepository.class);

                marketCandleRepository = mock(MarketCandleRepository.class);

                stockPriceRepository = mock(StockPriceRepository.class);

                baselineRepository = mock(VolumeTimeWindowBaselineRepository.class);

                marketStateService = mock(MarketStateService.class);

                tradingCalendar = mock(TradingCalendar.class);

                timeProvider = mock(TimeProvider.class);

                service = new DailyStockContextService(
                                dailyStockContextRepository,
                                marketCandleRepository,
                                stockPriceRepository,
                                baselineRepository,
                                marketStateService,
                                tradingCalendar,
                                timeProvider);
        }

        @Test
        void processFinalizedOneMinuteCandle_shouldBuildFirstCandleContext() {
                LocalDate date = LocalDate.of(2026, 6, 24);

                LocalDateTime current = date.atTime(9, 19);

                when(timeProvider.nowDateTime())
                                .thenReturn(date.atTime(9, 20));

                when(tradingCalendar.previousTradingDay(date))
                                .thenReturn(LocalDate.of(2026, 6, 23));

                when(stockPriceRepository
                                .findBySymbolAndDateLessThanEqualOrderByDateAsc(
                                                "ONGC",
                                                date))
                                .thenReturn(List.of());

                List<MarketCandle> firstFive = firstCandles(date);

                when(marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                "ONGC",
                                                "NSE",
                                                CandleTimeframe.ONE_MINUTE,
                                                date.atTime(9, 15),
                                                current))
                                .thenReturn(firstFive);

                when(dailyStockContextRepository
                                .findBySymbolAndExchangeAndTradingDate(
                                                "ONGC",
                                                "NSE",
                                                date))
                                .thenReturn(Optional.empty());

                when(dailyStockContextRepository.save(
                                any(DailyStockContext.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                service.processFinalizedOneMinuteCandle(
                                candle(
                                                "ONGC",
                                                current,
                                                101.1,
                                                102.0,
                                                100.9,
                                                101.5,
                                                50L));

                ArgumentCaptor<DailyStockContext> captor = ArgumentCaptor.forClass(
                                DailyStockContext.class);

                verify(dailyStockContextRepository)
                                .save(captor.capture());

                DailyStockContext saved = captor.getValue();

                assertEquals("ONGC", saved.getSymbol());
                assertEquals(date, saved.getTradingDate());
                assertEquals(100.0, saved.getFirstCandleOpen());
                assertEquals(102.0, saved.getFirstCandleHigh());
                assertEquals(99.0, saved.getFirstCandleLow());
                assertEquals(101.5, saved.getFirstCandleClose());
                assertEquals(150L, saved.getFirstCandleVolume());
                assertEquals(3.0, saved.getFirstCandleRange());
                assertTrue(saved.getFirstCandleReady());
                assertFalse(saved.getOpeningRangeReady());
        }

        @Test
        void processFinalizedOneMinuteCandle_shouldBuildOpeningRangeContext() {
                LocalDate date = LocalDate.of(2026, 6, 24);

                LocalDateTime current = date.atTime(9, 29);

                when(timeProvider.nowDateTime())
                                .thenReturn(date.atTime(9, 30));

                when(tradingCalendar.previousTradingDay(date))
                                .thenReturn(LocalDate.of(2026, 6, 23));

                when(stockPriceRepository
                                .findBySymbolAndDateLessThanEqualOrderByDateAsc(
                                                "ONGC",
                                                date))
                                .thenReturn(List.of());

                List<MarketCandle> openingRange = openingRangeCandles(date);

                when(marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                "ONGC",
                                                "NSE",
                                                CandleTimeframe.ONE_MINUTE,
                                                date.atTime(9, 15),
                                                current))
                                .thenReturn(openingRange);

                when(baselineRepository
                                .findBySymbolAndExchangeAndTradingDateAndSessionMinute(
                                                "ONGC",
                                                "NSE",
                                                date,
                                                14))
                                .thenReturn(Optional.empty());

                when(dailyStockContextRepository
                                .findBySymbolAndExchangeAndTradingDate(
                                                "ONGC",
                                                "NSE",
                                                date))
                                .thenReturn(Optional.empty());

                when(dailyStockContextRepository.save(
                                any(DailyStockContext.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                service.processFinalizedOneMinuteCandle(
                                candle(
                                                "ONGC",
                                                current,
                                                100.0,
                                                103.0,
                                                98.0,
                                                100.0,
                                                150L));

                ArgumentCaptor<DailyStockContext> captor = ArgumentCaptor.forClass(
                                DailyStockContext.class);

                verify(dailyStockContextRepository)
                                .save(captor.capture());

                DailyStockContext saved = captor.getValue();

                assertTrue(saved.getOpeningRangeReady());
                assertEquals(103.0, saved.getOpeningRangeHigh());
                assertEquals(98.0, saved.getOpeningRangeLow());
                assertEquals(5.0, saved.getOpeningRangeSize());
                assertEquals(1200L, saved.getOpeningRangeVolume());
                assertEquals(
                                0.4666666666666667,
                                saved.getOpeningRangeSkew(),
                                0.000001);
                assertFalse(saved.getOpeningRangeValid());
        }

        @Test
        void openingRangeSkew_shouldUseAverageTypicalPriceNearLow() {
                LocalDate date = LocalDate.of(2026, 6, 24);

                List<MarketCandle> candles = lowSkewCandles(date);

                DailyStockContext saved = processOpeningRangeScenario(
                                date,
                                candles,
                                15_000L);

                assertTrue(saved.getOpeningRangeReady());
                assertEquals(
                                0.10,
                                saved.getOpeningRangeSkew(),
                                0.000001);
                assertFalse(saved.getOpeningRangeValid());
        }

        @Test
        void openingRangeSkew_shouldUseAverageTypicalPriceNearHigh() {
                LocalDate date = LocalDate.of(2026, 6, 24);

                List<MarketCandle> candles = highSkewCandles(date);

                DailyStockContext saved = processOpeningRangeScenario(
                                date,
                                candles,
                                15_000L);

                assertTrue(saved.getOpeningRangeReady());
                assertEquals(
                                0.90,
                                saved.getOpeningRangeSkew(),
                                0.000001);
                assertFalse(saved.getOpeningRangeValid());
        }

        @Test
        void processFinalizedOneMinuteCandle_shouldRejectGappedFirstWindow() {
                LocalDate date = LocalDate.of(2026, 6, 24);

                LocalDateTime current = date.atTime(9, 19);

                List<MarketCandle> incomplete = firstCandles(date).subList(0, 4);

                when(timeProvider.nowDateTime())
                                .thenReturn(date.atTime(9, 20));

                when(tradingCalendar.previousTradingDay(date))
                                .thenReturn(LocalDate.of(2026, 6, 23));

                when(stockPriceRepository
                                .findBySymbolAndDateLessThanEqualOrderByDateAsc(
                                                "ONGC",
                                                date))
                                .thenReturn(List.of());

                when(marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                "ONGC",
                                                "NSE",
                                                CandleTimeframe.ONE_MINUTE,
                                                date.atTime(9, 15),
                                                current))
                                .thenReturn(incomplete);

                when(dailyStockContextRepository
                                .findBySymbolAndExchangeAndTradingDate(
                                                "ONGC",
                                                "NSE",
                                                date))
                                .thenReturn(Optional.empty());

                when(dailyStockContextRepository.save(
                                any(DailyStockContext.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                service.processFinalizedOneMinuteCandle(
                                candle(
                                                "ONGC",
                                                current,
                                                101.0,
                                                102.0,
                                                100.0,
                                                101.0,
                                                50L));

                verify(dailyStockContextRepository)
                                .save(argThat(context -> !Boolean.TRUE.equals(
                                                context.getFirstCandleReady())
                                                && !Boolean.TRUE.equals(
                                                                context.getFirstCandleValid())));
        }

        @Test
        void processFinalizedOneMinuteCandle_shouldResolveNormalDayReference() {
                LocalDate date = LocalDate.of(2026, 6, 24);

                LocalDate previousDate = LocalDate.of(2026, 6, 23);

                when(timeProvider.nowDateTime())
                                .thenReturn(date.atTime(9, 30));

                when(tradingCalendar.previousTradingDay(date))
                                .thenReturn(previousDate);

                when(stockPriceRepository
                                .findBySymbolAndDateLessThanEqualOrderByDateAsc(
                                                "ONGC",
                                                date))
                                .thenReturn(List.of(
                                                StockPrice.builder()
                                                                .symbol("ONGC")
                                                                .date(previousDate)
                                                                .openPrice(98.0)
                                                                .highPrice(105.0)
                                                                .lowPrice(97.0)
                                                                .closePrice(100.0)
                                                                .build()));

                when(marketStateService.currentState())
                                .thenReturn(
                                                marketState(
                                                                date,
                                                                DayType.NORMAL));

                when(marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                "ONGC",
                                                "NSE",
                                                CandleTimeframe.ONE_MINUTE,
                                                date.atTime(9, 15),
                                                date.atTime(9, 29)))
                                .thenReturn(openingRangeCandles(date));

                when(baselineRepository
                                .findBySymbolAndExchangeAndTradingDateAndSessionMinute(
                                                "ONGC",
                                                "NSE",
                                                date,
                                                14))
                                .thenReturn(Optional.empty());

                when(dailyStockContextRepository
                                .findBySymbolAndExchangeAndTradingDate(
                                                "ONGC",
                                                "NSE",
                                                date))
                                .thenReturn(Optional.empty());

                when(dailyStockContextRepository.save(
                                any(DailyStockContext.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                service.processFinalizedOneMinuteCandle(
                                candle(
                                                "ONGC",
                                                date.atTime(9, 29),
                                                100.0,
                                                103.0,
                                                98.0,
                                                100.0,
                                                150L));

                verify(dailyStockContextRepository)
                                .save(argThat(context -> context.getBreakoutReferencePrice() != null
                                                && context
                                                                .getBreakoutReferencePrice()
                                                                .equals(105.0)
                                                && context
                                                                .getPrevDayRangePct()
                                                                .equals(
                                                                                8.24742268041237)));
        }

        @Test
        void processFinalizedOneMinuteCandle_shouldUseOpeningRangeForGapUp() {
                LocalDate date = LocalDate.of(2026, 6, 24);

                LocalDate previousDate = LocalDate.of(2026, 6, 23);

                when(timeProvider.nowDateTime())
                                .thenReturn(date.atTime(9, 30));

                when(tradingCalendar.previousTradingDay(date))
                                .thenReturn(previousDate);

                when(stockPriceRepository
                                .findBySymbolAndDateLessThanEqualOrderByDateAsc(
                                                "ONGC",
                                                date))
                                .thenReturn(List.of(
                                                StockPrice.builder()
                                                                .symbol("ONGC")
                                                                .date(previousDate)
                                                                .openPrice(98.0)
                                                                .highPrice(105.0)
                                                                .lowPrice(97.0)
                                                                .closePrice(100.0)
                                                                .build()));

                when(marketStateService.currentState())
                                .thenReturn(
                                                marketState(
                                                                date,
                                                                DayType.GAP_UP));

                when(marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                "ONGC",
                                                "NSE",
                                                CandleTimeframe.ONE_MINUTE,
                                                date.atTime(9, 15),
                                                date.atTime(9, 29)))
                                .thenReturn(openingRangeCandles(date));

                when(baselineRepository
                                .findBySymbolAndExchangeAndTradingDateAndSessionMinute(
                                                "ONGC",
                                                "NSE",
                                                date,
                                                14))
                                .thenReturn(Optional.empty());

                when(dailyStockContextRepository
                                .findBySymbolAndExchangeAndTradingDate(
                                                "ONGC",
                                                "NSE",
                                                date))
                                .thenReturn(Optional.empty());

                when(dailyStockContextRepository.save(
                                any(DailyStockContext.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                service.processFinalizedOneMinuteCandle(
                                candle(
                                                "ONGC",
                                                date.atTime(9, 29),
                                                100.0,
                                                103.0,
                                                98.0,
                                                100.0,
                                                150L));

                verify(dailyStockContextRepository)
                                .save(argThat(context -> context.getBreakoutReferencePrice() != null
                                                && context
                                                                .getBreakoutReferencePrice()
                                                                .equals(103.0)));
        }

        @Test
        void processFinalizedOneMinuteCandle_shouldLeaveGapDownReferenceNull() {
                LocalDate date = LocalDate.of(2026, 6, 24);

                LocalDate previousDate = LocalDate.of(2026, 6, 23);

                when(timeProvider.nowDateTime())
                                .thenReturn(date.atTime(9, 30));

                when(tradingCalendar.previousTradingDay(date))
                                .thenReturn(previousDate);

                when(stockPriceRepository
                                .findBySymbolAndDateLessThanEqualOrderByDateAsc(
                                                "ONGC",
                                                date))
                                .thenReturn(List.of(
                                                StockPrice.builder()
                                                                .symbol("ONGC")
                                                                .date(previousDate)
                                                                .openPrice(98.0)
                                                                .highPrice(105.0)
                                                                .lowPrice(97.0)
                                                                .closePrice(100.0)
                                                                .build()));

                when(marketStateService.currentState())
                                .thenReturn(
                                                marketState(
                                                                date,
                                                                DayType.GAP_DOWN));

                when(marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                "ONGC",
                                                "NSE",
                                                CandleTimeframe.ONE_MINUTE,
                                                date.atTime(9, 15),
                                                date.atTime(9, 29)))
                                .thenReturn(openingRangeCandles(date));

                when(baselineRepository
                                .findBySymbolAndExchangeAndTradingDateAndSessionMinute(
                                                "ONGC",
                                                "NSE",
                                                date,
                                                14))
                                .thenReturn(Optional.empty());

                when(dailyStockContextRepository
                                .findBySymbolAndExchangeAndTradingDate(
                                                "ONGC",
                                                "NSE",
                                                date))
                                .thenReturn(Optional.empty());

                when(dailyStockContextRepository.save(
                                any(DailyStockContext.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                service.processFinalizedOneMinuteCandle(
                                candle(
                                                "ONGC",
                                                date.atTime(9, 29),
                                                100.0,
                                                103.0,
                                                98.0,
                                                100.0,
                                                150L));

                verify(dailyStockContextRepository)
                                .save(argThat(context -> context.getBreakoutReferencePrice() == null));
        }

        @Test
        void processFinalizedOneMinuteCandle_shouldIgnoreProvisionalCandle() {
                LocalDate date = LocalDate.of(2026, 6, 24);

                MarketCandle provisional = candle(
                                "ONGC",
                                date.atTime(9, 19),
                                100.0,
                                101.0,
                                99.0,
                                100.5,
                                100L);

                provisional.setProcessingStatus(
                                CandleProcessingStatus.PROVISIONAL);

                service.processFinalizedOneMinuteCandle(
                                provisional);

                verifyNoInteractions(
                                dailyStockContextRepository);

                verifyNoInteractions(
                                marketCandleRepository);
        }

        private DailyStockContext processOpeningRangeScenario(
                        LocalDate date,
                        List<MarketCandle> candles,
                        long baselineVolume) {

                LocalDateTime current = date.atTime(9, 29);

                when(timeProvider.nowDateTime())
                                .thenReturn(date.atTime(9, 30));

                when(tradingCalendar.previousTradingDay(date))
                                .thenReturn(LocalDate.of(2026, 6, 23));

                when(stockPriceRepository
                                .findBySymbolAndDateLessThanEqualOrderByDateAsc(
                                                "ONGC",
                                                date))
                                .thenReturn(List.of());

                when(marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                "ONGC",
                                                "NSE",
                                                CandleTimeframe.ONE_MINUTE,
                                                date.atTime(9, 15),
                                                current))
                                .thenReturn(candles);

                VolumeTimeWindowBaseline baseline = mock(VolumeTimeWindowBaseline.class);

                when(baseline.getAvgCumulativeVolume20())
                                .thenReturn(baselineVolume);

                when(baselineRepository
                                .findBySymbolAndExchangeAndTradingDateAndSessionMinute(
                                                "ONGC",
                                                "NSE",
                                                date,
                                                14))
                                .thenReturn(Optional.of(baseline));

                when(dailyStockContextRepository
                                .findBySymbolAndExchangeAndTradingDate(
                                                "ONGC",
                                                "NSE",
                                                date))
                                .thenReturn(Optional.empty());

                when(dailyStockContextRepository.save(
                                any(DailyStockContext.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                service.processFinalizedOneMinuteCandle(
                                candles.get(candles.size() - 1));

                ArgumentCaptor<DailyStockContext> captor = ArgumentCaptor.forClass(
                                DailyStockContext.class);

                verify(dailyStockContextRepository)
                                .save(captor.capture());

                return captor.getValue();
        }

        private List<MarketCandle> firstCandles(
                        LocalDate date) {

                return List.of(
                                candle(
                                                "ONGC",
                                                date.atTime(9, 15),
                                                100.0,
                                                101.0,
                                                99.0,
                                                100.5,
                                                10L),
                                candle(
                                                "ONGC",
                                                date.atTime(9, 16),
                                                100.5,
                                                101.5,
                                                100.0,
                                                101.0,
                                                20L),
                                candle(
                                                "ONGC",
                                                date.atTime(9, 17),
                                                101.0,
                                                101.8,
                                                100.6,
                                                101.2,
                                                30L),
                                candle(
                                                "ONGC",
                                                date.atTime(9, 18),
                                                101.2,
                                                101.9,
                                                100.8,
                                                101.1,
                                                40L),
                                candle(
                                                "ONGC",
                                                date.atTime(9, 19),
                                                101.1,
                                                102.0,
                                                100.9,
                                                101.5,
                                                50L));
        }

        private List<MarketCandle> openingRangeCandles(
                        LocalDate date) {

                List<MarketCandle> candles = new ArrayList<>();

                for (int i = 0; i < 15; i++) {
                        candles.add(
                                        candle(
                                                        "ONGC",
                                                        date.atTime(9, 15)
                                                                        .plusMinutes(i),
                                                        100.0,
                                                        103.0,
                                                        98.0,
                                                        100.0,
                                                        (i + 1L) * 10L));
                }

                return candles;
        }

        private List<MarketCandle> lowSkewCandles(
                        LocalDate date) {

                List<MarketCandle> candles = new ArrayList<>();

                for (int i = 0; i < 14; i++) {
                        candles.add(
                                        candle(
                                                        "ONGC",
                                                        date.atTime(9, 15)
                                                                        .plusMinutes(i),
                                                        100.1,
                                                        100.3,
                                                        100.0,
                                                        100.2,
                                                        1_000L));
                }

                candles.add(
                                candle(
                                                "ONGC",
                                                date.atTime(9, 29),
                                                100.0,
                                                102.0,
                                                100.0,
                                                100.0,
                                                1_000L));

                return candles;
        }

        private List<MarketCandle> highSkewCandles(
                        LocalDate date) {

                List<MarketCandle> candles = new ArrayList<>();

                for (int i = 0; i < 14; i++) {
                        candles.add(
                                        candle(
                                                        "ONGC",
                                                        date.atTime(9, 15)
                                                                        .plusMinutes(i),
                                                        101.7,
                                                        102.0,
                                                        101.6,
                                                        101.9,
                                                        1_000L));
                }

                candles.add(
                                candle(
                                                "ONGC",
                                                date.atTime(9, 29),
                                                102.0,
                                                102.0,
                                                100.0,
                                                102.0,
                                                1_000L));

                return candles;
        }

        private MarketStateService.MarketState marketState(
                        LocalDate date,
                        DayType dayType) {

                return new MarketStateService.MarketState(
                                date,
                                date.atTime(9, 30),
                                dayType,
                                ExpiryType.NONE,
                                false,
                                MarketSession.ACTIVE,
                                true,
                                0.1,
                                NiftyVwapDirection.FLAT);
        }

        private MarketCandle candle(
                        String symbol,
                        LocalDateTime time,
                        double open,
                        double high,
                        double low,
                        double close,
                        long volume) {

                return MarketCandle.builder()
                                .symbol(symbol)
                                .exchange("NSE")
                                .timeframe(
                                                CandleTimeframe.ONE_MINUTE)
                                .candleTime(time)
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
                                .build();
        }
}
