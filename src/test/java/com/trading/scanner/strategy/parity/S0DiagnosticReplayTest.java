package com.trading.scanner.strategy.parity;

import com.trading.scanner.model.DailyStockContext;
import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.risk.model.PortfolioMode;
import com.trading.scanner.risk.BrokerChargesCalculator;
import com.trading.scanner.risk.BrokerChargesCalculator.BrokerModel;
import com.trading.scanner.strategy.fchb.FchbPositionHandler;
import com.trading.scanner.strategy.fchb.FchbStrategy;
import com.trading.scanner.strategy.model.MarketState;
import com.trading.scanner.strategy.model.StockContext;
import com.trading.scanner.strategy.model.StrategyEvaluation;
import com.trading.scanner.strategy.replay.*;
import com.trading.scanner.util.PriceTickUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

/**
 * Task 3: S0 Diagnostic Replay across all 32 sessions.
 * Never falls back to the old volX formula.
 * Reports baseline status (full/partial/none), candidates, breakouts, gate funnel, and trades.
 */
@Tag("replay")
class S0DiagnosticReplayTest {

    @Test
    @DisplayName("Run S0 Diagnostic Replay across all 32 sessions")
    void testRunS0DiagnosticReplay() {
        BrokerChargesCalculator.setActiveBroker(BrokerModel.KOTAK_NEO);

        FchbStrategy strategy = new FchbStrategy();
        FchbPositionHandler handler = new FchbPositionHandler();

        try (DatabaseMarketReplayLoader dbLoader = new DatabaseMarketReplayLoader()) {
            List<LocalDate> allDates = dbLoader.getAvailableTradingDates(null, null);
            List<String> symbols = dbLoader.getTopStockUniverse(500);

            System.out.println("========================================================================================================================");
            System.out.println("                 S0 DIAGNOSTIC REPLAY AUDIT (32 SESSIONS) - NO VOLX FALLBACK - REAL LOGIC                               ");
            System.out.println("========================================================================================================================");
            System.out.printf("%-10s | %-7s | %-6s | %-5s | %-6s | %-6s | %-6s | %-6s | %-6s | %-6s | %-6s | %-6s | %-6s\n",
                    "DATE", "BASE", "EVALS", "BRK", "G0_FAIL", "G1_FAIL", "G2_MISS", "G2_FAIL", "G3_FAIL", "G5_FAIL", "G6_FAIL", "G7_FAIL", "TRADES");
            System.out.println("------------------------------------------------------------------------------------------------------------------------");

            int totalTrades = 0;
            double totalPnl = 0.0;

            for (LocalDate date : allDates) {
                HistoricalMarketDay day = dbLoader.loadSingleMarketDay(date, symbols);
                if (day == null) continue;

                // Determine baseline status
                int baselineSymbols = 0;
                for (String sym : day.getDailyContexts().keySet()) {
                    Long b = day.getVolumeBaseline(sym, 20); // 09:35 baseline
                    if (b != null && b > 0) baselineSymbols++;
                }

                String baseStatus = "NONE";
                if (baselineSymbols >= 300) baseStatus = "FULL";
                else if (baselineSymbols > 0) baseStatus = "PARTIAL";

                int evalsCount = 0;
                int breakoutsCount = 0;
                int g0Fail = 0;
                int g1Fail = 0;
                int g2Miss = 0;
                int g2Fail = 0;
                int g3Fail = 0;
                int g5Fail = 0;
                int g6Fail = 0;
                int g7Fail = 0;

                LocalTime entryStart = LocalTime.of(9, 35);
                LocalTime entryEnd = LocalTime.of(10, 15);

                for (int minute = 20; minute <= 60; minute += 5) {
                    LocalTime time = LocalTime.of(9, 15).plusMinutes(minute);
                    if (time.isBefore(entryStart) || time.isAfter(entryEnd)) continue;

                    MarketCandle nifty1m = day.getNiftyCandle1m(time);
                    double runningNiftySum = 0.0;
                    int runningNiftyCount = 0;
                    for (MarketCandle nc : day.get1mCandlesUpTo("NIFTY", time)) {
                        if (nc.getHighPrice() != null && nc.getLowPrice() != null && nc.getClosePrice() != null) {
                            runningNiftySum += (nc.getHighPrice() + nc.getLowPrice() + nc.getClosePrice()) / 3.0;
                            runningNiftyCount++;
                        }
                    }
                    double niftyPrice = nifty1m != null ? nifty1m.getClosePrice() : 0.0;
                    double niftyVwap = (nifty1m != null && nifty1m.getVwap() != null) ? nifty1m.getVwap() : (runningNiftyCount > 0 ? runningNiftySum / runningNiftyCount : niftyPrice);
                    double niftyDistPct = (niftyVwap > 0.0) ? ((niftyPrice - niftyVwap) / niftyVwap) * 100.0 : 0.0;
                    boolean niftyAboveVwap = niftyPrice >= niftyVwap;
                    MarketState.MarketRegime regime = niftyAboveVwap ? MarketState.MarketRegime.BULLISH : MarketState.MarketRegime.BEARISH;
                    LocalDateTime asOf = LocalDateTime.of(date, time);
                    MarketState marketState = new MarketState(date, asOf,
                            niftyPrice, niftyVwap, niftyDistPct, niftyAboveVwap, regime, true, "S0 Diagnostic");

                    for (String sym : day.getDailyContexts().keySet()) {
                        DailyStockContext daily = day.getDailyContext(sym);
                        if (daily == null || Boolean.TRUE.equals(daily.getSkipToday())) continue;

                        MarketCandle current1m = day.get1mCandle(sym, time);
                        if (current1m == null) continue;

                        List<MarketCandle> candles5m = day.get5mCandlesUpTo(sym, time);
                        if (candles5m.isEmpty()) continue;
                        MarketCandle latest5m = candles5m.get(candles5m.size() - 1);

                        List<MarketCandle> candles15m = day.get15mCandlesUpTo(sym, time);

                        evalsCount++;

                        // Check breakout above FCH
                        Double fch = daily.getFirstCandleHigh();
                        if (fch != null && latest5m.getClosePrice() != null && latest5m.getClosePrice() > fch) {
                            breakoutsCount++;
                        }

                        // Compute volX strictly from baseline table - NO FALLBACK!
                        int sessionMinute = (int) java.time.Duration.between(LocalTime.of(9, 15), time).toMinutes();
                        Long baselineAvg = day.getVolumeBaseline(sym, sessionMinute);
                        Double volX = null;
                        if (baselineAvg != null && baselineAvg > 0) {
                            long cumVol = 0L;
                            for (MarketCandle c : day.get1mCandlesUpTo(sym, time)) {
                                if (c.getVolume() != null) cumVol += c.getVolume();
                            }
                            volX = cumVol / (double) baselineAvg;
                        }

                        Map<String, String> parameters = new HashMap<>();
                        if (daily.getPrevDayClose() != null && daily.getPrevDayClose() > 0) {
                            double upperCircuit = PriceTickUtil.roundTarget(daily.getPrevDayClose() * 1.20, false, 0.05);
                            parameters.put("upperCircuit", String.valueOf(upperCircuit));
                        }

                        StockContext stockContext = new StockContext(sym, "NSE", date, daily,
                                Collections.emptyList(), candles5m, candles15m, false, volX);

                        StrategyEvaluation eval = strategy.evaluate(stockContext, marketState, parameters);

                        List<String> failed = eval.failedConditions();
                        if (failed.contains("FIRST_CANDLE_BULLISH")
                                || "FIRST_CANDLE_NOT_BULLISH".equals(eval.failureReason())
                                || failed.contains("FIRST_CANDLE_RANGE_0_15_TO_3_0")
                                || failed.contains("OPENING_GAP_MAX_1_5")
                                || failed.contains("MIN_OPENING_TURNOVER_25L")
                                || failed.contains("PREV_DAY_RANGE_MAX_4")) {
                            g0Fail++;
                        }
                        if (failed.contains("EXTENSION_RED")) g1Fail++;
                        if (failed.contains("VOL_X_MISSING")) g2Miss++;
                        if (failed.contains("VOL_X_RED")) g2Fail++;
                        if (failed.contains("CANDLE_SHAPE_RED")) g3Fail++;
                        if (failed.contains("VWAP_5M_RED") || failed.contains("STOCK_VWAP_FILTER")) g5Fail++;
                        if (failed.contains("VWAP_15M_RED") || failed.contains("FIFTEEN_MIN_ALIGNMENT_RED")) g6Fail++;
                        if (failed.contains("NIFTY_VWAP_RED") || failed.contains("NIFTY_VWAP_MISSING") || failed.contains("NIFTY_VWAP_FILTER")) g7Fail++;
                    }
                }

                // Run the actual replay runner for the day to count real executed trades
                StrategyPerformanceReplayRunner runner = new StrategyPerformanceReplayRunner(50000.0);
                DailyReplayReport dayReport = runner.replayDay(strategy, handler, day, 50000.0);
                int tradesToday = dayReport.getTrades().size();
                double pnlToday = dayReport.getNetPnl();
                totalTrades += tradesToday;
                totalPnl += pnlToday;

                System.out.printf("%-10s | %-7s | %5d  | %5d | %6d  | %6d  | %6d  | %6d  | %6d  | %6d  | %6d  | %6d  | %2d (₹%+.2f)\n",
                        date, baseStatus, evalsCount, breakoutsCount, g0Fail, g1Fail, g2Miss, g2Fail, g3Fail, g5Fail, g6Fail, g7Fail, tradesToday, pnlToday);
            }

            System.out.println("========================================================================================================================");
            System.out.printf("TOTAL S0 TRADES: %d | TOTAL S0 NET P&L: ₹%.2f\n", totalTrades, totalPnl);
            System.out.println("========================================================================================================================");
        }
    }
}
