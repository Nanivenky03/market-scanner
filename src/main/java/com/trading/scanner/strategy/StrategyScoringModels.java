package com.trading.scanner.strategy;

import java.util.List;
import java.util.Map;

public final class StrategyScoringModels {

    private StrategyScoringModels() {
    }

    public record ScoreBandDefinition(
            String label,
            Double min,
            Double max,
            Double score) {
    }

    public record FactorScoreDefinition(
            Double weight,
            List<ScoreBandDefinition> bands) {
    }

    public record DecisionThresholds(
            Double ignoreBelow,
            Double investAbove) {
    }

    public record StrategyScoringDefinition(
            Map<String, FactorScoreDefinition> factors,
            DecisionThresholds decisionThresholds) {
    }

    public record StrategyFactorResult(
            String factorName,
            Double rawValue,
            String bandLabel,
            Double rawScore,
            Double weight,
            Double weightedScore) {
    }

    public record StrategyScoreResult(
            Double totalScore,
            ScoreDecision decision,
            List<StrategyFactorResult> factors) {
    }
}