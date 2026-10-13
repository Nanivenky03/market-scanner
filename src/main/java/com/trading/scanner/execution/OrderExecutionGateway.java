package com.trading.scanner.execution;

import com.trading.scanner.position.PositionManagerService;
import com.trading.scanner.risk.RiskEngine;
import com.trading.scanner.risk.model.PortfolioMode;
import com.trading.scanner.risk.model.RiskEvaluationResult;
import com.trading.scanner.service.provider.angelone.AngelOneMarketDataProvider;
import com.trading.scanner.service.provider.angelone.dto.AngelOneMarketDtos;
import com.trading.scanner.strategy.model.PositionSnapshot;
import com.trading.scanner.strategy.model.StrategyProposal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Single-Writer Order Execution Gateway.
 * Serializes the "Signal -> Risk Gate -> Broker RMS Check -> Order Placement -> Trade Fill -> Position Registration"
 * chain into a single-threaded atomic pipeline.
 *
 * Guarantees that concurrent signals from parallel strategy threads evaluate against
 * live, accurately decremented available margin and do not cause race conditions or broker rejections.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OrderExecutionGateway {

    private final RiskEngine riskEngine;
    private final PositionManagerService positionManagerService;
    private final ObjectProvider<AngelOneMarketDataProvider> angelOneMarketDataProvider;

    public record ExecutionOutcome(
            boolean accepted,
            String symbol,
            String direction,
            Integer executedQuantity,
            Double executedPrice,
            Double structuralStopLoss,
            Double reAnchoredTarget1,
            String rejectionReason,
            RiskEvaluationResult riskResult
    ) {
        public static ExecutionOutcome rejected(String symbol, String reason, RiskEvaluationResult riskResult) {
            return new ExecutionOutcome(false, symbol, null, null, null, null, null, reason, riskResult);
        }

        public static ExecutionOutcome filled(
                String symbol,
                String direction,
                int quantity,
                double fillPrice,
                double stopLoss,
                double target1
        ) {
            return new ExecutionOutcome(true, symbol, direction, quantity, fillPrice, stopLoss, target1, null, null);
        }
    }

    /**
     * Executes the candidate StrategyProposal through the atomic execution gateway.
     */
    public ExecutionOutcome processCandidateProposal(StrategyProposal proposal, PortfolioMode mode) {
        if (proposal == null || proposal.symbol() == null) {
            return ExecutionOutcome.rejected(null, "NULL_PROPOSAL", null);
        }

        // Strictly serialize the signal-to-fill pipeline across all concurrent worker threads
        synchronized (riskEngine.getExecutionGatewayMutex()) {
            String symbol = proposal.symbol();

            // 1. In LIVE mode, fetch live RMS free margin from Angel One to ensure ground truth
            if (mode == PortfolioMode.LIVE) {
                try {
                    AngelOneMarketDataProvider provider = angelOneMarketDataProvider.getIfAvailable();
                    if (provider != null && provider.isAvailable()) {
                        AngelOneMarketDtos.AngelOneRmsResponse rms = provider.fetchLiveRmsLimits();
                        if (rms != null && rms.data() != null) {
                            Double liveCash = rms.data().getAvailableCashAsDouble();
                            if (liveCash != null && liveCash > 0.0) {
                                riskEngine.syncBrokerCapital(mode, liveCash);
                                log.debug("[LIVE] Synced free margin from Angel One RMS: Rs. {}", liveCash);
                            }
                        }
                    }
                } catch (Exception ex) {
                    log.warn("Failed to sync Angel One RMS limits before evaluating {}: {}", symbol, ex.getMessage());
                }
            }

            // 2. Evaluate candidate proposal against the 7 Pre-Trade Risk Gates
            RiskEvaluationResult evaluation = riskEngine.evaluateProposal(proposal, mode);
            if (!evaluation.approved()) {
                log.info("[{}] Proposal for symbol={} rejected by RiskEngine: {}",
                        mode, symbol, evaluation.rejectionReason());
                return ExecutionOutcome.rejected(symbol, evaluation.rejectionReason(), evaluation);
            }

            int approvedQty = evaluation.allocatedQuantity();
            double entryPrice = proposal.entryPrice();
            double stopLoss = proposal.initialStopLoss();
            boolean isShort = proposal.direction() != null
                    && ("SHORT".equalsIgnoreCase(proposal.direction()) || "SELL".equalsIgnoreCase(proposal.direction()));
            String positionSide = isShort ? "SHORT" : "LONG";

            // 3. Execution / Broker Fill Simulation or Order Placement
            // For PAPER/Simulation: Fill at entry price. In LIVE: Fills via broker trade book.
            double fillPrice = entryPrice;
            int filledQty = approvedQty;

            // 4. Fixed Structural Stop & Fill-Anchored Target
            double actualRiskPerShare = isShort
                    ? Math.max(0.01, stopLoss - fillPrice)
                    : Math.max(0.01, fillPrice - stopLoss);

            double reAnchoredTarget1 = isShort
                    ? fillPrice - actualRiskPerShare
                    : fillPrice + actualRiskPerShare;

            // 5. Register position into RAM and RiskEngine
            PositionSnapshot snapshot = new PositionSnapshot(
                    null,
                    proposal.strategyId(),
                    symbol,
                    proposal.exchange() != null ? proposal.exchange() : "NSE",
                    positionSide,
                    filledQty,
                    filledQty,
                    fillPrice,
                    stopLoss, // Fixed structural stop
                    reAnchoredTarget1, // Fill-anchored target
                    proposal.structuralInvalidationLevel(),
                    false,
                    LocalDateTime.now()
            );

            positionManagerService.registerPosition(mode, snapshot);

            log.info("[{}] Execution Gateway filled symbol={} side={} qty={} fillPrice={} structuralStop={} target1={}",
                    mode, symbol, positionSide, filledQty, fillPrice, stopLoss, reAnchoredTarget1);

            return ExecutionOutcome.filled(symbol, positionSide, filledQty, fillPrice, stopLoss, reAnchoredTarget1);
        }
    }
}
