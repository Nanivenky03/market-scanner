package com.trading.scanner.strategy.parity;

import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.risk.BrokerChargesCalculator;
import com.trading.scanner.risk.BrokerChargesCalculator.BrokerModel;
import com.trading.scanner.strategy.fchb.FchbPositionHandler;
import com.trading.scanner.strategy.fchb.FchbStrategy;
import com.trading.scanner.strategy.model.StockContext;
import com.trading.scanner.strategy.model.StrategyEvaluation;
import com.trading.scanner.strategy.replay.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * S0 diagnostic replay across every session in the replay database.
 *
 * Every entry decision and exit comes from the production conductor driven by StrategyPerformanceReplayRunner;
 * this test only observes the decisions (via the runner's observer) to build the gate funnel. It never
 * re-implements volX, Nifty state, gates or exits, so the funnel cannot drift from what live would decide.
 */
@Tag("replay")
class S0DiagnosticReplayTest {

    private static final LocalTime FIRST_DECISION = LocalTime.of(9, 35);
    private static final LocalTime LAST_DECISION = LocalTime.of(10, 20);

    private static final class Funnel {
        int evals;
        int breakouts;
        int g0Fail;
        int g1Fail;
        int g2Miss;
        int g2Fail;
        int g3Fail;
        int g5Fail;
        int g6Fail;
        int g7Fail;
        int qualified;
    }

    @Test
    @DisplayName("Run S0 diagnostic replay across all sessions through the production conductor")
    void testRunS0DiagnosticReplay() {
        BrokerChargesCalculator.setActiveBroker(BrokerModel.KOTAK_NEO);

        FchbStrategy strategy = new FchbStrategy();
        FchbPositionHandler handler = new FchbPositionHandler();

        try (DatabaseMarketReplayLoader dbLoader = new DatabaseMarketReplayLoader()) {
            List<LocalDate> allDates = dbLoader.getAvailableTradingDates(null, null);
            List<String> symbols = dbLoader.getTopStockUniverse(500);

            System.out.println("=".repeat(120));
            System.out.println("  S0 DIAGNOSTIC REPLAY - PRODUCTION CONDUCTOR, NO VOLX FALLBACK");
            System.out.println("=".repeat(120));
            System.out.printf("%-10s | %-7s | %-6s | %-5s | %-6s | %-6s | %-6s | %-6s | %-6s | %-6s | %-6s | %-6s | %-4s | %s%n",
                    "DATE", "BASE", "EVALS", "BRK", "G0_FAIL", "G1_FAIL", "G2_MISS", "G2_FAIL", "G3_FAIL", "G5_FAIL",
                    "G6_FAIL", "G7_FAIL", "QUAL", "TRADES");

            int totalTrades = 0;
            double totalPnl = 0.0;

            for (LocalDate date : allDates) {
                HistoricalMarketDay day = dbLoader.loadSingleMarketDay(date, symbols);
                if (day == null) {
                    continue;
                }

                int baselineSymbols = 0;
                for (String sym : day.getDailyContexts().keySet()) {
                    Long b = day.getVolumeBaseline(sym, 20);
                    if (b != null && b > 0) {
                        baselineSymbols++;
                    }
                }
                String baseStatus = baselineSymbols >= 300 ? "FULL" : (baselineSymbols > 0 ? "PARTIAL" : "NONE");

                Funnel f = new Funnel();
                StrategyPerformanceReplayRunner runner = new StrategyPerformanceReplayRunner(50000.0);
                runner.setDecisionObserver(d -> {
                    LocalTime t = d.decisionTime().toLocalTime();
                    if (t.isBefore(FIRST_DECISION) || t.isAfter(LAST_DECISION)) {
                        return;
                    }
                    record(f, d.context(), d.evaluation());
                });
                DailyReplayReport dayReport = runner.replayDay(strategy, handler, day, 50000.0);

                totalTrades += dayReport.getExecutedTrades();
                totalPnl += dayReport.getNetPnl();
                System.out.printf("%-10s | %-7s | %6d | %5d | %7d | %7d | %7d | %7d | %7d | %7d | %7d | %7d | %4d | %2d (Rs%+.2f)%n",
                        date, baseStatus, f.evals, f.breakouts, f.g0Fail, f.g1Fail, f.g2Miss, f.g2Fail, f.g3Fail,
                        f.g5Fail, f.g6Fail, f.g7Fail, f.qualified, dayReport.getExecutedTrades(), dayReport.getNetPnl());
            }

            System.out.println("=".repeat(120));
            System.out.printf("TOTAL S0 TRADES: %d | TOTAL S0 NET P&L: Rs%.2f%n", totalTrades, totalPnl);
            System.out.println("=".repeat(120));
        }
    }

    private static void record(Funnel f, StockContext context, StrategyEvaluation eval) {
        f.evals++;
        MarketCandle latest5m = context.latest5mCandle();
        Double fch = context.dailyContext() != null ? context.dailyContext().getFirstCandleHigh() : null;
        if (fch != null && latest5m != null && latest5m.getClosePrice() != null && latest5m.getClosePrice() > fch) {
            f.breakouts++;
        }
        if (eval.isQualified()) {
            f.qualified++;
        }
        List<String> failed = eval.failedConditions();
        if (failed.contains("FIRST_CANDLE_BULLISH")
                || "FIRST_CANDLE_NOT_BULLISH".equals(eval.failureReason())
                || failed.contains("FIRST_CANDLE_RANGE_0_15_TO_3_0")
                || failed.contains("OPENING_GAP_MAX_1_5")
                || failed.contains("MIN_OPENING_TURNOVER_25L")
                || failed.contains("PREV_DAY_RANGE_MAX_4")) {
            f.g0Fail++;
        }
        if (failed.contains("EXTENSION_RED")) f.g1Fail++;
        if (failed.contains("VOL_X_MISSING")) f.g2Miss++;
        if (failed.contains("VOL_X_RED")) f.g2Fail++;
        if (failed.contains("CANDLE_SHAPE_RED")) f.g3Fail++;
        if (failed.contains("VWAP_5M_RED")) f.g5Fail++;
        if (failed.contains("VWAP_15M_RED")) f.g6Fail++;
        if (failed.contains("NIFTY_VWAP_RED") || failed.contains("NIFTY_VWAP_MISSING")
                || failed.contains("NIFTY_VWAP_FILTER")) {
            f.g7Fail++;
        }
    }
}
