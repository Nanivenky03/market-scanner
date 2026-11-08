package com.trading.scanner.strategy.fchb;

import com.trading.scanner.strategy.replay.DailyReplayReport;
import com.trading.scanner.strategy.replay.DatabaseMarketReplayLoader;
import com.trading.scanner.strategy.replay.HistoricalMarketDay;
import com.trading.scanner.strategy.replay.MultiDayReplayReport;
import com.trading.scanner.strategy.replay.StrategyPerformanceReplayRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

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
