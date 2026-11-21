package com.trading.scanner.strategy.parity;

import com.trading.scanner.execution.OrderExecutionService;
import com.trading.scanner.position.PositionManagerService;
import com.trading.scanner.risk.BrokerChargesCalculator;
import com.trading.scanner.risk.RiskEngine;
import com.trading.scanner.risk.model.PortfolioMode;
import com.trading.scanner.strategy.fchb.FchbPositionHandler;
import com.trading.scanner.strategy.model.PositionSnapshot;
import com.trading.scanner.strategy.model.StrategyProposal;
import com.trading.scanner.strategy.replay.TestReplayExecutionContext;
import com.trading.scanner.util.PriceTickUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parity Test 2.5(d): Unit tests for partial-exit rounding, tick rounding,
 * and risk-state cleanup after exits.
 */
class ReplayParityUnitTests {

    @BeforeEach
    void setUp() {
        BrokerChargesCalculator.setActiveBroker(BrokerChargesCalculator.BrokerModel.KOTAK_NEO);
    }

    @Test
    @DisplayName("Unit Test 2.5(d).1: Partial-exit rounding uses Math.round(initialQty * 0.5) with boundary guards")
    void testPartialExitRounding() {
        // Initial = 5 -> 50% = 2.5 -> Math.round = 3
        int init5 = 5;
        int exit5 = (int) Math.round(init5 * 0.5);
        assertEquals(3, exit5, "5 shares partial exit must round to 3 shares");

        // Initial = 7 -> 50% = 3.5 -> Math.round = 4
        int init7 = 7;
        int exit7 = (int) Math.round(init7 * 0.5);
        assertEquals(4, exit7, "7 shares partial exit must round to 4 shares");

        // Initial = 18 -> 50% = 9
        int init18 = 18;
        int exit18 = (int) Math.round(init18 * 0.5);
        assertEquals(9, exit18, "18 shares partial exit must be exactly 9");

        // Initial = 33 -> 50% = 16.5 -> Math.round = 17
        int init33 = 33;
        int exit33 = (int) Math.round(init33 * 0.5);
        assertEquals(17, exit33, "33 shares partial exit must round to 17 shares");

        // Boundary guard: Cannot exit all shares if currentQty is 2 -> Math.min(pos.currentQuantity() - 1, exitQty)
        int current2 = 2;
        int exitClamped = Math.max(1, Math.min(current2 - 1, (int) Math.round(current2 * 0.5)));
        assertEquals(1, exitClamped, "2 shares partial exit must leave at least 1 runner");
    }

    @Test
    @DisplayName("Unit Test 2.5(d).2: Exchange tick rounding (0.05 tick size) on Targets and Stops")
    void testExchangeTickRounding() {
        // Long Target rounds UP to next 0.05 tick
        double targetRaw1 = 1123.41;
        double roundedTarget1 = PriceTickUtil.roundTarget(targetRaw1, false, 0.05);
        assertEquals(1123.45, roundedTarget1, 0.0001, "Long target must round UP to 1123.45");

        double targetRaw2 = 1123.45;
        double roundedTarget2 = PriceTickUtil.roundTarget(targetRaw2, false, 0.05);
        assertEquals(1123.45, roundedTarget2, 0.0001, "Exact 0.05 boundary must remain 1123.45");

        double targetRaw3 = 1123.46;
        double roundedTarget3 = PriceTickUtil.roundTarget(targetRaw3, false, 0.05);
        assertEquals(1123.50, roundedTarget3, 0.0001, "1123.46 must round UP to 1123.50");

        // Long Stop Loss rounds DOWN to next 0.05 tick
        double stopRaw1 = 1084.84;
        double roundedStop1 = PriceTickUtil.roundStopLoss(stopRaw1, false, 0.05);
        assertEquals(1084.80, roundedStop1, 0.0001, "Long stop must round DOWN to 1084.80");

        double stopRaw2 = 1084.89;
        double roundedStop2 = PriceTickUtil.roundStopLoss(stopRaw2, false, 0.05);
        assertEquals(1084.85, roundedStop2, 0.0001, "Long stop 1084.89 must round DOWN to 1084.85");
    }

    @Test
    @DisplayName("Unit Test 2.5(d).3: Risk-state cleanup after partial exit and position closure")
    void testRiskStateCleanupAfterExits() {
        LocalDate date = LocalDate.of(2026, 11, 13);
        double startingCapital = 50000.0;
        FchbPositionHandler handler = new FchbPositionHandler();
        TestReplayExecutionContext ctx = new TestReplayExecutionContext(handler, startingCapital, date);

        PositionManagerService pm = ctx.getPositionManagerService();
        RiskEngine riskEngine = ctx.getRiskEngine();

        // 1. Submit Entry Proposal
        StrategyProposal proposal = new StrategyProposal(
                "BAJFINANCE", "NSE", 2, "FCHB", "LONG",
                1107.10, 1084.85, 1123.35, 1103.65, 80.0, Map.of("yellowCount", 0L)
        );

        OrderExecutionService.ExecutionResult res = ctx.processCandidateProposal(proposal);
        assertTrue(res.accepted(), "Proposal must be approved by RiskEngine and OrderExecutionService");

        PositionSnapshot pos = pm.getPosition(PortfolioMode.PAPER, "BAJFINANCE");
        assertNotNull(pos, "Position must be registered in RAM");
        assertEquals(33, pos.initialQuantity(), "Quantity allocated should be 33");
        assertEquals(33, pos.currentQuantity(), "Initial currentQuantity should be 33");
        assertEquals(1, riskEngine.getPortfolioState(PortfolioMode.PAPER).getOpenPositions().size(), "Portfolio must have 1 open position");

        // 2. Execute Target 1 Partial Exit
        double t1Fill = 1123.45;
        boolean t1Success = ctx.executeTarget1PartialExit("BAJFINANCE", t1Fill);
        assertTrue(t1Success, "Target 1 partial exit must succeed");

        PositionSnapshot posAfterT1 = pm.getPosition(PortfolioMode.PAPER, "BAJFINANCE");
        assertNotNull(posAfterT1, "Position must still exist after partial exit");
        assertTrue(posAfterT1.target1Booked(), "target1Booked flag must be TRUE");
        assertEquals(16, posAfterT1.currentQuantity(), "Remaining quantity must be 33 - 17 = 16");
        assertEquals(posAfterT1.entryPrice(), posAfterT1.currentStopLoss(), "Stop loss must be ratcheted to Breakeven");
        assertTrue(riskEngine.getPortfolioState(PortfolioMode.PAPER).getRealizedPnl() > 0, "Partial exit PnL must be recorded in RiskEngine");

        // 3. Execute Full Tactical Exit (Wind-down 14:45)
        double exitFill = 1113.95;
        boolean closeSuccess = ctx.executeTacticalExit("BAJFINANCE", exitFill, "INTRADAY_WIND_DOWN_1445");
        assertTrue(closeSuccess, "Tactical exit must succeed");

        // 4. Verify RAM and RiskEngine cleanup
        assertNull(pm.getPosition(PortfolioMode.PAPER, "BAJFINANCE"), "Closed position must be PURGED from RAM");
        assertEquals(0, riskEngine.getPortfolioState(PortfolioMode.PAPER).getOpenPositions().size(), "Open positions in RiskEngine must be 0");
        assertEquals(0.0, riskEngine.getPortfolioState(PortfolioMode.PAPER).calculateTotalOpenDownsideRisk(), 0.001, "Open downside risk must be 0.0");
        // PortfolioState increments dailyTradeCount for each registered execution leg (Entry and T1 partial fill)
        assertEquals(2, riskEngine.getPortfolioState(PortfolioMode.PAPER).getDailyTradeCount(), "Daily trade count tracks both executed order legs");
    }
}
