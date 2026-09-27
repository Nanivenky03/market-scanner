package com.trading.scanner.service.data;

import com.trading.scanner.model.CandleDirection;
import com.trading.scanner.model.CandleQualityStatus;
import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.MarketCandle;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class CandleStructureCalculatorTest {

    @Test
    void strongBullish_shouldPassForStrategistExample() {
        MarketCandle candle = candle(100, 103, 99.5, 102.8);

        LiveMarketCandleService.applyCandleStructure(candle);

        assertEquals(CandleDirection.BULLISH, candle.getDirection());
        assertEquals(0.8, candle.getBodyRatio(), 0.0001);
        assertEquals(0.0571, candle.getUpperWickRatio(), 0.0001);
        assertEquals(3.404, candle.getRangePct(), 0.01);
        assertTrue(candle.getStrongBullish());
        assertFalse(candle.getStrongBearish());
    }

    @Test
    void doji_shouldNotBeStrong() {
        MarketCandle candle = candle(100, 101, 99, 100.1);

        LiveMarketCandleService.applyCandleStructure(candle);

        assertEquals(CandleDirection.BULLISH, candle.getDirection());
        assertFalse(candle.getStrongBullish());
        assertFalse(candle.getStrongBearish());
    }

    @Test
    void shootingStar_shouldNotBeStrongBullish() {
        MarketCandle candle = candle(100, 104, 99.8, 100.3);

        LiveMarketCandleService.applyCandleStructure(candle);

        assertFalse(candle.getStrongBullish());
    }

    @Test
    void zeroRange_shouldReturnNullRatiosAndFalseFlags() {
        MarketCandle candle = candle(100, 100, 100, 100);

        LiveMarketCandleService.applyCandleStructure(candle);

        assertEquals(CandleDirection.NEUTRAL, candle.getDirection());
        assertNull(candle.getBodyRatio());
        assertNull(candle.getUpperWickRatio());
        assertNull(candle.getLowerWickRatio());
        assertNull(candle.getRangePct());
        assertFalse(candle.getStrongBullish());
        assertFalse(candle.getStrongBearish());
    }

    @Test
    void borderlineRange_shouldUseThresholdWithoutExactValueAssumption() {
        MarketCandle candle = candle(100, 100.15, 99.95, 100.08);

        LiveMarketCandleService.applyCandleStructure(candle);

        assertTrue(candle.getRangePct() >= 0.15);
        assertTrue(candle.getBodyRatio() > 0.0);
    }

    private MarketCandle candle(
            double open,
            double high,
            double low,
            double close) {

        LocalDateTime time = LocalDateTime.of(2026, 8, 3, 9, 20);

        return MarketCandle.builder()
                .symbol("TEST")
                .exchange("NSE")
                .timeframe(CandleTimeframe.FIVE_MINUTE)
                .candleTime(time)
                .openPrice(open)
                .highPrice(high)
                .lowPrice(low)
                .closePrice(close)
                .volume(1000L)
                .source("TEST")
                .createdAt(time)
                .updatedAt(time)
                .isFinalized(true)
                .qualityStatus(CandleQualityStatus.LIVE)
                .build();
    }
}
