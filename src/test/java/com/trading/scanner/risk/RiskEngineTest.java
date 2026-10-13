package com.trading.scanner.risk;

import com.trading.scanner.risk.model.PortfolioMode;
import com.trading.scanner.risk.model.PortfolioState;
import com.trading.scanner.risk.model.RiskEvaluationResult;
import com.trading.scanner.strategy.model.PositionSnapshot;
import com.trading.scanner.strategy.model.StrategyProposal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RiskEngineTest {

    private RiskEngine riskEngine;

    @BeforeEach
    void setUp() {
        riskEngine = new RiskEngine();
        riskEngine.initializePortfolio(PortfolioMode.PAPER, 30000.0, LocalDate.of(2026, 10, 11));
        riskEngine.initializePortfolio(PortfolioMode.LIVE, 30000.0, LocalDate.of(2026, 10, 11));
    }

    private StrategyProposal createProposal(String symbol, double entryPrice, double stopLoss, long yellowCount) {
        return new StrategyProposal(
                symbol,
                "NSE",
                2,
                "FCHB",
                "BUY",
                entryPrice,
                stopLoss,
                entryPrice + (entryPrice - stopLoss),
                entryPrice - 2.0,
                100.0,
                Map.of("yellowCount", yellowCount));
    }

    private PositionSnapshot createPosition(String symbol, int qty, double entry, double sl) {
        return new PositionSnapshot(
                1, 2, symbol, "NSE", "LONG", qty, qty,
                entry, sl, entry + (entry - sl), entry - 2.0, false, LocalDateTime.now());
    }

    @Test
    @DisplayName("Gate 1: Rejects when 3 concurrent open positions already exist")
    void testGate1_ConcurrentPositionsCap() {
        riskEngine.recordTradeExecuted(PortfolioMode.PAPER, "SBIN", createPosition("SBIN", 50, 800.0, 795.0));
        riskEngine.recordTradeExecuted(PortfolioMode.PAPER, "INFY", createPosition("INFY", 30, 1900.0, 1890.0));
        riskEngine.recordTradeExecuted(PortfolioMode.PAPER, "TCS", createPosition("TCS", 15, 3500.0, 3480.0));

        StrategyProposal fourthProposal = createProposal("WIPRO", 550.0, 545.0, 0);
        RiskEvaluationResult result = riskEngine.evaluateProposal(fourthProposal, PortfolioMode.PAPER);

        assertThat(result.approved()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo("MAX_CONCURRENT_POSITIONS_REACHED");
    }

    @Test
    @DisplayName("Gate 2: Rejects when daily loss limit (-4.0% = -Rs.1200) is hit")
    void testGate2_DailyLossKillSwitch() {
        // Record realized loss of Rs. 1250 (> 4% of 30,000)
        riskEngine.recordPositionClosed(PortfolioMode.PAPER, "LOSS_STOCK", -1250.0);

        StrategyProposal proposal = createProposal("INFY", 1900.0, 1885.0, 0);
        RiskEvaluationResult result = riskEngine.evaluateProposal(proposal, PortfolioMode.PAPER);

        assertThat(result.approved()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo("DAILY_LOSS_KILL_SWITCH_TRIGGERED");
    }

    @Test
    @DisplayName("Gate 3: Enforces 5 daily trades cap and Trade 4-5 ALL GREEN rule")
    void testGate3_DailyTradesCap_And_Trade4And5AllGreenRule() {
        PortfolioState state = riskEngine.getPortfolioState(PortfolioMode.PAPER);

        // Record 3 trades (tradeCount = 3)
        riskEngine.recordTradeExecuted(PortfolioMode.PAPER, "S1", createPosition("S1", 10, 500.0, 495.0));
        riskEngine.recordPositionClosed(PortfolioMode.PAPER, "S1", 50.0);
        riskEngine.recordTradeExecuted(PortfolioMode.PAPER, "S2", createPosition("S2", 10, 500.0, 495.0));
        riskEngine.recordPositionClosed(PortfolioMode.PAPER, "S2", 50.0);
        riskEngine.recordTradeExecuted(PortfolioMode.PAPER, "S3", createPosition("S3", 10, 500.0, 495.0));
        riskEngine.recordPositionClosed(PortfolioMode.PAPER, "S3", 50.0);

        assertThat(state.getDailyTradeCount()).isEqualTo(3);

        // Trade 4 with yellowCount = 1 -> REJECTED (Trades 4-5 require ALL GREEN)
        StrategyProposal trade4Yellow = createProposal("S4", 600.0, 590.0, 1);
        RiskEvaluationResult resYellow = riskEngine.evaluateProposal(trade4Yellow, PortfolioMode.PAPER);
        assertThat(resYellow.approved()).isFalse();
        assertThat(resYellow.rejectionReason()).isEqualTo("TRADE_4_OR_5_REQUIRES_ALL_GREEN");

        // Trade 4 with yellowCount = 0 -> APPROVED
        StrategyProposal trade4Green = createProposal("S4", 600.0, 590.0, 0);
        RiskEvaluationResult resGreen = riskEngine.evaluateProposal(trade4Green, PortfolioMode.PAPER);
        assertThat(resGreen.approved()).isTrue();

        // Advance to 5 trades completed
        riskEngine.recordTradeExecuted(PortfolioMode.PAPER, "S4", createPosition("S4", 10, 600.0, 590.0));
        riskEngine.recordPositionClosed(PortfolioMode.PAPER, "S4", 50.0);
        riskEngine.recordTradeExecuted(PortfolioMode.PAPER, "S5", createPosition("S5", 10, 600.0, 590.0));
        riskEngine.recordPositionClosed(PortfolioMode.PAPER, "S5", 50.0);

        // Trade 6 -> REJECTED (Max 5 daily trades reached)
        StrategyProposal trade6 = createProposal("S6", 600.0, 590.0, 0);
        RiskEvaluationResult resTrade6 = riskEngine.evaluateProposal(trade6, PortfolioMode.PAPER);
        assertThat(resTrade6.approved()).isFalse();
        assertThat(resTrade6.rejectionReason()).isEqualTo("MAX_DAILY_TRADES_REACHED");
    }

    @Test
    @DisplayName("Gate 4: Rejects symbol already traded today (Symbol Lock)")
    void testGate4_SymbolLock() {
        riskEngine.recordTradeExecuted(PortfolioMode.PAPER, "RELIANCE", createPosition("RELIANCE", 10, 2500.0, 2480.0));
        riskEngine.recordPositionClosed(PortfolioMode.PAPER, "RELIANCE", 200.0);

        StrategyProposal secondAttempt = createProposal("RELIANCE", 2550.0, 2530.0, 0);
        RiskEvaluationResult result = riskEngine.evaluateProposal(secondAttempt, PortfolioMode.PAPER);

        assertThat(result.approved()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo("SYMBOL_ALREADY_TRADED_TODAY");
    }

    @Test
    @DisplayName("Gate 5: Rejects when quantity is less than minimum 2 shares")
    void testGate5_QuantityLessThan2() {
        // High priced stock with large stop: price 5000, stop 4700 -> risk = 300 pts.
        // On Rs. 450 risk budget (1.5% of 30k), quantity = floor(450 / 300) = 1 < 2!
        StrategyProposal proposal = createProposal("MRF", 5000.0, 4700.0, 0);
        RiskEvaluationResult result = riskEngine.evaluateProposal(proposal, PortfolioMode.PAPER);

        assertThat(result.approved()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo("QUANTITY_LESS_THAN_MINIMUM_2");
    }

    @Test
    @DisplayName("Gate 6: Rejects when total open downside risk exceeds 3.2% buffer, approves once stop ratchets to entry")
    void testGate6_PortfolioOpenDownsideRiskCap() {
        // 3.2% of 30,000 = Rs. 960 max portfolio open downside risk
        // Position 1: qty 45, entry 500, SL 490 -> risk = 45 * 10 = Rs. 450
        // Position 2: qty 45, entry 500, SL 490 -> risk = 45 * 10 = Rs. 450
        // Current open risk = 900.
        riskEngine.recordTradeExecuted(PortfolioMode.PAPER, "STOCK1", createPosition("STOCK1", 45, 500.0, 490.0));
        riskEngine.recordTradeExecuted(PortfolioMode.PAPER, "STOCK2", createPosition("STOCK2", 45, 500.0, 490.0));

        // Candidate 3: entry 600, SL 595 (risk = 5 pts). Qty = floor(450 / 5) = 90.
        // Actual risk = 90 * 5 = 450.
        // Potential total risk = 900 + 450 = 1350 > 960!
        StrategyProposal candidate3 = createProposal("STOCK3", 600.0, 595.0, 0);
        RiskEvaluationResult result = riskEngine.evaluateProposal(candidate3, PortfolioMode.PAPER);

        assertThat(result.approved()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo("PORTFOLIO_OPEN_RISK_CAP_EXCEEDED");

        // Now STOCK1 reaches Target 1: its trailing stop ratchets to entry (500.0)!
        // Downside risk on STOCK1 drops to 45 * max(0, 500 - 500) = Rs. 0!
        // Total open risk drops from 900 to 450.
        PositionSnapshot ratchetedStock1 = new PositionSnapshot(
                1, 2, "STOCK1", "NSE", "LONG", 45, 45,
                500.0, 500.0, 510.0, 498.0, true, LocalDateTime.now());
        riskEngine.updatePosition(PortfolioMode.PAPER, ratchetedStock1);

        // Candidate 3 evaluated again: potential risk = 450 + 450 = 900 <= 960!
        RiskEvaluationResult reEvaluated = riskEngine.evaluateProposal(candidate3, PortfolioMode.PAPER);
        assertThat(reEvaluated.approved()).isTrue();
    }

    @Test
    @DisplayName("Gate 7: Friction viability filter rejects trades where 1R < 3 * friction")
    void testGate7_FrictionTrapRejected() {
        // High priced stock with tight stop distance generating massive turnover
        // relative to risk:
        // Entry = 2000.0, SL = 1998.0 (risk per share = 2.0).
        // Target risk = 450 -> Qty = floor(450 / 2.0) = 225 shares.
        // Trade value = 225 * 2000 = 450,000. Required margin = 450,000 / 5 = 90,000 >
        // 30,000 capital.
        // Let's test with low capital Rs. 15,000 where 1R = Rs. 225:
        riskEngine.initializePortfolio(PortfolioMode.PAPER, 15000.0, LocalDate.of(2026, 10, 11));

        // Entry = 600.0, SL = 594.0 -> risk = 6.0.
        // Qty = floor(225 / 6.0) = 37. Trade value = 37 * 600 = 22,200. Turnover =
        // 44,400.
        // Friction: Brokerage = 60. Taxes = 44400 * 0.00035 = 15.54. GST = (60 + 2.6) *
        // 0.18 = 11.27. Total friction ~ 86.8.
        // 3 * Friction = 3 * 86.8 = 260.4 > actual risk 222 (37 * 6.0)!
        StrategyProposal tightProposal = createProposal("TIGHT_STOCK", 600.0, 594.0, 0);
        RiskEvaluationResult result = riskEngine.evaluateProposal(tightProposal, PortfolioMode.PAPER);

        assertThat(result.approved()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo("EXCESSIVE_FRICTION_DRAG");
    }

    @Test
    @DisplayName("Paper and Live portfolios are strictly independent")
    void testPaperAndLivePortfolioIndependence() {
        // Execute 3 trades in PAPER
        riskEngine.recordTradeExecuted(PortfolioMode.PAPER, "P1", createPosition("P1", 10, 100.0, 95.0));
        riskEngine.recordTradeExecuted(PortfolioMode.PAPER, "P2", createPosition("P2", 10, 100.0, 95.0));
        riskEngine.recordTradeExecuted(PortfolioMode.PAPER, "P3", createPosition("P3", 10, 100.0, 95.0));

        // LIVE portfolio has 0 open positions and 0 daily trades
        PortfolioState liveState = riskEngine.getPortfolioState(PortfolioMode.LIVE);
        assertThat(liveState.getOpenPositions()).isEmpty();
        assertThat(liveState.getDailyTradeCount()).isEqualTo(0);
        assertThat(liveState.hasTradedSymbolToday("P1")).isFalse();

        // A proposal on P1 in LIVE is approved because it was never traded in LIVE!
        StrategyProposal proposal = createProposal("P1", 500.0, 490.0, 0);
        RiskEvaluationResult liveResult = riskEngine.evaluateProposal(proposal, PortfolioMode.LIVE);
        assertThat(liveResult.approved()).isTrue();
    }

    @Test
    @DisplayName("Gate 5: Correctly approves and sizes SHORT proposal with stop above entry")
    void testGate5_ShortProposal_ApprovedAndSized() {
        // SHORT setup: Entry at 500.0, Stop Loss at 510.0 (risk = 10 pts)
        // Capital = 30k -> 1R = 450. Quantity = floor(450 / 10) = 45 shares.
        StrategyProposal shortProposal = new StrategyProposal(
                "INFY",
                "NSE",
                3,
                "SHORT_STRATEGY",
                "SHORT",
                500.0,
                510.0, // Stop above entry
                490.0, // Target below entry
                512.0,
                100.0,
                Map.of("yellowCount", 0L)
        );

        RiskEvaluationResult result = riskEngine.evaluateProposal(shortProposal, PortfolioMode.PAPER);
        assertThat(result.approved()).isTrue();
        assertThat(result.allocatedQuantity()).isEqualTo(45);
        assertThat(result.riskPerShare()).isEqualTo(10.0);
        assertThat(result.actualRiskRupees()).isEqualTo(450.0);
    }

    @Test
    @DisplayName("Gate 5: Rejects invalid SHORT proposal where stop is below or equal to entry")
    void testGate5_ShortProposal_InvalidStopRejected() {
        StrategyProposal invalidShort = new StrategyProposal(
                "INFY",
                "NSE",
                3,
                "SHORT_STRATEGY",
                "SHORT",
                500.0,
                495.0, // Invalid: stop below entry for short
                490.0,
                512.0,
                100.0,
                Map.of("yellowCount", 0L)
        );

        RiskEvaluationResult result = riskEngine.evaluateProposal(invalidShort, PortfolioMode.PAPER);
        assertThat(result.approved()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo("INVALID_PRICE_OR_STOP");
    }

    @Test
    @DisplayName("Margin & Sizing: Downsizes quantity when committed margin from position 1 constrains position 2")
    void testDynamicAvailableMargin_ConstrainsSubsequentPosition() {
        // Capital = 30k. Position 1 commits margin:
        // Position 1: qty = 260, entry = 500. Trade value = 130,000. Margin @ 5x = 26,000.
        // Remaining available margin = 30,000 - 26,000 = Rs. 4,000.
        PositionSnapshot pos1 = new PositionSnapshot(
                1, 2, "POS1", "NSE", "LONG", 260, 260,
                500.0, 500.0, 510.0, 495.0, true, LocalDateTime.now());
        riskEngine.recordTradeExecuted(PortfolioMode.PAPER, "POS1", pos1);

        // Position 2 proposal: entry = 500.0, stop = 490.0 (risk per share = 10.0)
        // By pure 1R risk: 450 / 10 = 45 shares. Required margin = (45 * 500) / 5 = 4,500.
        // But remaining margin is only 4,000!
        // Max qty by margin = (4,000 * 5) / 500 = 40 shares.
        // Engine should smart downsize from 45 to 40 shares!
        StrategyProposal pos2Proposal = createProposal("POS2", 500.0, 490.0, 0);
        RiskEvaluationResult result = riskEngine.evaluateProposal(pos2Proposal, PortfolioMode.PAPER);

        assertThat(result.approved()).isTrue();
        assertThat(result.allocatedQuantity()).isEqualTo(40);
        assertThat(result.requiredMargin()).isLessThanOrEqualTo(4000.0);
    }

    @Test
    @DisplayName("Gate 2: Daily loss kill switch triggers when realized loss + open downside risk breaches 4%")
    void testGate2_DailyLossKillSwitch_IncludesOpenDownsideRisk() {
        // Initial capital = 30,000. 4% loss limit = Rs. 1,200.
        // Realized loss = Rs. 800.
        riskEngine.recordPositionClosed(PortfolioMode.PAPER, "REALIZED_LOSS", -800.0);

        // Open position 1 has downside risk of Rs. 450 (entry 500, stop 490, qty 45)
        // Total worst-case loss = -800 - 450 = -1,250 <= -1,200 limit!
        PositionSnapshot openPos = createPosition("OPEN_STOCK", 45, 500.0, 490.0);
        riskEngine.recordTradeExecuted(PortfolioMode.PAPER, "OPEN_STOCK", openPos);

        StrategyProposal candidate = createProposal("CANDIDATE", 200.0, 195.0, 0);
        RiskEvaluationResult result = riskEngine.evaluateProposal(candidate, PortfolioMode.PAPER);

        assertThat(result.approved()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo("DAILY_LOSS_KILL_SWITCH_TRIGGERED");
    }

    @Test
    @DisplayName("Syncs LIVE portfolio capital directly from Angel One RMS balance")
    void testSyncBrokerCapital() {
        PortfolioState liveState = riskEngine.getPortfolioState(PortfolioMode.LIVE);
        assertThat(liveState.getCurrentCapital()).isEqualTo(30000.0);

        // Broker RMS reports live balance of Rs. 48,500
        riskEngine.syncBrokerCapital(PortfolioMode.LIVE, 48500.0);
        assertThat(liveState.getCurrentCapital()).isEqualTo(48500.0);
    }
}
