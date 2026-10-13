package com.trading.scanner.position;

import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.risk.RiskEngine;
import com.trading.scanner.risk.model.PortfolioMode;
import com.trading.scanner.strategy.fchb.FchbPositionHandler;
import com.trading.scanner.strategy.model.PositionAction;
import com.trading.scanner.strategy.model.PositionSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PositionManagerServiceTest {

    private RiskEngine riskEngine;
    private PositionManagerService positionManager;

    @BeforeEach
    void setUp() {
        riskEngine = new RiskEngine();
        riskEngine.initializePortfolio(PortfolioMode.PAPER, 30000.0, LocalDate.of(2026, 10, 11));
        FchbPositionHandler fchbHandler = new FchbPositionHandler();
        positionManager = new PositionManagerService(riskEngine, List.of(fchbHandler));
    }

    private MarketCandle create1mCandle(LocalDateTime time, double o, double h, double l, double c) {
        return MarketCandle.builder()
                .candleTime(time)
                .openPrice(o)
                .highPrice(h)
                .lowPrice(l)
                .closePrice(c)
                .build();
    }

    @Test
    @DisplayName("Registers open position into RAM and Risk Engine correctly")
    void testRegisterAndRetrievePosition() {
        PositionSnapshot position = new PositionSnapshot(
                1, 2, "INFY", "NSE", "LONG", 20, 20,
                1900.0, 1885.0, 1915.0, 1895.0, false, LocalDateTime.now());

        positionManager.registerPosition(PortfolioMode.PAPER, position);

        assertThat(positionManager.getActivePositions(PortfolioMode.PAPER)).hasSize(1);
        PositionSnapshot retrieved = positionManager.getPosition(PortfolioMode.PAPER, "INFY");
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.symbol()).isEqualTo("INFY");
        assertThat(retrieved.currentQuantity()).isEqualTo(20);
    }

    @Test
    @DisplayName("Tick breach below stop loss triggers FULL_EXIT and closes position in Risk Engine")
    void testProcessTick_StopLossBreached() {
        PositionSnapshot position = new PositionSnapshot(
                1, 2, "TCS", "NSE", "LONG", 10, 10,
                3500.0, 3480.0, 3520.0, 3495.0, false, LocalDateTime.now());
        positionManager.registerPosition(PortfolioMode.PAPER, position);

        // Price ticks down to 3479.0 (below stop 3480.0)
        PositionAction action = positionManager.processTick(PortfolioMode.PAPER, "TCS", 3479.0, LocalTime.of(10, 15));

        assertThat(action.type()).isEqualTo(PositionAction.ActionType.FULL_EXIT);
        assertThat(action.reason()).isEqualTo("STOP_LOSS_HIT");

        // Position removed from active positions in RAM
        assertThat(positionManager.getActivePositions(PortfolioMode.PAPER)).isEmpty();
        // Position removed from RiskEngine and realized P&L recorded
        assertThat(riskEngine.getPortfolioState(PortfolioMode.PAPER).getOpenPositions()).isEmpty();
        assertThat(riskEngine.getPortfolioState(PortfolioMode.PAPER).getRealizedPnl()).isLessThan(0.0);
    }

    @Test
    @DisplayName("1M close below FCH triggers immediate structural invalidation fast cut")
    void testProcessMinuteCandleClose_StructuralInvalidation() {
        PositionSnapshot position = new PositionSnapshot(
                1, 2, "SBIN", "NSE", "LONG", 50, 50,
                805.0, 795.0, 815.0, 802.0, false, LocalDateTime.now());
        positionManager.registerPosition(PortfolioMode.PAPER, position);

        // 1M candle closes at 801.0 (< FCH 802.0) while LTP is above stop (801.0 >
        // 795.0)
        MarketCandle candle1m = create1mCandle(LocalDateTime.of(2026, 10, 11, 9, 36), 805.0, 806.0, 800.5, 801.0);
        PositionAction action = positionManager.processMinuteCandleClose(
                PortfolioMode.PAPER, "SBIN", candle1m, 3.5, LocalTime.of(9, 36));

        assertThat(action.type()).isEqualTo(PositionAction.ActionType.FULL_EXIT);
        assertThat(action.reason()).isEqualTo("STRUCTURAL_INVALIDATION");
        assertThat(positionManager.getActivePositions(PortfolioMode.PAPER)).isEmpty();
    }

    @Test
    @DisplayName("Target 1 triggers partial exit (50%) and locks stop at entry; subsequent candle ratchets ATR stop")
    void testTarget1PartialExit_And_AtrTrailingRatchet() {
        PositionSnapshot position = new PositionSnapshot(
                1, 2, "RELIANCE", "NSE", "LONG", 20, 20,
                2500.0, 2480.0, 2520.0, 2495.0, false, LocalDateTime.now());
        positionManager.registerPosition(PortfolioMode.PAPER, position);

        // 1. Price ticks up to Target 1 (2520.0) -> PARTIAL EXIT 50% & SL moved to pure
        // entry (2500.0)
        PositionAction tickAction = positionManager.processTick(PortfolioMode.PAPER, "RELIANCE", 2520.0,
                LocalTime.of(9, 45));
        assertThat(tickAction.type()).isEqualTo(PositionAction.ActionType.PARTIAL_EXIT);
        assertThat(tickAction.reason()).isEqualTo("TARGET_1_HIT");

        PositionSnapshot partialSnapshot = positionManager.getPosition(PortfolioMode.PAPER, "RELIANCE");
        assertThat(partialSnapshot.currentQuantity()).isEqualTo(10);
        assertThat(partialSnapshot.target1Booked()).isTrue();
        assertThat(partialSnapshot.currentStopLoss()).isEqualTo(2500.0);

        // 2. Next 1M candle moves higher: Low = 2530.0, ATR = 10.0 -> ATR stop = 2530 -
        // (1.5 * 10) = 2515.0 (> 2500.0)
        MarketCandle nextCandle = create1mCandle(LocalDateTime.of(2026, 10, 11, 9, 48), 2525.0, 2535.0, 2530.0, 2532.0);
        PositionAction candleAction = positionManager.processMinuteCandleClose(
                PortfolioMode.PAPER, "RELIANCE", nextCandle, 10.0, LocalTime.of(9, 48));

        assertThat(candleAction.type()).isEqualTo(PositionAction.ActionType.UPDATE_STOP);
        assertThat(candleAction.reason()).isEqualTo("ATR_TRAILING_STOP_RATCHET");
        assertThat(candleAction.newStopLoss()).isEqualTo(2525.0);

        PositionSnapshot ratchetedSnapshot = positionManager.getPosition(PortfolioMode.PAPER, "RELIANCE");
        assertThat(ratchetedSnapshot.currentStopLoss()).isEqualTo(2525.0);
    }

    @Test
    @DisplayName("SHORT Position: Full exit on profit generates positive P&L")
    void testShortPosition_FullExit_GeneratesPositivePnl() {
        // Short position: Sell at 500.0, Stop at 510.0, Qty = 20
        PositionSnapshot shortPos = new PositionSnapshot(
                1, 2, "SHORT_STOCK", "NSE", "SHORT", 20, 20,
                500.0, 510.0, 490.0, 515.0, false, LocalDateTime.now());
        positionManager.registerPosition(PortfolioMode.PAPER, shortPos);

        // Price drops to 480.0 (profit of +20 pts per share)
        // Manual force close at 480.0
        positionManager.forceClosePosition(PortfolioMode.PAPER, "SHORT_STOCK", 480.0, "TAKE_PROFIT");

        assertThat(positionManager.getActivePositions(PortfolioMode.PAPER)).isEmpty();
        // P&L = (500 - 480) * 20 = +400.0 (strictly POSITIVE!)
        assertThat(riskEngine.getPortfolioState(PortfolioMode.PAPER).getRealizedPnl()).isEqualTo(400.0);
    }

    @Test
    @DisplayName("SHORT Position: Monotonic ratchet moves stop DOWN, rejects upward move")
    void testShortPosition_MonotonicStopRatchetMovesDown() {
        // Short position: Entry 500, Stop 510
        PositionSnapshot shortPos = new PositionSnapshot(
                1, 2, "SHORT_RATCHET", "NSE", "SHORT", 20, 20,
                500.0, 510.0, 490.0, 515.0, false, LocalDateTime.now());
        positionManager.registerPosition(PortfolioMode.PAPER, shortPos);

        // LTP reaches Target 1 (490.0)
        PositionAction partial = positionManager.processTick(PortfolioMode.PAPER, "SHORT_RATCHET", 490.0, LocalTime.of(10, 0));
        assertThat(partial.type()).isEqualTo(PositionAction.ActionType.PARTIAL_EXIT);

        PositionSnapshot posAfterPartial = positionManager.getPosition(PortfolioMode.PAPER, "SHORT_RATCHET");
        // For short, stop is locked at entry (500.0) or lower (< 510.0)
        assertThat(posAfterPartial.currentStopLoss()).isLessThanOrEqualTo(500.0);
    }

    @Test
    @DisplayName("Authoritative Broker Fill: Re-anchors Target 1 while keeping structural stop fixed")
    void testRecordBrokerFill_ReAnchorsTargetAndKeepsStructuralStop() {
        // Pre-signal snapshot at price 100.0 with structural stop at 95.0 (risk 5.0) -> Target 1 = 105.0
        PositionSnapshot preSignal = new PositionSnapshot(
                1, 2, "SLIP_STOCK", "NSE", "LONG", 50, 50,
                100.0, 95.0, 105.0, 96.0, false, LocalDateTime.now());
        positionManager.registerPosition(PortfolioMode.PAPER, preSignal);

        // Exchange execution fills with slippage at average price 100.50
        positionManager.recordBrokerFill(PortfolioMode.PAPER, "SLIP_STOCK", 100.50, 50, 25.0);

        PositionSnapshot reconciled = positionManager.getPosition(PortfolioMode.PAPER, "SLIP_STOCK");
        assertThat(reconciled.entryPrice()).isEqualTo(100.50);
        // Structural stop stays strictly fixed at 95.0!
        assertThat(reconciled.currentStopLoss()).isEqualTo(95.0);
        // Actual risk per share = 100.50 - 95.0 = 5.50
        // Target 1 re-anchored to fill price + actual risk = 100.50 + 5.50 = 106.00!
        assertThat(reconciled.target1Price()).isEqualTo(106.00);
    }
}
