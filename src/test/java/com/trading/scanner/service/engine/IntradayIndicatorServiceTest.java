package com.trading.scanner.service.engine;

import com.trading.scanner.model.CandleQualityStatus;
import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.MarketCandle;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class IntradayIndicatorServiceTest {

        private final IntradayIndicatorService service = new IntradayIndicatorService();

        @Test
        void calculateSessionVwap_shouldReturnExpectedValue() {
                List<MarketCandle> candles = List.of(
                                candle(LocalDateTime.of(2026, 7, 15, 9, 15), 100.0, 102.0, 99.0, 101.0, 100L),
                                candle(LocalDateTime.of(2026, 7, 15, 9, 16), 101.0, 103.0, 100.0, 102.0, 150L),
                                candle(LocalDateTime.of(2026, 7, 15, 9, 17), 102.0, 104.0, 101.0, 103.0, 200L));

                Double vwap = service.calculateSessionVwap(candles);

                double expected = ((((102.0 + 99.0 + 101.0) / 3.0) * 100.0) +
                                (((103.0 + 100.0 + 102.0) / 3.0) * 150.0) +
                                (((104.0 + 101.0 + 103.0) / 3.0) * 200.0)) / (100.0 + 150.0 + 200.0);

                assertEquals(expected, vwap, 0.000001);
        }

        @Test
        void calculateRsi14Wilder_shouldReturnNullWhenInsufficientCandles() {
                List<MarketCandle> candles = new ArrayList<>();
                for (int i = 0; i < 20; i++) {
                        candles.add(candle(
                                        LocalDateTime.of(2026, 7, 15, 9, 15).plusMinutes(i),
                                        100 + i,
                                        101 + i,
                                        99 + i,
                                        100 + i,
                                        100L));
                }

                assertNull(service.calculateRsi14Wilder(candles));
        }

        @Test
        void calculateRsi14Wilder_shouldReturnHundredForStrongUptrend() {
                List<MarketCandle> candles = new ArrayList<>();
                for (int i = 0; i < 42; i++) {
                        double close = 100.0 + i;
                        candles.add(candle(
                                        LocalDateTime.of(2026, 7, 15, 9, 15).plusMinutes(i),
                                        close,
                                        close + 1.0,
                                        close - 1.0,
                                        close,
                                        100L));
                }

                assertEquals(100.0, service.calculateRsi14Wilder(candles), 0.000001);
        }

        @Test
        void calculateRsi14Wilder_shouldReturnZeroForStrongDowntrend() {
                List<MarketCandle> candles = new ArrayList<>();
                for (int i = 0; i < 42; i++) {
                        double close = 200.0 - i;
                        candles.add(candle(
                                        LocalDateTime.of(2026, 7, 15, 9, 15).plusMinutes(i),
                                        close,
                                        close + 1.0,
                                        close - 1.0,
                                        close,
                                        100L));
                }

                assertEquals(0.0, service.calculateRsi14Wilder(candles), 0.000001);
        }

        @Test
        void calculateRsi14Wilder_shouldMatchGoldenValue() {
                double[] closes = {
                                100.0, 101.2, 100.8, 101.5, 102.1, 101.7, 102.8, 103.0, 102.4, 103.6,
                                104.1, 103.9, 104.8, 105.4, 104.9, 105.7, 106.2, 105.8, 106.9, 107.4,
                                106.8, 107.9, 108.5, 108.1, 109.0, 109.6, 109.1, 110.3, 110.9, 110.4,
                                111.2, 111.8, 111.1, 112.0, 112.7, 112.2, 113.1, 113.9, 113.3, 114.2,
                                114.8, 114.1
                };

                List<MarketCandle> candles = new ArrayList<>();
                for (int i = 0; i < closes.length; i++) {
                        double close = closes[i];
                        candles.add(candle(
                                        LocalDateTime.of(2026, 7, 15, 9, 15).plusMinutes(i),
                                        close,
                                        close + 1.0,
                                        close - 1.0,
                                        close,
                                        100L));
                }

                assertEquals(71.4233150200964, service.calculateRsi14Wilder(candles), 0.000001);
        }

        @Test
        void calculateAtr14Wilder_shouldReturnNullWhenInsufficientCandles() {
                List<MarketCandle> candles = new ArrayList<>();
                for (int i = 0; i < 14; i++) {
                        candles.add(candle(
                                        LocalDateTime.of(2026, 7, 15, 9, 15).plusMinutes(i),
                                        100 + i,
                                        101 + i,
                                        99 + i,
                                        100 + i,
                                        100L));
                }

                assertNull(service.calculateAtr14Wilder(candles));
        }

        @Test
        void calculateAtr14Wilder_shouldReturnExpectedValueForConstantRange() {
                List<MarketCandle> candles = new ArrayList<>();
                double close = 100.0;

                for (int i = 0; i < 20; i++) {
                        candles.add(candle(
                                        LocalDateTime.of(2026, 7, 15, 9, 15).plusMinutes(i),
                                        close,
                                        close + 2.0,
                                        close - 2.0,
                                        close,
                                        100L));
                        close += 1.0;
                }

                Double atr = service.calculateAtr14Wilder(candles);

                assertEquals(4.0, atr, 0.000001);
        }

        @Test
        void calculateAtr14Wilder_shouldMatchGoldenValue() {
                double[] closes = {
                                100.0, 101.2, 100.8, 101.5, 102.1, 101.7, 102.8, 103.0, 102.4, 103.6,
                                104.1, 103.9, 104.8, 105.4, 104.9, 105.7, 106.2, 105.8, 106.9, 107.4
                };

                List<MarketCandle> candles = new ArrayList<>();
                for (int i = 0; i < closes.length; i++) {
                        double close = closes[i];
                        double high = close + (1.4 + (i % 3) * 0.2);
                        double low = close - (1.1 + (i % 4) * 0.15);

                        candles.add(candle(
                                        LocalDateTime.of(2026, 7, 15, 9, 15).plusMinutes(i),
                                        close,
                                        high,
                                        low,
                                        close,
                                        100L));
                }

                assertEquals(2.9388014679789016, service.calculateAtr14Wilder(candles), 0.000001);
        }

        private MarketCandle candle(
                        LocalDateTime candleTime,
                        double open,
                        double high,
                        double low,
                        double close,
                        long volume) {
                return MarketCandle.builder()
                                .symbol("WIPRO")
                                .exchange("NSE")
                                .timeframe(CandleTimeframe.ONE_MINUTE)
                                .candleTime(candleTime)
                                .openPrice(open)
                                .highPrice(high)
                                .lowPrice(low)
                                .closePrice(close)
                                .volume(volume)
                                .source("LIVE_WEBSOCKET")
                                .createdAt(candleTime)
                                .updatedAt(candleTime)
                                .isFinalized(true)
                                .qualityStatus(CandleQualityStatus.VALID)
                                .build();
        }
}
