package com.trading.scanner.risk.model;

import com.trading.scanner.strategy.model.StrategyProposal;

/**
 * Result of evaluating a StrategyProposal through the 7 Pre-Trade Risk Gates.
 */
public record RiskEvaluationResult(
                boolean approved,
                int allocatedQuantity,
                double riskPerShare,
                double actualRiskRupees,
                double roundTripTurnover,
                double estimatedFriction,
                double requiredMargin,
                String rejectionReason,
                StrategyProposal proposal) {
        public static RiskEvaluationResult approved(
                        int allocatedQuantity,
                        double riskPerShare,
                        double actualRiskRupees,
                        double roundTripTurnover,
                        double estimatedFriction,
                        double requiredMargin,
                        StrategyProposal proposal) {
                return new RiskEvaluationResult(
                                true,
                                allocatedQuantity,
                                riskPerShare,
                                actualRiskRupees,
                                roundTripTurnover,
                                estimatedFriction,
                                requiredMargin,
                                null,
                                proposal);
        }

        public static RiskEvaluationResult rejected(String rejectionReason, StrategyProposal proposal) {
                return new RiskEvaluationResult(
                                false,
                                0,
                                0.0,
                                0.0,
                                0.0,
                                0.0,
                                0.0,
                                rejectionReason,
                                proposal);
        }
}
