package com.trading.scanner.strategy.fchb;

import com.trading.scanner.model.DailyStockContext;
import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.risk.RiskEngine;
import com.trading.scanner.risk.model.PortfolioMode;
import com.trading.scanner.risk.model.RiskEvaluationResult;
import com.trading.scanner.strategy.model.MarketState;
import com.trading.scanner.strategy.model.PositionAction;
import com.trading.scanner.strategy.model.PositionSnapshot;
import com.trading.scanner.strategy.model.StockContext;
import com.trading.scanner.strategy.model.StrategyEvaluation;
import com.trading.scanner.strategy.model.StrategyProposal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V2.3 Integration Test: Replays 4 Real-World Market Scenarios
 * across the Strategy (Layer 3), Risk Engine (Layer 4), and Position Handler pipeline.
 */
class FchbRealDataScenarioIntegrationTest {

    private FchbStrategy strategy;
    private FchbPositionHandler positionHandler;
    private RiskEngine riskEngine;

    @BeforeEach
    void setUp() {
        strategy = new FchbStrategy();
        positionHandler = new FchbPositionHandler();
        riskEngine = new RiskEngine();
        riskEngine.initializePortfolio(PortfolioMode.PAPER, 30000.0, LocalDate.of(2026, 10, 11));
    }

    private MarketCandle createCandle(LocalDateTime time, double o, double h, double l, double c, double vwap, double rsi, long vol) {
        return MarketCandle.builder()
                .candleTime(time)
                .openPrice(o)
                .highPrice(h)
                .lowPrice(l)
                .closePrice(c)
                .vwap(vwap)
                .rsi14(rsi)
                .volume(vol)
                .build();
    }

    private DailyStockContext createDailyContext(String symbol, double fch, double fcl) {
        return DailyStockContext.builder()
                .symbol(symbol)
                .exchange("NSE")
                .tradingDate(LocalDate.of(2026, 10, 11))
                .skipToday(false)
                .gapPct(0.4)
                .prevDayRangePct(2.1)
                .distFromResistancePct(3.0)
                .firstCandleOpen(fcl + 0.2)
                .firstCandleHigh(fch)
                .firstCandleLow(fcl)
                .firstCandleClose(fch - 0.1)
                .build();
    }

    @Test
    @DisplayName("SCENARIO 1: Strong Bullish Trend Day -> Signal qualifies, Risk approves, Target 1 hits and trails to multi-R")
    void testScenario1_BullishTrendDay() {
        // Nifty strongly bullish above VWAP
        MarketState bullishMarket = new MarketState(
                LocalDate.of(2026, 10, 11), LocalDateTime.of(2026, 10, 11, 9, 35),
                25100.0, 24950.0, 0.60, true, MarketState.MarketRegime.BULLISH, true, "NIFTY above VWAP"
        );

        DailyStockContext daily = createDailyContext("TCS", 1000.0, 990.0);
        MarketCandle current5m = createCandle(LocalDateTime.of(2026, 10, 11, 9, 35), 998.0, 1004.0, 997.0, 1003.0, 998.0, 62.0, 80000L);
        MarketCandle current15m = createCandle(LocalDateTime.of(2026, 10, 11, 9, 30), 992.0, 1004.0, 991.0, 1002.0, 996.0, 60.0, 200000L);

        StockContext stockContext = new StockContext(
                "TCS", "NSE", LocalDate.of(2026, 10, 11), daily,
                Collections.emptyList(), List.of(current5m), List.of(current15m), false, 2.6
        );

        // 1. Strategy Evaluation
        StrategyEvaluation evaluation = strategy.evaluate(stockContext, bullishMarket, Collections.emptyMap());
        assertThat(evaluation.isQualified()).isTrue();
        StrategyProposal proposal = evaluation.proposal();
        assertThat(proposal.direction()).isEqualTo("BUY");

        // 2. Risk Engine Evaluation
        RiskEvaluationResult riskResult = riskEngine.evaluateProposal(proposal, PortfolioMode.PAPER);
        assertThat(riskResult.approved()).isTrue();
        int qty = riskResult.allocatedQuantity();
        assertThat(qty).isGreaterThanOrEqualTo(2);

        // 3. Trade Entry into Position Management
        PositionSnapshot position = new PositionSnapshot(
                1, 2, "TCS", "NSE", "LONG", qty, qty,
                proposal.entryPrice(), proposal.initialStopLoss(), proposal.initialTarget(),
                proposal.structuralInvalidationLevel(), false, LocalDateTime.of(2026, 10, 11, 9, 35)
        );

        // 4. Price advances to Target 1 (+1R)
        PositionAction target1Action = positionHandler.evaluatePosition(
                position, proposal.initialTarget(), null, null, LocalTime.of(9, 50), Collections.emptyMap()
        );
        assertThat(target1Action.type()).isEqualTo(PositionAction.ActionType.PARTIAL_EXIT);
        assertThat(target1Action.reason()).isEqualTo("TARGET_1_HIT");
        assertThat(target1Action.newStopLoss()).isEqualTo(proposal.entryPrice()); // Stop moved to pure entry

        // 5. Runner 50% position trails ATR on 1M closes
        PositionSnapshot runnerPosition = new PositionSnapshot(
                1, 2, "TCS", "NSE", "LONG", qty, qty / 2,
                proposal.entryPrice(), proposal.entryPrice(), proposal.initialTarget(),
                proposal.structuralInvalidationLevel(), true, LocalDateTime.of(2026, 10, 11, 9, 35)
        );

        // Next 1M candle moves higher: Low = 1025.0, ATR = 5.0 -> ATR stop = 1025 - (1.5 * 5) = 1017.5 (> entry 1003)
        MarketCandle candle1m = createCandle(LocalDateTime.of(2026, 10, 11, 10, 5), 1022.0, 1028.0, 1025.0, 1027.0, 1005.0, 68.0, 15000L);
        PositionAction trailAction = positionHandler.evaluatePosition(
                runnerPosition, 1027.0, candle1m, 5.0, LocalTime.of(10, 5), Collections.emptyMap()
        );
        assertThat(trailAction.type()).isEqualTo(PositionAction.ActionType.UPDATE_STOP);
        assertThat(trailAction.reason()).isEqualTo("ATR_TRAILING_STOP_RATCHET");
        assertThat(trailAction.newStopLoss()).isEqualTo(1022.5);
    }

    @Test
    @DisplayName("SCENARIO 2: Neutral Day with Multiple Positions -> Stop decay to entry frees portfolio risk capacity for 3rd trade")
    void testScenario2_NeutralMarketRiskDecayAndCapacity() {
        MarketState neutralMarket = new MarketState(
                LocalDate.of(2026, 10, 11), LocalDateTime.of(2026, 10, 11, 9, 35),
                25030.0, 25000.0, 0.12, true, MarketState.MarketRegime.NEUTRAL, true, "NIFTY neutral"
        );

        // Position 1 (INFY) and Position 2 (RELIANCE) are open with original stops
        // Total open risk: 450 + 450 = Rs. 900 (close to 3.2% cap of Rs. 960)
        PositionSnapshot pos1 = new PositionSnapshot(
                1, 2, "INFY", "NSE", "LONG", 45, 45,
                1000.0, 990.0, 1010.0, 995.0, false, LocalDateTime.of(2026, 10, 11, 9, 30)
        );
        PositionSnapshot pos2 = new PositionSnapshot(
                2, 2, "RELIANCE", "NSE", "LONG", 45, 45,
                2000.0, 1990.0, 2010.0, 1995.0, false, LocalDateTime.of(2026, 10, 11, 9, 30)
        );
        riskEngine.recordTradeExecuted(PortfolioMode.PAPER, "INFY", pos1);
        riskEngine.recordTradeExecuted(PortfolioMode.PAPER, "RELIANCE", pos2);

        // Candidate 3 arrives: entry 500, SL 495 -> risk = Rs. 450. Total risk would be 900 + 450 = 1350 > 960!
        StrategyProposal candidate3 = new StrategyProposal(
                "WIPRO", "NSE", 2, "FCHB", "BUY", 500.0, 495.0, 505.0, 498.0, 85.0, Map.of("yellowCount", 1L)
        );
        RiskEvaluationResult rejected3 = riskEngine.evaluateProposal(candidate3, PortfolioMode.PAPER);
        assertThat(rejected3.approved()).isFalse();
        assertThat(rejected3.rejectionReason()).isEqualTo("PORTFOLIO_OPEN_RISK_CAP_EXCEEDED");

        // Now Position 1 (INFY) reaches Target 1: its stop ratchets to entry (1000.0) -> downside risk drops to Rs. 0!
        PositionSnapshot pos1AtEntry = new PositionSnapshot(
                1, 2, "INFY", "NSE", "LONG", 45, 22,
                1000.0, 1000.0, 1010.0, 995.0, true, LocalDateTime.of(2026, 10, 11, 9, 30)
        );
        riskEngine.updatePosition(PortfolioMode.PAPER, pos1AtEntry);

        // Open downside risk is now only 0 + 450 = Rs. 450. Candidate 3 re-evaluated:
        RiskEvaluationResult approved3 = riskEngine.evaluateProposal(candidate3, PortfolioMode.PAPER);
        assertThat(approved3.approved()).isTrue();

        // Register 3rd position: now 3 concurrent positions are open (portfolio at max capacity)
        PositionSnapshot pos3 = new PositionSnapshot(
                3, 2, "WIPRO", "NSE", "LONG", approved3.allocatedQuantity(), approved3.allocatedQuantity(),
                500.0, 495.0, 505.0, 498.0, false, LocalDateTime.of(2026, 10, 11, 9, 40)
        );
        riskEngine.recordTradeExecuted(PortfolioMode.PAPER, "WIPRO", pos3);

        // Candidate 4 arrives: Gate 1 rejects (Max 3 concurrent positions)
        StrategyProposal candidate4 = new StrategyProposal(
                "SBIN", "NSE", 2, "FCHB", "BUY", 800.0, 795.0, 805.0, 798.0, 100.0, Map.of("yellowCount", 0L)
        );
        RiskEvaluationResult rejected4 = riskEngine.evaluateProposal(candidate4, PortfolioMode.PAPER);
        assertThat(rejected4.approved()).isFalse();
        assertThat(rejected4.rejectionReason()).isEqualTo("MAX_CONCURRENT_POSITIONS_REACHED");
    }

    @Test
    @DisplayName("SCENARIO 3: Friction Trap -> High turnover / tight stop relative to capital triggers 3x friction filter rejection")
    void testScenario3_RangeBoundFrictionTrap() {
        // Reduced capital portfolio (Rs. 12,000 where 1R = Rs. 180)
        riskEngine.initializePortfolio(PortfolioMode.PAPER, 12000.0, LocalDate.of(2026, 10, 11));

        MarketState neutralMarket = new MarketState(
                LocalDate.of(2026, 10, 11), LocalDateTime.of(2026, 10, 11, 9, 35),
                25030.0, 25000.0, 0.12, true, MarketState.MarketRegime.NEUTRAL, true, "NIFTY neutral"
        );

        // Stock at 800 with tight 5-pt first candle: FCH = 800.0, FCL = 795.0
        // Bullish 1st candle: open = 795.2, close = 799.9
        DailyStockContext daily = createDailyContext("BAJFINANCE", 800.0, 795.0);
        // Breakout candle closes at 801.0 (> FCH 800.0, high 801.5, low 797.5, body 75%, upper wick 12.5% -> GREEN shape)
        MarketCandle current5m = createCandle(LocalDateTime.of(2026, 10, 11, 9, 35), 798.0, 801.5, 797.5, 801.0, 799.0, 60.0, 40000L);
        MarketCandle current15m = createCandle(LocalDateTime.of(2026, 10, 11, 9, 30), 796.0, 802.0, 795.0, 800.0, 798.0, 58.0, 90000L);

        StockContext stockContext = new StockContext(
                "BAJFINANCE", "NSE", LocalDate.of(2026, 10, 11), daily,
                Collections.emptyList(), List.of(current5m), List.of(current15m), false, 2.0
        );

        // 1. Strategy qualifies proposal
        StrategyEvaluation evaluation = strategy.evaluate(stockContext, neutralMarket, Collections.emptyMap());
        assertThat(evaluation.isQualified()).isTrue();

        // 2. Evaluated through Risk Engine: Friction trap rejected!
        // 1R risk = Rs. 175, but estimated 3-leg friction = Rs. 80+ -> 3 * 80 = 240 > 175!
        RiskEvaluationResult riskResult = riskEngine.evaluateProposal(evaluation.proposal(), PortfolioMode.PAPER);
        assertThat(riskResult.approved()).isFalse();
        assertThat(riskResult.rejectionReason()).isEqualTo("EXCESSIVE_FRICTION_DRAG");
    }

    @Test
    @DisplayName("SCENARIO 4: Stock-Level Trap on Bullish Day -> 1M close below FCH triggers immediate structural invalidation fast cut")
    void testScenario4_StockTrapStructuralInvalidation() {
        // Nifty is strongly bullish
        MarketState bullishMarket = new MarketState(
                LocalDate.of(2026, 10, 11), LocalDateTime.of(2026, 10, 11, 9, 35),
                25100.0, 24950.0, 0.60, true, MarketState.MarketRegime.BULLISH, true, "NIFTY above VWAP"
        );

        DailyStockContext daily = createDailyContext("HDFCBANK", 1500.0, 1485.0); // FCH = 1500.0, Stop = 1485 * 0.995 = 1477.5
        MarketCandle current5m = createCandle(LocalDateTime.of(2026, 10, 11, 9, 35), 1495.0, 1505.0, 1494.0, 1503.0, 1497.0, 62.0, 60000L);
        MarketCandle current15m = createCandle(LocalDateTime.of(2026, 10, 11, 9, 30), 1490.0, 1505.0, 1488.0, 1502.0, 1495.0, 60.0, 150000L);

        StockContext stockContext = new StockContext(
                "HDFCBANK", "NSE", LocalDate.of(2026, 10, 11), daily,
                Collections.emptyList(), List.of(current5m), List.of(current15m), false, 2.2
        );

        StrategyEvaluation evaluation = strategy.evaluate(stockContext, bullishMarket, Collections.emptyMap());
        assertThat(evaluation.isQualified()).isTrue();

        RiskEvaluationResult riskResult = riskEngine.evaluateProposal(evaluation.proposal(), PortfolioMode.PAPER);
        assertThat(riskResult.approved()).isTrue();

        // Trade enters at 1503.0
        PositionSnapshot position = new PositionSnapshot(
                10, 2, "HDFCBANK", "NSE", "LONG", riskResult.allocatedQuantity(), riskResult.allocatedQuantity(),
                1503.0, 1477.5, 1528.5, 1500.0, false, LocalDateTime.of(2026, 10, 11, 9, 35)
        );

        // Next 1-minute candle immediately closes at 1498.0 (< FCH 1500.0) while LTP 1498.0 is well above stop 1477.5
        MarketCandle failed1mCandle = createCandle(LocalDateTime.of(2026, 10, 11, 9, 36), 1503.0, 1504.0, 1497.5, 1498.0, 1500.0, 52.0, 10000L);
        PositionAction action = positionHandler.evaluatePosition(
                position, 1498.0, failed1mCandle, 6.0, LocalTime.of(9, 36), Collections.emptyMap()
        );

        // Immediate Structural Invalidation fast cut! Cuts loss to -5 pts instead of -25.5 pts!
        assertThat(action.type()).isEqualTo(PositionAction.ActionType.FULL_EXIT);
        assertThat(action.reason()).isEqualTo("STRUCTURAL_INVALIDATION");
    }
}
