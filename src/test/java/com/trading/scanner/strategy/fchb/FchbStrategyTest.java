package com.trading.scanner.strategy.fchb;

import com.trading.scanner.model.DailyStockContext;
import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.strategy.model.MarketState;
import com.trading.scanner.strategy.model.StockContext;
import com.trading.scanner.strategy.model.StrategyEvaluation;
import com.trading.scanner.strategy.model.StrategyProposal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FchbStrategyTest {

    private FchbStrategy strategy;
    private MarketState defaultMarketState;

    @BeforeEach
    void setUp() {
        strategy = new FchbStrategy();
        defaultMarketState = new MarketState(
                LocalDate.of(2026, 10, 9),
                LocalDateTime.of(2026, 10, 9, 9, 35),
                25000.0,
                24900.0,
                0.5,
                true,
                MarketState.MarketRegime.BULLISH,
                true,
                "OK"
        );
    }

    private MarketCandle createCandle(LocalDateTime time, double o, double h, double l, double c, double vwap, double rsi) {
        return MarketCandle.builder()
                .candleTime(time)
                .openPrice(o)
                .highPrice(h)
                .lowPrice(l)
                .closePrice(c)
                .vwap(vwap)
                .rsi14(rsi)
                .volume(50000L)
                .build();
    }

    private DailyStockContext createDefaultDailyContext() {
        return DailyStockContext.builder()
                .symbol("RELIANCE")
                .exchange("NSE")
                .tradingDate(LocalDate.of(2026, 10, 9))
                .skipToday(false)
                .gapPct(0.5)
                .prevDayRangePct(2.0)
                .distFromResistancePct(2.5)
                .firstCandleOpen(100.0)
                .firstCandleHigh(101.0)
                .firstCandleLow(99.5)
                .firstCandleClose(100.8)
                .build();
    }

    private StockContext createValidContext(DailyStockContext daily, MarketCandle current5m, MarketCandle current15m, Double volX) {
        return new StockContext(
                "RELIANCE",
                "NSE",
                LocalDate.of(2026, 10, 9),
                daily,
                Collections.emptyList(),
                List.of(current5m),
                List.of(current15m),
                false,
                volX
        );
    }

    @Test
    @DisplayName("GIVEN all green conditions WHEN evaluate THEN returns QUALIFIED evaluation with score 100")
    void testAllGreenConditions() {
        DailyStockContext daily = createDefaultDailyContext();
        MarketCandle current5m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 35), 101.0, 101.35, 100.9, 101.3, 100.9, 62.0);
        MarketCandle current15m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 30), 100.5, 101.4, 100.4, 101.2, 100.8, 60.0);
        StockContext context = createValidContext(daily, current5m, current15m, 2.8);

        StrategyEvaluation evaluation = strategy.evaluate(context, defaultMarketState, Collections.emptyMap());

        assertThat(evaluation.isQualified()).isTrue();
        assertThat(evaluation.confidenceScore()).isEqualTo(100.0);
        StrategyProposal proposal = evaluation.proposal();
        assertThat(proposal).isNotNull();
        assertThat(proposal.direction()).isEqualTo("BUY");
        assertThat(proposal.entryPrice()).isEqualTo(101.3);
        assertThat(proposal.initialStopLoss()).isEqualTo(99.5 * 0.995);
        assertThat(proposal.initialTarget()).isEqualTo(101.3 + (101.3 - (99.5 * 0.995)));
        assertThat(proposal.ruleContext().get("yellowCount")).isEqualTo(0L);
    }

    @Test
    @DisplayName("GIVEN 1 yellow condition (VOL_X 2.0) WHEN evaluate THEN returns QUALIFIED evaluation with score 85")
    void testOneYellowVolX() {
        DailyStockContext daily = createDefaultDailyContext();
        MarketCandle current5m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 35), 101.0, 101.35, 100.9, 101.3, 100.9, 62.0);
        MarketCandle current15m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 30), 100.5, 101.4, 100.4, 101.2, 100.8, 60.0);
        StockContext context = createValidContext(daily, current5m, current15m, 2.0); // 1.8 <= volX < 2.5 -> YELLOW

        StrategyEvaluation evaluation = strategy.evaluate(context, defaultMarketState, Collections.emptyMap());

        assertThat(evaluation.isQualified()).isTrue();
        assertThat(evaluation.confidenceScore()).isEqualTo(85.0);
        assertThat(evaluation.proposal().ruleContext().get("yellowCount")).isEqualTo(1L);
    }

    @Test
    @DisplayName("GIVEN 2 yellow conditions (VOL_X 2.0 and RSI 56) WHEN evaluate THEN returns QUALIFIED evaluation with score 70")
    void testTwoYellowsVolXAndRsi() {
        DailyStockContext daily = createDefaultDailyContext();
        MarketCandle current5m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 35), 101.0, 101.35, 100.9, 101.3, 100.9, 56.0); // RSI 56 -> YELLOW
        MarketCandle current15m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 30), 100.5, 101.4, 100.4, 101.2, 100.8, 60.0);
        StockContext context = createValidContext(daily, current5m, current15m, 2.0); // VOL_X 2.0 -> YELLOW

        StrategyEvaluation evaluation = strategy.evaluate(context, defaultMarketState, Collections.emptyMap());

        assertThat(evaluation.isQualified()).isTrue();
        assertThat(evaluation.confidenceScore()).isEqualTo(70.0);
        assertThat(evaluation.proposal().ruleContext().get("yellowCount")).isEqualTo(2L);
    }

    @Test
    @DisplayName("GIVEN 3 yellow conditions WHEN evaluate THEN returns NOT_QUALIFIED (disqualified by yellow count)")
    void testThreeYellowsDisqualified() {
        DailyStockContext daily = createDefaultDailyContext();
        // extension (101.6-101.0)/101.0 = 0.59% -> YELLOW, RSI 56 -> YELLOW
        MarketCandle current5m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 35), 101.0, 101.7, 100.9, 101.6, 100.9, 56.0);
        MarketCandle current15m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 30), 100.5, 101.4, 100.4, 101.2, 100.8, 60.0);
        StockContext context = createValidContext(daily, current5m, current15m, 2.0); // VOL_X 2.0 -> YELLOW (3 yellows total)

        StrategyEvaluation evaluation = strategy.evaluate(context, defaultMarketState, Collections.emptyMap());

        assertThat(evaluation.isQualified()).isFalse();
        assertThat(evaluation.failureReason()).contains("QUALITY_GATE_YELLOW_COUNT_3");
    }

    @Test
    @DisplayName("GIVEN extension > 0.8% WHEN evaluate THEN returns NOT_QUALIFIED (RED)")
    void testRedExtensionDisqualified() {
        DailyStockContext daily = createDefaultDailyContext();
        // extension (102.0-101.0)/101 = 0.99% -> RED
        MarketCandle current5m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 35), 101.0, 102.2, 100.9, 102.0, 100.9, 62.0);
        MarketCandle current15m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 30), 100.5, 101.4, 100.4, 101.2, 100.8, 60.0);
        StockContext context = createValidContext(daily, current5m, current15m, 2.8);

        StrategyEvaluation evaluation = strategy.evaluate(context, defaultMarketState, Collections.emptyMap());

        assertThat(evaluation.isQualified()).isFalse();
        assertThat(evaluation.failureReason()).contains("QUALITY_GATE_RED_COUNT");
    }

    @Test
    @DisplayName("GIVEN VOL_X < 1.8 WHEN evaluate THEN returns NOT_QUALIFIED (RED)")
    void testRedVolXDisqualified() {
        DailyStockContext daily = createDefaultDailyContext();
        MarketCandle current5m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 35), 101.0, 101.35, 100.9, 101.3, 100.9, 62.0);
        MarketCandle current15m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 30), 100.5, 101.4, 100.4, 101.2, 100.8, 60.0);
        StockContext context = createValidContext(daily, current5m, current15m, 1.5); // < 1.8 -> RED

        StrategyEvaluation evaluation = strategy.evaluate(context, defaultMarketState, Collections.emptyMap());

        assertThat(evaluation.isQualified()).isFalse();
        assertThat(evaluation.failureReason()).contains("QUALITY_GATE_RED_COUNT");
    }

    @Test
    @DisplayName("GIVEN RSI < 55 WHEN evaluate THEN returns NOT_QUALIFIED (RED)")
    void testRedRsiTooLowDisqualified() {
        DailyStockContext daily = createDefaultDailyContext();
        MarketCandle current5m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 35), 101.0, 101.35, 100.9, 101.3, 100.9, 52.0); // < 55 -> RED
        MarketCandle current15m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 30), 100.5, 101.4, 100.4, 101.2, 100.8, 60.0);
        StockContext context = createValidContext(daily, current5m, current15m, 2.8);

        StrategyEvaluation evaluation = strategy.evaluate(context, defaultMarketState, Collections.emptyMap());

        assertThat(evaluation.isQualified()).isFalse();
        assertThat(evaluation.failureReason()).contains("QUALITY_GATE_RED_COUNT");
    }

    @Test
    @DisplayName("GIVEN RSI > 72 WHEN evaluate THEN returns NOT_QUALIFIED (RED)")
    void testRedRsiOverboughtDisqualified() {
        DailyStockContext daily = createDefaultDailyContext();
        MarketCandle current5m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 35), 101.0, 101.35, 100.9, 101.3, 100.9, 75.0); // > 72 -> RED
        MarketCandle current15m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 30), 100.5, 101.4, 100.4, 101.2, 100.8, 60.0);
        StockContext context = createValidContext(daily, current5m, current15m, 2.8);

        StrategyEvaluation evaluation = strategy.evaluate(context, defaultMarketState, Collections.emptyMap());

        assertThat(evaluation.isQualified()).isFalse();
        assertThat(evaluation.failureReason()).contains("QUALITY_GATE_RED_COUNT");
    }

    @Test
    @DisplayName("GIVEN backfill in progress WHEN evaluate THEN returns NOT_EVALUABLE")
    void testGuardBackfillInProgress() {
        DailyStockContext daily = createDefaultDailyContext();
        MarketCandle current5m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 35), 101.0, 101.35, 100.9, 101.3, 100.9, 62.0);
        MarketCandle current15m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 30), 100.5, 101.4, 100.4, 101.2, 100.8, 60.0);
        StockContext context = new StockContext(
                "RELIANCE", "NSE", LocalDate.of(2026, 10, 9), daily,
                Collections.emptyList(), List.of(current5m), List.of(current15m), true, 2.8
        );

        StrategyEvaluation evaluation = strategy.evaluate(context, defaultMarketState, Collections.emptyMap());

        assertThat(evaluation.isNotEvaluable()).isTrue();
        assertThat(evaluation.failureReason()).isEqualTo("BACKFILL_IN_PROGRESS");
    }

    @Test
    @DisplayName("GIVEN NIFTY below VWAP WHEN evaluate THEN returns NOT_QUALIFIED")
    void testGuardNiftyBelowVwap() {
        DailyStockContext daily = createDefaultDailyContext();
        MarketCandle current5m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 35), 101.0, 101.35, 100.9, 101.3, 100.9, 62.0);
        MarketCandle current15m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 30), 100.5, 101.4, 100.4, 101.2, 100.8, 60.0);
        StockContext context = createValidContext(daily, current5m, current15m, 2.8);

        MarketState bearishMarketState = new MarketState(
                LocalDate.of(2026, 10, 9), LocalDateTime.of(2026, 10, 9, 9, 35),
                24800.0, 24900.0, -0.4, false, MarketState.MarketRegime.BEARISH, true, "OK"
        );

        StrategyEvaluation evaluation = strategy.evaluate(context, bearishMarketState, Collections.emptyMap());

        assertThat(evaluation.isQualified()).isFalse();
        assertThat(evaluation.failureReason()).isEqualTo("NIFTY_BELOW_VWAP");
    }

    @Test
    @DisplayName("GIVEN skipToday flagged WHEN evaluate THEN returns NOT_QUALIFIED")
    void testGuardSkipToday() {
        DailyStockContext daily = createDefaultDailyContext();
        daily.setSkipToday(true);
        MarketCandle current5m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 35), 101.0, 101.35, 100.9, 101.3, 100.9, 62.0);
        MarketCandle current15m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 30), 100.5, 101.4, 100.4, 101.2, 100.8, 60.0);
        StockContext context = createValidContext(daily, current5m, current15m, 2.8);

        StrategyEvaluation evaluation = strategy.evaluate(context, defaultMarketState, Collections.emptyMap());

        assertThat(evaluation.isQualified()).isFalse();
        assertThat(evaluation.failureReason()).isEqualTo("SKIP_TODAY_FLAGGED");
    }

    @Test
    @DisplayName("GIVEN opening gap > 1.5% WHEN evaluate THEN returns NOT_QUALIFIED")
    void testGuardOpeningGapTooLarge() {
        DailyStockContext daily = createDefaultDailyContext();
        daily.setGapPct(1.8);
        MarketCandle current5m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 35), 101.0, 101.35, 100.9, 101.3, 100.9, 62.0);
        MarketCandle current15m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 30), 100.5, 101.4, 100.4, 101.2, 100.8, 60.0);
        StockContext context = createValidContext(daily, current5m, current15m, 2.8);

        StrategyEvaluation evaluation = strategy.evaluate(context, defaultMarketState, Collections.emptyMap());

        assertThat(evaluation.isQualified()).isFalse();
        assertThat(evaluation.failureReason()).isEqualTo("OPENING_GAP_EXCEEDS_1_5_PCT");
    }

    @Test
    @DisplayName("GIVEN prev day range > 4% WHEN evaluate THEN returns NOT_QUALIFIED")
    void testGuardPrevDayRangeTooLarge() {
        DailyStockContext daily = createDefaultDailyContext();
        daily.setPrevDayRangePct(4.5);
        MarketCandle current5m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 35), 101.0, 101.35, 100.9, 101.3, 100.9, 62.0);
        MarketCandle current15m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 30), 100.5, 101.4, 100.4, 101.2, 100.8, 60.0);
        StockContext context = createValidContext(daily, current5m, current15m, 2.8);

        StrategyEvaluation evaluation = strategy.evaluate(context, defaultMarketState, Collections.emptyMap());

        assertThat(evaluation.isQualified()).isFalse();
        assertThat(evaluation.failureReason()).isEqualTo("PREV_DAY_RANGE_EXCEEDS_4_PCT");
    }

    @Test
    @DisplayName("GIVEN resistance < 1.0% WHEN evaluate THEN returns NOT_QUALIFIED")
    void testGuardResistanceTooClose() {
        DailyStockContext daily = createDefaultDailyContext();
        daily.setDistFromResistancePct(0.8);
        MarketCandle current5m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 35), 101.0, 101.35, 100.9, 101.3, 100.9, 62.0);
        MarketCandle current15m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 30), 100.5, 101.4, 100.4, 101.2, 100.8, 60.0);
        StockContext context = createValidContext(daily, current5m, current15m, 2.8);

        StrategyEvaluation evaluation = strategy.evaluate(context, defaultMarketState, Collections.emptyMap());

        assertThat(evaluation.isQualified()).isFalse();
        assertThat(evaluation.failureReason()).isEqualTo("RESISTANCE_CLOSER_THAN_1_PCT");
    }

    @Test
    @DisplayName("GIVEN first candle not bullish WHEN evaluate THEN returns NOT_QUALIFIED")
    void testFirstCandleNotBullish() {
        DailyStockContext daily = createDefaultDailyContext();
        daily.setFirstCandleOpen(101.0);
        daily.setFirstCandleClose(100.0); // Bearish
        MarketCandle current5m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 35), 101.0, 101.35, 100.9, 101.3, 100.9, 62.0);
        MarketCandle current15m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 30), 100.5, 101.4, 100.4, 101.2, 100.8, 60.0);
        StockContext context = createValidContext(daily, current5m, current15m, 2.8);

        StrategyEvaluation evaluation = strategy.evaluate(context, defaultMarketState, Collections.emptyMap());

        assertThat(evaluation.isQualified()).isFalse();
        assertThat(evaluation.failureReason()).isEqualTo("FIRST_CANDLE_NOT_BULLISH");
    }

    @Test
    @DisplayName("GIVEN first candle range < 0.15% WHEN evaluate THEN returns NOT_QUALIFIED")
    void testFirstCandleRangeTooNarrow() {
        DailyStockContext daily = createDefaultDailyContext();
        daily.setFirstCandleOpen(100.0);
        daily.setFirstCandleHigh(100.08);
        daily.setFirstCandleLow(99.98);
        daily.setFirstCandleClose(100.05);
        MarketCandle current5m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 35), 101.0, 101.35, 100.9, 101.3, 100.9, 62.0);
        MarketCandle current15m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 30), 100.5, 101.4, 100.4, 101.2, 100.8, 60.0);
        StockContext context = createValidContext(daily, current5m, current15m, 2.8);

        StrategyEvaluation evaluation = strategy.evaluate(context, defaultMarketState, Collections.emptyMap());

        assertThat(evaluation.isQualified()).isFalse();
        assertThat(evaluation.failureReason()).isEqualTo("FIRST_CANDLE_RANGE_INVALID");
    }

    @Test
    @DisplayName("GIVEN time before 09:30 WHEN evaluate THEN returns NOT_QUALIFIED")
    void testOutsideEntryWindowBefore() {
        DailyStockContext daily = createDefaultDailyContext();
        MarketCandle early5m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 25), 101.0, 101.5, 100.5, 101.2, 100.9, 62.0);
        MarketCandle current15m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 30), 100.5, 101.4, 100.4, 101.2, 100.8, 60.0);
        StockContext context = createValidContext(daily, early5m, current15m, 2.8);

        StrategyEvaluation evaluation = strategy.evaluate(context, defaultMarketState, Collections.emptyMap());

        assertThat(evaluation.isQualified()).isFalse();
        assertThat(evaluation.failureReason()).isEqualTo("OUTSIDE_ENTRY_WINDOW");
    }

    @Test
    @DisplayName("GIVEN time after 13:00 WHEN evaluate THEN returns NOT_QUALIFIED")
    void testOutsideEntryWindowAfter() {
        DailyStockContext daily = createDefaultDailyContext();
        MarketCandle late5m = createCandle(LocalDateTime.of(2026, 10, 9, 13, 5), 101.0, 101.5, 100.5, 101.2, 100.9, 62.0);
        MarketCandle current15m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 30), 100.5, 101.4, 100.4, 101.2, 100.8, 60.0);
        StockContext context = createValidContext(daily, late5m, current15m, 2.8);

        StrategyEvaluation evaluation = strategy.evaluate(context, defaultMarketState, Collections.emptyMap());

        assertThat(evaluation.isQualified()).isFalse();
        assertThat(evaluation.failureReason()).isEqualTo("OUTSIDE_ENTRY_WINDOW");
    }

    @Test
    @DisplayName("GIVEN close <= FCH WHEN evaluate THEN returns NOT_QUALIFIED")
    void testCloseNotAboveFch() {
        DailyStockContext daily = createDefaultDailyContext();
        MarketCandle subFch5m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 35), 100.0, 100.9, 99.8, 100.8, 100.2, 62.0);
        MarketCandle current15m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 30), 100.5, 101.4, 100.4, 101.2, 100.8, 60.0);
        StockContext context = createValidContext(daily, subFch5m, current15m, 2.8);

        StrategyEvaluation evaluation = strategy.evaluate(context, defaultMarketState, Collections.emptyMap());

        assertThat(evaluation.isQualified()).isFalse();
        assertThat(evaluation.failureReason()).isEqualTo("CLOSE_NOT_ABOVE_FCH");
    }

    @Test
    @DisplayName("GIVEN 15M close <= 15M VWAP WHEN evaluate THEN returns RED disqualified")
    void test15mNotAboveVwap() {
        DailyStockContext daily = createDefaultDailyContext();
        MarketCandle current5m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 35), 101.0, 101.35, 100.9, 101.3, 100.9, 62.0);
        MarketCandle subVwap15m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 30), 100.5, 101.0, 100.4, 100.5, 100.8, 55.0); // close 100.5 <= VWAP 100.8
        StockContext context = createValidContext(daily, current5m, subVwap15m, 2.8);

        StrategyEvaluation evaluation = strategy.evaluate(context, defaultMarketState, Collections.emptyMap());

        assertThat(evaluation.isQualified()).isFalse();
        assertThat(evaluation.failureReason()).contains("QUALITY_GATE_RED_COUNT");
    }

    @Test
    @DisplayName("GIVEN close <= 5M VWAP WHEN evaluate THEN returns RED disqualified")
    void testCloseNotAbove5mVwap() {
        DailyStockContext daily = createDefaultDailyContext();
        MarketCandle subVwap5m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 35), 101.0, 101.6, 100.9, 101.3, 101.5, 62.0); // close 101.3 <= VWAP 101.5
        MarketCandle current15m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 30), 100.5, 101.4, 100.4, 101.2, 100.8, 60.0);
        StockContext context = createValidContext(daily, subVwap5m, current15m, 2.8);

        StrategyEvaluation evaluation = strategy.evaluate(context, defaultMarketState, Collections.emptyMap());

        assertThat(evaluation.isQualified()).isFalse();
        assertThat(evaluation.failureReason()).contains("QUALITY_GATE_RED_COUNT");
    }
}
