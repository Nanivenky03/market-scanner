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

    public static final int MAX_CONCURRENT_POSITIONS = 3;
    public static final int MAX_DAILY_TRADES = 5;
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
     * Evaluates a candidate StrategyProposal against the 7 Pre-Trade Risk Gates.
     */
    public synchronized RiskEvaluationResult evaluateProposal(StrategyProposal proposal, PortfolioMode mode) {
        if (proposal == null) {
            return RiskEvaluationResult.rejected("PROPOSAL_NULL", null);
        }

        PortfolioState portfolio = portfolios.get(mode);
        if (portfolio == null) {
            return RiskEvaluationResult.rejected("PORTFOLIO_NOT_INITIALIZED", proposal);
        }

        // Gate 1: Concurrent Open Positions Cap (Max 3)
        if (portfolio.getOpenPositions().size() >= MAX_CONCURRENT_POSITIONS) {
            return RiskEvaluationResult.rejected("MAX_CONCURRENT_POSITIONS_REACHED", proposal);
        }

        // Gate 2: Daily Loss Kill Switch (4.0% of Initial Capital)
        double maxLossLimitRupees = portfolio.getInitialCapital() * (DAILY_LOSS_LIMIT_PCT / 100.0);
        if (portfolio.getRealizedPnl() <= -maxLossLimitRupees) {
            return RiskEvaluationResult.rejected("DAILY_LOSS_KILL_SWITCH_TRIGGERED", proposal);
        }

        // Gate 3: Daily Trade Count (Max 5) & Trades 4-5 ALL GREEN Rule
        int tradeCount = portfolio.getDailyTradeCount();
        if (tradeCount >= MAX_DAILY_TRADES) {
            return RiskEvaluationResult.rejected("MAX_DAILY_TRADES_REACHED", proposal);
        }

        // For Trades 4 and 5 (when dailyTradeCount >= 3): Must be strictly ALL GREEN
        // (yellowCount == 0)
        if (tradeCount >= 3) {
            Object yellowCountObj = proposal.ruleContext() != null ? proposal.ruleContext().get("yellowCount") : null;
            long yellowCount = 0;
            if (yellowCountObj instanceof Number num) {
                yellowCount = num.longValue();
            }
            if (yellowCount > 0) {
                return RiskEvaluationResult.rejected("TRADE_4_OR_5_REQUIRES_ALL_GREEN", proposal);
            }
        }

        // Gate 4: Symbol Lock (Strictly 1 entry per symbol per day)
        if (portfolio.hasTradedSymbolToday(proposal.symbol())) {
            return RiskEvaluationResult.rejected("SYMBOL_ALREADY_TRADED_TODAY", proposal);
        }

        // Gate 5: Price Validity & Sizing Calculation
        Double entryPrice = proposal.entryPrice();
        Double stopLoss = proposal.initialStopLoss();
        if (entryPrice == null || stopLoss == null || entryPrice <= 0.0 || stopLoss <= 0.0 || entryPrice <= stopLoss) {
            return RiskEvaluationResult.rejected("INVALID_PRICE_OR_STOP", proposal);
        }

        double riskPerShare = entryPrice - stopLoss;
        double capital = portfolio.getCurrentCapital();
        double targetRiskRupees = capital * RISK_PER_TRADE_FRACTION; // 1R (e.g. Rs. 450 on Rs. 30k)

        // Fixed Fractional Sizing: Quantity = floor(targetRiskRupees / riskPerShare)
        int quantity = (int) Math.floor(targetRiskRupees / riskPerShare);
        if (quantity < 2) {
            // Need at least 2 shares to perform 50% partial exit at Target 1
            return RiskEvaluationResult.rejected("QUANTITY_LESS_THAN_MINIMUM_2", proposal);
        }

        double actualRiskRupees = quantity * riskPerShare;

        // Gate 6: Total Current Portfolio Open Downside Risk (< 3.2% of Initial
        // Capital)
        double currentOpenRisk = portfolio.calculateTotalOpenDownsideRisk();
        double potentialTotalRisk = currentOpenRisk + actualRiskRupees;
        double maxAllowedOpenRiskRupees = portfolio.getInitialCapital() * (MAX_PORTFOLIO_OPEN_RISK_PCT / 100.0);
        if (potentialTotalRisk > maxAllowedOpenRiskRupees) {
            return RiskEvaluationResult.rejected("PORTFOLIO_OPEN_RISK_CAP_EXCEEDED", proposal);
        }

        // Sizing & Margin: Check available capital against required intraday margin + already committed margin
        double tradeValue = quantity * entryPrice;
        double requiredMargin = tradeValue / INTRADAY_LEVERAGE_FACTOR;
        double committedMargin = portfolio.calculateTotalCommittedMargin(INTRADAY_LEVERAGE_FACTOR);
        if ((committedMargin + requiredMargin) > capital) {
            return RiskEvaluationResult.rejected("INSUFFICIENT_MARGIN_CAPITAL", proposal);
        }

        // Gate 7: 3x Friction Viability Filter
        // Estimated round-trip turnover = 2 * (Quantity * EntryPrice)
        double roundTripTurnover = 2.0 * tradeValue;
        double statutoryTaxes = roundTripTurnover * STATUTORY_TAX_RATE;
        double gstOnBrokerage = (FLAT_BROKERAGE_3_LEG + (roundTripTurnover * 0.0000594)) * GST_RATE;
        double estimatedFriction = FLAT_BROKERAGE_3_LEG + statutoryTaxes + gstOnBrokerage;

        // Viability Rule: Expected 1R Gross Profit >= 3 * Estimated Friction
        if (actualRiskRupees < (3.0 * estimatedFriction)) {
            return RiskEvaluationResult.rejected("EXCESSIVE_FRICTION_DRAG", proposal);
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
     * Records order execution into the active portfolio state.
     */
    public synchronized void recordTradeExecuted(PortfolioMode mode, String symbol, PositionSnapshot position) {
        PortfolioState portfolio = portfolios.get(mode);
        if (portfolio != null) {
            portfolio.recordTradeExecuted(symbol, position);
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
