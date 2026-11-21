package com.trading.scanner.strategy.parity;

import com.trading.scanner.risk.BrokerChargesCalculator;
import com.trading.scanner.risk.BrokerChargesCalculator.BrokerModel;
import com.trading.scanner.strategy.fchb.FchbPositionHandler;
import com.trading.scanner.strategy.fchb.FchbStrategy;
import com.trading.scanner.strategy.replay.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Parity Test 2.5(c): Determinism Verification.
 * Proves that running the clean baseline twice produces 100% identical output hashes.
 */
class ReplayDeterminismTest {

    @Test
    @DisplayName("Executes two identical replay passes and verifies SHA-256 output hash match")
    void testReplayDeterminismAcrossTwoRuns() throws Exception {
        com.trading.scanner.risk.BrokerChargesCalculator.setActiveBroker(
                com.trading.scanner.risk.BrokerChargesCalculator.BrokerModel.KOTAK_NEO);

        MarketReplayFixtureLoader loader = new MarketReplayFixtureLoader();
        List<HistoricalMarketDay> daysRun1 = loader.loadAllDays();
        List<HistoricalMarketDay> daysRun2 = loader.loadAllDays();

        assertFalse(daysRun1.isEmpty(), "Fixture dataset must contain days");

        FchbStrategy strategy = new FchbStrategy();
        FchbPositionHandler handler = new FchbPositionHandler();

        // RUN 1
        StrategyPerformanceReplayRunner runner1 = new StrategyPerformanceReplayRunner(50000.0);
        MultiDayReplayReport report1 = runner1.replayAllDays(strategy, handler);

        // RUN 2
        StrategyPerformanceReplayRunner runner2 = new StrategyPerformanceReplayRunner(50000.0);
        MultiDayReplayReport report2 = runner2.replayAllDays(strategy, handler);

        // Compute SHA-256 hash of all trade parameters
        String hash1 = computeTradeListHash(report1);
        String hash2 = computeTradeListHash(report2);

        assertEquals(report1.getTotalTrades(), report2.getTotalTrades(), "Total trades must be identical");
        assertEquals(report1.getTotalGrossPnl(), report2.getTotalGrossPnl(), 0.0001, "Gross PnL must be identical");
        assertEquals(report1.getTotalNetPnl(), report2.getTotalNetPnl(), 0.0001, "Net PnL must be identical");
        assertEquals(hash1, hash2, "SHA-256 hash of complete trade outputs must be 100% identical");
    }

    private String computeTradeListHash(MultiDayReplayReport report) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        StringBuilder sb = new StringBuilder();
        for (DailyReplayReport day : report.getDailyReports()) {
            for (ReplayTrade t : day.getTrades()) {
                sb.append(t.getTradeId()).append("|")
                        .append(t.getSymbol()).append("|")
                        .append(t.getEntryTime()).append("|")
                        .append(t.getEntryPrice()).append("|")
                        .append(t.getQuantity()).append("|")
                        .append(t.getFinalExitPrice()).append("|")
                        .append(t.getFinalExitTime()).append("|")
                        .append(t.getGrossPnl()).append("|")
                        .append(t.getNetPnl()).append("|")
                        .append(t.getCharges()).append(";");
            }
        }
        byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder hexString = new StringBuilder();
        for (byte b : digest) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
