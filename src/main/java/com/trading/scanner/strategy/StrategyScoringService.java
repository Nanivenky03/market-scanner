package com.trading.scanner.strategy;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class StrategyScoringService {

    public StrategyScoringModels.StrategyScoreResult score(
            StrategyYamlDefinition strategyDefinition,
            Map<String, Double> factorValues) {
        if (strategyDefinition == null) {
            throw new IllegalArgumentException("strategyDefinition is required");
        }
        if (strategyDefinition.scoring() == null) {
            throw new IllegalArgumentException(
                    "Strategy " + strategyDefinition.strategyId() + " has no scoring definition");
        }
        if (strategyDefinition.scoring().factors() == null || strategyDefinition.scoring().factors().isEmpty()) {
            throw new IllegalArgumentException(
                    "Strategy " + strategyDefinition.strategyId() + " has no scoring factors");
        }

        List<StrategyScoringModels.StrategyFactorResult> factorResults = new ArrayList<>();
        double totalWeightedScore = 0.0;
        double totalWeight = 0.0;

        for (Map.Entry<String, StrategyScoringModels.FactorScoreDefinition> entry : strategyDefinition.scoring()
                .factors().entrySet()) {
            String factorName = entry.getKey();
            StrategyScoringModels.FactorScoreDefinition factorDefinition = entry.getValue();

            Double rawValue = factorValues.get(factorName);
            if (rawValue == null) {
                throw new IllegalArgumentException("Missing factor value for: " + factorName);
            }

            StrategyScoringModels.ScoreBandDefinition matchedBand = factorDefinition.bands().stream()
                    .filter(band -> matchesBand(rawValue, band))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No score band matched for factor=" + factorName + ", value=" + rawValue));

            double weight = factorDefinition.weight() != null ? factorDefinition.weight() : 1.0;
            double rawScore = matchedBand.score() != null ? matchedBand.score() : 0.0;
            double weightedScore = rawScore * weight;

            factorResults.add(new StrategyScoringModels.StrategyFactorResult(
                    factorName,
                    rawValue,
                    matchedBand.label(),
                    rawScore,
                    weight,
                    weightedScore));

            totalWeight += weight;
            totalWeightedScore += weightedScore;
        }

        double finalScore = totalWeight == 0.0 ? 0.0 : totalWeightedScore / totalWeight;
        ScoreDecision decision = resolveDecision(strategyDefinition.scoring().decisionThresholds(), finalScore);

        return new StrategyScoringModels.StrategyScoreResult(finalScore, decision, factorResults);
    }

    private boolean matchesBand(Double value, StrategyScoringModels.ScoreBandDefinition band) {
        boolean minOk = band.min() == null || value >= band.min();
        boolean maxOk = band.max() == null || value < band.max();
        return minOk && maxOk;
    }

    private ScoreDecision resolveDecision(StrategyScoringModels.DecisionThresholds thresholds, double finalScore) {
        if (thresholds == null) {
            return ScoreDecision.WATCH;
        }

        double ignoreBelow = thresholds.ignoreBelow() != null ? thresholds.ignoreBelow() : 0.0;
        double investAbove = thresholds.investAbove() != null ? thresholds.investAbove() : 1.0;

        if (finalScore < ignoreBelow) {
            return ScoreDecision.IGNORE;
        }
        if (finalScore >= investAbove) {
            return ScoreDecision.INVEST;
        }
        return ScoreDecision.WATCH;
    }
}