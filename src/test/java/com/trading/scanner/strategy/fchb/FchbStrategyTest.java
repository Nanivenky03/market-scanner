package com.trading.scanner.strategy.fchb;

import com.trading.scanner.model.DailyStockContext;
import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.strategy.model.MarketState;
import com.trading.scanner.strategy.model.StockContext;
import com.trading.scanner.strategy.model.StrategyEvaluation;
import com.trading.scanner.strategy.model.StrategyProposal;
import com.trading.scanner.util.PriceTickUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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
                25050.0,
                24950.0,
                0.40, // 0.40% > 0.30% -> GREEN
                true,
                MarketState.MarketRegime.BULLISH,
                true,
                "Valid NIFTY above VWAP"
        );
    }

    private DailyStockContext createDefaultDailyContext() {
        return DailyStockContext.builder()
                .symbol("RELIANCE")
                .exchange("NSE")
                .tradingDate(LocalDate.of(2026, 10, 9))
                .firstCandleOpen(100.0)
                .firstCandleHigh(101.0)
                .firstCandleLow(99.5)
                .firstCandleClose(100.8)
                .firstCandleVolume(50000L)
                .firstCandleRange(1.5)
                .firstCandleRangePct(1.5)
                .firstCandleBullish(true)
                .firstCandleValid(true)
                .firstCandleReady(true)
                .prevDayHigh(102.0)
                .prevDayLow(99.0)
                .prevDayClose(100.5)
                .prevDayRangePct(3.0)
                .highestClose15d(105.0)
                .distFromResistancePct(4.0)
                .gapPct(0.5)
                .skipToday(false)
                .corporateActionFlag(false)
                .foBanFlag(false)
                .resultsLast3dFlag(false)
                .build();
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
    @DisplayName("GIVEN textbook green conditions WHEN evaluate THEN returns QUALIFIED evaluation with score 100")
    void testAllGreenConditions() {
        DailyStockContext daily = createDefaultDailyContext();
        // 5M candle: extension = (101.3 - 101.0)/101.0 = 0.297% (<= 0.4% -> GREEN)
        // Body = 0.3, Range = 0.45 -> BodyRatio = 0.667 (>= 0.65 -> GREEN), UpperWick = 0.05 -> UpperWickRatio = 0.11 (<= 0.15 -> GREEN)
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
        double expectedSl = PriceTickUtil.roundStopLoss(99.5 * 0.995, false, 0.05);
        assertThat(proposal.initialStopLoss()).isEqualTo(expectedSl);
        assertThat(proposal.initialTarget()).isEqualTo(PriceTickUtil.roundTarget(101.3 + (FchbStrategy.TARGET_1_R_MULTIPLE * (101.3 - expectedSl)), false, 0.05));
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
    @DisplayName("GIVEN 2 yellow conditions (VOL_X 2.0 and Extension 0.6%) WHEN evaluate THEN returns QUALIFIED with score 70")
    void testTwoYellowConditions() {
        DailyStockContext daily = createDefaultDailyContext();
        // extension = (101.6 - 101.0)/101.0 = 0.594% (0.4% < ext <= 0.8% -> YELLOW)
        MarketCandle current5m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 35), 101.1, 101.65, 101.0, 101.6, 101.0, 62.0);
        MarketCandle current15m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 30), 100.5, 101.7, 100.4, 101.5, 100.8, 60.0);
        StockContext context = createValidContext(daily, current5m, current15m, 2.0); // YELLOW

        StrategyEvaluation evaluation = strategy.evaluate(context, defaultMarketState, Collections.emptyMap());

        assertThat(evaluation.isQualified()).isTrue();
        assertThat(evaluation.confidenceScore()).isEqualTo(70.0);
        assertThat(evaluation.proposal().ruleContext().get("yellowCount")).isEqualTo(2L);
    }

    @Test
    @DisplayName("GIVEN 3 yellow conditions WHEN evaluate THEN returns NOT_QUALIFIED (Quality Gate max 2 yellows)")
    void testThreeYellowConditionsDisqualified() {
        DailyStockContext daily = createDefaultDailyContext();
        // Yellow 1: Extension 0.6%
        // Yellow 2: VOL_X 2.0
        // Yellow 3: NIFTY distance 0.15%
        MarketState yellowNiftyState = new MarketState(
                LocalDate.of(2026, 10, 9),
                LocalDateTime.of(2026, 10, 9, 9, 35),
                25010.0,
                24972.0,
                0.15, // 0.15% (>= 0.0% and < 0.30% -> YELLOW)
                true,
                MarketState.MarketRegime.BULLISH,
                true,
                "Valid NIFTY above VWAP (Yellow)"
        );
        MarketCandle current5m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 35), 101.1, 101.65, 101.0, 101.6, 101.0, 56.0);
        MarketCandle current15m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 30), 100.5, 101.7, 100.4, 101.5, 100.8, 60.0);
        StockContext context = createValidContext(daily, current5m, current15m, 2.0);

        StrategyEvaluation evaluation = strategy.evaluate(context, yellowNiftyState, Collections.emptyMap());

        assertThat(evaluation.isQualified()).isFalse();
        assertThat(evaluation.failureReason()).contains("QUALITY_GATE_YELLOW_COUNT_3");
    }

    @Test
    @DisplayName("GIVEN red condition (Extension > 0.8%) WHEN evaluate THEN returns NOT_QUALIFIED")
    void testRedExtensionDisqualified() {
        DailyStockContext daily = createDefaultDailyContext();
        // extension = (102.0 - 101.0)/101.0 = 0.99% (> 0.8% -> RED)
        MarketCandle current5m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 35), 101.2, 102.1, 101.1, 102.0, 101.0, 62.0);
        MarketCandle current15m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 30), 100.5, 102.1, 100.4, 101.8, 100.8, 60.0);
        StockContext context = createValidContext(daily, current5m, current15m, 2.8);

        StrategyEvaluation evaluation = strategy.evaluate(context, defaultMarketState, Collections.emptyMap());

        assertThat(evaluation.isQualified()).isFalse();
        assertThat(evaluation.failureReason()).contains("QUALITY_GATE_RED_COUNT");
        assertThat(evaluation.failedConditions()).contains("EXTENSION_RED");
    }

    @Test
    @DisplayName("GIVEN red condition (VOL_X < 1.8) WHEN evaluate THEN returns NOT_QUALIFIED")
    void testRedVolXDisqualified() {
        DailyStockContext daily = createDefaultDailyContext();
        MarketCandle current5m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 35), 101.0, 101.35, 100.9, 101.3, 100.9, 62.0);
        MarketCandle current15m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 30), 100.5, 101.4, 100.4, 101.2, 100.8, 60.0);
        StockContext context = createValidContext(daily, current5m, current15m, 1.5); // < 1.8 -> RED

        StrategyEvaluation evaluation = strategy.evaluate(context, defaultMarketState, Collections.emptyMap());

        assertThat(evaluation.isQualified()).isFalse();
        assertThat(evaluation.failureReason()).contains("QUALITY_GATE_RED_COUNT");
        assertThat(evaluation.failedConditions()).contains("VOL_X_RED");
    }

    @Test
    @DisplayName("GIVEN low or high RSI WHEN evaluate THEN is NOT disqualified (RSI gate removed)")
    void testRedRsiDisqualified() {
        DailyStockContext daily = createDefaultDailyContext();
        MarketCandle current5m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 35), 101.0, 101.35, 100.9, 101.3, 100.9, 52.0);
        MarketCandle current15m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 30), 100.5, 101.4, 100.4, 101.2, 100.8, 60.0);
        StockContext context = createValidContext(daily, current5m, current15m, 2.8);

        StrategyEvaluation evaluation = strategy.evaluate(context, defaultMarketState, Collections.emptyMap());

        assertThat(evaluation.isQualified()).isTrue();
    }

    @Test
    @DisplayName("GIVEN NIFTY below VWAP WHEN evaluate THEN returns NOT_QUALIFIED by master filter")
    void testNiftyBelowVwapDisqualified() {
        DailyStockContext daily = createDefaultDailyContext();
        MarketCandle current5m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 35), 101.0, 101.35, 100.9, 101.3, 100.9, 62.0);
        MarketCandle current15m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 30), 100.5, 101.4, 100.4, 101.2, 100.8, 60.0);
        StockContext context = createValidContext(daily, current5m, current15m, 2.8);

        MarketState bearishMarket = new MarketState(
                LocalDate.of(2026, 10, 9),
                LocalDateTime.of(2026, 10, 9, 9, 35),
                24900.0,
                24950.0,
                -0.20,
                false, // NIFTY below VWAP
                MarketState.MarketRegime.BEARISH,
                true,
                "NIFTY below VWAP"
        );

        StrategyEvaluation evaluation = strategy.evaluate(context, bearishMarket, Collections.emptyMap());

        assertThat(evaluation.isQualified()).isFalse();
        assertThat(evaluation.failureReason()).isEqualTo("NIFTY_BELOW_VWAP");
    }

    @Test
    @DisplayName("GIVEN active backfill WHEN evaluate THEN returns NOT_EVALUABLE (fail-closed)")
    void testBackfillActiveFailClosed() {
        DailyStockContext daily = createDefaultDailyContext();
        MarketCandle current5m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 35), 101.0, 101.35, 100.9, 101.3, 100.9, 62.0);
        MarketCandle current15m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 30), 100.5, 101.4, 100.4, 101.2, 100.8, 60.0);

        StockContext context = new StockContext(
                "RELIANCE", "NSE", LocalDate.of(2026, 10, 9), daily,
                Collections.emptyList(), List.of(current5m), List.of(current15m), true, 2.8 // backfill active!
        );

        StrategyEvaluation evaluation = strategy.evaluate(context, defaultMarketState, Collections.emptyMap());

        assertThat(evaluation.isNotEvaluable()).isTrue();
        assertThat(evaluation.failureReason()).isEqualTo("BACKFILL_IN_PROGRESS");
    }

    @Test
    @DisplayName("GIVEN invalid market state data WHEN evaluate THEN returns NOT_EVALUABLE")
    void testInvalidMarketStateData() {
        DailyStockContext daily = createDefaultDailyContext();
        MarketCandle current5m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 35), 101.0, 101.35, 100.9, 101.3, 100.9, 62.0);
        MarketCandle current15m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 30), 100.5, 101.4, 100.4, 101.2, 100.8, 60.0);
        StockContext context = createValidContext(daily, current5m, current15m, 2.8);

        MarketState invalidMarket = new MarketState(
                LocalDate.of(2026, 10, 9), LocalDateTime.of(2026, 10, 9, 9, 35),
                null, null, null, false, MarketState.MarketRegime.UNKNOWN, false, "Stale Nifty"
        );

        StrategyEvaluation evaluation = strategy.evaluate(context, invalidMarket, Collections.emptyMap());

        assertThat(evaluation.isNotEvaluable()).isTrue();
        assertThat(evaluation.failureReason()).isEqualTo("INVALID_MARKET_STATE");
    }

    @Test
    @DisplayName("GIVEN candle close <= firstCandleHigh WHEN evaluate THEN returns NOT_QUALIFIED")
    void testNoBreakoutAboveFch() {
        DailyStockContext daily = createDefaultDailyContext(); // FCH = 101.0
        MarketCandle current5m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 35), 100.5, 100.9, 100.2, 100.8, 100.5, 62.0); // close 100.8 <= FCH 101.0
        MarketCandle current15m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 30), 100.5, 101.4, 100.4, 101.2, 100.8, 60.0);
        StockContext context = createValidContext(daily, current5m, current15m, 2.8);

        StrategyEvaluation evaluation = strategy.evaluate(context, defaultMarketState, Collections.emptyMap());

        assertThat(evaluation.isQualified()).isFalse();
        assertThat(evaluation.failureReason()).isEqualTo("CLOSE_NOT_ABOVE_FCH");
    }

    @Test
    @DisplayName("GIVEN candle close > FCH but less than +0.15% margin WHEN evaluate THEN returns NOT_QUALIFIED")
    void testBreakoutLessThan015PctAboveFch() {
        DailyStockContext daily = createDefaultDailyContext(); // FCH = 101.0 -> 101.0 * 1.0015 = 101.1515
        MarketCandle current5m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 35), 100.8, 101.10, 100.7, 101.05, 100.8, 62.0); // close 101.05 > 101.0 but < 101.1515
        MarketCandle current15m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 30), 100.5, 101.4, 100.4, 101.2, 100.8, 60.0);
        StockContext context = createValidContext(daily, current5m, current15m, 2.8);

        StrategyEvaluation evaluation = strategy.evaluate(context, defaultMarketState, Collections.emptyMap());

        assertThat(evaluation.isQualified()).isFalse();
        assertThat(evaluation.failureReason()).isEqualTo("BREAKOUT_LESS_THAN_0_15_PCT_ABOVE_FCH");
    }

    @Test
    @DisplayName("GIVEN evaluation time outside 09:30-13:00 window WHEN evaluate THEN returns NOT_QUALIFIED")
    void testOutsideEntryWindow() {
        DailyStockContext daily = createDefaultDailyContext();
        MarketCandle current5m = createCandle(LocalDateTime.of(2026, 10, 9, 13, 5), 101.0, 101.35, 100.9, 101.3, 100.9, 62.0); // 13:05 > 13:00
        MarketCandle current15m = createCandle(LocalDateTime.of(2026, 10, 9, 13, 0), 100.5, 101.4, 100.4, 101.2, 100.8, 60.0);
        StockContext context = createValidContext(daily, current5m, current15m, 2.8);

        StrategyEvaluation evaluation = strategy.evaluate(context, defaultMarketState, Collections.emptyMap());

        assertThat(evaluation.isQualified()).isFalse();
        assertThat(evaluation.failureReason()).isEqualTo("OUTSIDE_ENTRY_WINDOW");
    }

    @Test
    @DisplayName("GIVEN first candle bearish WHEN evaluate THEN returns NOT_QUALIFIED")
    void testFirstCandleBearish() {
        DailyStockContext daily = createDefaultDailyContext();
        daily.setFirstCandleOpen(101.0);
        daily.setFirstCandleClose(100.5); // close <= open -> Bearish!

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
        daily.setFirstCandleHigh(100.10);
        daily.setFirstCandleLow(100.00); // range = 0.10 / 100 = 0.10% < 0.15%

        MarketCandle current5m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 35), 100.0, 100.2, 99.9, 100.15, 100.0, 62.0);
        MarketCandle current15m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 30), 100.0, 100.2, 99.9, 100.15, 100.0, 60.0);
        StockContext context = createValidContext(daily, current5m, current15m, 2.8);

        StrategyEvaluation evaluation = strategy.evaluate(context, defaultMarketState, Collections.emptyMap());

        assertThat(evaluation.isQualified()).isFalse();
        assertThat(evaluation.failureReason()).isEqualTo("FIRST_CANDLE_RANGE_INVALID");
    }

    @Test
    @DisplayName("GIVEN first candle range > 3.0% WHEN evaluate THEN returns NOT_QUALIFIED")
    void testFirstCandleRangeTooWide() {
        DailyStockContext daily = createDefaultDailyContext();
        daily.setFirstCandleHigh(104.0);
        daily.setFirstCandleLow(100.0); // range = 4.0% > 3.0%

        MarketCandle current5m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 35), 103.0, 104.5, 102.5, 104.2, 103.0, 62.0);
        MarketCandle current15m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 30), 102.0, 104.5, 101.5, 104.0, 102.5, 60.0);
        StockContext context = createValidContext(daily, current5m, current15m, 2.8);

        StrategyEvaluation evaluation = strategy.evaluate(context, defaultMarketState, Collections.emptyMap());

        assertThat(evaluation.isQualified()).isFalse();
        assertThat(evaluation.failureReason()).isEqualTo("FIRST_CANDLE_RANGE_INVALID");
    }

    @Test
    @DisplayName("GIVEN gap > 1.5% WHEN evaluate THEN returns NOT_QUALIFIED")
    void testOpeningGapTooLarge() {
        DailyStockContext daily = createDefaultDailyContext();
        daily.setGapPct(1.8); // > 1.5%

        MarketCandle current5m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 35), 101.0, 101.35, 100.9, 101.3, 100.9, 62.0);
        MarketCandle current15m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 30), 100.5, 101.4, 100.4, 101.2, 100.8, 60.0);
        StockContext context = createValidContext(daily, current5m, current15m, 2.8);

        StrategyEvaluation evaluation = strategy.evaluate(context, defaultMarketState, Collections.emptyMap());

        assertThat(evaluation.isQualified()).isFalse();
        assertThat(evaluation.failureReason()).isEqualTo("OPENING_GAP_EXCEEDS_1_5_PCT");
    }

    @Test
    @DisplayName("GIVEN prevDayRange > 4.0% WHEN evaluate THEN returns NOT_QUALIFIED")
    void testPrevDayRangeTooLarge() {
        DailyStockContext daily = createDefaultDailyContext();
        daily.setPrevDayRangePct(4.5); // > 4.0%

        MarketCandle current5m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 35), 101.0, 101.35, 100.9, 101.3, 100.9, 62.0);
        MarketCandle current15m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 30), 100.5, 101.4, 100.4, 101.2, 100.8, 60.0);
        StockContext context = createValidContext(daily, current5m, current15m, 2.8);

        StrategyEvaluation evaluation = strategy.evaluate(context, defaultMarketState, Collections.emptyMap());

        assertThat(evaluation.isQualified()).isFalse();
        assertThat(evaluation.failureReason()).isEqualTo("PREV_DAY_RANGE_EXCEEDS_4_PCT");
    }

    @Test
    @DisplayName("GIVEN distance to resistance < 1.0% WHEN evaluate THEN qualifies for multi-week breakout")
    void testResistanceCloseAllowedForMultiWeekBreakout() {
        DailyStockContext daily = createDefaultDailyContext();
        daily.setDistFromResistancePct(0.5); // < 1.0%

        MarketCandle current5m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 35), 101.0, 101.35, 100.9, 101.3, 100.9, 62.0);
        MarketCandle current15m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 30), 100.5, 101.4, 100.4, 101.2, 100.8, 60.0);
        StockContext context = createValidContext(daily, current5m, current15m, 2.8);

        StrategyEvaluation evaluation = strategy.evaluate(context, defaultMarketState, Collections.emptyMap());

        assertThat(evaluation.isQualified()).isTrue();
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

    @Test
    @DisplayName("GIVEN upperCircuit provides < 2.0% headroom WHEN evaluate THEN returns RED disqualified")
    void testUpperCircuitRoomUnder2Pct() {
        DailyStockContext daily = createDefaultDailyContext();
        MarketCandle current5m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 35), 101.0, 101.35, 100.9, 101.3, 100.9, 62.0);
        MarketCandle current15m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 30), 100.5, 101.4, 100.4, 101.2, 100.8, 60.0);
        StockContext context = createValidContext(daily, current5m, current15m, 2.8);

        // Upper circuit at 102.0 (only 0.69% above close 101.3) -> < 2% room -> RED!
        StrategyEvaluation evaluation = strategy.evaluate(context, defaultMarketState, Map.of("upperCircuit", "102.0"));

        assertThat(evaluation.isQualified()).isFalse();
        assertThat(evaluation.failedConditions()).contains("CIRCUIT_ROOM_LESS_THAN_2_PCT");
    }

    @Test
    @DisplayName("GIVEN price > Rs. 5000 WHEN evaluate THEN returns disqualified PRICE_EXCEEDS_5000")
    void testPriceCeilingMax5000() {
        DailyStockContext daily = createDefaultDailyContext();
        daily.setFirstCandleHigh(5020.0);
        daily.setFirstCandleLow(5000.0);
        daily.setFirstCandleOpen(5005.0);
        daily.setFirstCandleClose(5015.0);
        MarketCandle current5m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 35), 5015.0, 5035.0, 5010.0, 5030.0, 5012.0, 62.0);
        MarketCandle current15m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 30), 5000.0, 5035.0, 4995.0, 5025.0, 5010.0, 60.0);
        StockContext context = createValidContext(daily, current5m, current15m, 2.8);

        StrategyEvaluation evaluation = strategy.evaluate(context, defaultMarketState, Collections.emptyMap());

        assertThat(evaluation.isQualified()).isFalse();
        assertThat(evaluation.failureReason()).isEqualTo("PRICE_EXCEEDS_5000");
    }

    @Test
    @DisplayName("GIVEN opening turnover < Rs. 25 Lakhs WHEN evaluate THEN returns disqualified OPENING_TURNOVER_TOO_LOW")
    void testMinOpeningTurnover25L() {
        DailyStockContext daily = createDefaultDailyContext();
        // Volume 1000 shares * price 100.8 = Rs. 1,00,800 (< 25 Lakhs)
        daily.setFirstCandleVolume(1000L);
        MarketCandle current5m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 35), 101.0, 101.35, 100.9, 101.3, 100.9, 62.0);
        MarketCandle current15m = createCandle(LocalDateTime.of(2026, 10, 9, 9, 30), 100.5, 101.4, 100.4, 101.2, 100.8, 60.0);
        StockContext context = createValidContext(daily, current5m, current15m, 2.8);

        StrategyEvaluation evaluation = strategy.evaluate(context, defaultMarketState, Collections.emptyMap());

        assertThat(evaluation.isQualified()).isFalse();
        assertThat(evaluation.failureReason()).isEqualTo("OPENING_TURNOVER_TOO_LOW");
    }
}
