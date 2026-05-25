package com.trading.scanner.service.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.scanner.config.BreakoutRuleProperties;
import com.trading.scanner.model.StockPrice;
import com.trading.scanner.service.indicators.IndicatorBundle;
import com.trading.scanner.service.scanner.rules.BreakoutConfirmedRule;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BreakoutConfirmedRuleTest {

    private final BreakoutRuleProperties properties = new BreakoutRuleProperties(
            21,     // lookbackWindow
            14,     // rsiPeriod
            20,     // smaShortPeriod
            50,     // smaMediumPeriod
            200,    // smaLongPeriod
            50.0,   // rsiThresholdMatch
            1.5,    // volumeMultiplierMatch
            60.0,   // rsiThresholdConfidence
            2.0,    // volumeMultiplierConfidence
            0.5,    // baseConfidence
            0.1,    // confidenceIncrement
            1.0,    // maxConfidenceCap
            0.05    // maxGap
    );

    private final BreakoutConfirmedRule rule =
            new BreakoutConfirmedRule(new ObjectMapper(), properties);

    @Test
    void matches_shouldReturnFalse_whenInsufficientHistory() {
        List<StockPrice> prices = buildPriceSeries(10, 103.0, 1800);
        IndicatorBundle indicators = validIndicators();

        boolean result = rule.matches("TCS", prices, indicators);

        assertFalse(result);
    }

    @Test
    void matches_shouldReturnFalse_whenIndicatorsMissing() {
        List<StockPrice> prices = buildPriceSeries(21, 103.0, 1800);

        IndicatorBundle indicators = IndicatorBundle.builder()
                .aboveSma20(true)
                .build();

        boolean result = rule.matches("TCS", prices, indicators);

        assertFalse(result);
    }

    @Test
    void matches_shouldReturnTrue_forValidBreakout() {
        List<StockPrice> prices = buildPriceSeries(21, 103.0, 1800);
        IndicatorBundle indicators = validIndicators();

        boolean result = rule.matches("TCS", prices, indicators);

        assertTrue(result);
    }

    @Test
    void matches_shouldReturnFalse_whenGapTooLarge() {
        List<StockPrice> prices = buildPriceSeries(21, 110.0, 1800); // 10% above recent high=100
        IndicatorBundle indicators = validIndicators();

        boolean result = rule.matches("TCS", prices, indicators);

        assertFalse(result);
    }

    @Test
    void matches_shouldReturnFalse_whenVolumeTooWeak() {
        List<StockPrice> prices = buildPriceSeries(21, 103.0, 1200); // avgVolume20=1000, need >1500
        IndicatorBundle indicators = validIndicators();

        boolean result = rule.matches("TCS", prices, indicators);

        assertFalse(result);
    }

    @Test
    void matches_shouldReturnFalse_whenRsiTooWeak() {
        List<StockPrice> prices = buildPriceSeries(21, 103.0, 1800);

        IndicatorBundle indicators = IndicatorBundle.builder()
                .rsi(45.0)
                .sma20(95.0)
                .avgVolume20(1000L)
                .aboveSma20(true)
                .aboveSma50(true)
                .aboveSma200(true)
                .build();

        boolean result = rule.matches("TCS", prices, indicators);

        assertFalse(result);
    }

    @Test
    void getConfidence_shouldReturnZero_whenRuleDoesNotMatch() {
        List<StockPrice> prices = buildPriceSeries(21, 103.0, 1200); // weak volume -> no match
        IndicatorBundle indicators = validIndicators();

        Double confidence = rule.getConfidence("TCS", prices, indicators);

        assertEquals(0.0, confidence);
    }

    @Test
    void getConfidence_shouldIncreaseWithStrongConditions() {
        List<StockPrice> prices = buildPriceSeries(21, 103.0, 2500);

        IndicatorBundle indicators = IndicatorBundle.builder()
                .rsi(65.0)              // above confidence threshold
                .sma20(95.0)
                .avgVolume20(1000L)     // today volume 2500 > 1000*2.0
                .aboveSma20(true)
                .aboveSma50(true)
                .aboveSma200(true)
                .build();

        Double confidence = rule.getConfidence("TCS", prices, indicators);

        assertEquals(0.9d, confidence.doubleValue(), 0.000001d);
    }

    @Test
    void getParameterSnapshot_shouldBeDeterministicAndSorted() {
        String snapshot = rule.getParameterSnapshot();

        assertEquals(
                "{\"baseConfidence\":0.5," +
                        "\"confidenceIncrement\":0.1," +
                        "\"lookbackWindow\":21," +
                        "\"maxConfidenceCap\":1.0," +
                        "\"maxGap\":0.05," +
                        "\"rsiPeriod\":14," +
                        "\"rsiThresholdConfidence\":60.0," +
                        "\"rsiThresholdMatch\":50.0," +
                        "\"smaLongPeriod\":200," +
                        "\"smaMediumPeriod\":50," +
                        "\"smaShortPeriod\":20," +
                        "\"volumeMultiplierConfidence\":2.0," +
                        "\"volumeMultiplierMatch\":1.5}",
                snapshot
        );
    }

    @Test
    void getMetadata_shouldContainKeySignalFields() {
        List<StockPrice> prices = buildPriceSeries(21, 103.0, 1800);
        IndicatorBundle indicators = validIndicators();

        String metadata = rule.getMetadata("TCS", prices, indicators);

        assertNotNull(metadata);
        assertTrue(metadata.contains("close=103.0"));
        assertTrue(metadata.contains("volume=1800"));
        assertTrue(metadata.contains("rsi=55.0"));
        assertTrue(metadata.contains("sma20=95.0"));
        assertTrue(metadata.contains("avgVolume20=1000"));
    }

    private IndicatorBundle validIndicators() {
        return IndicatorBundle.builder()
                .rsi(55.0)
                .sma20(95.0)
                .avgVolume20(1000L)
                .aboveSma20(true)
                .aboveSma50(true)
                .aboveSma200(true)
                .build();
    }

    /**
     * Builds a deterministic 21-day series where:
     * - first 20 days have high <= 100
     * - day 21 is the candidate breakout day
     */
    private List<StockPrice> buildPriceSeries(int size, double finalClose, int finalVolume) {
        List<StockPrice> prices = new ArrayList<>();
        LocalDate start = LocalDate.of(2024, 3, 1);

        for (int i = 0; i < size - 1; i++) {
            prices.add(StockPrice.builder()
                    .symbol("TCS")
                    .date(start.plusDays(i))
                    .openPrice(95.0)
                    .highPrice(100.0)
                    .lowPrice(94.0)
                    .closePrice(99.0)
                    .adjClose(99.0)
                    .volume(1000)
                    .build());
        }

        prices.add(StockPrice.builder()
                .symbol("TCS")
                .date(start.plusDays(size - 1))
                .openPrice(finalClose - 1.0)
                .highPrice(finalClose + 1.0)
                .lowPrice(finalClose - 2.0)
                .closePrice(finalClose)
                .adjClose(finalClose)
                .volume(finalVolume)
                .build());

        return prices;
    }
}