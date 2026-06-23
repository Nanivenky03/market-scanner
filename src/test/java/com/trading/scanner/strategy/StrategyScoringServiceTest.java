package com.trading.scanner.strategy;

import com.trading.scanner.config.BreakoutRuleProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StrategyScoringServiceTest {

    private final StrategyScoringService scoringService = new StrategyScoringService();

    @Test
    void score_shouldReturnInvestForStrongInputs() {
        StrategyYamlDefinition definition = buildStrategy();

        StrategyScoringModels.StrategyScoreResult result = scoringService.score(definition, Map.of(
                "breakoutPercent", 0.30,
                "volumeRatio", 2.0,
                "rsi", 60.0));

        assertEquals(ScoreDecision.INVEST, result.decision());
        assertEquals(3, result.factors().size());
    }

    @Test
    void score_shouldReturnIgnoreForWeakInputs() {
        StrategyYamlDefinition definition = buildStrategy();

        StrategyScoringModels.StrategyScoreResult result = scoringService.score(definition, Map.of(
                "breakoutPercent", 1.20,
                "volumeRatio", 1.2,
                "rsi", 52.0));

        assertEquals(ScoreDecision.IGNORE, result.decision());
    }

    private StrategyYamlDefinition buildStrategy() {
        return new StrategyYamlDefinition(
                "breakout_v1",
                "1.0.0",
                "Breakout V1",
                StrategyTimeframe.FIFTEEN_MINUTE,
                true,
                false,
                false,
                StrategyStatus.DRAFT,
                new BreakoutRuleProperties(
                        21, 14, 20, 50, 200,
                        50.0, 1.5, 60.0, 2.0,
                        0.5, 0.1, 1.0, 0.05),
                new StrategyScoringModels.StrategyScoringDefinition(
                        Map.of(
                                "breakoutPercent", new StrategyScoringModels.FactorScoreDefinition(1.0, List.of(
                                        new StrategyScoringModels.ScoreBandDefinition("GOOD", 0.0, 0.4, 1.0),
                                        new StrategyScoringModels.ScoreBandDefinition("LATE", 1.0, 999.0, 0.0))),
                                "volumeRatio", new StrategyScoringModels.FactorScoreDefinition(1.0, List.of(
                                        new StrategyScoringModels.ScoreBandDefinition("IGNORE", 0.0, 1.5, 0.0),
                                        new StrategyScoringModels.ScoreBandDefinition("GOOD", 1.5, 999.0, 1.0))),
                                "rsi", new StrategyScoringModels.FactorScoreDefinition(1.0, List.of(
                                        new StrategyScoringModels.ScoreBandDefinition("WEAK", 0.0, 55.0, 0.0),
                                        new StrategyScoringModels.ScoreBandDefinition("GOOD", 55.0, 70.0, 1.0),
                                        new StrategyScoringModels.ScoreBandDefinition("LATE", 70.0, 999.0, 0.0)))),
                        new StrategyScoringModels.DecisionThresholds(0.45, 0.65)));
    }
}