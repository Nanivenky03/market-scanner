package com.trading.scanner.service.engine;

import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.CandleProcessingStatus;
import com.trading.scanner.model.CandleQualityStatus;
import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.DayType;
import com.trading.scanner.model.ExpiryType;
import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.model.MarketSession;
import com.trading.scanner.model.NiftyVwapDirection;
import com.trading.scanner.model.StockPrice;
import com.trading.scanner.repository.MarketCandleRepository;
import com.trading.scanner.repository.StockPriceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

class MarketStateServiceTest {

        private MarketCandleRepository marketCandleRepository;
        private StockPriceRepository stockPriceRepository;
        private TimeProvider timeProvider;
        private TradingCalendar tradingCalendar;
        private MarketStateService service;

        @BeforeEach
        void setUp() {
                marketCandleRepository = org.mockito.Mockito.mock(
                                MarketCandleRepository.class);

                stockPriceRepository = org.mockito.Mockito.mock(
                                StockPriceRepository.class);

                timeProvider = org.mockito.Mockito.mock(
                                TimeProvider.class);

                tradingCalendar = org.mockito.Mockito.mock(
                                TradingCalendar.class);

                service = new MarketStateService(
                                marketCandleRepository,
                                stockPriceRepository,
                                timeProvider,
                                tradingCalendar);
        }

        @Test
        void sessionBoundaries_shouldMatchRequirements() {
                assertSession(
                                LocalDateTime.of(
                                                2026, 8, 24, 9, 14),
                                MarketSession.CLOSED);

                assertSession(
                                LocalDateTime.of(
                                                2026, 8, 24, 9, 15),
                                MarketSession.OPENING_RANGE);

                assertSession(
                                LocalDateTime.of(
                                                2026, 8, 24, 9, 29),
                                MarketSession.OPENING_RANGE);

                assertSession(
                                LocalDateTime.of(
                                                2026, 8, 24, 9, 30),
                                MarketSession.ACTIVE);

                assertSession(
                                LocalDateTime.of(
                                                2026, 8, 24, 12, 30),
                                MarketSession.ORB_CUTOFF);

                assertSession(
                                LocalDateTime.of(
                                                2026, 8, 24, 13, 0),
                                MarketSession.FCHB_CUTOFF);

                assertSession(
                                LocalDateTime.of(
                                                2026, 8, 24, 14, 15),
                                MarketSession.FCHB_CUTOFF);

                assertSession(
                                LocalDateTime.of(
                                                2026, 8, 24, 14, 45),
                                MarketSession.WIND_DOWN);

                assertSession(
                                LocalDateTime.of(
                                                2026, 8, 24, 15, 0),
                                MarketSession.HARD_CLOSE);

                assertSession(
                                LocalDateTime.of(
                                                2026, 8, 24, 15, 0, 59),
                                MarketSession.HARD_CLOSE);

                assertSession(
                                LocalDateTime.of(
                                                2026, 8, 24, 15, 1),
                                MarketSession.CLOSED);
        }

        @Test
        void expirySessionBoundaries_shouldUseExpiryWindDownAt1415() {
                LocalDate date = LocalDate.of(2026, 8, 27);

                LocalDateTime now = date.atTime(14, 15);

                stubBase(now);

                when(tradingCalendar.previousTradingDay(date))
                                .thenReturn(
                                                LocalDate.of(2026, 8, 26));

                service.scheduledUpdate();

                assertEquals(
                                ExpiryType.MONTHLY,
                                service.currentState().expiryType());

                assertEquals(
                                MarketSession.EXPIRY_WIND_DOWN,
                                service.currentState().marketSession());
        }

        @Test
        void vwapDirection_shouldUseCurrentVwapAsDenominator() {
                LocalDate date = LocalDate.of(2026, 8, 24);

                LocalDateTime now = date.atTime(10, 0);

                stubBase(now);

                when(marketCandleRepository
                                .findTop100BySymbolAndExchangeAndTimeframeAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
                                                "NIFTY",
                                                "NSE",
                                                CandleTimeframe.ONE_MINUTE,
                                                now))
                                .thenReturn(List.of(
                                                vwapCandle(
                                                                date.atTime(9, 57),
                                                                99.90,
                                                                100.0),
                                                vwapCandle(
                                                                date.atTime(9, 58),
                                                                99.92,
                                                                100.0),
                                                vwapCandle(
                                                                date.atTime(9, 59),
                                                                99.95,
                                                                100.0),
                                                vwapCandle(
                                                                date.atTime(10, 0),
                                                                100.00,
                                                                100.0)));

                service.scheduledUpdate();

                MarketStateService.MarketState state = service.currentState();

                /*
                 * (100.00 - 99.95) / 100.00 * 100 = 0.05,
                 * which is FLAT because the threshold is strict.
                 */
                assertEquals(
                                NiftyVwapDirection.RISING,
                                state.niftyVwapDirection());

                assertFalse(state.niftyAboveVwap());
                assertEquals(
                                0.0,
                                state.niftyVwapDistancePct(),
                                0.000001);
        }

        @Test
        void vwapDirection_shouldRequireTheExactThreeMinuteEarlierCandle() {
                LocalDate date = LocalDate.of(2026, 8, 24);

                LocalDateTime now = date.atTime(10, 0);

                stubBase(now);

                when(marketCandleRepository
                                .findTop100BySymbolAndExchangeAndTimeframeAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
                                                "NIFTY",
                                                "NSE",
                                                CandleTimeframe.ONE_MINUTE,
                                                now))
                                .thenReturn(List.of(
                                                vwapCandle(
                                                                date.atTime(9, 56),
                                                                99.0,
                                                                100.0),
                                                vwapCandle(
                                                                date.atTime(9, 58),
                                                                99.0,
                                                                100.0),
                                                vwapCandle(
                                                                date.atTime(9, 59),
                                                                99.0,
                                                                100.0),
                                                vwapCandle(
                                                                date.atTime(10, 0),
                                                                100.0,
                                                                100.0)));

                service.scheduledUpdate();

                assertEquals(
                                NiftyVwapDirection.UNKNOWN,
                                service.currentState()
                                                .niftyVwapDirection());
        }

        @Test
        void dayType_shouldBeLatchedAfterFirstSuccessfulResolution() {
                LocalDate date = LocalDate.of(2026, 8, 24);

                LocalDateTime firstUpdate = date.atTime(10, 0);

                LocalDateTime secondUpdate = date.atTime(10, 1);

                when(timeProvider.nowDateTime())
                                .thenReturn(firstUpdate, secondUpdate);

                when(tradingCalendar.previousTradingDay(date))
                                .thenReturn(
                                                LocalDate.of(2026, 8, 21));

                when(stockPriceRepository.findBySymbolAndDate(
                                "NIFTY",
                                LocalDate.of(2026, 8, 21)))
                                .thenReturn(Optional.of(
                                                StockPrice.builder()
                                                                .symbol("NIFTY")
                                                                .date(LocalDate.of(
                                                                                2026, 8, 21))
                                                                .closePrice(100.0)
                                                                .build()));

                when(marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                "NIFTY",
                                                "NSE",
                                                CandleTimeframe.ONE_MINUTE,
                                                date.atTime(9, 15),
                                                date.atTime(9, 30)))
                                .thenReturn(
                                                List.of(
                                                                candle(
                                                                                date.atTime(9, 15),
                                                                                101.0,
                                                                                101.0,
                                                                                100.0,
                                                                                101.0)));

                when(marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                "NIFTY",
                                                "NSE",
                                                CandleTimeframe.ONE_MINUTE,
                                                date.atTime(9, 15),
                                                date.atTime(9, 29)))
                                .thenReturn(List.of());

                when(marketCandleRepository
                                .findTop100BySymbolAndExchangeAndTimeframeAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
                                                anyString(),
                                                anyString(),
                                                eq(CandleTimeframe.ONE_MINUTE),
                                                any(LocalDateTime.class)))
                                .thenReturn(List.of());

                service.scheduledUpdate();
                service.scheduledUpdate();

                assertEquals(
                                DayType.GAP_UP,
                                service.currentState().dayType());
        }

        @Test
        void highVolatilityDay_shouldBeLatchedAfterOpeningRangeCompletes() {
                LocalDate date = LocalDate.of(2026, 8, 24);

                LocalDateTime firstUpdate = date.atTime(9, 30);

                LocalDateTime secondUpdate = date.atTime(10, 0);

                when(timeProvider.nowDateTime())
                                .thenReturn(firstUpdate, secondUpdate);

                when(tradingCalendar.previousTradingDay(date))
                                .thenReturn(
                                                LocalDate.of(2026, 8, 21));

                when(stockPriceRepository.findBySymbolAndDate(
                                anyString(),
                                any(LocalDate.class)))
                                .thenReturn(Optional.empty());

                when(marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                "NIFTY",
                                                "NSE",
                                                CandleTimeframe.ONE_MINUTE,
                                                date.atTime(9, 15),
                                                date.atTime(9, 29)))
                                .thenReturn(
                                                openingRange(
                                                                date,
                                                                102.0,
                                                                100.0));

                when(marketCandleRepository
                                .findTop100BySymbolAndExchangeAndTimeframeAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
                                                anyString(),
                                                anyString(),
                                                eq(CandleTimeframe.ONE_MINUTE),
                                                any(LocalDateTime.class)))
                                .thenReturn(List.of());

                service.scheduledUpdate();

                when(marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                "NIFTY",
                                                "NSE",
                                                CandleTimeframe.ONE_MINUTE,
                                                date.atTime(9, 15),
                                                date.atTime(9, 29)))
                                .thenReturn(
                                                openingRange(
                                                                date,
                                                                100.1,
                                                                100.0));

                service.scheduledUpdate();

                assertTrue(
                                service.currentState()
                                                .highVolatilityDay());
        }

        @Test
        void session_shouldNeverMoveBackwardDuringTheSameDay() {
                LocalDate date = LocalDate.of(2026, 8, 24);

                when(timeProvider.nowDateTime())
                                .thenReturn(
                                                date.atTime(10, 0),
                                                date.atTime(9, 20),
                                                date.atTime(12, 45),
                                                date.atTime(15, 0, 30),
                                                date.atTime(14, 45));

                when(tradingCalendar.previousTradingDay(date))
                                .thenReturn(
                                                LocalDate.of(2026, 8, 21));

                when(stockPriceRepository.findBySymbolAndDate(
                                anyString(),
                                any(LocalDate.class)))
                                .thenReturn(Optional.empty());

                when(marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                anyString(),
                                                anyString(),
                                                any(CandleTimeframe.class),
                                                any(LocalDateTime.class),
                                                any(LocalDateTime.class)))
                                .thenReturn(List.of());

                when(marketCandleRepository
                                .findTop100BySymbolAndExchangeAndTimeframeAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
                                                anyString(),
                                                anyString(),
                                                any(CandleTimeframe.class),
                                                any(LocalDateTime.class)))
                                .thenReturn(List.of());

                service.scheduledUpdate();
                assertEquals(
                                MarketSession.ACTIVE,
                                service.currentState().marketSession());

                service.scheduledUpdate();
                assertEquals(
                                MarketSession.ACTIVE,
                                service.currentState().marketSession());

                service.scheduledUpdate();
                assertEquals(
                                MarketSession.ORB_CUTOFF,
                                service.currentState().marketSession());

                service.scheduledUpdate();
                assertEquals(
                                MarketSession.HARD_CLOSE,
                                service.currentState().marketSession());

                service.scheduledUpdate();
                assertEquals(
                                MarketSession.HARD_CLOSE,
                                service.currentState().marketSession());
        }

        @Test
        void postMarketClosed_shouldRemainClosedIfClockMovesBackward() {
                LocalDate date = LocalDate.of(2026, 8, 24);

                when(timeProvider.nowDateTime())
                                .thenReturn(
                                                date.atTime(15, 1),
                                                date.atTime(14, 45));

                when(tradingCalendar.previousTradingDay(date))
                                .thenReturn(
                                                LocalDate.of(2026, 8, 21));

                when(stockPriceRepository.findBySymbolAndDate(
                                anyString(),
                                any(LocalDate.class)))
                                .thenReturn(Optional.empty());

                when(marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                anyString(),
                                                anyString(),
                                                any(CandleTimeframe.class),
                                                any(LocalDateTime.class),
                                                any(LocalDateTime.class)))
                                .thenReturn(List.of());

                when(marketCandleRepository
                                .findTop100BySymbolAndExchangeAndTimeframeAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
                                                anyString(),
                                                anyString(),
                                                any(CandleTimeframe.class),
                                                any(LocalDateTime.class)))
                                .thenReturn(List.of());

                service.scheduledUpdate();
                assertEquals(
                                MarketSession.CLOSED,
                                service.currentState().marketSession());

                service.scheduledUpdate();
                assertEquals(
                                MarketSession.CLOSED,
                                service.currentState().marketSession());
        }

        @Test
        void exactGapThreshold_shouldRemainNormal() {
                LocalDate date = LocalDate.of(2026, 8, 24);

                LocalDate previousDate = LocalDate.of(2026, 8, 21);

                LocalDateTime now = date.atTime(10, 0);

                stubBase(now);

                when(tradingCalendar.previousTradingDay(date))
                                .thenReturn(previousDate);

                when(stockPriceRepository.findBySymbolAndDate(
                                "NIFTY",
                                previousDate))
                                .thenReturn(Optional.of(
                                                StockPrice.builder()
                                                                .symbol("NIFTY")
                                                                .date(previousDate)
                                                                .closePrice(100.0)
                                                                .build()));

                when(marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                "NIFTY",
                                                "NSE",
                                                CandleTimeframe.ONE_MINUTE,
                                                date.atTime(9, 15),
                                                date.atTime(9, 30)))
                                .thenReturn(List.of(
                                                candle(
                                                                date.atTime(9, 15),
                                                                100.75,
                                                                101.0,
                                                                100.0,
                                                                100.75)));

                service.scheduledUpdate();

                assertEquals(
                                DayType.NORMAL,
                                service.currentState().dayType());
        }

        private void assertSession(
                        LocalDateTime now,
                        MarketSession expected) {

                stubBase(now);

                service.scheduledUpdate();

                assertEquals(
                                expected,
                                service.currentState().marketSession());
        }

        private void stubBase(
                        LocalDateTime now) {

                reset(
                                marketCandleRepository,
                                stockPriceRepository,
                                timeProvider,
                                tradingCalendar);

                service = new MarketStateService(
                                marketCandleRepository,
                                stockPriceRepository,
                                timeProvider,
                                tradingCalendar);

                LocalDate date = now.toLocalDate();

                when(timeProvider.nowDateTime())
                                .thenReturn(now);

                when(tradingCalendar.previousTradingDay(date))
                                .thenReturn(date.minusDays(1));

                when(stockPriceRepository.findBySymbolAndDate(
                                anyString(),
                                any(LocalDate.class)))
                                .thenReturn(Optional.empty());

                when(marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                anyString(),
                                                anyString(),
                                                any(CandleTimeframe.class),
                                                any(LocalDateTime.class),
                                                any(LocalDateTime.class)))
                                .thenReturn(List.of());

                when(marketCandleRepository
                                .findTop100BySymbolAndExchangeAndTimeframeAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
                                                anyString(),
                                                anyString(),
                                                any(CandleTimeframe.class),
                                                any(LocalDateTime.class)))
                                .thenReturn(List.of());
        }

        private List<MarketCandle> openingRange(
                        LocalDate date,
                        double high,
                        double low) {

                List<MarketCandle> candles = new ArrayList<>();

                for (int i = 0; i < 15; i++) {
                        candles.add(
                                        candle(
                                                        date.atTime(9, 15)
                                                                        .plusMinutes(i),
                                                        100.0,
                                                        high,
                                                        low,
                                                        100.0));
                }

                return candles;
        }

        private MarketCandle vwapCandle(
                        LocalDateTime time,
                        double vwap,
                        double close) {

                return MarketCandle.builder()
                                .symbol("NIFTY")
                                .exchange("NSE")
                                .timeframe(
                                                CandleTimeframe.ONE_MINUTE)
                                .candleTime(time)
                                .openPrice(close)
                                .highPrice(close)
                                .lowPrice(close)
                                .closePrice(close)
                                .volume(100L)
                                .vwap(vwap)
                                .isFinalized(true)
                                .qualityStatus(
                                                CandleQualityStatus.LIVE)
                                .processingStatus(
                                                CandleProcessingStatus.RELEASED)
                                .build();
        }

        private MarketCandle candle(
                        LocalDateTime time,
                        double open,
                        double high,
                        double low,
                        double close) {

                return MarketCandle.builder()
                                .symbol("NIFTY")
                                .exchange("NSE")
                                .timeframe(
                                                CandleTimeframe.ONE_MINUTE)
                                .candleTime(time)
                                .openPrice(open)
                                .highPrice(high)
                                .lowPrice(low)
                                .closePrice(close)
                                .volume(100L)
                                .isFinalized(true)
                                .qualityStatus(
                                                CandleQualityStatus.LIVE)
                                .processingStatus(
                                                CandleProcessingStatus.RELEASED)
                                .build();
        }
}
