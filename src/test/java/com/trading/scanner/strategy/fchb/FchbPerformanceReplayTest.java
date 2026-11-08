package com.trading.scanner.strategy.fchb;

import com.trading.scanner.strategy.replay.MultiDayReplayReport;
import com.trading.scanner.strategy.replay.StrategyPerformanceReplayRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Strategy Performance Replay Runner for FCHB Strategy across 8 Real Market Regimes.
 * Excluded from standard 'mvn clean test' so it is only executed on-demand for strategy evaluation.
 *
 * To execute specifically:
 *   mvn test -Dtest=FchbPerformanceReplayTest
 */
@Tag("replay")
class FchbPerformanceReplayTest {

    @Test
    @DisplayName("Replay FCHB Strategy across All 8 Real Market Regimes with Production Services")
    void testFchbAcrossAllMarketRegimes() {
        FchbStrategy strategy = new FchbStrategy();
        FchbPositionHandler positionHandler = new FchbPositionHandler();

        // Initialize replay with starting capital of Rs. 30,000
        StrategyPerformanceReplayRunner runner = new StrategyPerformanceReplayRunner(30000.0);

        // Run full multi-day replay across all 8 market days using production PositionManagerService
        MultiDayReplayReport report = runner.replayAllDays(strategy, positionHandler);

        // Print the complete formatted ledger, trade journal, and rejected signals audit
        report.printFinancialLedger();
        report.printDetailedTradeJournal();
        report.printRejectedSignalsAudit();

        // Basic sanity assertions
        assertThat(report.getDailyReports()).hasSize(8);
        assertThat(report.getFinalCapital()).isGreaterThan(0.0);
        assertThat(report.getInitialCapital()).isEqualTo(30000.0);
    }
}
