package com.trading.scanner.strategy.parity;

import com.trading.scanner.position.PositionManagerService;
import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.risk.model.PortfolioMode;
import com.trading.scanner.risk.BrokerChargesCalculator;
import com.trading.scanner.risk.BrokerChargesCalculator.BrokerModel;
import com.trading.scanner.strategy.fchb.FchbPositionHandler;
import com.trading.scanner.strategy.fchb.FchbStrategy;
import com.trading.scanner.strategy.model.PositionAction;
import com.trading.scanner.strategy.model.PositionSnapshot;
import com.trading.scanner.strategy.replay.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Parity Test: Forced-Entry Exit-Parity Test.
 * Injects the 7 recorded positions from simulated_positions at their exact recorded
 * entry price, time, quantity, initial SL, and target1, and runs production position
 * management against historical market candles.
 * Compares exit time, exit price, exit reason, and net P&L with the DB records.
 */
@Tag("replay")
class ReplayForcedEntryExitParityTest {

    public record RecordedPosition(
            int id,
            String date,
            String symbol,
            String entryTime,
            double entryPrice,
            int quantity,
            double stopLoss,
            double target1,
            double firstCandleHigh,
            String exitTime,
            double exitPrice,
            String exitReason,
            double netPnl
    ) {}

    private final List<RecordedPosition> goldenPositions = List.of(
            new RecordedPosition(1, "2026-11-13", "BAJFINANCE", "09:35", 1107.15, 33, 1084.85, 1123.45, 1103.65, "14:46", 1113.95, "INTRADAY_WIND_DOWN_1445", +164.17),
            new RecordedPosition(2, "2026-11-13", "TATACOMM", "09:35", 1760.15, 23, 1727.60, 1783.95, 1746.90, "14:46", 1763.45, "INTRADAY_WIND_DOWN_1445", +14.31),
            new RecordedPosition(3, "2026-11-13", "NH", "10:00", 1868.05, 18, 1827.60, 1897.60, 1855.90, "14:46", 1852.45, "INTRADAY_WIND_DOWN_1445", -339.85),
            new RecordedPosition(4, "2026-11-16", "TITAN", "09:35", 4914.05, 5, 4825.75, 4978.55, 4892.80, "10:11", 4867.95, "FAILED_BREAKOUT_EXIT", -286.35),
            new RecordedPosition(5, "2026-11-16", "LTF", "09:35", 280.25, 74, 274.15, 284.75, 278.64, "14:46", 277.60, "INTRADAY_WIND_DOWN_1445", -250.60),
            new RecordedPosition(6, "2026-11-17", "PNBHOUSING", "09:40", 1144.05, 45, 1127.40, 1156.25, 1141.70, "12:41", 1131.35, "STOP_LOSS_TRIGGERED", -636.80),
            new RecordedPosition(7, "2026-11-17", "NBCC", "09:45", 79.30, 424, 77.45, 80.70, 78.72, "14:46", 79.05, "INTRADAY_WIND_DOWN_1445", -165.10)
    );

    @Test
    @DisplayName("Exit Parity: Forced entry of 7 recorded positions and exit lifecycle comparison")
    void testForcedEntryExitParity() {
        BrokerChargesCalculator.setActiveBroker(BrokerModel.KOTAK_NEO);

        try (DatabaseMarketReplayLoader dbLoader = new DatabaseMarketReplayLoader()) {
            System.out.println("========================================================================================================================");
            System.out.println("                    FORCED-ENTRY EXIT PARITY AUDIT: REPLAY VS RECORDED SIMULATED_POSITIONS                              ");
            System.out.println("========================================================================================================================");

            for (RecordedPosition pos : goldenPositions) {
                LocalDate date = LocalDate.parse(pos.date());
                HistoricalMarketDay day = dbLoader.loadSingleMarketDay(date, List.of(pos.symbol()));
                assertNotNull(day, "Market day must load for " + pos.date());

                FchbPositionHandler handler = new FchbPositionHandler();
                TestReplayExecutionContext execContext = new TestReplayExecutionContext(handler, 50000.0, date);
                PositionManagerService positionManager = execContext.getPositionManagerService();

                LocalTime entryTime = LocalTime.parse(pos.entryTime());
                LocalDateTime entryDateTime = LocalDateTime.of(date, entryTime);

                PositionSnapshot initialSnapshot = new PositionSnapshot(
                        pos.id(),
                        FchbStrategy.STRATEGY_ID,
                        pos.symbol(),
                        "NSE",
                        "BUY",
                        pos.quantity(),
                        pos.quantity(),
                        pos.entryPrice(),
                        pos.stopLoss(),
                        pos.target1(),
                        pos.firstCandleHigh(),
                        false,
                        pos.entryPrice(),
                        pos.entryPrice(),
                        entryDateTime,
                        "REPLAY-RESTING-SL"
                );

                // Register the forced position into production PositionManagerService
                positionManager.registerPosition(PortfolioMode.PAPER, initialSnapshot);

                // Step minute-by-minute through the remainder of the session
                LocalTime current = entryTime.plusMinutes(1);
                LocalTime end = LocalTime.of(15, 30);

                LocalTime replayExitTime = null;
                double replayExitPrice = 0.0;
                String replayExitReason = null;
                double finalStopLoss = pos.stopLoss();
                boolean target1Hit = false;

                while (!current.isAfter(end)) {
                    MarketCandle candle1m = day.get1mCandle(pos.symbol(), current);
                    if (candle1m != null) {
                        PositionSnapshot snap = positionManager.getPosition(PortfolioMode.PAPER, pos.symbol());
                        if (snap == null) {
                            break; // Position already closed
                        }
                        if (snap.currentStopLoss() != null) {
                            finalStopLoss = snap.currentStopLoss();
                        }
                        if (snap.target1Booked()) {
                            target1Hit = true;
                        }

                        double high = candle1m.getHighPrice() != null ? candle1m.getHighPrice() : candle1m.getClosePrice();
                        double low = candle1m.getLowPrice() != null ? candle1m.getLowPrice() : candle1m.getClosePrice();
                        double close = candle1m.getClosePrice();

                        // Tick 1: High (favorable target check & trailing peak)
                        PositionAction a1 = positionManager.processTick(PortfolioMode.PAPER, pos.symbol(), high, current);
                        if (a1 != null && a1.type() == PositionAction.ActionType.FULL_EXIT) {
                            replayExitTime = current;
                            replayExitPrice = high;
                            replayExitReason = a1.reason();
                            break;
                        }

                        // Tick 2: Low (adverse stop check)
                        PositionAction a2 = positionManager.processTick(PortfolioMode.PAPER, pos.symbol(), low, current);
                        if (a2 != null && a2.type() == PositionAction.ActionType.FULL_EXIT) {
                            replayExitTime = current;
                            replayExitPrice = snap.currentStopLoss() != null ? snap.currentStopLoss() : low;
                            replayExitReason = a2.reason();
                            break;
                        }

                        // Candle close (structural invalidation & wind-down)
                        List<MarketCandle> c5m = day.get5mCandlesUpTo(pos.symbol(), current);
                        MarketCandle l5m = c5m.isEmpty() ? null : c5m.get(c5m.size() - 1);
                        Double atr = (l5m != null && l5m.getAtr14() != null) ? l5m.getAtr14() : 5.0;

                        PositionAction a3 = positionManager.processMinuteCandleClose(PortfolioMode.PAPER, pos.symbol(), candle1m, atr, current);
                        if (a3 != null && a3.type() == PositionAction.ActionType.FULL_EXIT) {
                            replayExitTime = current;
                            replayExitPrice = close;
                            replayExitReason = a3.reason();
                            break;
                        }
                    }
                    current = current.plusMinutes(1);
                }

                // If exited, apply 1-tick slippage (0.05) on SELL exit
                double filledExitPrice = replayExitPrice > 0 ? (replayExitPrice - 0.05) : 0.0;
                double grossPnl = (filledExitPrice - pos.entryPrice()) * pos.quantity();
                double buyTurnover = pos.entryPrice() * pos.quantity();
                double sellTurnover = filledExitPrice * pos.quantity();
                double statutoryCharges = BrokerChargesCalculator.calculateRoundTripCharges(buyTurnover, sellTurnover, BrokerModel.KOTAK_NEO);
                double netPnl = grossPnl - statutoryCharges;

                System.out.printf("[%s] %-10s | Rec: Entry=%.2f Qty=%d SL=%.2f T1=%.2f | DB Exit: %s @ %.2f (%s) NetP&L=%+.2f\n",
                        pos.date(), pos.symbol(), pos.entryPrice(), pos.quantity(), pos.stopLoss(), pos.target1(),
                        pos.exitTime(), pos.exitPrice(), pos.exitReason(), pos.netPnl());
                System.out.printf("                | Replay Exit: %s @ %.2f (%s) NetP&L=%+.2f | Final SL=%.2f T1Hit=%b\n",
                        replayExitTime, filledExitPrice, replayExitReason, netPnl, finalStopLoss, target1Hit);
                System.out.println("------------------------------------------------------------------------------------------------------------------------");
            }
        }
    }
}
