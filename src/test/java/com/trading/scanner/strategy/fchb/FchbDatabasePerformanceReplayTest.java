package com.trading.scanner.strategy.fchb;

import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.risk.BrokerChargesCalculator;
import com.trading.scanner.strategy.model.MarketState;
import com.trading.scanner.strategy.model.StockContext;
import com.trading.scanner.strategy.model.StrategyEvaluation;
import com.trading.scanner.strategy.replay.DailyReplayReport;
import com.trading.scanner.strategy.replay.DatabaseMarketReplayLoader;
import com.trading.scanner.strategy.replay.HistoricalMarketDay;
import com.trading.scanner.strategy.replay.MultiDayReplayReport;
import com.trading.scanner.strategy.replay.ReplayTrade;
import com.trading.scanner.strategy.replay.StrategyPerformanceReplayRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * On-Demand Historical Database Replay Test for FCHB Strategy across Production PostgreSQL Data.
 * <p>
 * Connects directly to PostgreSQL (mithron_prod) in strict READ-ONLY mode.
 * Streams data day-by-day to guarantee heap memory usage stays below 60 MB.
 * <p>
 * Execution Options (via Maven System Properties):
 * <ul>
 *   <li>{@code -Dcapital=50000.0} (Default: Rs. 50,000.00)</li>
 *   <li>{@code -Dsymbols=50} (Default: 50 liquid stocks; set to 100 for expanded universe)</li>
 *   <li>{@code -Ddays=20} (Default: all available trading days in DB)</li>
 *   <li>{@code -DstartDate=2026-10-06} (Optional: start replay from a specific date)</li>
 * </ul>
 * <p>
 * Command to execute:
 * <pre>
 *   mvn test -Dtest=FchbDatabasePerformanceReplayTest
 *   mvn test -Dtest=FchbDatabasePerformanceReplayTest -Dsymbols=100 -Dcapital=50000.0
 * </pre>
 */
@Tag("replay")
class FchbDatabasePerformanceReplayTest {

    @Test
    @DisplayName("Execute Streaming Multi-Day FCHB Performance Replay from PostgreSQL (mithron_prod)")
    void testFchbReplayFromDatabase() {
        double initialCapital = getDoubleProperty("capital", 50000.0);
        int symbolLimit = getIntProperty("symbols", 50);
        String daysProp = System.getProperty("days");
        Integer maxDays = (daysProp != null && !daysProp.isBlank() && !"all".equalsIgnoreCase(daysProp))
                ? Integer.parseInt(daysProp.trim())
                : null;
        String startDateStr = System.getProperty("startDate");
        LocalDate startDate = (startDateStr != null && !startDateStr.isBlank())
                ? LocalDate.parse(startDateStr.trim())
                : null;

        FchbStrategy strategy = new FchbStrategy();
        FchbPositionHandler positionHandler = new FchbPositionHandler();
        StrategyPerformanceReplayRunner runner = new StrategyPerformanceReplayRunner(initialCapital);

        List<DailyReplayReport> dailyReports = new ArrayList<>();
        double currentCapital = initialCapital;
        double peakCapital = initialCapital;
        double maxDrawdown = 0.0;

        int totalTrades = 0;
        int totalWins = 0;
        int totalLosses = 0;
        double totalGrossPnl = 0.0;
        double totalCharges = 0.0;
        double totalNetPnl = 0.0;

        System.out.println("\n=========================================================================================================");
        System.out.println("                   DATABASE MARKET REPLAY: FCHB STRATEGY INITIALIZATION");
        System.out.println("=========================================================================================================");
        System.out.printf("  Starting Capital: Rs. %,.2f\n", initialCapital);
        System.out.printf("  Target Universe : Top %d Liquid Tradable Stocks (+ NIFTY 50 Benchmark)\n", symbolLimit);
        System.out.printf("  Filter Start    : %s\n", startDate != null ? startDate : "EARLIEST_AVAILABLE");
        System.out.printf("  Days Limit      : %s\n", maxDays != null ? maxDays : "ALL_AVAILABLE");
        System.out.println("---------------------------------------------------------------------------------------------------------");

        try (DatabaseMarketReplayLoader loader = new DatabaseMarketReplayLoader()) {
            List<LocalDate> dates = loader.getAvailableTradingDates(startDate, maxDays);
            List<String> symbols = loader.getTopStockUniverse(symbolLimit);

            System.out.printf("  Resolved Trading Sessions: %d days [%s to %s]\n",
                    dates.size(), dates.get(0), dates.get(dates.size() - 1));
            System.out.printf("  Loaded Symbols (%d): %s\n", symbols.size(), symbols.subList(0, Math.min(10, symbols.size())) + " ...");
            System.out.println("=========================================================================================================");
            System.out.println("STREAMING EXECUTION LOG (DAY-BY-DAY PROGRESS):");
            System.out.println("---------------------------------------------------------------------------------------------------------");

            long startTime = System.currentTimeMillis();

            for (int i = 0; i < dates.size(); i++) {
                LocalDate date = dates.get(i);
                HistoricalMarketDay day = loader.loadSingleMarketDay(date, symbols);

                DailyReplayReport dayReport = runner.replayDay(strategy, positionHandler, day, currentCapital);
                dailyReports.add(dayReport);

                currentCapital = dayReport.getEndingCapital();
                if (currentCapital > peakCapital) {
                    peakCapital = currentCapital;
                }
                double dd = peakCapital - currentCapital;
                if (dd > maxDrawdown) {
                    maxDrawdown = dd;
                }

                totalTrades += dayReport.getExecutedTrades();
                totalWins += dayReport.getWinTrades();
                totalLosses += dayReport.getLossTrades();
                totalGrossPnl += dayReport.getGrossPnl();
                totalCharges += dayReport.getTotalCharges();
                totalNetPnl += dayReport.getNetPnl();

                String wl = String.format("%dW/%dL", dayReport.getWinTrades(), dayReport.getLossTrades());
                String netSign = dayReport.getNetPnl() >= 0 ? "+" : "";
                System.out.printf("[%02d/%02d] %s | Trades: %2d (%-5s) | Gross: Rs. %+8.2f | Charges: -Rs. %6.2f | Net: %sRs. %+8.2f | Capital: Rs. %,10.2f\n",
                        (i + 1), dates.size(), date,
                        dayReport.getExecutedTrades(), wl,
                        dayReport.getGrossPnl(), dayReport.getTotalCharges(),
                        netSign, dayReport.getNetPnl(), currentCapital);
            }

            long elapsed = System.currentTimeMillis() - startTime;
            System.out.println("---------------------------------------------------------------------------------------------------------");
            System.out.printf("Total Streaming Replay Execution Time: %.2f seconds (%.1f ms/day across %d symbols)\n",
                    elapsed / 1000.0, (double) elapsed / dates.size(), symbols.size());

            double netReturnPct = (totalNetPnl / initialCapital) * 100.0;
            double winRatePct = totalTrades > 0 ? ((double) totalWins / totalTrades) * 100.0 : 0.0;
            double maxDrawdownPct = peakCapital > 0.0 ? (maxDrawdown / peakCapital) * 100.0 : 0.0;

            MultiDayReplayReport multiDayReport = MultiDayReplayReport.builder()
                    .strategyKey(strategy.getStrategyKey())
                    .initialCapital(initialCapital)
                    .finalCapital(currentCapital)
                    .totalGrossPnl(totalGrossPnl)
                    .totalCharges(totalCharges)
                    .totalNetPnl(totalNetPnl)
                    .netReturnPct(netReturnPct)
                    .totalTrades(totalTrades)
                    .totalWins(totalWins)
                    .totalLosses(totalLosses)
                    .winRatePct(winRatePct)
                    .maxDrawdown(maxDrawdown)
                    .maxDrawdownPct(maxDrawdownPct)
                    .dailyReports(dailyReports)
                    .build();

            // Print full institutional reports
            multiDayReport.printFinancialLedger();
            multiDayReport.printDetailedTradeJournal();
            multiDayReport.printRejectedSignalsAudit();

            // Assertions
            assertThat(dailyReports).isNotEmpty();
            assertThat(currentCapital).isGreaterThan(0.0);
        }
    }

    static class ScenarioConfig {
        final String label;
        final LocalTime entryCutoff;
        final StrategyPerformanceReplayRunner runner;
        final FchbStrategy strategy;
        final FchbPositionHandler positionHandler;
        double currentCapital;
        double peakCapital;
        double maxDrawdown;
        int totalTrades;
        int totalWins;
        int totalLosses;
        double totalGrossPnl;
        double totalCharges;
        double totalNetPnl;
        double grossWins;
        double grossLosses;
        final List<DailyReplayReport> dailyReports = new ArrayList<>();
        final List<ReplayTrade> allTrades = new ArrayList<>();

        public ScenarioConfig(String label, LocalTime entryCutoff, double initialCapital) {
            this.label = label;
            this.entryCutoff = entryCutoff;
            this.currentCapital = initialCapital;
            this.peakCapital = initialCapital;
            this.runner = new StrategyPerformanceReplayRunner(initialCapital, entryCutoff);
            this.positionHandler = new FchbPositionHandler();
            this.strategy = new FchbStrategy() {
                @Override
                public StrategyEvaluation evaluate(StockContext context, MarketState marketState, Map<String, String> parameters) {
                    MarketCandle c5m = context != null ? context.latest5mCandle() : null;
                    if (c5m != null && c5m.getCandleTime().toLocalTime().isAfter(entryCutoff)) {
                        return StrategyEvaluation.notQualified(0.0, Collections.emptyList(),
                                List.of("OUTSIDE_ENTRY_WINDOW"), "AFTER_WINDOW_CUTOFF_" + entryCutoff);
                    }
                    return super.evaluate(context, marketState, parameters);
                }
            };
        }

        public void processDay(HistoricalMarketDay day) {
            DailyReplayReport dayReport = runner.replayDay(strategy, positionHandler, day, currentCapital);
            dailyReports.add(dayReport);

            currentCapital = dayReport.getEndingCapital();
            if (currentCapital > peakCapital) {
                peakCapital = currentCapital;
            }
            double dd = peakCapital - currentCapital;
            if (dd > maxDrawdown) {
                maxDrawdown = dd;
            }

            totalTrades += dayReport.getExecutedTrades();
            totalWins += dayReport.getWinTrades();
            totalLosses += dayReport.getLossTrades();
            totalGrossPnl += dayReport.getGrossPnl();
            totalCharges += dayReport.getTotalCharges();
            totalNetPnl += dayReport.getNetPnl();

            for (ReplayTrade trade : dayReport.getTrades()) {
                if (trade.getGrossPnl() > 0) {
                    grossWins += trade.getGrossPnl();
                } else {
                    grossLosses += Math.abs(trade.getGrossPnl());
                }
            }
            allTrades.addAll(dayReport.getTrades());
        }

        public double getProfitFactor() {
            return grossLosses > 0 ? (grossWins / grossLosses) : (grossWins > 0 ? 99.99 : 0.0);
        }

        public double getWinRatePct() {
            return totalTrades > 0 ? ((double) totalWins / totalTrades) * 100.0 : 0.0;
        }

        public double getNetReturnPct(double initialCapital) {
            return (totalNetPnl / initialCapital) * 100.0;
        }

        public double getMaxDrawdownPct() {
            return peakCapital > 0.0 ? (maxDrawdown / peakCapital) * 100.0 : 0.0;
        }

        public double getAvgTradeNetPnl() {
            return totalTrades > 0 ? (totalNetPnl / totalTrades) : 0.0;
        }
    }

    @Test
    @DisplayName("Empirical Entry Window Sensitivity Test: 10:00 vs 10:15 vs 10:30 vs 10:45 AM")
    void testCompareEntryWindowCutoffs() {
        BrokerChargesCalculator.setActiveBroker(BrokerChargesCalculator.BrokerModel.KOTAK_NEO);
        try {
        double initialCapital = getDoubleProperty("capital", 50000.0);
        int symbolLimit = getIntProperty("symbols", 500); // full liquid universe
        String daysProp = System.getProperty("days");
        Integer maxDays = (daysProp != null && !daysProp.isBlank() && !"all".equalsIgnoreCase(daysProp))
                ? Integer.parseInt(daysProp.trim())
                : null;
        String startDateStr = System.getProperty("startDate");
        LocalDate startDate = (startDateStr != null && !startDateStr.isBlank())
                ? LocalDate.parse(startDateStr.trim())
                : null;

        List<ScenarioConfig> scenarios = List.of(
                new ScenarioConfig("Scenario A (10:00 AM Cutoff)", LocalTime.of(10, 0), initialCapital),
                new ScenarioConfig("Scenario B (10:15 AM Cutoff)", LocalTime.of(10, 15), initialCapital),
                new ScenarioConfig("Scenario C (10:30 AM Cutoff)", LocalTime.of(10, 30), initialCapital),
                new ScenarioConfig("Scenario D (10:45 AM Cutoff)", LocalTime.of(10, 45), initialCapital)
        );

        System.out.println("\n=================================================================================================================");
        System.out.println("              FCHB ENTRY WINDOW SENSITIVITY REPLAY: 10:00 vs 10:15 vs 10:30 vs 10:45 AM");
        System.out.println("=================================================================================================================");
        System.out.printf("  Starting Capital: Rs. %,.2f\n", initialCapital);
        System.out.printf("  Target Universe : Top %d Liquid Tradable Stocks (+ NIFTY 50 Benchmark)\n", symbolLimit);
        System.out.printf("  Filter Start    : %s\n", startDate != null ? startDate : "EARLIEST_AVAILABLE");
        System.out.printf("  Days Limit      : %s\n", maxDays != null ? maxDays : "ALL_AVAILABLE");
        System.out.println("-----------------------------------------------------------------------------------------------------------------");

        try (DatabaseMarketReplayLoader loader = new DatabaseMarketReplayLoader()) {
            List<LocalDate> dates = loader.getAvailableTradingDates(startDate, maxDays);
            List<String> symbols = loader.getTopStockUniverse(symbolLimit);

            System.out.printf("  Resolved Trading Sessions: %d days [%s to %s]\n",
                    dates.size(), dates.get(0), dates.get(dates.size() - 1));
            System.out.printf("  Loaded Symbols (%d): %s\n", symbols.size(), symbols.subList(0, Math.min(10, symbols.size())) + " ...");
            System.out.println("=================================================================================================================");
            System.out.println("STREAMING REPLAY IN PROGRESS (LOADING EACH TRADING DAY ONCE AND RUNNING ALL 4 SCENARIOS)...");
            System.out.println("-----------------------------------------------------------------------------------------------------------------");

            long startTime = System.currentTimeMillis();

            for (int i = 0; i < dates.size(); i++) {
                LocalDate date = dates.get(i);
                HistoricalMarketDay day = loader.loadSingleMarketDay(date, symbols);

                for (ScenarioConfig scenario : scenarios) {
                    scenario.processDay(day);
                }

                System.out.printf("[%02d/%02d] %s | 10:00: %2dT (%+7.1f) | 10:15: %2dT (%+7.1f) | 10:30: %2dT (%+7.1f) | 10:45: %2dT (%+7.1f)\n",
                        (i + 1), dates.size(), date,
                        scenarios.get(0).dailyReports.get(i).getExecutedTrades(), scenarios.get(0).dailyReports.get(i).getNetPnl(),
                        scenarios.get(1).dailyReports.get(i).getExecutedTrades(), scenarios.get(1).dailyReports.get(i).getNetPnl(),
                        scenarios.get(2).dailyReports.get(i).getExecutedTrades(), scenarios.get(2).dailyReports.get(i).getNetPnl(),
                        scenarios.get(3).dailyReports.get(i).getExecutedTrades(), scenarios.get(3).dailyReports.get(i).getNetPnl()
                );
            }

            long elapsed = System.currentTimeMillis() - startTime;
            System.out.println("-----------------------------------------------------------------------------------------------------------------");
            System.out.printf("Streaming Replay Completed in %.2f seconds (Avg %.1f ms/day across %d symbols)\n\n",
                    elapsed / 1000.0, (double) elapsed / dates.size(), symbols.size());

            // 1. Comparative Executive Performance Summary
            System.out.println("=================================================================================================================");
            System.out.println("                        EXECUTIVE PERFORMANCE COMPARISON MATRIX");
            System.out.println("=================================================================================================================");
            System.out.printf("%-30s | %-6s | %-12s | %-9s | %-12s | %-10s | %-12s | %-8s | %-8s | %-12s\n",
                    "Scenario", "Trades", "Wins / Losses", "Win Rate", "Gross P&L", "Charges", "Net P&L", "Return", "PF", "Max DD");
            System.out.println("-----------------------------------------------------------------------------------------------------------------");

            for (ScenarioConfig sc : scenarios) {
                System.out.printf("%-30s | %6d | %4dW / %-4dL | %7.2f%% | Rs.%+9.2f | Rs.%7.2f | Rs.%+9.2f | %+6.2f%% | %7.2f | Rs.%7.2f (%.1f%%)\n",
                        sc.label,
                        sc.totalTrades,
                        sc.totalWins,
                        sc.totalLosses,
                        sc.getWinRatePct(),
                        sc.totalGrossPnl,
                        sc.totalCharges,
                        sc.totalNetPnl,
                        sc.getNetReturnPct(initialCapital),
                        sc.getProfitFactor(),
                        sc.maxDrawdown,
                        sc.getMaxDrawdownPct()
                );
            }
            System.out.println("=================================================================================================================\n");

            // 2. Incremental Window Trades Deep Dive
            printIncrementalTradesAnalysis(scenarios);

            // 3. Day-by-Day Net PnL Matrix
            printDayByDayMatrix(dates, scenarios);
        }
        } finally {
            BrokerChargesCalculator.setActiveBroker(BrokerChargesCalculator.BrokerModel.ANGEL_ONE);
        }
    }

    private void printIncrementalTradesAnalysis(List<ScenarioConfig> scenarios) {
        System.out.println("=================================================================================================================");
        System.out.println("                       INCREMENTAL TIME-WINDOW TRADE DEEP DIVE");
        System.out.println("=================================================================================================================");

        // Bucket 1: 10:00 to 10:15 incremental trades (in Scenario B but entry time > 10:00)
        printTimeBucketTrades("WINDOW 1: 10:00 AM - 10:15 AM (Incremental vs 10:00 Baseline)",
                scenarios.get(1).allTrades, LocalTime.of(10, 0, 1), LocalTime.of(10, 15, 0));

        // Bucket 2: 10:15 to 10:30 incremental trades (in Scenario C but entry time > 10:15)
        printTimeBucketTrades("WINDOW 2: 10:15 AM - 10:30 AM (Incremental vs 10:15 Cutoff)",
                scenarios.get(2).allTrades, LocalTime.of(10, 15, 1), LocalTime.of(10, 30, 0));

        // Bucket 3: 10:30 to 10:45 incremental trades (in Scenario D but entry time > 10:30)
        printTimeBucketTrades("WINDOW 3: 10:30 AM - 10:45 AM (Incremental vs 10:30 Cutoff)",
                scenarios.get(3).allTrades, LocalTime.of(10, 30, 1), LocalTime.of(10, 45, 0));
    }

    private void printTimeBucketTrades(String title, List<ReplayTrade> trades, LocalTime from, LocalTime to) {
        List<ReplayTrade> bucketTrades = trades.stream()
                .filter(t -> t.getEntryTime() != null)
                .filter(t -> {
                    LocalTime time = t.getEntryTime().toLocalTime();
                    return !time.isBefore(from) && !time.isAfter(to);
                })
                .sorted(Comparator.comparing(ReplayTrade::getEntryTime))
                .collect(Collectors.toList());

        System.out.println("\n>>> " + title);
        System.out.println("-----------------------------------------------------------------------------------------------------------------");

        if (bucketTrades.isEmpty()) {
            System.out.println("  No trades triggered in this time window across the entire historical period.");
            return;
        }

        System.out.printf("%-12s | %-8s | %-5s | %-6s | %-9s | %-9s | %-12s | %-9s | %-22s | %-10s | %-10s\n",
                "Date", "Symbol", "Entry", "Qty", "Entry Px", "Exit Px", "Exit Time", "Gross PnL", "Exit Reason", "Charges", "Net PnL");
        System.out.println("-----------------------------------------------------------------------------------------------------------------");

        int wins = 0;
        int losses = 0;
        double sumGross = 0;
        double sumCharges = 0;
        double sumNet = 0;

        for (ReplayTrade t : bucketTrades) {
            if (t.isProfitable()) wins++; else losses++;
            sumGross += t.getGrossPnl();
            sumCharges += t.getCharges();
            sumNet += t.getNetPnl();

            LocalDate d = t.getEntryTime().toLocalDate();
            LocalTime entryT = t.getEntryTime().toLocalTime();
            String exitT = t.getFinalExitTime() != null ? t.getFinalExitTime().toLocalTime().toString() : "--";
            double exitPx = t.getFinalExitPrice() != null ? t.getFinalExitPrice() : 0.0;

            System.out.printf("%-12s | %-8s | %-5s | %6d | %9.2f | %9.2f | %-12s | Rs.%+7.2f | %-22s | Rs.%6.2f | Rs.%+7.2f\n",
                    d, t.getSymbol(), entryT, t.getQuantity(), t.getEntryPrice(), exitPx, exitT,
                    t.getGrossPnl(), t.getExitReason() != null ? t.getExitReason() : "--", t.getCharges(), t.getNetPnl());
        }

        System.out.println("-----------------------------------------------------------------------------------------------------------------");
        double wr = ((double) wins / bucketTrades.size()) * 100.0;
        System.out.printf("  SUBTOTAL: %d Trades | %dW / %dL (%.1f%% Win Rate) | Gross: Rs.%+8.2f | Charges: Rs.%.2f | Net: Rs.%+8.2f\n",
                bucketTrades.size(), wins, losses, wr, sumGross, sumCharges, sumNet);
        System.out.printf("  VERDICT FOR THIS WINDOW: %s\n",
                sumNet > 0 ? "PRODUCTIVE (Generates positive alpha after brokerage)" : "COUNTER-PRODUCTIVE (Friction trap / destroys capital)");
    }

    private void printDayByDayMatrix(List<LocalDate> dates, List<ScenarioConfig> scenarios) {
        System.out.println("\n=================================================================================================================");
        System.out.println("                                  DAY-BY-DAY NET P&L MATRIX");
        System.out.println("=================================================================================================================");
        System.out.printf("%-12s | %-15s | %-15s | %-15s | %-15s\n",
                "Date", "10:00 AM (A)", "10:15 AM (B)", "10:30 AM (C)", "10:45 AM (D)");
        System.out.println("-----------------------------------------------------------------------------------------------------------------");

        for (int i = 0; i < dates.size(); i++) {
            LocalDate d = dates.get(i);
            DailyReplayReport a = scenarios.get(0).dailyReports.get(i);
            DailyReplayReport b = scenarios.get(1).dailyReports.get(i);
            DailyReplayReport c = scenarios.get(2).dailyReports.get(i);
            DailyReplayReport dRep = scenarios.get(3).dailyReports.get(i);

            System.out.printf("%-12s | %2dT: Rs.%+8.2f | %2dT: Rs.%+8.2f | %2dT: Rs.%+8.2f | %2dT: Rs.%+8.2f\n",
                    d,
                    a.getExecutedTrades(), a.getNetPnl(),
                    b.getExecutedTrades(), b.getNetPnl(),
                    c.getExecutedTrades(), c.getNetPnl(),
                    dRep.getExecutedTrades(), dRep.getNetPnl()
            );
        }
        System.out.println("=================================================================================================================\n");
    }

    private static double getDoubleProperty(String key, double fallback) {
        String val = System.getProperty(key);
        if (val != null && !val.isBlank()) {
            try {
                return Double.parseDouble(val.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private static int getIntProperty(String key, int fallback) {
        String val = System.getProperty(key);
        if (val != null && !val.isBlank()) {
            try {
                return Integer.parseInt(val.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }
}
