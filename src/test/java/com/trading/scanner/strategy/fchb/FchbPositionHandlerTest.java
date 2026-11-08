package com.trading.scanner.strategy.fchb;

import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.strategy.model.PositionAction;
import com.trading.scanner.strategy.model.PositionSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class FchbPositionHandlerTest {

    private FchbPositionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new FchbPositionHandler();
    }

    private MarketCandle createCandle(double o, double h, double l, double c) {
        return MarketCandle.builder()
                .candleTime(LocalDateTime.of(2026, 10, 9, 10, 0))
                .openPrice(o)
                .highPrice(h)
                .lowPrice(l)
                .closePrice(c)
                .volume(1000L)
                .build();
    }

    private PositionSnapshot createDefaultPosition() {
        return new PositionSnapshot(
                1,
                2,
                "RELIANCE",
                "NSE",
                "LONG",
                100,
                100,
                100.0, // entryPrice
                98.0,  // currentStopLoss
                com.trading.scanner.util.PriceTickUtil.roundTarget(100.0 + (FchbStrategy.TARGET_1_R_MULTIPLE * (100.0 - 98.0)), false, 0.05), // target1Price (+0.73R = 101.45)
                99.5,  // firstCandleHigh
                false, // target1Booked
                LocalDateTime.of(2026, 10, 9, 9, 35)
        );
    }

    @Test
    @DisplayName("GIVEN time >= 15:00:00 WHEN evaluate THEN returns FULL_EXIT due to hard close")
    void testHardCloseTimeCutoff() {
        PositionSnapshot position = createDefaultPosition();
        LocalTime time = LocalTime.of(15, 0, 0);

        PositionAction action = handler.evaluatePosition(position, 101.0, createCandle(100.5, 101.2, 100.4, 101.0), 1.0, time, Collections.emptyMap());

        assertThat(action.type()).isEqualTo(PositionAction.ActionType.FULL_EXIT);
        assertThat(action.reason()).isEqualTo("HARD_CLOSE_TIME_CUTOFF");
        assertThat(action.exitQuantityFraction()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("GIVEN time >= 14:45:00 AND stop < entry WHEN evaluate THEN returns FULL_EXIT due to wind down")
    void testWindDownExitWhenStopBelowEntry() {
        PositionSnapshot position = createDefaultPosition(); // stop = 98.0 < entry = 100.0
        LocalTime time = LocalTime.of(14, 45, 0);

        PositionAction action = handler.evaluatePosition(position, 100.5, createCandle(100.2, 100.8, 100.1, 100.5), 1.0, time, Collections.emptyMap());

        assertThat(action.type()).isEqualTo(PositionAction.ActionType.FULL_EXIT);
        assertThat(action.reason()).isEqualTo("WIND_DOWN_EXIT");
    }

    @Test
    @DisplayName("GIVEN time >= 14:45:00 AND stop >= entry WHEN evaluate THEN holds/trails position")
    void testWindDownHoldWhenStopAtOrAboveEntry() {
        PositionSnapshot position = new PositionSnapshot(
                1, 2, "RELIANCE", "NSE", "LONG", 100, 50,
                100.0, 100.5, 102.0, 99.5, true, LocalDateTime.of(2026, 10, 9, 9, 35)
        );
        LocalTime time = LocalTime.of(14, 50, 0);

        // candidateStop: 101.0 - (0.5 * 1.0) = 100.5 (equals current stop loss, so hold)
        PositionAction action = handler.evaluatePosition(position, 101.2, createCandle(101.1, 101.5, 101.0, 101.2), 1.0, time, Collections.emptyMap());

        assertThat(action.type()).isEqualTo(PositionAction.ActionType.HOLD);
    }

    @Test
    @DisplayName("GIVEN LTP <= currentStopLoss WHEN evaluate THEN returns FULL_EXIT due to stop loss hit")
    void testStopLossHit() {
        PositionSnapshot position = createDefaultPosition(); // stop = 98.0
        LocalTime time = LocalTime.of(10, 15, 0);

        PositionAction action = handler.evaluatePosition(position, 97.9, createCandle(98.2, 98.5, 97.8, 97.9), 1.0, time, Collections.emptyMap());

        assertThat(action.type()).isEqualTo(PositionAction.ActionType.FULL_EXIT);
        assertThat(action.reason()).isEqualTo("STOP_LOSS_HIT");
    }

    @Test
    @DisplayName("GIVEN 1M candle close < FCH AND LTP > stopLoss WHEN evaluate THEN holds position (retest allowed)")
    void testBreakoutRetestHoldingWhenAboveStopLoss() {
        PositionSnapshot position = createDefaultPosition(); // FCH = 99.5, stop = 98.0
        LocalTime time = LocalTime.of(9, 44, 0); // 1M intermediate candle (not 5M close boundary)
        MarketCandle broken1m = createCandle(99.6, 99.8, 99.1, 99.2); // close 99.2 < FCH 99.5, LTP 99.2 > SL 98.0

        PositionAction action = handler.evaluatePosition(position, 99.2, broken1m, 1.0, time, Collections.emptyMap());

        assertThat(action.type()).isEqualTo(PositionAction.ActionType.HOLD);
    }

    @Test
    @DisplayName("GIVEN 5M candle close < FCH AND drawdown >= 0.50R WHEN evaluate THEN returns FULL_EXIT FAILED_BREAKOUT_EXIT")
    void testFailedBreakout5mCloseExit() {
        PositionSnapshot position = createDefaultPosition(); // FCH = 99.5, entry = 100.0, target1 = 101.45
        LocalTime time = LocalTime.of(9, 45, 0); // 5M close boundary
        MarketCandle broken5m = createCandle(99.6, 99.8, 98.7, 98.8); // close 98.8 < FCH 99.5 AND <= entry - 0.50R (99.0)

        PositionAction action = handler.evaluatePosition(position, 98.8, broken5m, 1.0, time, Collections.emptyMap());

        assertThat(action.type()).isEqualTo(PositionAction.ActionType.FULL_EXIT);
        assertThat(action.reason()).isEqualTo("FAILED_BREAKOUT_EXIT");
    }

    @Test
    @DisplayName("GIVEN 5M candle close < stock VWAP AND drawdown >= 0.50R WHEN evaluate THEN returns FULL_EXIT VWAP_BREAKDOWN_EXIT")
    void testVwapBreakdown5mCloseExit() {
        // FCH = 98.0 (price is still above FCH, so failed breakout does not trigger), entry = 100.0, SL = 98.0
        PositionSnapshot position = new PositionSnapshot(
                1, 2, "RELIANCE", "NSE", "LONG", 100, 100,
                100.0, 98.0, 101.45, 98.0, false, LocalDateTime.of(2026, 10, 9, 9, 35)
        );
        LocalTime time = LocalTime.of(9, 50, 0); // 5M close boundary
        MarketCandle vwapBroken5m = MarketCandle.builder()
                .candleTime(LocalDateTime.of(2026, 10, 28, 9, 50, 0))
                .openPrice(100.2).highPrice(100.5).lowPrice(98.8).closePrice(98.9) // close 98.9 <= entry - 0.50R (99.0) and > FCH 98.0
                .vwap(100.1) // close 98.9 < vwap 100.1
                .build();

        PositionAction action = handler.evaluatePosition(position, 98.9, vwapBroken5m, 1.0, time, Collections.emptyMap());

        assertThat(action.type()).isEqualTo(PositionAction.ActionType.FULL_EXIT);
        assertThat(action.reason()).isEqualTo("VWAP_BREAKDOWN_EXIT");
    }

    @Test
    @DisplayName("GIVEN trade duration >= 45 minutes AND underwater WHEN evaluate THEN holds position (no elapsed minute dump)")
    void testNoElapsedMinuteStagnationUnderwaterDump() {
        // Entered at 09:35, evaluating at 10:20 (45 min duration), price is 99.4 > SL 98.0
        PositionSnapshot position = createDefaultPosition(); // entry = 100.0, stop = 98.0
        LocalTime time = LocalTime.of(10, 20, 0);
        MarketCandle candle = createCandle(99.8, 100.0, 99.3, 99.4);

        PositionAction action = handler.evaluatePosition(position, 99.4, candle, 1.0, time, Collections.emptyMap());

        assertThat(action.type()).isEqualTo(PositionAction.ActionType.HOLD);
    }

    @Test
    @DisplayName("GIVEN time == 11:30 AM AND in profit WHEN evaluate THEN does not force breakeven stop ratchet")
    void testNoElapsedMinuteBreakevenRatchet() {
        PositionSnapshot position = createDefaultPosition(); // entry = 100.0, stop = 98.0
        LocalTime time = LocalTime.of(11, 30, 0);
        MarketCandle candle = createCandle(100.1, 100.6, 100.1, 100.4);

        PositionAction action = handler.evaluatePosition(position, 100.4, candle, 1.0, time, Collections.emptyMap());

        // Should NOT force stop to breakeven (100.0) with STAGNATION_BREAKEVEN_LOCK
        assertThat(action.reason()).isNotEqualTo("STAGNATION_BREAKEVEN_LOCK");
        if (action.type() == PositionAction.ActionType.UPDATE_STOP) {
            assertThat(action.newStopLoss()).isLessThan(position.entryPrice());
        }
    }

    @Test
    @DisplayName("GIVEN target 1 not booked AND LTP >= target1Price WHEN evaluate THEN returns PARTIAL_EXIT 0.5 AND moves stop to at least entryPrice")
    void testTarget1HitPartialExit() {
        PositionSnapshot position = createDefaultPosition(); // entry = 100, target1 = 102, stop = 98.0
        LocalTime time = LocalTime.of(10, 0, 0);
        MarketCandle candle1m = createCandle(101.5, 102.2, 101.4, 102.1); // close 102.1 > FCH 99.5

        PositionAction action = handler.evaluatePosition(position, 102.05, candle1m, 1.0, time, Collections.emptyMap());

        assertThat(action.type()).isEqualTo(PositionAction.ActionType.PARTIAL_EXIT);
        assertThat(action.exitQuantityFraction()).isEqualTo(0.5);
        assertThat(action.newStopLoss()).isEqualTo(100.0); // Stop moved to entry price immediately
        assertThat(action.reason()).isEqualTo("TARGET_1_HIT");
    }

    @Test
    @DisplayName("GIVEN target 1 not booked AND peak advances WHEN evaluate THEN pre-T1 dynamic trail ratchets stop reducing risk")
    void testPreTarget1DynamicTrailReducesRisk() {
        PositionSnapshot position = createDefaultPosition(); // entry = 100.0, stop = 98.0, 1R = 2.0
        LocalTime time = LocalTime.of(9, 45, 0);
        MarketCandle candle1m = createCandle(100.5, 101.0, 100.4, 100.9); // peak = 101.0 (+0.50R)

        PositionAction action = handler.evaluatePosition(position, 100.9, candle1m, 1.0, time, Collections.emptyMap());

        assertThat(action.type()).isEqualTo(PositionAction.ActionType.UPDATE_STOP);
        // peak 101.0 - 1.0R (with ceiled T1 risk ~2.05) -> floored stop = 98.90
        assertThat(action.newStopLoss()).isEqualTo(98.90);
        assertThat(action.reason()).isEqualTo("DYNAMIC_TRAILING_STOP_RATCHET");
    }

    @Test
    @DisplayName("GIVEN target 1 booked AND peak advances WHEN evaluate THEN runner dynamic trail ratchets stop")
    void testRunnerDynamicTrailRatchet() {
        PositionSnapshot position = new PositionSnapshot(
                1, 2, "RELIANCE", "NSE", "LONG", 100, 50,
                100.0, 100.0, 101.46, 99.5, true, LocalDateTime.of(2026, 10, 9, 9, 35)
        );
        LocalTime time = LocalTime.of(10, 0, 0);
        MarketCandle candle1m = createCandle(102.5, 103.0, 102.4, 102.8); // peak = 103.0

        PositionAction action = handler.evaluatePosition(position, 102.8, candle1m, 2.0, time, Collections.emptyMap());

        assertThat(action.type()).isEqualTo(PositionAction.ActionType.UPDATE_STOP);
        // peak 103.0 - (0.73 * 2.0) = 101.54 -> rounded stop = 101.50
        assertThat(action.newStopLoss()).isEqualTo(101.50);
        assertThat(action.reason()).isEqualTo("RUNNER_PROFIT_LOCK_RATCHET");
    }

    @Test
    @DisplayName("GIVEN target 1 booked AND candidate stop <= currentStopLoss WHEN evaluate THEN holds (never lowers stop)")
    void testRunnerDynamicTrailNeverLowersStop() {
        PositionSnapshot position = new PositionSnapshot(
                1, 2, "RELIANCE", "NSE", "LONG", 100, 50,
                100.0, 102.0, 101.46, 99.5, true, LocalDateTime.of(2026, 10, 9, 9, 35)
        );
        LocalTime time = LocalTime.of(10, 30, 0);
        MarketCandle candle1m = createCandle(102.6, 103.0, 102.5, 102.8); // peak = 103.0 -> candidate = 101.50 <= 102.0

        PositionAction action = handler.evaluatePosition(position, 102.8, candle1m, 1.0, time, Collections.emptyMap());

        assertThat(action.type()).isEqualTo(PositionAction.ActionType.HOLD);
    }

    @Test
    @DisplayName("GIVEN null input parameters WHEN evaluate THEN returns hold safely")
    void testNullInputsFailSafe() {
        PositionAction action = handler.evaluatePosition(null, null, null, null, null, Collections.emptyMap());

        assertThat(action.type()).isEqualTo(PositionAction.ActionType.HOLD);
    }
}
