package com.trading.scanner.strategy;

import com.trading.scanner.service.runtime.RuleExecutionPolicyService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StrategyScoringServiceTest {

        private final StrategyScoringService strategyScoringService = new StrategyScoringService();

        @Test
        void score_shouldLoadYamlStrategyAndReturnInvestDecisionForStrongFactors() {
                StrategyCatalogService strategyCatalogService = new StrategyCatalogService(
                                new RuleExecutionPolicyService());
                strategyCatalogService.load();

                StrategyYamlDefinition definition = strategyCatalogService.getRequired("breakout_v1");

                StrategyScoringModels.StrategyScoreResult result = strategyScoringService.score(
                                definition,
                                Map.of(
                                                "breakoutPercent", 0.35,
                                                "volumeRatio", 2.1,
                                                "rsi", 61.0));

                assertNotNull(result);
                assertNotNull(result.decision());
                assertNotNull(result.factors());
                assertTrue(result.totalScore() > 0.0);
                assertEquals(3, result.factors().size());
                assertEquals(ScoreDecision.INVEST, result.decision());
        }

        @Test
        void score_shouldReturnNoInvestForWeakFactors() {
                StrategyCatalogService strategyCatalogService = new StrategyCatalogService(
                                new RuleExecutionPolicyService());
                strategyCatalogService.load();

                StrategyYamlDefinition definition = strategyCatalogService.getRequired("breakout_v1");

                StrategyScoringModels.StrategyScoreResult result = strategyScoringService.score(
                                definition,
                                Map.of(
                                                "breakoutPercent", 0.01,
                                                "volumeRatio", 1.0,
                                                "rsi", 40.0));

                assertNotNull(result);
                assertNotNull(result.decision());
                assertTrue(result.totalScore() >= 0.0);
                assertEquals(ScoreDecision.IGNORE, result.decision());
        }
}