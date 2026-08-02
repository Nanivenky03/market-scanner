package com.trading.scanner.service.engine;

import com.trading.scanner.model.CandleQualityStatus;
import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.model.StockPrice;
import com.trading.scanner.strategy.ScoreDecision;
import com.trading.scanner.strategy.StrategyScoringModels;
import com.trading.scanner.strategy.StrategyScoringService;
import com.trading.scanner.strategy.StrategyYamlDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

class BreakoutSignalEvaluatorTest {

    private StrategyScoringService strategyScoringService;
    private VolumeEngineService volumeEngineService;
    private BreakoutSignalEvaluator breakoutSignalEvaluator;

    @BeforeEach
    void setUp() {
        strategyScoringService = mock(StrategyScoringService.class);
        volumeEngineService = mock(VolumeEngineService.class);
        breakoutSignalEvaluator = new BreakoutSignalEvaluator(strategyScoringService, volumeEngineService);
    }

    @Test
    void evaluate_shouldRejectWhenVolXIsBelowThreshold() {
        StrategyYamlDefinition strategy = mock(StrategyYamlDefinition.class, RETURNS_DEEP_STUBS);
        when(strategy.breakout().maxGap()).thenReturn(0.05);
        when(strategy.breakout().volumeMultiplierMatch()).thenReturn(1.5);
        when(strategy.breakout().rsiThresholdMatch()).thenReturn(55.0);

        List<MarketCandle> candles = candlesForEvaluation();
        List<StockPrice> dailyPrices = dailyPricesForEvaluation();

        when(volumeEngineService.currentVolumeState("ONGC", "NSE", LocalDate.of(2026, 7, 15)))
                .thenReturn(new VolumeEngineService.VolumeState(
                        "ONGC",
                        "NSE",
                        LocalDate.of(2026, 7, 15),
                        LocalDateTime.of(2026, 7, 15, 10, 0),
                        45,
                        120000L,
                        100000L,
                        1.2,
                        500000L,
                        40000L,
                        60000L,
                        0.6666666667,
                        "UP"));

        BreakoutSignalEvaluator.EvaluationSnapshot result = breakoutSignalEvaluator.evaluate(strategy, candles,
                dailyPrices);

        assertNull(result);
        verify(strategyScoringService, never()).score(any(), anyMap());
    }

    @Test
    void evaluate_shouldUseCommonVolXAndPassWhenThresholdIsMet() {
        StrategyYamlDefinition strategy = mock(StrategyYamlDefinition.class, RETURNS_DEEP_STUBS);
        when(strategy.breakout().maxGap()).thenReturn(0.05);
        when(strategy.breakout().volumeMultiplierMatch()).thenReturn(1.5);
        when(strategy.breakout().rsiThresholdMatch()).thenReturn(55.0);

        List<MarketCandle> candles = candlesForEvaluation();
        List<StockPrice> dailyPrices = dailyPricesForEvaluation();

        when(volumeEngineService.currentVolumeState("ONGC", "NSE", LocalDate.of(2026, 7, 15)))
                .thenReturn(new VolumeEngineService.VolumeState(
                        "ONGC",
                        "NSE",
                        LocalDate.of(2026, 7, 15),
                        LocalDateTime.of(2026, 7, 15, 10, 0),
                        45,
                        180000L,
                        100000L,
                        1.8,
                        500000L,
                        50000L,
                        60000L,
                        0.8333333333,
                        "UP"));

        StrategyScoringModels.StrategyScoreResult scoreResult = mock(StrategyScoringModels.StrategyScoreResult.class);
        when(scoreResult.decision()).thenReturn(ScoreDecision.INVEST);

        when(strategyScoringService.score(eq(strategy), anyMap())).thenReturn(scoreResult);

        BreakoutSignalEvaluator.EvaluationSnapshot result = breakoutSignalEvaluator.evaluate(strategy, candles,
                dailyPrices);

        assertEquals(1.8, result.volumeRatio(), 0.000001);
        assertEquals(60.0, result.rsi(), 0.000001);
        assertEquals(100.5, result.vwap(), 0.000001);
        verify(strategyScoringService, times(1)).score(eq(strategy), anyMap());
    }

    private List<MarketCandle> candlesForEvaluation() {
        List<MarketCandle> candles = new ArrayList<>();

        for (int i = 0; i < 19; i++) {
            candles.add(MarketCandle.builder()
                    .symbol("ONGC")
                    .exchange("NSE")
                    .timeframe(CandleTimeframe.FIVE_MINUTE)
                    .candleTime(LocalDateTime.of(2026, 7, 15, 9, 15).plusMinutes(i * 5L))
                    .openPrice(95.0 + i * 0.1)
                    .highPrice(96.0 + i * 0.1)
                    .lowPrice(94.0 + i * 0.1)
                    .closePrice(95.5 + i * 0.1)
                    .volume(1000L + i)
                    .isFinalized(true)
                    .qualityStatus(CandleQualityStatus.VALID)
                    .build());
        }

        candles.add(MarketCandle.builder()
                .symbol("ONGC")
                .exchange("NSE")
                .timeframe(CandleTimeframe.FIVE_MINUTE)
                .candleTime(LocalDateTime.of(2026, 7, 15, 10, 0))
                .openPrice(100.2)
                .highPrice(102.0)
                .lowPrice(99.8)
                .closePrice(101.8)
                .volume(2500L)
                .vwap(100.5)
                .rsi14(60.0)
                .isFinalized(true)
                .qualityStatus(CandleQualityStatus.VALID)
                .build());

        return candles;
    }

    private List<StockPrice> dailyPricesForEvaluation() {
        return List.of(
                StockPrice.builder()
                        .symbol("ONGC")
                        .date(LocalDate.of(2026, 7, 14))
                        .highPrice(100.0)
                        .closePrice(98.0)
                        .adjClose(98.0)
                        .volume(100000)
                        .build(),
                StockPrice.builder()
                        .symbol("ONGC")
                        .date(LocalDate.of(2026, 7, 15))
                        .highPrice(102.0)
                        .closePrice(101.8)
                        .adjClose(101.8)
                        .volume(120000)
                        .build());
    }
}
