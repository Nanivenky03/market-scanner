package com.trading.scanner.strategy.replay;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregated multi-day financial, execution, and research report across all replay scenarios.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MultiDayReplayReport {
    private String strategyKey;
    private double initialCapital;
    private double finalCapital;
    private double totalGrossPnl;
    private double totalCharges;
    private double totalNetPnl;
    private double netReturnPct;

    private int totalTrades;
    private int totalWins;
    private int totalLosses;
    private double winRatePct;

    private double maxDrawdown;
    private double maxDrawdownPct;

    @Builder.Default
    private List<DailyReplayReport> dailyReports = new ArrayList<>();

    public DailyReplayReport getDayReport(String date) {
        return dailyReports.stream()
                .filter(d -> d.getTradingDate().equals(date))
                .findFirst()
                .orElse(null);
    }

    public void printFinancialLedger() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=========================================================================================================\n");
        sb.append("                           STRATEGY REAL-DATA PERFORMANCE REPLAY REPORT\n");
        sb.append("=========================================================================================================\n");
        sb.append(String.format("Strategy: %s\n", strategyKey));
        sb.append(String.format("Starting Capital: Rs. %,.2f\n", initialCapital));
        sb.append(String.format("Universe: 9 Liquid Heavyweights + NIFTY 50 (Authoritative 1M/5M/15M Production Data)\n"));
        sb.append("---------------------------------------------------------------------------------------------------------\n");
        sb.append(String.format("%-12s %-20s %-8s %-8s %-14s %-16s %-14s %-14s\n",
                "DATE", "REGIME", "TRADES", "W/L", "GROSS P&L", "CHARGES & TAXES", "NET P&L", "CAPITAL"));
        sb.append("---------------------------------------------------------------------------------------------------------\n");

        for (DailyReplayReport day : dailyReports) {
            String wl = String.format("%d/%d", day.getWinTrades(), day.getLossTrades());
            String grossSign = day.getGrossPnl() > 0 ? "+" : "";
            String netSign = day.getNetPnl() > 0 ? "+" : "";

            sb.append(String.format("%-12s %-20s %-8d %-8s %sRs. %,-13.2f -Rs. %,-15.2f %sRs. %,-13.2f Rs. %,-13.2f\n",
                    day.getTradingDate(),
                    day.getScenarioTag(),
                    day.getExecutedTrades(),
                    wl,
                    grossSign, day.getGrossPnl(),
                    day.getTotalCharges(),
                    netSign, day.getNetPnl(),
                    day.getEndingCapital()));
        }

        sb.append("---------------------------------------------------------------------------------------------------------\n");
        sb.append("FINAL FINANCIAL SUMMARY:\n");
        sb.append("---------------------------------------------------------------------------------------------------------\n");
        sb.append(String.format("  Starting Capital:         Rs. %,.2f\n", initialCapital));
        sb.append(String.format("  Ending Capital:           Rs. %,.2f\n", finalCapital));

        String grossTotalSign = totalGrossPnl > 0 ? "+" : "";
        sb.append(String.format("  Gross Realized P&L:      %sRs. %,.2f\n", grossTotalSign, totalGrossPnl));
        sb.append(String.format("  Total Charges & Taxes:     -Rs. %,.2f (Brokerage, STT, Exchange, SEBI, GST)\n", totalCharges));

        String netTotalSign = totalNetPnl > 0 ? "+" : "";
        sb.append(String.format("  Net Realized P&L:        %sRs. %,.2f (%s%.2f%% Net Return on Capital)\n",
                netTotalSign, totalNetPnl, netTotalSign, netReturnPct));
        sb.append(String.format("  Total Trades Executed:    %d\n", totalTrades));
        sb.append(String.format("  Win Rate:                 %.1f%% (%d Wins / %d Losses)\n",
                winRatePct, totalWins, totalLosses));
        sb.append(String.format("  Max Drawdown:             -Rs. %,.2f (-%.2f%%)\n", maxDrawdown, maxDrawdownPct));
        sb.append("=========================================================================================================\n");

        System.out.println(sb);
    }

    public void printDetailedTradeJournal() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=========================================================================================================\n");
        sb.append("                           DETAILED TRADE EXECUTION JOURNAL (AUDIT OF ALL ORDERS)\n");
        sb.append("=========================================================================================================\n");

        int count = 0;
        for (DailyReplayReport day : dailyReports) {
            for (ReplayTrade t : day.getTrades()) {
                count++;
                String outcome = t.isProfitable() ? "WIN (PROFIT)" : "LOSS (DEFENSE)";
                String sign = t.getNetPnl() >= 0 ? "+" : "";
                sb.append(String.format("#%02d | [%s %s] %-10s %s | Entry: Rs. %.2f | Qty: %d | Initial SL: Rs. %.2f | Target: Rs. %.2f\n",
                        count, day.getTradingDate(), t.getEntryTime().toLocalTime(), t.getSymbol(), t.getDirection(),
                        t.getEntryPrice(), t.getQuantity(), t.getInitialStopLoss(), t.getTarget1Price()));

                if (t.isTarget1Hit()) {
                    sb.append(String.format("     └─> Target 1 HIT at %s @ Rs. %.2f (Sold 50%%: %d shares) | Stop Ratcheted to Breakeven (Rs. %.2f)\n",
                            t.getTarget1ExitTime() != null ? t.getTarget1ExitTime().toLocalTime() : "--",
                            t.getTarget1ExitPrice() != null ? t.getTarget1ExitPrice() : 0.0,
                            t.getTarget1Quantity(), t.getEntryPrice()));
                }

                sb.append(String.format("     └─> Final Exit at %s @ Rs. %.2f (Qty: %d) | Reason: %s\n",
                        t.getFinalExitTime() != null ? t.getFinalExitTime().toLocalTime() : "--",
                        t.getFinalExitPrice() != null ? t.getFinalExitPrice() : 0.0,
                        t.getFinalExitQuantity(), t.getExitReason()));

                sb.append(String.format("     └─> Result: Gross P&L = Rs. %.2f | Taxes & Brokerage = -Rs. %.2f | Net Cash P&L = %sRs. %.2f [%s]\n\n",
                        t.getGrossPnl(), t.getCharges(), sign, t.getNetPnl(), outcome));
            }
        }

        if (count == 0) {
            sb.append("No trades were executed across the evaluated sessions.\n");
        }
        sb.append("=========================================================================================================\n");
        System.out.println(sb);
    }

    public void printRejectedSignalsAudit() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=========================================================================================================\n");
        sb.append("                           FILTERED & REJECTED SIGNALS AUDIT (WHY SETUPS WERE BLOCKED)\n");
        sb.append("=========================================================================================================\n");

        int count = 0;
        for (DailyReplayReport day : dailyReports) {
            if (!day.getRejectedSignals().isEmpty()) {
                sb.append(String.format("--- %s (%s) ---\n", day.getTradingDate(), day.getScenarioTag()));
                for (RejectedSignalAudit r : day.getRejectedSignals()) {
                    count++;
                    sb.append(String.format("  [%s] %-10s @ Rs. %-9.2f | Stage: %-14s | Filter: %-32s | Info: %s\n",
                            r.getTime(), r.getSymbol(), r.getPrice(), r.getRejectionStage(), r.getReason(), r.getDetails()));
                }
                sb.append("\n");
            }
        }

        if (count == 0) {
            sb.append("No candidate signals were rejected.\n");
        }
        sb.append("=========================================================================================================\n");
        System.out.println(sb);
    }
}
