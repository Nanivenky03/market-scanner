package com.trading.scanner.service.engine;

import com.trading.scanner.model.CandleQualityStatus;
import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.model.VolumeDirection;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class IntradayIndicatorServiceTest {

        private final IntradayIndicatorService service = new IntradayIndicatorService();

        @Test
        void calculateSessionVwap_shouldMatchReferenceValue() {
                List<MarketCandle> candles = List.of(
                                candle(
                                                CandleTimeframe.ONE_MINUTE,
                                                LocalDateTime.of(
                                                                2026, 7, 15, 9, 15),
                                                100.0,
                                                102.0,
                                                99.0,
                                                101.0,
                                                100L),
                                candle(
                                                CandleTimeframe.ONE_MINUTE,
                                                LocalDateTime.of(
                                                                2026, 7, 15, 9, 16),
                                                101.0,
                                                103.0,
                                                100.0,
                                                102.0,
                                                150L),
                                candle(
                                                CandleTimeframe.ONE_MINUTE,
                                                LocalDateTime.of(
                                                                2026, 7, 15, 9, 17),
                                                102.0,
                                                104.0,
                                                101.0,
                                                103.0,
                                                200L));

                double expected = ((((102.0 + 99.0 + 101.0) / 3.0) * 100.0)
                                + (((103.0 + 100.0 + 102.0) / 3.0) * 150.0)
                                + (((104.0 + 101.0 + 103.0) / 3.0) * 200.0))
                                / 450.0;

                assertEquals(
                                expected,
                                service.calculateSessionVwap(candles),
                                0.000001);
        }

        @Test
        void calculateSessionVwap_shouldKeepTradingDaysSeparate() {
                List<MarketCandle> firstDay = List.of(
                                candle(
                                                CandleTimeframe.ONE_MINUTE,
                                                LocalDateTime.of(
                                                                2026, 7, 15, 9, 15),
                                                100.0,
                                                102.0,
                                                99.0,
                                                101.0,
                                                100L));

                List<MarketCandle> secondDay = List.of(
                                candle(
                                                CandleTimeframe.ONE_MINUTE,
                                                LocalDateTime.of(
                                                                2026, 7, 16, 9, 15),
                                                200.0,
                                                202.0,
                                                199.0,
                                                201.0,
                                                100L));

                assertEquals(
                                100.66666666666667,
                                service.calculateSessionVwap(firstDay),
                                0.000001);

                assertEquals(
                                200.66666666666666,
                                service.calculateSessionVwap(secondDay),
                                0.000001);
        }

        @Test
        void calculateSessionVwap_shouldRejectMixedTradingDates() {
                List<MarketCandle> mixedDates = List.of(
                                candle(
                                                CandleTimeframe.ONE_MINUTE,
                                                LocalDateTime.of(
                                                                2026, 7, 15, 15, 29),
                                                100.0,
                                                102.0,
                                                99.0,
                                                101.0,
                                                100L),
                                candle(
                                                CandleTimeframe.ONE_MINUTE,
                                                LocalDateTime.of(
                                                                2026, 7, 16, 9, 15),
                                                200.0,
                                                202.0,
                                                199.0,
                                                201.0,
                                                100L));

                assertNull(
                                service.calculateSessionVwap(mixedDates));
        }

        @Test
        void calculateRsi14Wilder_shouldReturnNullWithInsufficientHistory() {
                List<MarketCandle> candles = closeSeries(
                                CandleTimeframe.ONE_MINUTE,
                                20);

                assertNull(
                                service.calculateRsi14Wilder(candles));
        }

        @Test
        void calculateRsi14Wilder_shouldReturnNullWhenCloseIsMissing() {
                List<MarketCandle> candles = new ArrayList<>(
                                closeSeries(
                                                CandleTimeframe.ONE_MINUTE,
                                                42));

                candles.get(20).setClosePrice(null);

                assertNull(
                                service.calculateRsi14Wilder(candles));
        }

        @Test
        void calculateRsi14Wilder_shouldMatchOneMinuteReferenceValue() {
                assertEquals(
                                71.4233150200964,
                                service.calculateRsi14Wilder(
                                                rsiReferenceCandles(
                                                                CandleTimeframe.ONE_MINUTE)),
                                0.000001);
        }

        @Test
        void calculateRsi14Wilder_shouldMatchFiveMinuteReferenceValue() {
                assertEquals(
                                71.4233150200964,
                                service.calculateRsi14Wilder(
                                                rsiReferenceCandles(
                                                                CandleTimeframe.FIVE_MINUTE)),
                                0.000001);
        }

        @Test
        void calculateRsi14Wilder_shouldMatchFifteenMinuteReferenceValue() {
                assertEquals(
                                71.4233150200964,
                                service.calculateRsi14Wilder(
                                                rsiReferenceCandles(
                                                                CandleTimeframe.FIFTEEN_MINUTE)),
                                0.000001);
        }

        @Test
        void calculateRsi14Wilder_shouldReturnHundredForStrongUptrend() {
                List<MarketCandle> candles = new ArrayList<>();

                for (int i = 0; i < 42; i++) {
                        double close = 100.0 + i;

                        candles.add(
                                        candle(
                                                        CandleTimeframe.FIVE_MINUTE,
                                                        LocalDateTime.of(
                                                                        2026,
                                                                        7,
                                                                        15,
                                                                        9,
                                                                        15)
                                                                        .plusMinutes(i * 5L),
                                                        close,
                                                        close + 1.0,
                                                        close - 1.0,
                                                        close,
                                                        100L));
                }

                assertEquals(
                                100.0,
                                service.calculateRsi14Wilder(candles),
                                0.000001);
        }

        @Test
        void calculateRsi14Wilder_shouldReturnZeroForStrongDowntrend() {
                List<MarketCandle> candles = new ArrayList<>();

                for (int i = 0; i < 42; i++) {
                        double close = 200.0 - i;

                        candles.add(
                                        candle(
                                                        CandleTimeframe.FIVE_MINUTE,
                                                        LocalDateTime.of(
                                                                        2026,
                                                                        7,
                                                                        15,
                                                                        9,
                                                                        15)
                                                                        .plusMinutes(i * 5L),
                                                        close,
                                                        close + 1.0,
                                                        close - 1.0,
                                                        close,
                                                        100L));
                }

                assertEquals(
                                0.0,
                                service.calculateRsi14Wilder(candles),
                                0.000001);
        }

        @Test
        void calculateRsi14Wilder_shouldReturnFiftyForFlatSeries() {
                List<MarketCandle> candles = new ArrayList<>();

                for (int i = 0; i < 42; i++) {
                        candles.add(
                                        candle(
                                                        CandleTimeframe.FIVE_MINUTE,
                                                        LocalDateTime.of(
                                                                        2026,
                                                                        7,
                                                                        15,
                                                                        9,
                                                                        15)
                                                                        .plusMinutes(i * 5L),
                                                        100.0,
                                                        101.0,
                                                        99.0,
                                                        100.0,
                                                        100L));
                }

                assertEquals(
                                50.0,
                                service.calculateRsi14Wilder(candles),
                                0.000001);
        }

        @Test
        void calculateAtr14Wilder_shouldReturnNullWithInsufficientHistory() {
                List<MarketCandle> candles = closeSeries(
                                CandleTimeframe.ONE_MINUTE,
                                13);

                assertNull(
                                service.calculateAtr14Wilder(
                                                candles,
                                                100.0));
        }

        @Test
        void calculateAtr14Wilder_shouldReturnNullWithoutPreviousDayClose() {
                List<MarketCandle> candles = atrReferenceCandles(
                                CandleTimeframe.ONE_MINUTE);

                assertNull(
                                service.calculateAtr14Wilder(
                                                candles,
                                                null));
        }

        @Test
        void calculateAtr14Wilder_shouldMatchOneMinuteReferenceValue() {
                assertEquals(
                                2.9196051907163607,
                                service.calculateAtr14Wilder(
                                                atrReferenceCandles(
                                                                CandleTimeframe.ONE_MINUTE),
                                                100.0),
                                0.000001);
        }

        @Test
        void calculateAtr14Wilder_shouldMatchFiveMinuteReferenceValue() {
                assertEquals(
                                2.9196051907163607,
                                service.calculateAtr14Wilder(
                                                atrReferenceCandles(
                                                                CandleTimeframe.FIVE_MINUTE),
                                                100.0),
                                0.000001);
        }

        @Test
        void calculateAtr14Wilder_shouldMatchFifteenMinuteReferenceValue() {
                assertEquals(
                                2.9196051907163607,
                                service.calculateAtr14Wilder(
                                                atrReferenceCandles(
                                                                CandleTimeframe.FIFTEEN_MINUTE),
                                                100.0),
                                0.000001);
        }

        @Test
        void calculateAtr14Wilder_shouldUsePreviousTradingDayCloseForFirstCandle() {
                List<MarketCandle> candles = new ArrayList<>();

                for (int i = 0; i < 14; i++) {
                        double close = 100.0;

                        candles.add(
                                        candle(
                                                        CandleTimeframe.ONE_MINUTE,
                                                        LocalDateTime.of(
                                                                        2026,
                                                                        7,
                                                                        15,
                                                                        9,
                                                                        15)
                                                                        .plusMinutes(i),
                                                        close,
                                                        close + 1.0,
                                                        close - 1.0,
                                                        close,
                                                        100L));
                }

                Double atr = service.calculateAtr14Wilder(
                                candles,
                                90.0);

                assertEquals(
                                (11.0 + (13.0 * 2.0)) / 14.0,
                                atr,
                                0.000001);
        }

        @Test
        void calculateAtr14Wilder_shouldMatchConstantRangeReference() {
                List<MarketCandle> candles = new ArrayList<>();

                double close = 100.0;

                for (int i = 0; i < 20; i++) {
                        candles.add(
                                        candle(
                                                        CandleTimeframe.FIVE_MINUTE,
                                                        LocalDateTime.of(
                                                                        2026,
                                                                        7,
                                                                        15,
                                                                        9,
                                                                        15)
                                                                        .plusMinutes(i * 5L),
                                                        close,
                                                        close + 2.0,
                                                        close - 2.0,
                                                        close,
                                                        100L));

                        close += 1.0;
                }

                assertEquals(
                                4.0,
                                service.calculateAtr14Wilder(
                                                candles,
                                                100.0),
                                0.000001);
        }

        @Test
        void calculateCumulativeVolume_shouldIgnoreInvalidVolume() {
                List<MarketCandle> candles = List.of(
                                candle(
                                                CandleTimeframe.ONE_MINUTE,
                                                LocalDateTime.of(
                                                                2026, 7, 15, 9, 15),
                                                100.0,
                                                101.0,
                                                99.0,
                                                100.0,
                                                100L),
                                candle(
                                                CandleTimeframe.ONE_MINUTE,
                                                LocalDateTime.of(
                                                                2026, 7, 15, 9, 16),
                                                100.0,
                                                101.0,
                                                99.0,
                                                100.0,
                                                0L));

                assertEquals(
                                100L,
                                service.calculateCumulativeVolume(candles));
        }

        @Test
        void calculateVolumeDirection_shouldDetectIncreasingVolume() {
                assertEquals(
                                VolumeDirection.INCREASING,
                                service.calculateVolumeDirection(
                                                volumeCandles(
                                                                100L,
                                                                200L,
                                                                300L)));
        }

        @Test
        void calculateVolumeDirection_shouldDetectDecreasingVolume() {
                assertEquals(
                                VolumeDirection.DECREASING,
                                service.calculateVolumeDirection(
                                                volumeCandles(
                                                                300L,
                                                                200L,
                                                                100L)));
        }

        @Test
        void calculateVolumeDirection_shouldReturnFlatForMixedVolume() {
                assertEquals(
                                VolumeDirection.FLAT,
                                service.calculateVolumeDirection(
                                                volumeCandles(
                                                                100L,
                                                                300L,
                                                                200L)));
        }

        @Test
        void calculateVolX_shouldMatchReferenceValue() {
                assertEquals(
                                1.25,
                                service.calculateVolX(
                                                50_000L,
                                                40_000L),
                                0.000001);
        }

        @Test
        void calculateVolX_shouldReturnNullWithoutPositiveBaseline() {
                assertNull(
                                service.calculateVolX(
                                                50_000L,
                                                null));

                assertNull(
                                service.calculateVolX(
                                                50_000L,
                                                0L));
        }

        private List<MarketCandle> rsiReferenceCandles(
                        CandleTimeframe timeframe) {

                double[] closes = {
                                100.0, 101.2, 100.8, 101.5, 102.1,
                                101.7, 102.8, 103.0, 102.4, 103.6,
                                104.1, 103.9, 104.8, 105.4, 104.9,
                                105.7, 106.2, 105.8, 106.9, 107.4,
                                106.8, 107.9, 108.5, 108.1, 109.0,
                                109.6, 109.1, 110.3, 110.9, 110.4,
                                111.2, 111.8, 111.1, 112.0, 112.7,
                                112.2, 113.1, 113.9, 113.3, 114.2,
                                114.8, 114.1
                };

                List<MarketCandle> candles = new ArrayList<>();

                for (int i = 0; i < closes.length; i++) {
                        double close = closes[i];

                        candles.add(
                                        candle(
                                                        timeframe,
                                                        LocalDateTime.of(
                                                                        2026,
                                                                        7,
                                                                        15,
                                                                        9,
                                                                        15)
                                                                        .plusMinutes(
                                                                                        i * timeframeMinutes(
                                                                                                        timeframe)),
                                                        close,
                                                        close + 1.0,
                                                        close - 1.0,
                                                        close,
                                                        100L));
                }

                return candles;
        }

        private List<MarketCandle> atrReferenceCandles(
                        CandleTimeframe timeframe) {

                double[] closes = {
                                100.0, 101.2, 100.8, 101.5, 102.1,
                                101.7, 102.8, 103.0, 102.4, 103.6,
                                104.1, 103.9, 104.8, 105.4, 104.9,
                                105.7, 106.2, 105.8, 106.9, 107.4
                };

                List<MarketCandle> candles = new ArrayList<>();

                for (int i = 0; i < closes.length; i++) {
                        double close = closes[i];

                        double high = close + (1.4 + (i % 3) * 0.2);

                        double low = close - (1.1 + (i % 4) * 0.15);

                        candles.add(
                                        candle(
                                                        timeframe,
                                                        LocalDateTime.of(
                                                                        2026,
                                                                        7,
                                                                        15,
                                                                        9,
                                                                        15)
                                                                        .plusMinutes(
                                                                                        i * timeframeMinutes(
                                                                                                        timeframe)),
                                                        close,
                                                        high,
                                                        low,
                                                        close,
                                                        100L));
                }

                return candles;
        }

        private List<MarketCandle> closeSeries(
                        CandleTimeframe timeframe,
                        int count) {

                List<MarketCandle> candles = new ArrayList<>();

                for (int i = 0; i < count; i++) {
                        double close = 100.0 + i;

                        candles.add(
                                        candle(
                                                        timeframe,
                                                        LocalDateTime.of(
                                                                        2026,
                                                                        7,
                                                                        15,
                                                                        9,
                                                                        15)
                                                                        .plusMinutes(
                                                                                        i * timeframeMinutes(
                                                                                                        timeframe)),
                                                        close,
                                                        close + 1.0,
                                                        close - 1.0,
                                                        close,
                                                        100L));
                }

                return candles;
        }

        private List<MarketCandle> volumeCandles(
                        long first,
                        long second,
                        long third) {

                return List.of(
                                candle(
                                                CandleTimeframe.ONE_MINUTE,
                                                LocalDateTime.of(
                                                                2026, 7, 15, 9, 15),
                                                100.0,
                                                101.0,
                                                99.0,
                                                100.0,
                                                first),
                                candle(
                                                CandleTimeframe.ONE_MINUTE,
                                                LocalDateTime.of(
                                                                2026, 7, 15, 9, 16),
                                                100.0,
                                                101.0,
                                                99.0,
                                                100.0,
                                                second),
                                candle(
                                                CandleTimeframe.ONE_MINUTE,
                                                LocalDateTime.of(
                                                                2026, 7, 15, 9, 17),
                                                100.0,
                                                101.0,
                                                99.0,
                                                100.0,
                                                third));
        }

        private int timeframeMinutes(
                        CandleTimeframe timeframe) {

                return switch (timeframe) {
                        case ONE_MINUTE -> 1;
                        case FIVE_MINUTE -> 5;
                        case FIFTEEN_MINUTE -> 15;
                        default -> throw new IllegalArgumentException(
                                        "Unsupported test timeframe: "
                                                        + timeframe);
                };
        }

        private MarketCandle candle(
                        CandleTimeframe timeframe,
                        LocalDateTime candleTime,
                        double open,
                        double high,
                        double low,
                        double close,
                        long volume) {

                return MarketCandle.builder()
                                .symbol("WIPRO")
                                .exchange("NSE")
                                .timeframe(timeframe)
                                .candleTime(candleTime)
                                .openPrice(open)
                                .highPrice(high)
                                .lowPrice(low)
                                .closePrice(close)
                                .volume(volume)
                                .source("REFERENCE_TEST")
                                .createdAt(candleTime)
                                .updatedAt(candleTime)
                                .isFinalized(true)
                                .qualityStatus(
                                                CandleQualityStatus.LIVE)
                                .build();
        }
}
