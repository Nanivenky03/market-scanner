package com.trading.scanner.strategy.fchb;

import com.trading.scanner.model.DailyStockContext;
import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.strategy.StrategyPositionHandler;
import com.trading.scanner.strategy.model.PositionAction;
import com.trading.scanner.strategy.model.PositionSnapshot;
import com.trading.scanner.strategy.model.MarketState;
import com.trading.scanner.strategy.model.StockContext;
import com.trading.scanner.strategy.model.StrategyEvaluation;
import com.trading.scanner.util.PriceTickUtil;
import com.trading.scanner.strategy.replay.*;
import com.trading.scanner.risk.BrokerChargesCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Deep Forensic Trade-by-Trade Quantitative Analyzer for FCHB Strategy.
 * <p>
 * Connects to PostgreSQL (mithron_prod) in read-only mode, replays all 23 days,
 * and traces every single trade:
 * - Why & when signal generated (5M candle RSI, VWAP dist, extension, volume, candle shape)
 * - What happened in the first 15m, 30m, 45m
 * - MFE (Max Favorable Excursion) & MAE (Max Adverse Excursion) in R-multiples
 * - Exit reason, exit price, gross and net returns
 * - Post-exit behavior: Did it continue falling (good save), or reverse and hit T1 (whipsaw)?
 * - Aggregated pattern recognition across Winners vs Losers.
 */
@Tag("replay")
class FchbForensicAnalysisTest {

    static class ForensicTradeRecord {
        int tradeNum;
        LocalDate date;
        String symbol;
        LocalTime entryTime;
        double entryPrice;
        double initialStopLoss;
        double target1Price;
        double riskPerShare;
        int quantity;

        // Entry 5M Signal Indicators
        double fch;
        double fcl;
        double firstCandleRangePct;
        double extensionPct;
        Double rsi14;
        Double stockVwap;
        double stockVwapDistPct;
        Double bodyRatio;
        Double upperWickRatio;
        long signalVolume;
        Long firstCandleVolume;
        double volToFirstCandleRatio;

        // Path of trade
        Double priceAt15m;
        Double priceAt30m;
        Double priceAt45m;
        double highestPriceDay;
        double lowestPriceDay;
        double mfeR; // Max Favorable in R
        double maeR; // Max Adverse in R
        LocalTime peakTime;
        LocalTime troughTime;

        // Exit details
        LocalTime exitTime;
        double exitPrice;
        String exitReason;
        double grossPnl;
        double charges;
        double netPnl;
        double realizedR;

        // Post-exit analysis
        boolean reachedT1LaterInDay;
        LocalTime t1ReachedLaterTime;
        Double eodPrice;
        double eodPnlIfHeld;
        String verdict; // "CLEAN_WINNER", "WHIPSAWED_WINNER", "GENUINE_FAILURE_SAVED", "CHOP_SCRATCH"
    }

    @Test
    @DisplayName("Run Comprehensive Forensic Data Analyst Audit across all FCHB Trades")
    void runForensicAudit() {
        double initialCapital = 50000.0;
        int symbolLimit = 1000;

        FchbStrategy strategy = new FchbStrategy();
        FchbPositionHandler positionHandler = new FchbPositionHandler();
        StrategyPerformanceReplayRunner runner = new StrategyPerformanceReplayRunner(initialCapital);

        List<ForensicTradeRecord> records = new ArrayList<>();

        try (DatabaseMarketReplayLoader loader = new DatabaseMarketReplayLoader()) {
            List<LocalDate> dates = loader.getAvailableTradingDates(null, null);
            List<String> symbols = loader.getTopStockUniverse(symbolLimit);

            double currentCapital = initialCapital;
            int tradeCounter = 0;

            for (LocalDate date : dates) {
                HistoricalMarketDay day = loader.loadSingleMarketDay(date, symbols);
                DailyReplayReport dayReport = runner.replayDay(strategy, positionHandler, day, currentCapital);
                currentCapital = dayReport.getEndingCapital();

                for (ReplayTrade trade : dayReport.getTrades()) {
                    tradeCounter++;
                    ForensicTradeRecord rec = analyzeTrade(tradeCounter, trade, day, date);
                    records.add(rec);
                }
            }
        }

        printForensicReport(records);
    }

    @Test
    @DisplayName("Evaluate User Proposed Setup: No Stagnation Exit, No Breakeven Lock, Pure Technical Exits")
    void evaluateUserProposedSetup() {
        double initialCapital = 50000.0;
        int symbolLimit = 1000;

        FchbStrategy strategy = new FchbStrategy();
        FchbPositionHandler pureTechnicalHandler = new FchbPositionHandler();
        StrategyPerformanceReplayRunner runner = new StrategyPerformanceReplayRunner(initialCapital);

        try (DatabaseMarketReplayLoader loader = new DatabaseMarketReplayLoader()) {
            List<LocalDate> dates = loader.getAvailableTradingDates(null, null);
            List<String> symbols = loader.getTopStockUniverse(symbolLimit);

            double currentCapital = initialCapital;
            int totalTrades = 0;
            int totalWins = 0;
            int totalLosses = 0;
            double totalGross = 0.0;
            double totalCharges = 0.0;
            double totalNet = 0.0;

            System.out.println("\n==================================================================================================");
            System.out.println("                 REPLAYING USER'S PROPOSED SETUP (NO TIME-BASED STAGNATION/BREAKEVEN)");
            System.out.println("==================================================================================================");

            for (LocalDate date : dates) {
                HistoricalMarketDay day = loader.loadSingleMarketDay(date, symbols);
                DailyReplayReport dayReport = runner.replayDay(strategy, pureTechnicalHandler, day, currentCapital);
                currentCapital = dayReport.getEndingCapital();

                totalTrades += dayReport.getExecutedTrades();
                totalWins += dayReport.getWinTrades();
                totalLosses += dayReport.getLossTrades();
                totalGross += dayReport.getGrossPnl();
                totalCharges += dayReport.getTotalCharges();
                totalNet += dayReport.getNetPnl();

                for (ReplayTrade trade : dayReport.getTrades()) {
                    System.out.printf("  [%s] %-10s | Entry: %5s @ %8.2f | Exit: %5s @ %8.2f | Reason: %-22s | Gross: %+7.1f | Net: %+7.1f\n",
                            date, trade.getSymbol(), trade.getEntryTime() != null ? trade.getEntryTime().toLocalTime() : "-", trade.getEntryPrice(),
                            trade.getFinalExitTime() != null ? trade.getFinalExitTime().toLocalTime() : "-",
                            trade.getFinalExitPrice() != null ? trade.getFinalExitPrice() : 0.0,
                            trade.getExitReason(),
                            trade.getGrossPnl(), trade.getNetPnl());
                }
            }

            System.out.println("==================================================================================================");
            System.out.printf("SUMMARY: Total Trades=%d | Wins=%d | Losses=%d | Win Rate=%.1f%%\n",
                    totalTrades, totalWins, totalLosses, (totalTrades > 0 ? (totalWins * 100.0 / totalTrades) : 0));
            System.out.printf("P&L: Gross=%+8.2f | Friction=-%8.2f | Net=%+8.2f | Ending Capital=Rs.%.2f\n",
                    totalGross, totalCharges, totalNet, currentCapital);
            System.out.println("==================================================================================================");
        }
    }

    @Test
    @DisplayName("Run Production Replay across ALL Stocks in Database (~500 Symbols Universe)")
    void replayAllUniverseStocks() {
        double initialCapital = 50000.0;
        int symbolLimit = 1000; // Query full active tradable stock universe

        FchbStrategy strategy = new FchbStrategy();
        FchbPositionHandler positionHandler = new FchbPositionHandler();
        StrategyPerformanceReplayRunner runner = new StrategyPerformanceReplayRunner(initialCapital);

        List<ReplayTrade> allTrades = new ArrayList<>();
        Map<LocalDate, Double> dailyPnlMap = new LinkedHashMap<>();

        try (DatabaseMarketReplayLoader loader = new DatabaseMarketReplayLoader()) {
            List<LocalDate> dates = loader.getAvailableTradingDates(null, null);
            List<String> symbols = loader.getTopStockUniverse(symbolLimit);

            System.out.println("\n==================================================================================================");
            System.out.printf("   STARTING PRODUCTION REPLAY ON ENTIRE UNIVERSE: %d SYMBOLS ACROSS %d SESSIONS\n",
                    symbols.size(), dates.size());
            System.out.println("   RULES: 09:30-10:30 AM Cutoff | Pure Technical Exits (No Stagnation Cut, No 12:30 PM Cut)");
            System.out.println("==================================================================================================");

            double currentCapital = initialCapital;
            double peakCapital = initialCapital;
            double maxDrawdown = 0.0;

            for (LocalDate date : dates) {
                HistoricalMarketDay day = loader.loadSingleMarketDay(date, symbols);
                DailyReplayReport dayReport = runner.replayDay(strategy, positionHandler, day, currentCapital);
                double dayNet = dayReport.getNetPnl();
                dailyPnlMap.put(date, dayNet);

                currentCapital = dayReport.getEndingCapital();
                if (currentCapital > peakCapital) {
                    peakCapital = currentCapital;
                }
                double dd = peakCapital - currentCapital;
                if (dd > maxDrawdown) {
                    maxDrawdown = dd;
                }

                allTrades.addAll(dayReport.getTrades());

                if (dayReport.getExecutedTrades() > 0) {
                    System.out.printf("[%s] Trades: %d | Wins: %d | Gross: %+7.1f | Charges: -%6.1f | Net: %+7.1f | Ending: Rs.%.2f\n",
                            date, dayReport.getExecutedTrades(), dayReport.getWinTrades(),
                            dayReport.getGrossPnl(), dayReport.getTotalCharges(), dayReport.getNetPnl(), currentCapital);
                    for (ReplayTrade trade : dayReport.getTrades()) {
                        System.out.printf("    %-10s | En: %5s @ %7.2f | SL: %7.2f | T1: %7.2f (Hit:%s) | Ex: %5s @ %7.2f | %-24s | Gross: %+7.1f | Fee: %5.1f | Net: %+7.1f\n",
                                trade.getSymbol(),
                                trade.getEntryTime() != null ? trade.getEntryTime().toLocalTime() : "-",
                                trade.getEntryPrice(),
                                trade.getInitialStopLoss(),
                                trade.getTarget1Price(),
                                trade.isTarget1Hit() ? "YES" : " NO",
                                trade.getFinalExitTime() != null ? trade.getFinalExitTime().toLocalTime() : "-",
                                trade.getFinalExitPrice() != null ? trade.getFinalExitPrice() : 0.0,
                                trade.getExitReason(),
                                trade.getGrossPnl(),
                                trade.getCharges(),
                                trade.getNetPnl());
                    }
                }
            }

            // Summary Statistics
            int totalTrades = allTrades.size();
            List<ReplayTrade> wins = allTrades.stream().filter(t -> t.getNetPnl() > 0).collect(Collectors.toList());
            List<ReplayTrade> losses = allTrades.stream().filter(t -> t.getNetPnl() <= 0).collect(Collectors.toList());

            double totalGross = allTrades.stream().mapToDouble(ReplayTrade::getGrossPnl).sum();
            double totalCharges = allTrades.stream().mapToDouble(ReplayTrade::getCharges).sum();
            double totalNet = allTrades.stream().mapToDouble(ReplayTrade::getNetPnl).sum();

            double winGross = wins.stream().mapToDouble(ReplayTrade::getGrossPnl).sum();
            double lossGross = Math.abs(losses.stream().mapToDouble(ReplayTrade::getGrossPnl).sum());
            double profitFactor = lossGross > 0 ? (winGross / lossGross) : 99.9;

            System.out.println("\n==================================================================================================");
            System.out.println("                       FULL DATABASE UNIVERSE PERFORMANCE SUMMARY");
            System.out.println("==================================================================================================");
            System.out.printf("Total Sessions Replayed  : %d\n", dates.size());
            System.out.printf("Universe Symbol Count    : %d active tradable stocks\n", symbols.size());
            System.out.printf("Total Trades Executed    : %d (Avg %.1f trades/day)\n", totalTrades, (double) totalTrades / dates.size());
            System.out.printf("Winning Trades           : %d (%.1f%%)\n", wins.size(), totalTrades > 0 ? (wins.size() * 100.0 / totalTrades) : 0);
            System.out.printf("Losing Trades            : %d (%.1f%%)\n", losses.size(), totalTrades > 0 ? (losses.size() * 100.0 / totalTrades) : 0);
            System.out.printf("Gross Realized P&L       : %+8.2f\n", totalGross);
            System.out.printf("Statutory Friction       : -%8.2f\n", totalCharges);
            System.out.printf("Net Realized P&L         : %+8.2f (%.2f%% return on capital)\n", totalNet, (totalNet / initialCapital) * 100.0);
            System.out.printf("Starting / Ending Capital: Rs.%.2f -> Rs.%.2f\n", initialCapital, currentCapital);
            System.out.printf("Profit Factor            : %.2f\n", profitFactor);
            System.out.printf("Max Account Drawdown     : Rs.%.2f (%.2f%%)\n", maxDrawdown, (maxDrawdown / peakCapital) * 100.0);
            if (!wins.isEmpty()) {
                System.out.printf("Avg Winning Trade (Net)  : Rs.%+6.2f\n", wins.stream().mapToDouble(ReplayTrade::getNetPnl).average().orElse(0.0));
            }
            if (!losses.isEmpty()) {
                System.out.printf("Avg Losing Trade (Net)   : Rs.%+6.2f\n", losses.stream().mapToDouble(ReplayTrade::getNetPnl).average().orElse(0.0));
            }

            // Breakdown by Exit Reason
            System.out.println("\n--- BREAKDOWN BY EXIT REASON ---");
            Map<String, List<ReplayTrade>> byReason = allTrades.stream()
                    .collect(Collectors.groupingBy(ReplayTrade::getExitReason));
            byReason.forEach((reason, trades) -> {
                double gross = trades.stream().mapToDouble(ReplayTrade::getGrossPnl).sum();
                double charges = trades.stream().mapToDouble(ReplayTrade::getCharges).sum();
                double net = trades.stream().mapToDouble(ReplayTrade::getNetPnl).sum();
                long w = trades.stream().filter(t -> t.getNetPnl() > 0).count();
                System.out.printf("%-26s : %2d trades | Wins: %2d (%.0f%%) | Gross: %+7.1f | Net: %+7.1f\n",
                        reason, trades.size(), w, (w * 100.0 / trades.size()), gross, net);
            });

            // Symbol Performance Leaderboard
            System.out.println("\n--- TOP CONTRIBUTING SYMBOLS ---");
            Map<String, List<ReplayTrade>> bySymbol = allTrades.stream()
                    .collect(Collectors.groupingBy(ReplayTrade::getSymbol));
            bySymbol.entrySet().stream()
                    .sorted((e1, e2) -> Double.compare(
                            e2.getValue().stream().mapToDouble(ReplayTrade::getNetPnl).sum(),
                            e1.getValue().stream().mapToDouble(ReplayTrade::getNetPnl).sum()))
                    .forEach(e -> {
                        double net = e.getValue().stream().mapToDouble(ReplayTrade::getNetPnl).sum();
                        long w = e.getValue().stream().filter(t -> t.getNetPnl() > 0).count();
                        System.out.printf("  %-12s : %2d trades (%d wins) | Net P&L: %+7.1f\n",
                                e.getKey(), e.getValue().size(), w, net);
                    });
            System.out.println("==================================================================================================");
        }
    }

    @Test
    @DisplayName("Deep Quantitative Audit on Time Windows, Friction Drag, and Expectancy")
    void auditTimeWindowsAndExpectancy() {
        double initialCapital = 50000.0;
        int symbolLimit = 1000;

        FchbStrategy strategy = new FchbStrategy();

        FchbPositionHandler pureTechnicalHandler = new FchbPositionHandler();

        StrategyPerformanceReplayRunner runner = new StrategyPerformanceReplayRunner(initialCapital);
        List<ReplayTrade> allTrades = new ArrayList<>();

        try (DatabaseMarketReplayLoader loader = new DatabaseMarketReplayLoader()) {
            List<LocalDate> dates = loader.getAvailableTradingDates(null, null);
            List<String> symbols = loader.getTopStockUniverse(symbolLimit);

            double currentCapital = initialCapital;

            for (LocalDate date : dates) {
                HistoricalMarketDay day = loader.loadSingleMarketDay(date, symbols);
                DailyReplayReport dayReport = runner.replayDay(strategy, pureTechnicalHandler, day, currentCapital);
                currentCapital = dayReport.getEndingCapital();
                allTrades.addAll(dayReport.getTrades());
            }
        }

        // 1. Time Window Analysis
        System.out.println("\n==================================================================================================");
        System.out.println("                         1. TIME WINDOW PERFORMANCE BREAKDOWN");
        System.out.println("==================================================================================================");
        System.out.printf("%-20s | %-6s | %-4s | %-6s | %-9s | %-9s | %-9s | %-9s\n",
                "ENTRY WINDOW", "TRADES", "WINS", "LOSSES", "WIN RATE", "GROSS P&L", "CHARGES", "NET P&L");
        System.out.println("--------------------------------------------------------------------------------------------------");

        printWindowStats("09:30 - 10:30 AM", allTrades, t -> {
            LocalTime time = t.getEntryTime().toLocalTime();
            return !time.isBefore(LocalTime.of(9, 30)) && time.isBefore(LocalTime.of(10, 30));
        });

        printWindowStats("10:30 - 11:00 AM", allTrades, t -> {
            LocalTime time = t.getEntryTime().toLocalTime();
            return !time.isBefore(LocalTime.of(10, 30)) && time.isBefore(LocalTime.of(11, 0));
        });

        printWindowStats("11:00 - 11:30 AM", allTrades, t -> {
            LocalTime time = t.getEntryTime().toLocalTime();
            return !time.isBefore(LocalTime.of(11, 0)) && !time.isAfter(LocalTime.of(11, 30));
        });

        // 2. Payoff Expectancy & Win/Loss Anatomy
        System.out.println("\n==================================================================================================");
        System.out.println("                         2. WIN / LOSS ASYMMETRY & CHARGES RATIO");
        System.out.println("==================================================================================================");
        List<ReplayTrade> wins = allTrades.stream().filter(t -> t.getNetPnl() > 0).collect(Collectors.toList());
        List<ReplayTrade> losses = allTrades.stream().filter(t -> t.getNetPnl() <= 0).collect(Collectors.toList());

        double totalWinGross = wins.stream().mapToDouble(ReplayTrade::getGrossPnl).sum();
        double totalWinCharges = wins.stream().mapToDouble(ReplayTrade::getCharges).sum();
        double totalWinNet = wins.stream().mapToDouble(ReplayTrade::getNetPnl).sum();

        double totalLossGross = losses.stream().mapToDouble(ReplayTrade::getGrossPnl).sum();
        double totalLossCharges = losses.stream().mapToDouble(ReplayTrade::getCharges).sum();
        double totalLossNet = losses.stream().mapToDouble(ReplayTrade::getNetPnl).sum();

        System.out.printf("WINNING TRADES (%d) : Avg Gross = +Rs.%6.1f | Avg Charges = -Rs.%5.1f | Avg Net = +Rs.%6.1f | Total Net = +Rs.%8.2f\n",
                wins.size(), totalWinGross / wins.size(), totalWinCharges / wins.size(), totalWinNet / wins.size(), totalWinNet);
        System.out.printf("LOSING TRADES  (%d) : Avg Gross = -Rs.%6.1f | Avg Charges = -Rs.%5.1f | Avg Net = -Rs.%6.1f | Total Net = -Rs.%8.2f\n",
                losses.size(), totalLossGross / losses.size(), totalLossCharges / losses.size(), totalLossNet / losses.size(), totalLossNet);
        System.out.printf("OVERALL CHARGES    : Rs. %.2f across %d trades (Avg Rs. %.2f per trade)\n",
                (totalWinCharges + totalLossCharges), allTrades.size(), (totalWinCharges + totalLossCharges) / allTrades.size());
        System.out.printf("PROFIT FACTOR      : %.2f (Gross Win / Gross Loss = %.1f / %.1f)\n",
                Math.abs(totalWinGross / totalLossGross), totalWinGross, Math.abs(totalLossGross));

        // 3. Loss Reasons Breakdown
        System.out.println("\n==================================================================================================");
        System.out.println("                         3. WHY DID THE 17 LOSING TRADES LOSE?");
        System.out.println("==================================================================================================");
        Map<String, List<ReplayTrade>> byExitReason = losses.stream().collect(Collectors.groupingBy(ReplayTrade::getExitReason));
        byExitReason.forEach((reason, list) -> {
            double sumLoss = list.stream().mapToDouble(ReplayTrade::getNetPnl).sum();
            System.out.printf("  • %-24s: %2d trades | Net Loss: Rs. %8.2f | Avg Loss: Rs. %6.1f\n",
                    reason, list.size(), sumLoss, sumLoss / list.size());
        });

        System.out.println("\n--- LIST OF ALL 17 LOSING TRADES ---");
        for (ReplayTrade t : losses) {
            System.out.printf("  %-10s | Entry: %5s @ %8.2f | Exit: %5s @ %8.2f | Reason: %-22s | Gross: %7.1f | Net: %7.1f\n",
                    t.getSymbol(), t.getEntryTime().toLocalTime(), t.getEntryPrice(),
                    t.getFinalExitTime() != null ? t.getFinalExitTime().toLocalTime() : "-",
                    t.getFinalExitPrice() != null ? t.getFinalExitPrice() : 0.0,
                    t.getExitReason(), t.getGrossPnl(), t.getNetPnl());
        }
        System.out.println("==================================================================================================");
    }

    @Test
    @DisplayName("Diagnose Macro Trap Days vs Winning Days")
    void diagnoseTrapDays() {
        try (DatabaseMarketReplayLoader loader = new DatabaseMarketReplayLoader()) {
            List<LocalDate> dates = loader.getAvailableTradingDates(null, null);
            System.out.println("\n==================================================================================================");
            System.out.println("                         DIAGNOSTIC: NIFTY MACRO REGIME & MARKET BREADTH");
            System.out.println("==================================================================================================");
            System.out.printf("%-10s | %-8s | %-8s | %-8s | %-8s | %-8s | %-16s\n",
                    "DATE", "NIF_OPEN", "NIF_935", "NIF_935_RET", "NIF_935_VWAP", "NIF_C1_DIR", "DAY_TYPE");
            System.out.println("--------------------------------------------------------------------------------------------------");

            Set<LocalDate> trapDates = Set.of(
                    LocalDate.of(2026, 10, 14),
                    LocalDate.of(2026, 10, 21),
                    LocalDate.of(2026, 10, 27),
                    LocalDate.of(2026, 10, 30)
            );

            for (LocalDate date : dates) {
                HistoricalMarketDay day = loader.loadSingleMarketDay(date, List.of("NIFTY"));
                MarketCandle c1 = day.get1mCandle("NIFTY", LocalTime.of(9, 15));
                MarketCandle c935 = day.get1mCandle("NIFTY", LocalTime.of(9, 35));
                if (c1 == null || c935 == null) continue;

                double dayOpen = c1.getOpenPrice();
                double p935 = c935.getClosePrice();
                double ret935 = ((p935 - dayOpen) / dayOpen) * 100.0;
                double vwap935 = c935.getVwap() != null ? c935.getVwap() : p935;
                double vwapDist = ((p935 - vwap935) / vwap935) * 100.0;

                MarketCandle nif5m_1 = day.getCandles5m().stream()
                        .filter(c -> "NIFTY".equalsIgnoreCase(c.getSymbol()))
                        .findFirst().orElse(null);
                String c1Dir = (nif5m_1 != null && nif5m_1.getClosePrice() >= nif5m_1.getOpenPrice()) ? "GREEN" : "RED";

                String dayType = trapDates.contains(date) ? "*** TRAP DAY ***" : "NORMAL/WIN";

                System.out.printf("%-10s | %8.1f | %8.1f | %+7.2f%% | %+7.2f%% | %-10s | %-16s\n",
                        date, dayOpen, p935, ret935, vwapDist, c1Dir, dayType);
            }
            System.out.println("==================================================================================================");
        }
    }

    @Test
    @DisplayName("Audit Excluded Breakout Opportunities: What Did We Miss and Why?")
    void auditExcludedProfitableOpportunities() {
        int symbolLimit = 1000;
        FchbStrategy strategy = new FchbStrategy();

        record ExcludedCandidate(
                LocalDate date,
                LocalTime time,
                String symbol,
                double entryPrice,
                double stopLoss,
                double rValue,
                List<String> failedRules,
                double maxPriceDay,
                double minPriceDay,
                double mfeR,
                double maeR,
                boolean reachedT1First,
                boolean hitStopFirst
        ) {}

        List<ExcludedCandidate> excludedList = new ArrayList<>();

        try (DatabaseMarketReplayLoader loader = new DatabaseMarketReplayLoader()) {
            List<LocalDate> dates = loader.getAvailableTradingDates(null, null);
            List<String> symbols = loader.getTopStockUniverse(symbolLimit);

            for (LocalDate date : dates) {
                HistoricalMarketDay day = loader.loadSingleMarketDay(date, symbols);
                List<LocalTime> times = day.getDistinct1mTimes();

                // Compute Nifty VWAP map
                double runningNiftySum = 0.0;
                int runningNiftyCount = 0;
                Map<LocalTime, MarketState> marketStateMap = new HashMap<>();
                for (LocalTime t : times) {
                    MarketCandle nifty1m = day.getNiftyCandle1m(t);
                    if (nifty1m != null) {
                        double tp = (nifty1m.getHighPrice() + nifty1m.getLowPrice() + nifty1m.getClosePrice()) / 3.0;
                        runningNiftySum += tp;
                        runningNiftyCount++;
                        double nPrice = nifty1m.getClosePrice();
                        double nVwap = nifty1m.getVwap() != null ? nifty1m.getVwap() : (runningNiftySum / runningNiftyCount);
                        double nDist = ((nPrice - nVwap) / nVwap) * 100.0;
                        boolean above = nPrice > nVwap;
                        MarketState.MarketRegime regime = above ? MarketState.MarketRegime.BULLISH : MarketState.MarketRegime.BEARISH;
                        marketStateMap.put(t, new MarketState(date, nifty1m.getCandleTime(), nPrice, nVwap, nDist, above, regime, true, "Audit"));
                    }
                }

                Set<String> alreadyEvaluated = new HashSet<>();

                for (LocalTime time : times) {
                    if (time.isBefore(LocalTime.of(9, 30)) || time.isAfter(LocalTime.of(10, 0)) || (time.getMinute() % 5 != 0)) {
                        continue;
                    }

                    MarketState mState = marketStateMap.get(time);
                    if (mState == null) continue;

                    for (String symbol : day.getDailyContexts().keySet()) {
                        if (alreadyEvaluated.contains(symbol)) continue;

                        MarketCandle current1m = day.get1mCandle(symbol, time);
                        if (current1m == null) continue;

                        DailyStockContext daily = day.getDailyContext(symbol);
                        if (daily == null || daily.getFirstCandleHigh() == null || daily.getFirstCandleLow() == null) {
                            continue;
                        }

                        List<MarketCandle> candles5m = day.get5mCandlesUpTo(symbol, time);
                        if (candles5m.isEmpty()) continue;
                        MarketCandle breakout5m = candles5m.get(candles5m.size() - 1);

                        // Only evaluate stocks that actually closed above First Candle High
                        if (breakout5m.getClosePrice() >= daily.getFirstCandleHigh()) {
                            List<MarketCandle> candles15m = day.get15mCandlesUpTo(symbol, time);
                            StockContext stockContext = new StockContext(symbol, "NSE", date, daily,
                                    Collections.emptyList(), candles5m, candles15m, false, 2.5);

                            StrategyEvaluation eval = strategy.evaluate(stockContext, mState, Collections.emptyMap());
                            if (!eval.isQualified()) {
                                alreadyEvaluated.add(symbol);

                                double entry = breakout5m.getClosePrice();
                                double sl = PriceTickUtil.roundStopLoss(daily.getFirstCandleLow() * 0.995, false, 0.05);
                                double r = entry - sl;
                                if (r <= 0.0) continue;
                                double t1 = entry + (0.73 * r);

                                // Trace subsequent 1m price action
                                double maxPrice = entry;
                                double minPrice = entry;
                                boolean reachedT1 = false;
                                boolean hitSl = false;

                                for (LocalTime futureTime : times) {
                                    if (futureTime.isBefore(time)) continue;
                                    MarketCandle futureCandle = day.get1mCandle(symbol, futureTime);
                                    if (futureCandle == null) continue;

                                    if (futureCandle.getHighPrice() > maxPrice) maxPrice = futureCandle.getHighPrice();
                                    if (futureCandle.getLowPrice() < minPrice) minPrice = futureCandle.getLowPrice();

                                    if (!reachedT1 && !hitSl) {
                                        if (futureCandle.getHighPrice() >= t1) {
                                            reachedT1 = true;
                                        } else if (futureCandle.getLowPrice() <= sl) {
                                            hitSl = true;
                                        }
                                    }
                                }

                                double mfeR = (maxPrice - entry) / r;
                                double maeR = (entry - minPrice) / r;

                                excludedList.add(new ExcludedCandidate(
                                        date, time, symbol, entry, sl, r, eval.failedConditions(),
                                        maxPrice, minPrice, mfeR, maeR, reachedT1, hitSl
                                ));
                            }
                        }
                    }
                }
            }
        }

        System.out.println("\n==================================================================================================");
        System.out.println("                 EXCLUDED BREAKOUT OPPORTUNITIES AUDIT (09:30 - 10:00 AM)");
        System.out.println("==================================================================================================");
        System.out.printf("Total Excluded Breakouts Found : %d\n", excludedList.size());

        long reachedT1Count = excludedList.stream().filter(c -> c.reachedT1First).count();
        long hitSlCount = excludedList.stream().filter(c -> c.hitStopFirst).count();
        long neitherCount = excludedList.size() - reachedT1Count - hitSlCount;

        System.out.printf("Potential Winners (T1 Hit First) : %d (%.1f%%)\n", reachedT1Count, (reachedT1Count * 100.0 / excludedList.size()));
        System.out.printf("Saved Losses      (SL Hit First) : %d (%.1f%%)\n", hitSlCount, (hitSlCount * 100.0 / excludedList.size()));
        System.out.printf("Rangebound / EOD Square-off      : %d (%.1f%%)\n", neitherCount, (neitherCount * 100.0 / excludedList.size()));

        System.out.println("\n--- BREAKDOWN BY REJECTION RULE ---");
        Map<String, List<ExcludedCandidate>> byRule = new HashMap<>();
        for (ExcludedCandidate c : excludedList) {
            for (String r : c.failedRules) {
                byRule.computeIfAbsent(r, k -> new ArrayList<>()).add(c);
            }
        }

        byRule.forEach((rule, list) -> {
            long t1Wins = list.stream().filter(c -> c.reachedT1First).count();
            long slLosses = list.stream().filter(c -> c.hitStopFirst).count();
            double avgMfe = list.stream().mapToDouble(c -> c.mfeR).average().orElse(0.0);
            double avgMae = list.stream().mapToDouble(c -> c.maeR).average().orElse(0.0);
            System.out.printf("%-35s : %3d candidates | Winners: %2d (%.0f%%) | Saved Losses: %2d (%.0f%%) | Avg MFE: +%.2fR | Avg MAE: -%.2fR\n",
                    rule, list.size(), t1Wins, (t1Wins * 100.0 / list.size()), slLosses, (slLosses * 100.0 / list.size()), avgMfe, avgMae);
        });

        System.out.println("\n--- TOP MISSED WINNERS (MFE >= +1.5R) ---");
        excludedList.stream()
                .filter(c -> c.reachedT1First && c.mfeR >= 1.5)
                .sorted((c1, c2) -> Double.compare(c2.mfeR, c1.mfeR))
                .limit(15)
                .forEach(c -> {
                    System.out.printf("  [%s %5s] %-10s @ %7.2f | MFE: +%4.2fR | Rules: %s\n",
                            c.date, c.time, c.symbol, c.entryPrice, c.mfeR, c.failedRules);
                });

        System.out.println("==================================================================================================");
    }

    @Test
    @DisplayName("Evaluate Optimization Scenarios to Beat Charges and Maximize Net P&L")
    void evaluateOptimizationScenarios() {
        double initialCapital = 50000.0;
        int symbolLimit = 1000;

        try (DatabaseMarketReplayLoader loader = new DatabaseMarketReplayLoader()) {
            List<LocalDate> dates = loader.getAvailableTradingDates(null, null);
            List<String> symbols = loader.getTopStockUniverse(symbolLimit);

            System.out.println("\n==================================================================================================");
            System.out.println("                         4. OPTIMIZATION SCENARIOS ANALYSIS");
            System.out.println("==================================================================================================");

            // Base Runner: 10:30 AM cutoff vs 11:30 AM cutoff
            runScenario("SCENARIO A: Strict 10:30 AM Entry Cutoff (No Entries after 10:30 AM)",
                    loader, dates, symbols, initialCapital, LocalTime.of(10, 30), 0.73, null);

            runScenario("SCENARIO B: 10:30 AM Cutoff + Target 1 at 1.0R (instead of 0.73R)",
                    loader, dates, symbols, initialCapital, LocalTime.of(10, 30), 1.00, null);

            runScenario("SCENARIO C: 10:15 AM Cutoff (Early Morning Momentum Only)",
                    loader, dates, symbols, initialCapital, LocalTime.of(10, 15), 0.73, null);

            runScenario("SCENARIO D: 10:15 AM Cutoff + 12:30 PM Lunchtime Chop Exit (If in profit < 0.2R)",
                    loader, dates, symbols, initialCapital, LocalTime.of(10, 15), 0.73, LocalTime.of(12, 30));
        }
    }

    private void runScenario(String scenarioName, DatabaseMarketReplayLoader loader,
                             List<LocalDate> dates, List<String> symbols,
                             double initialCapital, LocalTime entryCutoff,
                             double t1RMultiple, LocalTime middayExitTime) {
        FchbStrategy strategy = new FchbStrategy();

        // Custom runner adjusting entryCutoff
        StrategyPerformanceReplayRunner runner = new StrategyPerformanceReplayRunner(initialCapital) {
            // We can override entryWindowEnd via reflection or subclass if needed, but let's test via strategy or runner
        };

        // Let's create custom StrategyPositionHandler
        StrategyPositionHandler handler = new StrategyPositionHandler() {
            private final FchbPositionHandler delegate = new FchbPositionHandler();

            @Override
            public int getStrategyId() {
                return 2;
            }

            @Override
            public String getStrategyKey() {
                return "FCHB";
            }

            @Override
            public PositionAction evaluatePosition(PositionSnapshot position, Double currentLtp,
                                                   MarketCandle last1mCandle, Double currentAtr,
                                                   LocalTime currentTime, Map<String, String> parameters) {
                // If Target 1 is overridden to 1.0R:
                if (t1RMultiple > 0.73 && !position.target1Booked() && position.entryPrice() != null) {
                    double risk = position.target1Price() != null
                            ? (Math.abs(position.target1Price() - position.entryPrice()) / 0.73)
                            : 5.0;
                    double newT1 = position.entryPrice() + (t1RMultiple * risk);
                    if (currentLtp >= newT1) {
                        return PositionAction.partialExit(0.5, position.entryPrice(), "TARGET_1_HIT");
                    }
                }

                PositionAction action = delegate.evaluatePosition(position, currentLtp, last1mCandle, currentAtr, currentTime, parameters);
                if (action != null && action.type() == PositionAction.ActionType.FULL_EXIT
                        && "STAGNATION_UNDERWATER_EXIT".equals(action.reason())) {
                    return PositionAction.hold();
                }
                if (action != null && action.type() == PositionAction.ActionType.UPDATE_STOP
                        && "STAGNATION_BREAKEVEN_LOCK".equals(action.reason())) {
                    return PositionAction.hold();
                }

                if (middayExitTime != null && !position.target1Booked() && !currentTime.isBefore(middayExitTime)) {
                    if (currentLtp < position.entryPrice()) {
                        return PositionAction.fullExit("MIDDAY_CHOP_EXIT");
                    }
                }
                return action;
            }
        };

        double currentCapital = initialCapital;
        int totalTrades = 0;
        int totalWins = 0;
        int totalLosses = 0;
        double totalGross = 0.0;
        double totalCharges = 0.0;
        double totalNet = 0.0;

        for (LocalDate date : dates) {
            HistoricalMarketDay day = loader.loadSingleMarketDay(date, symbols);

            // Replay with custom entry cutoff filter
            DailyReplayReport dayReport = replayWithCutoff(strategy, handler, day, currentCapital, entryCutoff);
            currentCapital = dayReport.getEndingCapital();

            totalTrades += dayReport.getExecutedTrades();
            totalWins += dayReport.getWinTrades();
            totalLosses += dayReport.getLossTrades();
            totalGross += dayReport.getGrossPnl();
            totalCharges += dayReport.getTotalCharges();
            totalNet += dayReport.getNetPnl();
        }

        System.out.printf("%-65s | Trades: %2d | Wins: %2d (%4.1f%%) | Gross: %+8.1f | Charges: -%7.1f | Net: %+8.1f\n",
                scenarioName, totalTrades, totalWins,
                (totalTrades > 0 ? (totalWins * 100.0 / totalTrades) : 0),
                totalGross, totalCharges, totalNet);
    }

    private DailyReplayReport replayWithCutoff(FchbStrategy strategy, StrategyPositionHandler handler,
                                               HistoricalMarketDay day, double startingCapital,
                                               LocalTime entryCutoff) {
        // Wrap strategy to enforce custom entry cutoff
        FchbStrategy cutoffStrategy = new FchbStrategy() {
            @Override
            public com.trading.scanner.strategy.model.StrategyEvaluation evaluate(
                    com.trading.scanner.strategy.model.StockContext stockContext,
                    com.trading.scanner.strategy.model.MarketState marketState,
                    Map<String, String> parameters) {
                if (marketState.evaluatedAt() != null && marketState.evaluatedAt().toLocalTime().isAfter(entryCutoff)) {
                    return com.trading.scanner.strategy.model.StrategyEvaluation.notQualified(0.0,
                            Collections.emptyList(), List.of("TIME_AFTER_CUTOFF"), "TIME_AFTER_CUTOFF");
                }
                return super.evaluate(stockContext, marketState, parameters);
            }
        };

        StrategyPerformanceReplayRunner runner = new StrategyPerformanceReplayRunner(startingCapital);
        return runner.replayDay(cutoffStrategy, handler, day, startingCapital);
    }

    private void printWindowStats(String label, List<ReplayTrade> allTrades, java.util.function.Predicate<ReplayTrade> filter) {
        List<ReplayTrade> subset = allTrades.stream().filter(filter).collect(Collectors.toList());
        int total = subset.size();
        if (total == 0) {
            System.out.printf("%-20s | %6d | %4d | %6d | %8s | %9s | %9s | %9s\n",
                    label, 0, 0, 0, "0.0%", "Rs. 0.0", "Rs. 0.0", "Rs. 0.0");
            return;
        }
        long wins = subset.stream().filter(t -> t.getNetPnl() > 0).count();
        long losses = total - wins;
        double winRate = (wins * 100.0) / total;
        double gross = subset.stream().mapToDouble(ReplayTrade::getGrossPnl).sum();
        double charges = subset.stream().mapToDouble(ReplayTrade::getCharges).sum();
        double net = subset.stream().mapToDouble(ReplayTrade::getNetPnl).sum();

        System.out.printf("%-20s | %6d | %4d | %6d | %7.1f%% | %+8.1f | -%8.1f | %+8.1f\n",
                label, total, wins, losses, winRate, gross, charges, net);
    }

    private ForensicTradeRecord analyzeTrade(int tradeNum, ReplayTrade trade, HistoricalMarketDay day, LocalDate date) {
        ForensicTradeRecord r = new ForensicTradeRecord();
        r.tradeNum = tradeNum;
        r.date = date;
        r.symbol = trade.getSymbol();
        r.entryTime = trade.getEntryTime().toLocalTime();
        r.entryPrice = trade.getEntryPrice();
        r.initialStopLoss = trade.getInitialStopLoss();
        r.target1Price = trade.getTarget1Price();
        r.riskPerShare = Math.abs(r.entryPrice - r.initialStopLoss);
        r.quantity = trade.getQuantity();

        r.exitTime = trade.getFinalExitTime().toLocalTime();
        r.exitPrice = trade.getFinalExitPrice();
        r.exitReason = trade.getExitReason();
        r.grossPnl = trade.getGrossPnl();
        r.charges = trade.getCharges();
        r.netPnl = trade.getNetPnl();
        r.realizedR = r.riskPerShare > 0 ? (r.grossPnl / (r.riskPerShare * r.quantity)) : 0.0;

        DailyStockContext daily = day.getDailyContext(r.symbol);
        if (daily != null) {
            r.fch = daily.getFirstCandleHigh() != null ? daily.getFirstCandleHigh() : 0.0;
            r.fcl = daily.getFirstCandleLow() != null ? daily.getFirstCandleLow() : 0.0;
            r.firstCandleRangePct = daily.getFirstCandleRangePct() != null ? daily.getFirstCandleRangePct() : 0.0;
            r.firstCandleVolume = daily.getFirstCandleVolume();
        }

        // Find signal 5M candle (the 5M candle ending at or right before entryTime)
        LocalTime signalCandleEnd = r.entryTime;
        MarketCandle signal5m = null;
        for (MarketCandle c : day.getCandles5m()) {
            if (c.getSymbol().equalsIgnoreCase(r.symbol) && !c.getCandleTime().toLocalTime().isAfter(signalCandleEnd)) {
                if (signal5m == null || c.getCandleTime().isAfter(signal5m.getCandleTime())) {
                    signal5m = c;
                }
            }
        }

        if (signal5m != null) {
            r.extensionPct = r.fch > 0 ? ((signal5m.getClosePrice() - r.fch) / r.fch) * 100.0 : 0.0;
            r.rsi14 = signal5m.getRsi14();
            r.stockVwap = signal5m.getVwap();
            r.stockVwapDistPct = r.stockVwap != null && r.stockVwap > 0 ? ((signal5m.getClosePrice() - r.stockVwap) / r.stockVwap) * 100.0 : 0.0;
            r.bodyRatio = signal5m.getBodyRatio();
            r.upperWickRatio = signal5m.getUpperWickRatio();
            r.signalVolume = signal5m.getVolume() != null ? signal5m.getVolume() : 0L;
            r.volToFirstCandleRatio = (r.firstCandleVolume != null && r.firstCandleVolume > 0) ? ((double) r.signalVolume / r.firstCandleVolume) : 0.0;
        }

        // Trace 1M candles from entry to end of day
        List<MarketCandle> candlesAfterEntry = day.getCandles1m().stream()
                .filter(c -> c.getSymbol().equalsIgnoreCase(r.symbol))
                .filter(c -> !c.getCandleTime().toLocalTime().isBefore(r.entryTime))
                .sorted(Comparator.comparing(MarketCandle::getCandleTime))
                .collect(Collectors.toList());

        double highest = r.entryPrice;
        double lowest = r.entryPrice;
        LocalTime peakTime = r.entryTime;
        LocalTime troughTime = r.entryTime;

        boolean hitT1Later = false;
        LocalTime t1Time = null;

        for (MarketCandle c : candlesAfterEntry) {
            LocalTime ct = c.getCandleTime().toLocalTime();
            double h = c.getHighPrice() != null ? c.getHighPrice() : c.getClosePrice();
            double l = c.getLowPrice() != null ? c.getLowPrice() : c.getClosePrice();

            if (h > highest) {
                highest = h;
                peakTime = ct;
            }
            if (l < lowest) {
                lowest = l;
                troughTime = ct;
            }

            // Check milestone intervals
            long mins = java.time.Duration.between(r.entryTime, ct).toMinutes();
            if (mins == 15 && r.priceAt15m == null) r.priceAt15m = c.getClosePrice();
            if (mins == 30 && r.priceAt30m == null) r.priceAt30m = c.getClosePrice();
            if (mins == 45 && r.priceAt45m == null) r.priceAt45m = c.getClosePrice();

            // Check if touched target 1 price after exit
            if (ct.isAfter(r.exitTime) && h >= r.target1Price && !hitT1Later) {
                hitT1Later = true;
                t1Time = ct;
            }

            // EOD price at 14:45
            if (!ct.isAfter(LocalTime.of(14, 45, 0))) {
                r.eodPrice = c.getClosePrice();
            }
        }

        r.highestPriceDay = highest;
        r.lowestPriceDay = lowest;
        r.peakTime = peakTime;
        r.troughTime = troughTime;
        r.mfeR = r.riskPerShare > 0 ? (highest - r.entryPrice) / r.riskPerShare : 0.0;
        r.maeR = r.riskPerShare > 0 ? (r.entryPrice - lowest) / r.riskPerShare : 0.0;
        r.reachedT1LaterInDay = hitT1Later;
        r.t1ReachedLaterTime = t1Time;

        if (r.eodPrice != null) {
            r.eodPnlIfHeld = (r.eodPrice - r.entryPrice) * r.quantity;
        }

        // Categorize verdict
        if ("TARGET_1_HIT".equals(r.exitReason) || r.netPnl > 0.0) {
            r.verdict = "CLEAN_WINNER";
        } else if (r.reachedT1LaterInDay || (r.eodPnlIfHeld > (r.riskPerShare * r.quantity * 0.5))) {
            r.verdict = "WHIPSAWED_WINNER"; // Was cut early, but went on to win!
        } else if (r.maeR >= 0.8 || (r.eodPrice != null && r.eodPrice < r.initialStopLoss)) {
            r.verdict = "GENUINE_FAILURE_SAVED"; // Truly broke down, exit saved money!
        } else {
            r.verdict = "CHOP_SCRATCH"; // Never went anywhere
        }

        return r;
    }

    private void printForensicReport(List<ForensicTradeRecord> records) {
        System.out.println("\n==================================================================================================================================");
        System.out.println("                                      DEEP FORENSIC TRADE-BY-TRADE QUANTITATIVE AUDIT");
        System.out.println("==================================================================================================================================");
        System.out.printf("%-4s | %-10s | %-10s | %-5s | %-8s | %-8s | %-8s | %-5s | %-5s | %-24s | %-7s | %-7s | %-16s | %-16s\n",
                "TR#", "DATE", "SYMBOL", "ENTRY", "PRICE", "1R_RISK", "TARGET1", "MFE_R", "MAE_R", "EXIT_REASON", "GROSS", "NET_CASH", "VERDICT", "POST_EXIT_TRUTH");
        System.out.println("----------------------------------------------------------------------------------------------------------------------------------");

        for (ForensicTradeRecord r : records) {
            String postTruth = "-";
            if (r.reachedT1LaterInDay) {
                postTruth = "Hit T1 @ " + r.t1ReachedLaterTime;
            } else if ("GENUINE_FAILURE_SAVED".equals(r.verdict)) {
                postTruth = String.format("Dropped to -%.2fR", r.maeR);
            } else if ("CHOP_SCRATCH".equals(r.verdict)) {
                postTruth = String.format("Drifted at EOD");
            } else if ("CLEAN_WINNER".equals(r.verdict)) {
                postTruth = String.format("Peak +%.2fR", r.mfeR);
            }

            System.out.printf("#%02d | %s | %-10s | %s | %8.2f | %8.2f | %8.2f | +%4.2fR | -%4.2fR | %-24s | %+7.1f | %+8.2f | %-16s | %s\n",
                    r.tradeNum, r.date, r.symbol, r.entryTime, r.entryPrice, r.riskPerShare, r.target1Price,
                    r.mfeR, r.maeR, r.exitReason, r.grossPnl, r.netPnl, r.verdict, postTruth);
        }

        System.out.println("==================================================================================================================================");
        System.out.println("                                         QUANTITATIVE PATTERN SUMMARY & INSIGHTS");
        System.out.println("==================================================================================================================================");

        Map<String, List<ForensicTradeRecord>> byVerdict = records.stream().collect(Collectors.groupingBy(r -> r.verdict));

        List<ForensicTradeRecord> cleanWinners = byVerdict.getOrDefault("CLEAN_WINNER", Collections.emptyList());
        List<ForensicTradeRecord> whipsawed = byVerdict.getOrDefault("WHIPSAWED_WINNER", Collections.emptyList());
        List<ForensicTradeRecord> savedFailures = byVerdict.getOrDefault("GENUINE_FAILURE_SAVED", Collections.emptyList());
        List<ForensicTradeRecord> chopScratches = byVerdict.getOrDefault("CHOP_SCRATCH", Collections.emptyList());

        System.out.printf("1. CLEAN WINNERS       : %2d trades | Net P&L: %+8.2f | Avg MFE: +%.2fR | Avg MAE: -%.2fR\n",
                cleanWinners.size(), cleanWinners.stream().mapToDouble(r -> r.netPnl).sum(),
                cleanWinners.stream().mapToDouble(r -> r.mfeR).average().orElse(0.0),
                cleanWinners.stream().mapToDouble(r -> r.maeR).average().orElse(0.0));

        System.out.printf("2. WHIPSAWED WINNERS   : %2d trades | Net P&L: %+8.2f | Avg MFE: +%.2fR | (Prematurely cut, but hit T1 later!)\n",
                whipsawed.size(), whipsawed.stream().mapToDouble(r -> r.netPnl).sum(),
                whipsawed.stream().mapToDouble(r -> r.mfeR).average().orElse(0.0));

        System.out.printf("3. GENUINE SAVED TRADES: %2d trades | Net P&L: %+8.2f | (True failures where early cut saved -1.0R loss)\n",
                savedFailures.size(), savedFailures.stream().mapToDouble(r -> r.netPnl).sum());

        System.out.printf("4. CHOP SCRATCH TRADES : %2d trades | Net P&L: %+8.2f | (Flat trades eaten by brokerage friction)\n",
                chopScratches.size(), chopScratches.stream().mapToDouble(r -> r.netPnl).sum());

        System.out.println("----------------------------------------------------------------------------------------------------------------------------------");
        System.out.println("INDICATOR CORRELATION (ENTRY METRICS):");
        printMetricComparison("RSI(14) at Entry", cleanWinners, whipsawed, savedFailures, chopScratches, r -> r.rsi14 != null ? r.rsi14 : 0.0);
        printMetricComparison("Extension % over FCH", cleanWinners, whipsawed, savedFailures, chopScratches, r -> r.extensionPct);
        printMetricComparison("Candle 1 Range %", cleanWinners, whipsawed, savedFailures, chopScratches, r -> r.firstCandleRangePct);
        printMetricComparison("Vol to FCL Ratio", cleanWinners, whipsawed, savedFailures, chopScratches, r -> r.volToFirstCandleRatio);
        printMetricComparison("Stock VWAP Dist %", cleanWinners, whipsawed, savedFailures, chopScratches, r -> r.stockVwapDistPct);
        System.out.println("==================================================================================================================================");
    }

    @Test
    @DisplayName("Diagnose Net P&L Sensitivity across Capital Sizes, Macro Gates, and Execution Factors")
    void diagnoseNetPnlSolutions() {
        int symbolLimit = 1000;
        FchbStrategy strategy = new FchbStrategy();
        FchbPositionHandler positionHandler = new FchbPositionHandler();

        try (DatabaseMarketReplayLoader loader = new DatabaseMarketReplayLoader()) {
            List<LocalDate> dates = loader.getAvailableTradingDates(null, null);
            List<String> symbols = loader.getTopStockUniverse(symbolLimit);

            double[] capitalTiers = {50000.0, 100000.0, 150000.0, 200000.0};

            System.out.println("\n==================================================================================================");
            System.out.println("                 CAPITAL SCALING SENSITIVITY TEST (FCHB STRATEGY)");
            System.out.println("==================================================================================================");

            for (double cap : capitalTiers) {
                StrategyPerformanceReplayRunner runner = new StrategyPerformanceReplayRunner(cap);
                double currentCap = cap;
                double totalGross = 0.0;
                double totalCharges = 0.0;
                double totalNet = 0.0;
                int totalTrades = 0;
                int totalWins = 0;

                for (LocalDate date : dates) {
                    HistoricalMarketDay day = loader.loadSingleMarketDay(date, symbols);
                    DailyReplayReport dayReport = runner.replayDay(strategy, positionHandler, day, currentCap);
                    currentCap = dayReport.getEndingCapital();
                    totalGross += dayReport.getGrossPnl();
                    totalCharges += dayReport.getTotalCharges();
                    totalNet += dayReport.getNetPnl();
                    totalTrades += dayReport.getExecutedTrades();
                    totalWins += dayReport.getWinTrades();
                }

                double winRate = totalTrades > 0 ? (totalWins * 100.0 / totalTrades) : 0.0;
                double returnPct = (totalNet / cap) * 100.0;
                System.out.printf("Capital: Rs. %,8.0f | Trades: %2d (Win: %4.1f%%) | Gross: Rs. %+8.1f | Fee: -Rs. %7.1f | Net: Rs. %+8.1f (%+5.2f%%)\n",
                        cap, totalTrades, winRate, totalGross, totalCharges, totalNet, returnPct);
            }
            System.out.println("==================================================================================================");
        }
    }

    @Test
    @DisplayName("Replay FCHB Strategy under Kotak Neo Zero-Brokerage vs Angel One across 23 Days")
    void replayUnderKotakNeoCharges() {
        int symbolLimit = 1000;
        FchbStrategy strategy = new FchbStrategy();
        FchbPositionHandler positionHandler = new FchbPositionHandler();

        try (DatabaseMarketReplayLoader loader = new DatabaseMarketReplayLoader()) {
            List<LocalDate> dates = loader.getAvailableTradingDates(null, null);
            List<String> symbols = loader.getTopStockUniverse(symbolLimit);

            double startingCap = 50000.0;
            BrokerChargesCalculator.BrokerModel[] models = {
                    BrokerChargesCalculator.BrokerModel.ANGEL_ONE,
                    BrokerChargesCalculator.BrokerModel.KOTAK_NEO
            };

            System.out.println("\n========================================================================================================================");
            System.out.println("                 FCHB STRATEGY PERFORMANCE: ANGEL ONE vs KOTAK NEO ZERO-BROKERAGE (Rs. 50,000 CAPITAL)");
            System.out.println("========================================================================================================================");

            for (BrokerChargesCalculator.BrokerModel model : models) {
                BrokerChargesCalculator.setActiveBroker(model);
                StrategyPerformanceReplayRunner runner = new StrategyPerformanceReplayRunner(startingCap);
                double currentCap = startingCap;
                double totalGross = 0.0;
                double totalCharges = 0.0;
                double totalNet = 0.0;
                int totalTrades = 0;
                int totalWins = 0;
                int totalLosses = 0;

                for (LocalDate date : dates) {
                    HistoricalMarketDay day = loader.loadSingleMarketDay(date, symbols);
                    DailyReplayReport dayReport = runner.replayDay(strategy, positionHandler, day, currentCap);
                    currentCap = dayReport.getEndingCapital();
                    totalGross += dayReport.getGrossPnl();
                    totalCharges += dayReport.getTotalCharges();
                    totalNet += dayReport.getNetPnl();
                    totalTrades += dayReport.getExecutedTrades();
                    totalWins += dayReport.getWinTrades();
                    totalLosses += dayReport.getLossTrades();
                }

                double winRate = totalTrades > 0 ? (totalWins * 100.0 / totalTrades) : 0.0;
                double returnPct = (totalNet / startingCap) * 100.0;
                System.out.printf("[%10s] Trades: %2d (Wins: %2d, Loss: %2d, WinRate: %4.1f%%) | Gross: Rs. %+8.2f | Total Fee: -Rs. %7.2f | Net: Rs. %+8.2f (%+5.2f%%) | EndCap: Rs. %,.2f\n",
                        model.name(), totalTrades, totalWins, totalLosses, winRate, totalGross, totalCharges, totalNet, returnPct, currentCap);
            }
            System.out.println("========================================================================================================================");
        } finally {
            BrokerChargesCalculator.setActiveBroker(BrokerChargesCalculator.BrokerModel.ANGEL_ONE);
        }
    }

    @Test
    @DisplayName("Comprehensive 23-Day Replay under Kotak Neo with 14:15 vs 14:30 vs 14:45 Wind-Down Audit")
    void runKotakNeoReplayWithWindDownPriceAudit() {
        int symbolLimit = 1000;
        FchbStrategy strategy = new FchbStrategy();
        FchbPositionHandler positionHandler = new FchbPositionHandler();

        try (DatabaseMarketReplayLoader loader = new DatabaseMarketReplayLoader()) {
            List<LocalDate> dates = loader.getAvailableTradingDates(null, null);
            List<String> symbols = loader.getTopStockUniverse(symbolLimit);

            double startingCap = 50000.0;
            BrokerChargesCalculator.setActiveBroker(BrokerChargesCalculator.BrokerModel.KOTAK_NEO);
            StrategyPerformanceReplayRunner runner = new StrategyPerformanceReplayRunner(startingCap);

            double currentCap = startingCap;
            double peakCap = startingCap;
            double maxDrawdown = 0.0;

            List<ReplayTrade> allTrades = new ArrayList<>();
            Map<ReplayTrade, HistoricalMarketDay> dayMap = new IdentityHashMap<>();

            System.out.println("\n========================================================================================================================");
            System.out.println("            23-DAY HISTORICAL MARKET REPLAY UNDER KOTAK NEO ZERO-BROKERAGE (STARTING CAPITAL Rs. 50,000)");
            System.out.println("========================================================================================================================");

            for (LocalDate date : dates) {
                HistoricalMarketDay day = loader.loadSingleMarketDay(date, symbols);
                DailyReplayReport dayReport = runner.replayDay(strategy, positionHandler, day, currentCap);
                currentCap = dayReport.getEndingCapital();
                if (currentCap > peakCap) peakCap = currentCap;
                double dd = peakCap - currentCap;
                if (dd > maxDrawdown) maxDrawdown = dd;

                for (ReplayTrade t : dayReport.getTrades()) {
                    allTrades.add(t);
                    dayMap.put(t, day);
                }

                System.out.printf("[%s] Executed: %d trades | Wins: %d | Gross: Rs. %+8.2f | Kotak Fees: -Rs. %6.2f | Net: Rs. %+8.2f | EndCap: Rs. %,.2f\n",
                        date, dayReport.getExecutedTrades(), dayReport.getWinTrades(),
                        dayReport.getGrossPnl(), dayReport.getTotalCharges(), dayReport.getNetPnl(), currentCap);

                for (ReplayTrade t : dayReport.getTrades()) {
                    System.out.printf("   • %-10s %s | En: %5s @ %7.2f (Qty:%3d) | SL: %7.2f | T1: %7.2f (Hit:%s) | Ex: %5s @ %7.2f | Reason: %-26s | Gross: Rs. %+7.2f | Fee: Rs. %5.2f | Net: Rs. %+7.2f\n",
                            t.getSymbol(), t.getDirection(),
                            t.getEntryTime() != null ? t.getEntryTime().toLocalTime() : "-",
                            t.getEntryPrice(),
                            t.getQuantity(),
                            t.getInitialStopLoss(),
                            t.getTarget1Price(),
                            t.isTarget1Hit() ? "YES" : " NO",
                            t.getFinalExitTime() != null ? t.getFinalExitTime().toLocalTime() : "-",
                            t.getFinalExitPrice() != null ? t.getFinalExitPrice() : 0.0,
                            t.getExitReason(),
                            t.getGrossPnl(),
                            t.getCharges(),
                            t.getNetPnl());
                }
            }

            // Overall Summary
            int totalTrades = allTrades.size();
            List<ReplayTrade> wins = allTrades.stream().filter(t -> t.getNetPnl() > 0).collect(Collectors.toList());
            List<ReplayTrade> losses = allTrades.stream().filter(t -> t.getNetPnl() <= 0).collect(Collectors.toList());
            double totalGross = allTrades.stream().mapToDouble(ReplayTrade::getGrossPnl).sum();
            double totalCharges = allTrades.stream().mapToDouble(ReplayTrade::getCharges).sum();
            double totalNet = allTrades.stream().mapToDouble(ReplayTrade::getNetPnl).sum();
            double winGross = wins.stream().mapToDouble(ReplayTrade::getGrossPnl).sum();
            double lossGross = Math.abs(losses.stream().mapToDouble(ReplayTrade::getGrossPnl).sum());
            double pf = lossGross > 0 ? (winGross / lossGross) : 99.9;

            System.out.println("\n========================================================================================================================");
            System.out.println("                                      OVERALL REPLAY PERFORMANCE AUDIT (KOTAK NEO)");
            System.out.println("========================================================================================================================");
            System.out.printf("Total Sessions Replayed  : %d days\n", dates.size());
            System.out.printf("Total Executed Trades    : %d (Avg %.1f trades/day)\n", totalTrades, (double) totalTrades / dates.size());
            System.out.printf("Winning Trades           : %d (%.1f%%)\n", wins.size(), totalTrades > 0 ? (wins.size() * 100.0 / totalTrades) : 0);
            System.out.printf("Losing Trades            : %d (%.1f%%)\n", losses.size(), totalTrades > 0 ? (losses.size() * 100.0 / totalTrades) : 0);
            System.out.printf("Total Gross P&L          : Rs. %+8.2f\n", totalGross);
            System.out.printf("Total Kotak Neo Charges  : -Rs. %8.2f (Avg Rs. %.2f/trade)\n", totalCharges, totalTrades > 0 ? (totalCharges / totalTrades) : 0);
            System.out.printf("Total Net Realized P&L   : Rs. %+8.2f (Return: %+5.2f%%)\n", totalNet, (totalNet / startingCap) * 100.0);
            System.out.printf("Ending Capital           : Rs. %,.2f\n", currentCap);
            System.out.printf("Profit Factor            : %.2f\n", pf);
            System.out.printf("Max Drawdown             : Rs. %.2f (%.2f%%)\n", maxDrawdown, (maxDrawdown / peakCap) * 100.0);

            // BREAKDOWN BY EXIT REASON
            System.out.println("\n--- BREAKDOWN BY EXIT REASON ---");
            Map<String, List<ReplayTrade>> byReason = allTrades.stream()
                    .collect(Collectors.groupingBy(ReplayTrade::getExitReason));
            byReason.forEach((reason, trades) -> {
                double gross = trades.stream().mapToDouble(ReplayTrade::getGrossPnl).sum();
                double charges = trades.stream().mapToDouble(ReplayTrade::getCharges).sum();
                double net = trades.stream().mapToDouble(ReplayTrade::getNetPnl).sum();
                long w = trades.stream().filter(t -> t.getNetPnl() > 0).count();
                System.out.printf("%-28s : %2d trades | Wins: %2d (%.0f%%) | Gross: %+7.1f | Fee: -%5.1f | Net: %+7.1f\n",
                        reason, trades.size(), w, (w * 100.0 / trades.size()), gross, charges, net);
            });

            // WIND-DOWN & LATE AFTERNOON ANALYSIS
            System.out.println("\n========================================================================================================================");
            System.out.println("                   WIND-DOWN EXIT FORENSIC PRICE COMPARISON: 14:15 vs 14:30 vs 14:45");
            System.out.println("========================================================================================================================");
            List<ReplayTrade> windDownTrades = allTrades.stream()
                    .filter(t -> t.getExitReason() != null && (t.getExitReason().contains("WIND_DOWN") || t.getExitReason().contains("HARD_CLOSE")))
                    .collect(Collectors.toList());

            System.out.printf("Found %d trades that exited via WIND_DOWN_EXIT / HARD_CLOSE.\n\n", windDownTrades.size());
            System.out.printf("%-11s | %-10s | %-7s | %-7s | %-4s | %-8s | %-8s | %-8s | %-9s | %-9s | %-9s | %-8s | %-16s\n",
                    "DATE", "SYMBOL", "ENTRY", "T1 HIT", "QTY", "P@14:15", "P@14:30", "P@14:45", "GROSS@1415", "GROSS@1430", "GROSS@1445", "1445-1415", "REASON");
            System.out.println("----------------------------------------------------------------------------------------------------------------------------------");

            double totalGrossAt1415 = 0.0;
            double totalGrossAt1430 = 0.0;
            double totalGrossAt1445 = 0.0;
            double totalNetAt1415 = 0.0;
            double totalNetAt1430 = 0.0;
            double totalNetAt1445 = 0.0;

            for (ReplayTrade t : windDownTrades) {
                HistoricalMarketDay day = dayMap.get(t);
                Double p1415 = getPriceAtTime(day, t.getSymbol(), LocalTime.of(14, 15));
                Double p1430 = getPriceAtTime(day, t.getSymbol(), LocalTime.of(14, 30));
                Double p1445 = getPriceAtTime(day, t.getSymbol(), LocalTime.of(14, 45));

                if (p1415 == null) p1415 = t.getEntryPrice();
                if (p1430 == null) p1430 = p1415;
                if (p1445 == null) p1445 = t.getFinalExitPrice() != null ? t.getFinalExitPrice() : p1430;

                double g1415 = computeTradeGrossPnl(t, p1415);
                double g1430 = computeTradeGrossPnl(t, p1430);
                double g1445 = computeTradeGrossPnl(t, p1445);

                double fee1415 = computeSimulatedCharges(t, p1415);
                double fee1430 = computeSimulatedCharges(t, p1430);
                double fee1445 = t.getCharges();

                totalGrossAt1415 += g1415;
                totalGrossAt1430 += g1430;
                totalGrossAt1445 += g1445;

                totalNetAt1415 += (g1415 - fee1415);
                totalNetAt1430 += (g1430 - fee1430);
                totalNetAt1445 += t.getNetPnl();

                double diff1445_1415 = g1445 - g1415;

                LocalDate tDate = t.getEntryTime() != null ? t.getEntryTime().toLocalDate() : null;
                System.out.printf("%-11s | %-10s | %7.2f | %-7s | %4d | %8.2f | %8.2f | %8.2f | %+9.2f | %+9.2f | %+9.2f | %+8.2f | %-16s\n",
                        tDate != null ? tDate.toString() : "-",
                        t.getSymbol(),
                        t.getEntryPrice(),
                        t.isTarget1Hit() ? "YES" : "NO",
                        t.getQuantity(),
                        p1415, p1430, p1445,
                        g1415, g1430, g1445,
                        diff1445_1415,
                        t.getExitReason());
            }

            System.out.println("----------------------------------------------------------------------------------------------------------------------------------");
            System.out.printf("%-46s | %+9.2f | %+9.2f | %+9.2f | %+8.2f |\n",
                    "TOTAL GROSS P&L ACROSS WIND-DOWN TRADES",
                    totalGrossAt1415, totalGrossAt1430, totalGrossAt1445, (totalGrossAt1445 - totalGrossAt1415));
            System.out.printf("%-46s | %+9.2f | %+9.2f | %+9.2f | %+8.2f |\n",
                    "TOTAL NET P&L ACROSS WIND-DOWN TRADES",
                    totalNetAt1415, totalNetAt1430, totalNetAt1445, (totalNetAt1445 - totalNetAt1415));
            System.out.println("========================================================================================================================");
            System.out.printf("Wind-Down Cumulative Return Delta (14:45 vs 14:15): Gross: Rs. %+8.2f | Net: Rs. %+8.2f\n",
                    (totalGrossAt1445 - totalGrossAt1415), (totalNetAt1445 - totalNetAt1415));
            System.out.printf("Wind-Down Cumulative Return Delta (14:45 vs 14:30): Gross: Rs. %+8.2f | Net: Rs. %+8.2f\n",
                    (totalGrossAt1445 - totalGrossAt1430), (totalNetAt1445 - totalNetAt1430));
            System.out.println("========================================================================================================================");

        } finally {
            BrokerChargesCalculator.setActiveBroker(BrokerChargesCalculator.BrokerModel.ANGEL_ONE);
        }
    }

    private double computeTradeGrossPnl(ReplayTrade t, double currentPrice) {
        if (t.isTarget1Hit()) {
            double t1Pnl = (t.getTarget1ExitPrice() - t.getEntryPrice()) * t.getTarget1Quantity();
            double runnerPnl = (currentPrice - t.getEntryPrice()) * t.getFinalExitQuantity();
            return t1Pnl + runnerPnl;
        } else {
            return (currentPrice - t.getEntryPrice()) * t.getQuantity();
        }
    }

    private double computeSimulatedCharges(ReplayTrade t, double currentPrice) {
        double buyTurnover = t.getEntryPrice() * t.getQuantity();
        double sellTurnover;
        if (t.isTarget1Hit()) {
            sellTurnover = (t.getTarget1ExitPrice() * t.getTarget1Quantity()) + (currentPrice * t.getFinalExitQuantity());
        } else {
            sellTurnover = currentPrice * t.getQuantity();
        }
        return BrokerChargesCalculator.calculateRoundTripCharges(buyTurnover, sellTurnover);
    }

    private Double getPriceAtTime(HistoricalMarketDay day, String symbol, LocalTime targetTime) {
        if (day == null) return null;
        MarketCandle candle = day.get1mCandle(symbol, targetTime);
        if (candle != null && candle.getClosePrice() != null) {
            return candle.getClosePrice();
        }
        if (day.getCandles1m() != null) {
            MarketCandle best = null;
            for (MarketCandle c : day.getCandles1m()) {
                if (c.getSymbol().equalsIgnoreCase(symbol) && !c.getCandleTime().toLocalTime().isAfter(targetTime)) {
                    if (best == null || c.getCandleTime().isAfter(best.getCandleTime())) {
                        best = c;
                    }
                }
            }
            if (best != null) {
                return best.getClosePrice();
            }
        }
        return null;
    }

    private void printMetricComparison(String name,
                                       List<ForensicTradeRecord> w,
                                       List<ForensicTradeRecord> whip,
                                       List<ForensicTradeRecord> f,
                                       List<ForensicTradeRecord> s,
                                       java.util.function.ToDoubleFunction<ForensicTradeRecord> mapper) {
        double avgW = w.stream().mapToDouble(mapper).average().orElse(0.0);
        double avgWhip = whip.stream().mapToDouble(mapper).average().orElse(0.0);
        double avgF = f.stream().mapToDouble(mapper).average().orElse(0.0);
        double avgS = s.stream().mapToDouble(mapper).average().orElse(0.0);

        System.out.printf("  • %-22s: Clean Winners=%.2f | Whipsaws=%.2f | Failures=%.2f | Chop=%.2f\n",
                name, avgW, avgWhip, avgF, avgS);
    }
}
