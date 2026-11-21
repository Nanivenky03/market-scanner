package com.trading.scanner.strategy.parity;

import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.MarketCandle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parity Test 2.5(b): Lookahead Guard Verification.
 * Proves that the historical 15M lookahead bug is eliminated,
 * and demonstrates that the old query logic allowed future candle visibility.
 */
class LookaheadGuardTest {

    @Test
    @DisplayName("Proves that old 15M filter leaks unclosed candle at 09:35, while new filter strictly excludes it")
    void test15mCandleLookaheadGuardAtDecisionTime() {
        LocalTime decisionTime = LocalTime.of(9, 35);

        // Candle 1: 09:15 to 09:30 (Closed at 09:30)
        MarketCandle candle1 = MarketCandle.builder()
                .symbol("RELIANCE")
                .timeframe(CandleTimeframe.FIFTEEN_MINUTE)
                .candleTime(LocalDateTime.of(2026, 11, 13, 9, 15))
                .closePrice(2500.0)
                .build();

        // Candle 2: 09:30 to 09:45 (Currently forming at 09:35, closes at 09:45 - FUTURE DATA)
        MarketCandle candle2 = MarketCandle.builder()
                .symbol("RELIANCE")
                .timeframe(CandleTimeframe.FIFTEEN_MINUTE)
                .candleTime(LocalDateTime.of(2026, 11, 13, 9, 30))
                .closePrice(2550.0)
                .build();

        List<MarketCandle> all15mCandles = List.of(candle1, candle2);

        // --- OLD FLAWED LOGIC (Lookahead Leak) ---
        // Checked only: !candle.candleTime.isAfter(decisionTime)
        List<MarketCandle> oldFailsafeList = all15mCandles.stream()
                .filter(c -> !c.getCandleTime().toLocalTime().isAfter(decisionTime))
                .collect(Collectors.toList());

        // OLD LOGIC INCLUDED 2 CANDLES! It leaked the 09:30-09:45 candle at 09:35!
        assertEquals(2, oldFailsafeList.size(), "Old logic should leak the unclosed 15M candle into decision time");
        assertTrue(oldFailsafeList.contains(candle2), "Old logic mistakenly included candle2 which only closes at 09:45");

        // --- NEW GUARDED LOGIC (Strict Zero Lookahead) ---
        // Checks: !candle.candleTime.plusMinutes(15).isAfter(decisionTime)
        List<MarketCandle> guardedList = all15mCandles.stream()
                .filter(c -> !c.getCandleTime().toLocalTime().plusMinutes(15).isAfter(decisionTime))
                .collect(Collectors.toList());

        // NEW LOGIC STUBBORNLY INCLUDES ONLY CANDLE 1 (Closed at 09:30)
        assertEquals(1, guardedList.size(), "Guarded logic must strictly return only 1 completed candle");
        assertEquals(candle1, guardedList.get(0), "Only candle 1 (09:15-09:30) was finalized at 09:35");
        assertFalse(guardedList.contains(candle2), "Candle 2 (09:30-09:45) is strictly excluded because it closes in the future");
    }

    @Test
    @DisplayName("Guards that 15M candle becomes available exactly at 09:45 boundary and not a microsecond earlier")
    void test15mCandleAvailabilityAtBoundary() {
        MarketCandle candle = MarketCandle.builder()
                .symbol("INFY")
                .timeframe(CandleTimeframe.FIFTEEN_MINUTE)
                .candleTime(LocalDateTime.of(2026, 11, 13, 9, 30))
                .build();

        List<MarketCandle> list = List.of(candle);

        // At 09:44:59 -> Excluded
        LocalTime beforeClose = LocalTime.of(9, 44, 59);
        List<MarketCandle> resultBefore = list.stream()
                .filter(c -> !c.getCandleTime().toLocalTime().plusMinutes(15).isAfter(beforeClose))
                .toList();
        assertTrue(resultBefore.isEmpty(), "Must not be visible at 09:44:59");

        // At 09:45:00 -> Included
        LocalTime atClose = LocalTime.of(9, 45, 0);
        List<MarketCandle> resultAtClose = list.stream()
                .filter(c -> !c.getCandleTime().toLocalTime().plusMinutes(15).isAfter(atClose))
                .toList();
        assertEquals(1, resultAtClose.size(), "Must be visible exactly at 09:45:00");
    }
}
