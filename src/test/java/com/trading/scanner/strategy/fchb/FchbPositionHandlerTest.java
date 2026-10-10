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
                102.0, // target1Price (+1R = 100 + (100 - 98) = 102)
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
    @DisplayName("GIVEN 1M candle close < FCH WHEN evaluate THEN returns FULL_EXIT due to structural invalidation")
    void testStructuralInvalidation() {
        PositionSnapshot position = createDefaultPosition(); // FCH = 99.5, stop = 98.0
        LocalTime time = LocalTime.of(9, 45, 0);
        MarketCandle broken1m = createCandle(99.6, 99.8, 99.1, 99.2); // close 99.2 < FCH 99.5, LTP 99.2 > SL 98.0

        PositionAction action = handler.evaluatePosition(position, 99.2, broken1m, 1.0, time, Collections.emptyMap());

        assertThat(action.type()).isEqualTo(PositionAction.ActionType.FULL_EXIT);
        assertThat(action.reason()).isEqualTo("STRUCTURAL_INVALIDATION");
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
    @DisplayName("GIVEN LTP >= entry + 0.8R AND stop < entry WHEN evaluate THEN returns UPDATE_STOP to entryPrice")
    void testBreakevenRatchetTriggered() {
        PositionSnapshot position = createDefaultPosition(); // entry 100, target 102 -> 1R = 2.0 -> 0.8R = 1.6 -> trigger = 101.6
        LocalTime time = LocalTime.of(10, 0, 0);
        MarketCandle candle1m = createCandle(101.2, 101.8, 101.1, 101.7); // close > FCH

        PositionAction action = handler.evaluatePosition(position, 101.65, candle1m, 1.0, time, Collections.emptyMap());

        assertThat(action.type()).isEqualTo(PositionAction.ActionType.UPDATE_STOP);
        assertThat(action.newStopLoss()).isEqualTo(100.0);
        assertThat(action.reason()).isEqualTo("BREAKEVEN_0_8R_TRIGGERED");
    }

    @Test
    @DisplayName("GIVEN stop already >= entry WHEN evaluate breakeven condition THEN holds")
    void testBreakevenNotTriggeredIfStopAlreadyAtOrAboveEntry() {
        PositionSnapshot position = new PositionSnapshot(
                1, 2, "RELIANCE", "NSE", "LONG", 100, 100,
                100.0, 100.0, 102.0, 99.5, false, LocalDateTime.of(2026, 10, 9, 9, 35)
        );
        LocalTime time = LocalTime.of(10, 0, 0);
        MarketCandle candle1m = createCandle(101.2, 101.8, 101.1, 101.7);

        PositionAction action = handler.evaluatePosition(position, 101.65, candle1m, 1.0, time, Collections.emptyMap());

        assertThat(action.type()).isEqualTo(PositionAction.ActionType.HOLD);
    }

    @Test
    @DisplayName("GIVEN target 1 booked AND candidate ATR stop > currentStopLoss WHEN evaluate THEN ratchets stop")
    void testAtrTrailingStopRatchetUp() {
        PositionSnapshot position = new PositionSnapshot(
                1, 2, "RELIANCE", "NSE", "LONG", 100, 50,
                100.0, 100.0, 102.0, 99.5, true, LocalDateTime.of(2026, 10, 9, 9, 35)
        );
        LocalTime time = LocalTime.of(10, 30, 0);
        // candle low = 102.5, 5m ATR = 1.0 -> candidate stop = 102.5 - (0.5 * 1.0) = 102.0 > current SL 100.0
        MarketCandle candle1m = createCandle(102.6, 103.0, 102.5, 102.8);

        PositionAction action = handler.evaluatePosition(position, 102.8, candle1m, 1.0, time, Collections.emptyMap());

        assertThat(action.type()).isEqualTo(PositionAction.ActionType.UPDATE_STOP);
        assertThat(action.newStopLoss()).isEqualTo(102.0);
        assertThat(action.reason()).isEqualTo("ATR_TRAILING_STOP_RATCHET");
    }

    @Test
    @DisplayName("GIVEN target 1 booked AND candidate ATR stop <= currentStopLoss WHEN evaluate THEN holds (never lowers stop)")
    void testAtrTrailingStopNeverLowersStop() {
        PositionSnapshot position = new PositionSnapshot(
                1, 2, "RELIANCE", "NSE", "LONG", 100, 50,
                100.0, 102.2, 102.0, 99.5, true, LocalDateTime.of(2026, 10, 9, 9, 35)
        );
        LocalTime time = LocalTime.of(10, 30, 0);
        // candle low = 102.5, 5m ATR = 1.0 -> candidate stop = 102.5 - 0.5 = 102.0 <= current SL 102.2
        MarketCandle candle1m = createCandle(102.6, 103.0, 102.5, 102.8);

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
