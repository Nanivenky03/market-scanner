package com.trading.scanner.risk;

import com.trading.scanner.risk.model.PortfolioMode;
import com.trading.scanner.risk.model.PortfolioState;
import com.trading.scanner.risk.model.RiskEvaluationResult;
import com.trading.scanner.strategy.model.PositionSnapshot;
import com.trading.scanner.strategy.model.StrategyProposal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Layer 4: Strategy-Agnostic Risk & Sizing Engine.
 * Evaluates candidate proposals across the 7 Pre-Trade Risk Gates,
 * calculates fixed fractional position sizing (1R = 1.5%), enforces the 3.2%
 * live downside
 * risk buffer, and filters out friction traps before any order reaches broker
 * or execution.
 */
@Service
@Slf4j
public class RiskEngine {

    public static final int MAX_DAILY_TRADES_PER_STRATEGY = 5;
    public static final int MAX_DAILY_LOSSES_PER_STRATEGY = 3;
    public static final double DAILY_LOSS_LIMIT_PCT = 4.0;
    public static final double MAX_PORTFOLIO_OPEN_RISK_PCT = 3.2;
    public static final double RISK_PER_TRADE_FRACTION = 0.015; // 1.5% of capital (1R)
    public static final double INTRADAY_LEVERAGE_FACTOR = 5.0; // MIS 5x leverage

    public static final double FLAT_BROKERAGE_3_LEG = 60.0; // Rs. 20 * 3 legs (Entry, Target 1, Target 2/Stop)
    public static final double STATUTORY_TAX_RATE = 0.00035; // Round-trip statutory taxes (~0.035%)
    public static final double GST_RATE = 0.18; // 18% on fees

    private final Map<PortfolioMode, PortfolioState> portfolios = new ConcurrentHashMap<>();

    public RiskEngine() {
        // Default initialization for PAPER and LIVE portfolios
        portfolios.put(PortfolioMode.PAPER, new PortfolioState(PortfolioMode.PAPER, 30000.0, LocalDate.now()));
        portfolios.put(PortfolioMode.LIVE, new PortfolioState(PortfolioMode.LIVE, 30000.0, LocalDate.now()));
    }

    public synchronized void initializePortfolio(PortfolioMode mode, double initialCapital, LocalDate date) {
        portfolios.put(mode, new PortfolioState(mode, initialCapital, date));
    }

    public PortfolioState getPortfolioState(PortfolioMode mode) {
        return portfolios.get(mode);
    }

    /**
     * Evaluates a candidate StrategyProposal against the Pre-Trade Risk Gates
     * in a strict pipeline sequence:
     * Phase 1: Fast zero-cost disqualifications (Symbol lock, trade count, positions cap, existing drawdown)
     * Phase 2: Price and direction sanity
     * Phase 3: Exact sizing, margin availability, and candidate friction calculation
     * Phase 4: Friction drag viability filter
     * Phase 5: Master portfolio open risk cap and total projected worst-case loss protection
     */
    public synchronized RiskEvaluationResult evaluateProposal(StrategyProposal proposal, PortfolioMode mode) {
        if (proposal == null) {
            return RiskEvaluationResult.rejected("PROPOSAL_NULL", null);
        }

        PortfolioState portfolio = portfolios.get(mode);
        if (portfolio == null) {
            return RiskEvaluationResult.rejected("PORTFOLIO_NOT_INITIALIZED", proposal);
        }

        // ==========================================
        // PHASE 1: FAST ZERO-COST DISQUALIFICATIONS
        // ==========================================

        // Gate 1: Symbol Lock (Strictly 1 entry per symbol per day)
        if (portfolio.hasTradedSymbolToday(proposal.symbol())) {
            return RiskEvaluationResult.rejected("SYMBOL_ALREADY_TRADED_TODAY", proposal);
        }

        // Gate 2: Strategy-Level Daily Limits (Max 5 trades, Max 3 losses cooling off)
        Integer strategyId = proposal.strategyId();
        if (strategyId != null) {
            if (portfolio.getTradeCountForStrategy(strategyId) >= MAX_DAILY_TRADES_PER_STRATEGY) {
                return RiskEvaluationResult.rejected("STRATEGY_MAX_DAILY_TRADES_EXCEEDED", proposal);
            }
            if (portfolio.getLossCountForStrategy(strategyId) >= MAX_DAILY_LOSSES_PER_STRATEGY) {
                return RiskEvaluationResult.rejected("STRATEGY_COOLING_OFF_MAX_LOSSES_REACHED", proposal);
            }
        }

        // Gate 3: Existing Account Circuit Breaker
        double currentCapital = portfolio.getCurrentCapital();
        double maxLossLimitRupees = currentCapital * (DAILY_LOSS_LIMIT_PCT / 100.0);
        double currentOpenRisk = portfolio.calculateTotalOpenDownsideRisk();
        double openExitFriction = portfolio.calculateTotalReservedExitFriction();
        double netRealizedPnl = portfolio.getNetRealizedPnl();
        double realizedLoss = netRealizedPnl < 0.0 ? Math.abs(netRealizedPnl) : 0.0;
        if (realizedLoss + currentOpenRisk + openExitFriction >= maxLossLimitRupees) {
            return RiskEvaluationResult.rejected("DAILY_LOSS_KILL_SWITCH_TRIGGERED", proposal);
        }

        // ==========================================
        // PHASE 2: SIGNAL VALIDITY & SANITY
        // ==========================================

        Double entryPrice = proposal.entryPrice();
        Double stopLoss = proposal.initialStopLoss();
        if (entryPrice == null || stopLoss == null || entryPrice <= 0.0 || stopLoss <= 0.0) {
            return RiskEvaluationResult.rejected("INVALID_PRICE_OR_STOP", proposal);
        }

        boolean isShort = proposal.direction() != null
                && ("SHORT".equalsIgnoreCase(proposal.direction()) || "SELL".equalsIgnoreCase(proposal.direction()));

        double riskPerShare;
        if (isShort) {
            if (stopLoss <= entryPrice) {
                return RiskEvaluationResult.rejected("INVALID_PRICE_OR_STOP", proposal);
            }
            if (proposal.initialTarget() != null && proposal.initialTarget() >= entryPrice) {
                return RiskEvaluationResult.rejected("INVALID_PRICE_OR_STOP", proposal);
            }
            riskPerShare = stopLoss - entryPrice;
        } else {
            if (entryPrice <= stopLoss) {
                return RiskEvaluationResult.rejected("INVALID_PRICE_OR_STOP", proposal);
            }
            if (proposal.initialTarget() != null && proposal.initialTarget() <= entryPrice) {
                return RiskEvaluationResult.rejected("INVALID_PRICE_OR_STOP", proposal);
            }
            riskPerShare = entryPrice - stopLoss;
        }

        // ==========================================
        // PHASE 3: SIZING, MARGIN & CHARGES ENGINE
        // ==========================================

        double capital = portfolio.getCurrentCapital();
        double targetRiskRupees = capital * RISK_PER_TRADE_FRACTION; // 1R risk target

        int qtyByRisk = (int) Math.floor(targetRiskRupees / riskPerShare);

        double availableMargin = portfolio.calculateAvailableMargin(INTRADAY_LEVERAGE_FACTOR);
        if (availableMargin <= 0.0) {
            return RiskEvaluationResult.rejected("INSUFFICIENT_MARGIN_CAPITAL", proposal);
        }
        int maxQtyByMargin = (int) Math.floor((availableMargin * INTRADAY_LEVERAGE_FACTOR) / entryPrice);

        int quantity = Math.min(qtyByRisk, maxQtyByMargin);
        if (quantity < 2) {
            return RiskEvaluationResult.rejected("QUANTITY_LESS_THAN_MINIMUM_2", proposal);
        }

        double actualRiskRupees = quantity * riskPerShare;
        double tradeValue = quantity * entryPrice;
        double requiredMargin = tradeValue / INTRADAY_LEVERAGE_FACTOR;

        // Calculate candidate friction using dedicated statutory calculator (3 legs: Entry, Target 1, Runner)
        double estimatedFriction = BrokerChargesCalculator.calculate3LegEstimatedFriction(tradeValue);
        double roundTripTurnover = 2.0 * tradeValue;

        // ==========================================
        // PHASE 4: FRICTION VIABILITY FILTER
        // ==========================================

        // Viability Rule: Setup viability against strategy's 1R target (not temporarily downsized rupee risk)
        if (targetRiskRupees < (3.0 * estimatedFriction)) {
            return RiskEvaluationResult.rejected("EXCESSIVE_FRICTION_DRAG", proposal);
        }

        // Downsized execution viability: actual gross risk/gain must exceed actual round-trip charges
        if (actualRiskRupees <= estimatedFriction) {
            return RiskEvaluationResult.rejected("EXCESSIVE_FRICTION_DRAG", proposal);
        }

        // ==========================================
        // PHASE 5: DYNAMIC RUPEE RISK SHIELD
        // ==========================================

        // Total Downside Exposure: Negative Realized P&L + Open Positions Downside Risk (from active order SL)
        // + Open Exit Friction + Candidate Trade Risk + Candidate Friction <= 4.0% of Current Capital
        double totalDownsideExposure = realizedLoss + currentOpenRisk + openExitFriction + actualRiskRupees + estimatedFriction;
        if (totalDownsideExposure > maxLossLimitRupees) {
            return RiskEvaluationResult.rejected("DAILY_LOSS_KILL_SWITCH_TRIGGERED", proposal);
        }

        return RiskEvaluationResult.approved(
                quantity,
                riskPerShare,
                actualRiskRupees,
                roundTripTurnover,
                estimatedFriction,
                requiredMargin,
                proposal);
    }

    /**
     * Gateway mutex to guarantee single-threaded execution from signal evaluation
     * to broker fill to position registration.
     */
    private final Object executionGatewayMutex = new Object();

    public Object getExecutionGatewayMutex() {
        return executionGatewayMutex;
    }

    /**
     * Records order execution into the active portfolio state.
     */
    public synchronized void recordTradeExecuted(PortfolioMode mode, String symbol, PositionSnapshot position) {
        PortfolioState portfolio = portfolios.get(mode);
        if (portfolio != null) {
            portfolio.recordTradeExecuted(symbol, position);
        }
    }

    /**
     * Unlocks a symbol if an order was rejected or cancelled before execution.
     */
    public synchronized void unlockSymbol(PortfolioMode mode, String symbol) {
        PortfolioState portfolio = portfolios.get(mode);
        if (portfolio != null) {
            portfolio.unlockSymbol(symbol);
        }
    }

    /**
     * Synchronizes portfolio capital with live broker RMS cash/net balance.
     */
    public synchronized void syncBrokerCapital(PortfolioMode mode, double liveBrokerCapital) {
        PortfolioState portfolio = portfolios.get(mode);
        if (portfolio != null) {
            portfolio.syncBrokerCapital(liveBrokerCapital);
        }
    }

    /**
     * Rehydrates portfolio state upon application restart or mid-day crash recovery.
     */
    public synchronized void rehydrateState(PortfolioMode mode, java.util.List<PositionSnapshot> positions, Integer tradeCount, java.util.Set<String> symbols, Double liveCapital) {
        PortfolioState portfolio = portfolios.get(mode);
        if (portfolio != null) {
            portfolio.rehydrate(positions, tradeCount, symbols, liveCapital);
        }
    }

    /**
     * Updates an open position (e.g. after a partial exit or trailing stop update).
     */
    public synchronized void updatePosition(PortfolioMode mode, PositionSnapshot position) {
        PortfolioState portfolio = portfolios.get(mode);
        if (portfolio != null) {
            portfolio.updatePosition(position);
        }
    }

    /**
     * Records partial exit P&L credit to realized capital.
     */
    public synchronized void recordPartialExit(PortfolioMode mode, double pnl) {
        PortfolioState portfolio = portfolios.get(mode);
        if (portfolio != null) {
            portfolio.recordPartialExit(pnl);
        }
    }

    /**
     * Records position closure and realizes its P&L.
     */
    public synchronized void recordPositionClosed(PortfolioMode mode, String symbol, double pnl) {
        PortfolioState portfolio = portfolios.get(mode);
        if (portfolio != null) {
            portfolio.recordPositionClosed(symbol, pnl);
        }
    }

    /**
     * Records statutory friction and brokerage incurred.
     */
    public synchronized void recordFrictionIncurred(PortfolioMode mode, double friction) {
        PortfolioState portfolio = portfolios.get(mode);
        if (portfolio != null) {
            portfolio.recordFrictionIncurred(friction);
        }
    }

    /**
     * Resets risk quotas, daily trade counts, and symbol locks for a new trading day.
     * Carries forward current capital into starting capital.
     */
    public synchronized void resetForNewDay(LocalDate newDate) {
        if (newDate == null) {
            return;
        }
        for (PortfolioState portfolio : portfolios.values()) {
            if (portfolio != null) {
                try {
                    // Strictly only reset if transitioning to a NEW calendar date!
                    // Mid-day restarts on the same date preserve today's trade count and open positions.
                    if (portfolio.getTradingDate() == null || newDate.isAfter(portfolio.getTradingDate())) {
                        portfolio.resetForNewDay(newDate, portfolio.getCurrentCapital());
                        log.info("[{}] Reset quotas for new trading day: {}", portfolio.getMode(), newDate);
                    } else {
                        log.info("[{}] Mid-day restart detected for date {}: preserved dailyTradeCount={}, openPositions={}",
                                portfolio.getMode(), newDate, portfolio.getDailyTradeCount(), portfolio.getOpenPositions().size());
                    }
                } catch (Exception ex) {
                    // Defensive guard: never fail JVM thread
                }
            }
        }
    }
}
