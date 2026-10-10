package com.trading.scanner.strategy.fchb;

import com.trading.scanner.model.DailyStockContext;
import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.strategy.Strategy;
import com.trading.scanner.strategy.model.MarketState;
import com.trading.scanner.strategy.model.StockContext;
import com.trading.scanner.strategy.model.StrategyEvaluation;
import com.trading.scanner.strategy.model.StrategyProposal;
import com.trading.scanner.strategy.model.TrafficLight;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * FCHB (First Candle High Breakout) Strategy - Long Side Only.
 * Pure evaluation function generating ENTRY proposals using the Red/Yellow/Green Traffic-Light Quality Gate.
 */
@Component
public class FchbStrategy implements Strategy {

    private static final Logger log = LoggerFactory.getLogger(FchbStrategy.class);

    public static final int STRATEGY_ID = 2;
    public static final String STRATEGY_KEY = "FCHB";

    private static final LocalTime ENTRY_WINDOW_START = LocalTime.of(9, 30);
    private static final LocalTime ENTRY_WINDOW_END = LocalTime.of(13, 0);

    @Override
    public Integer getStrategyId() {
        return STRATEGY_ID;
    }

    @Override
    public String getStrategyKey() {
        return STRATEGY_KEY;
    }

    @Override
    public String getTimeframe() {
        return "5M";
    }

    @Override
    public StrategyEvaluation evaluate(StockContext context, MarketState marketState, Map<String, String> parameters) {
        // 1. Fail-closed guards
        if (context == null || marketState == null) {
            return StrategyEvaluation.notEvaluable("MISSING_CONTEXT_OR_MARKET_STATE");
        }
        if (context.backfillActive()) {
            return StrategyEvaluation.notEvaluable("BACKFILL_IN_PROGRESS");
        }
        if (!marketState.dataValid()) {
            return StrategyEvaluation.notEvaluable("INVALID_MARKET_STATE");
        }
        if (!marketState.niftyAboveVwap()) {
            return StrategyEvaluation.notQualified(0.0, Collections.emptyList(), List.of("NIFTY_VWAP_FILTER"), "NIFTY_BELOW_VWAP");
        }

        DailyStockContext daily = context.dailyContext();
        if (daily == null) {
            return StrategyEvaluation.notEvaluable("DAILY_CONTEXT_MISSING");
        }
        if (Boolean.TRUE.equals(daily.getSkipToday())) {
            return StrategyEvaluation.notQualified(0.0, Collections.emptyList(), List.of("STOCK_SKIP_TODAY"), "SKIP_TODAY_FLAGGED");
        }
        if (daily.getGapPct() != null && Math.abs(daily.getGapPct()) > 1.5) {
            return StrategyEvaluation.notQualified(0.0, Collections.emptyList(), List.of("OPENING_GAP_MAX_1_5"), "OPENING_GAP_EXCEEDS_1_5_PCT");
        }
        if (daily.getPrevDayRangePct() != null && daily.getPrevDayRangePct() > 4.0) {
            return StrategyEvaluation.notQualified(0.0, Collections.emptyList(), List.of("PREV_DAY_RANGE_MAX_4"), "PREV_DAY_RANGE_EXCEEDS_4_PCT");
        }
        if (daily.getDistFromResistancePct() != null && daily.getDistFromResistancePct() < 1.0) {
            return StrategyEvaluation.notQualified(0.0, Collections.emptyList(), List.of("RESISTANCE_DISTANCE_MIN_1"), "RESISTANCE_CLOSER_THAN_1_PCT");
        }

        // 2. First candle reference validation (from DailyStockContext)
        Double firstCandleHigh = daily.getFirstCandleHigh();
        Double firstCandleLow = daily.getFirstCandleLow();
        Double firstCandleOpen = daily.getFirstCandleOpen();
        Double firstCandleClose = daily.getFirstCandleClose();

        if (firstCandleHigh == null || firstCandleLow == null || firstCandleOpen == null || firstCandleClose == null) {
            return StrategyEvaluation.notEvaluable("FIRST_CANDLE_MISSING");
        }

        if (firstCandleClose <= firstCandleOpen) {
            return StrategyEvaluation.notQualified(0.0, Collections.emptyList(), List.of("FIRST_CANDLE_BULLISH"), "FIRST_CANDLE_NOT_BULLISH");
        }
        double firstCandleRangePct = ((firstCandleHigh - firstCandleLow) / firstCandleLow) * 100.0;
        if (firstCandleRangePct < 0.15 || firstCandleRangePct > 3.0) {
            return StrategyEvaluation.notQualified(0.0, Collections.emptyList(), List.of("FIRST_CANDLE_RANGE_0_15_TO_3_0"), "FIRST_CANDLE_RANGE_INVALID");
        }

        // 3. Current 5M candle trigger validation
        MarketCandle current5m = context.latest5mCandle();
        if (current5m == null) {
            return StrategyEvaluation.notEvaluable("CURRENT_5M_CANDLE_MISSING");
        }
        LocalTime candleTime = current5m.getCandleTime().toLocalTime();
        if (candleTime.isBefore(ENTRY_WINDOW_START) || candleTime.isAfter(ENTRY_WINDOW_END)) {
            return StrategyEvaluation.notQualified(0.0, Collections.emptyList(), List.of("ENTRY_WINDOW_0930_1300"), "OUTSIDE_ENTRY_WINDOW");
        }

        Double close = current5m.getClosePrice();
        if (close == null || close <= firstCandleHigh) {
            return StrategyEvaluation.notQualified(0.0, Collections.emptyList(), List.of("CLOSE_ABOVE_FCH"), "CLOSE_NOT_ABOVE_FCH");
        }

        // 4. Traffic-Light Evaluation
        List<TrafficLight> lights = new ArrayList<>();
        List<String> passed = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        // Gate 1: Breakout Extension
        double extensionPct = ((close - firstCandleHigh) / firstCandleHigh) * 100.0;
        if (extensionPct <= 0.4) {
            lights.add(TrafficLight.GREEN);
            passed.add("EXTENSION_GREEN");
        } else if (extensionPct <= 0.8) {
            lights.add(TrafficLight.YELLOW);
            passed.add("EXTENSION_YELLOW");
        } else {
            lights.add(TrafficLight.RED);
            failed.add("EXTENSION_RED");
        }

        // Gate 2: Time-Normalized Relative Volume (VOL_X)
        Double volX = context.volX();
        if (volX == null) {
            lights.add(TrafficLight.RED);
            failed.add("VOL_X_MISSING");
        } else if (volX >= 2.5) {
            lights.add(TrafficLight.GREEN);
            passed.add("VOL_X_GREEN");
        } else if (volX >= 1.8) {
            lights.add(TrafficLight.YELLOW);
            passed.add("VOL_X_YELLOW");
        } else {
            lights.add(TrafficLight.RED);
            failed.add("VOL_X_RED");
        }

        // Gate 3: 5M RSI (14)
        Double rsi = current5m.getRsi14();
        if (rsi == null) {
            lights.add(TrafficLight.RED);
            failed.add("RSI_MISSING");
        } else if (rsi >= 58.0 && rsi <= 65.0) {
            lights.add(TrafficLight.GREEN);
            passed.add("RSI_GREEN");
        } else if ((rsi >= 55.0 && rsi < 58.0) || (rsi > 65.0 && rsi <= 72.0)) {
            lights.add(TrafficLight.YELLOW);
            passed.add("RSI_YELLOW");
        } else {
            lights.add(TrafficLight.RED);
            failed.add("RSI_RED");
        }

        // Gate 4: Candle Shape (Body Ratio & Upper Wick Ratio)
        Double open = current5m.getOpenPrice();
        Double high = current5m.getHighPrice();
        Double low = current5m.getLowPrice();
        if (open == null || high == null || low == null) {
            lights.add(TrafficLight.RED);
            failed.add("CANDLE_OHLC_INCOMPLETE");
        } else {
            double candleRange = high - low;
            if (candleRange <= 0.0) {
                lights.add(TrafficLight.RED);
                failed.add("CANDLE_RANGE_ZERO");
            } else {
                double body = Math.abs(close - open);
                double bodyRatio = body / candleRange;
                double upperWick = high - Math.max(open, close);
                double upperWickRatio = upperWick / candleRange;

                if (bodyRatio >= 0.65 && upperWickRatio <= 0.15) {
                    lights.add(TrafficLight.GREEN);
                    passed.add("CANDLE_SHAPE_GREEN");
                } else if (bodyRatio >= 0.60 && upperWickRatio <= 0.25) {
                    lights.add(TrafficLight.YELLOW);
                    passed.add("CANDLE_SHAPE_YELLOW");
                } else {
                    lights.add(TrafficLight.RED);
                    failed.add("CANDLE_SHAPE_RED");
                }
            }
        }

        // Gate 5: Stock 5M VWAP Alignment
        Double vwap = current5m.getVwap();
        if (vwap == null || close <= vwap) {
            lights.add(TrafficLight.RED);
            failed.add("VWAP_5M_RED");
        } else {
            lights.add(TrafficLight.GREEN);
            passed.add("VWAP_5M_GREEN");
        }

        // Gate 6: Stock 15M Alignment
        MarketCandle latest15m = context.latest15mCandle();
        if (latest15m == null || latest15m.getClosePrice() == null || latest15m.getVwap() == null || latest15m.getClosePrice() <= latest15m.getVwap()) {
            lights.add(TrafficLight.RED);
            failed.add("VWAP_15M_RED");
        } else {
            lights.add(TrafficLight.GREEN);
            passed.add("VWAP_15M_GREEN");
        }

        // Gate 7: NIFTY 50 Support
        Double niftyVwapDist = marketState.vwapDistancePct();
        if (niftyVwapDist == null) {
            lights.add(TrafficLight.RED);
            failed.add("NIFTY_VWAP_MISSING");
        } else if (niftyVwapDist >= 0.3) {
            lights.add(TrafficLight.GREEN);
            passed.add("NIFTY_VWAP_GREEN");
        } else if (niftyVwapDist >= 0.0) {
            lights.add(TrafficLight.YELLOW);
            passed.add("NIFTY_VWAP_YELLOW");
        } else {
            lights.add(TrafficLight.RED);
            failed.add("NIFTY_VWAP_RED");
        }

        // 5. Evaluate Quality Gate
        long redCount = lights.stream().filter(l -> l == TrafficLight.RED).count();
        if (redCount > 0) {
            return StrategyEvaluation.notQualified(0.0, passed, failed, "QUALITY_GATE_RED_COUNT_" + redCount);
        }

        long yellowCount = lights.stream().filter(l -> l == TrafficLight.YELLOW).count();
        if (yellowCount > 2) {
            return StrategyEvaluation.notQualified(50.0, passed, failed, "QUALITY_GATE_YELLOW_COUNT_" + yellowCount);
        }

        double score;
        if (yellowCount == 0) {
            score = 100.0;
        } else if (yellowCount == 1) {
            score = 85.0;
        } else {
            score = 70.0;
        }

        // 6. Proposal Generation: Stop Loss and Target Calculation
        double stopLoss = firstCandleLow * 0.995;
        double riskPerShare = close - stopLoss;
        if (riskPerShare <= 0.0) {
            return StrategyEvaluation.notQualified(score, passed, List.of("INVALID_RISK_PER_SHARE"), "INVALID_RISK_PER_SHARE");
        }
        double targetPrice = close + riskPerShare;

        StrategyProposal proposal = new StrategyProposal(
                context.symbol(),
                context.exchange(),
                STRATEGY_ID,
                STRATEGY_KEY,
                "BUY",
                close,
                stopLoss,
                targetPrice,
                firstCandleHigh, // structural invalidation level: 1M close below FCH
                score,
                Map.of(
                        "yellowCount", yellowCount,
                        "firstCandleHigh", firstCandleHigh,
                        "firstCandleLow", firstCandleLow,
                        "volX", volX != null ? volX : 0.0,
                        "extensionPct", extensionPct
                )
        );

        return StrategyEvaluation.qualified(proposal, score, passed);
    }
}
