package com.trading.scanner.strategy.model;

import java.util.Collections;
import java.util.List;

/**
 * Outcome of a strategy evaluation on a specific symbol and market state.
 */
public record StrategyEvaluation(
        EvaluationStatus status,
        StrategyProposal proposal,
        Double confidenceScore,
        List<String> passedConditions,
        List<String> failedConditions,
        String failureReason
) {

    public enum EvaluationStatus {
        QUALIFIED,
        NOT_QUALIFIED,
        NOT_EVALUABLE
    }

    public StrategyEvaluation {
        passedConditions = passedConditions == null ? Collections.emptyList() : Collections.unmodifiableList(passedConditions);
        failedConditions = failedConditions == null ? Collections.emptyList() : Collections.unmodifiableList(failedConditions);
    }

    public boolean isQualified() {
        return status == EvaluationStatus.QUALIFIED;
    }

    public boolean isNotEvaluable() {
        return status == EvaluationStatus.NOT_EVALUABLE;
    }

    public static StrategyEvaluation qualified(StrategyProposal proposal, Double score, List<String> passed) {
        return new StrategyEvaluation(EvaluationStatus.QUALIFIED, proposal, score, passed, Collections.emptyList(), null);
    }

    public static StrategyEvaluation notQualified(Double score, List<String> passed, List<String> failed, String reason) {
        return new StrategyEvaluation(EvaluationStatus.NOT_QUALIFIED, null, score, passed, failed, reason);
    }

    public static StrategyEvaluation notEvaluable(String reason) {
        return new StrategyEvaluation(EvaluationStatus.NOT_EVALUABLE, null, 0.0, Collections.emptyList(), Collections.emptyList(), reason);
    }
}
