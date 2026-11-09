package com.trading.scanner.strategy.fchb;

import com.trading.scanner.model.DailyStockContext;
import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.strategy.Strategy;
import com.trading.scanner.strategy.model.MarketState;
import com.trading.scanner.strategy.model.StockContext;
import com.trading.scanner.strategy.model.StrategyEvaluation;
import com.trading.scanner.strategy.model.StrategyProposal;
import com.trading.scanner.strategy.model.TrafficLight;
import com.trading.scanner.util.PriceTickUtil;
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
 * Enforces exchange tick-size rounding on stops/targets and guards against circuit freeze traps.
 */
@Component
public class FchbStrategy implements Strategy {

    private static final Logger log = LoggerFactory.getLogger(FchbStrategy.class);

    public static final int STRATEGY_ID = 2;
    public static final String STRATEGY_KEY = "FCHB";
    public static final double TARGET_1_R_MULTIPLE = 0.73;

    private static final LocalTime ENTRY_WINDOW_START = LocalTime.of(9, 30);
    private static final LocalTime ENTRY_WINDOW_END = LocalTime.of(10, 45);

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

        // Opening 5-Minute Turnover Floor (Minimum Rs. 25 Lakhs to ensure institutional liquidity)
        Long firstCandleVolume = daily.getFirstCandleVolume();
        if (firstCandleVolume != null && firstCandleClose != null) {
            double openingTurnover = firstCandleVolume * firstCandleClose;
            if (openingTurnover < 25_00_000.0) {
                return StrategyEvaluation.notQualified(0.0, Collections.emptyList(), List.of("MIN_OPENING_TURNOVER_25L"), "OPENING_TURNOVER_TOO_LOW");
            }
        }

        // 3. Current 5M candle trigger validation
        MarketCandle current5m = context.latest5mCandle();
        if (current5m == null) {
            return StrategyEvaluation.notEvaluable("CURRENT_5M_CANDLE_MISSING");
        }
        LocalTime candleTime = current5m.getCandleTime().toLocalTime();
        if (candleTime.isBefore(ENTRY_WINDOW_START) || candleTime.isAfter(ENTRY_WINDOW_END)) {
            return StrategyEvaluation.notQualified(0.0, Collections.emptyList(), List.of("ENTRY_WINDOW_0930_1000"), "OUTSIDE_ENTRY_WINDOW");
        }

        Double close = current5m.getClosePrice();
        if (close == null || close <= firstCandleHigh) {
            return StrategyEvaluation.notQualified(0.0, Collections.emptyList(), List.of("CLOSE_ABOVE_FCH"), "CLOSE_NOT_ABOVE_FCH");
        }

        // Minimum Breakout Margin (Must close at least +0.15% above FCH to filter out micro-pierce bull traps)
        if (close < (firstCandleHigh * 1.0015)) {
            return StrategyEvaluation.notQualified(0.0, Collections.emptyList(), List.of("MIN_BREAKOUT_MARGIN_0_15"), "BREAKOUT_LESS_THAN_0_15_PCT_ABOVE_FCH");
        }

        // Share Price Ceiling (Exclude ultra-high ticket stocks > Rs. 5,000)
        if (close > 5000.0) {
            return StrategyEvaluation.notQualified(0.0, Collections.emptyList(), List.of("PRICE_CEILING_MAX_5000"), "PRICE_EXCEEDS_5000");
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

        // Gate 3: Candle Shape (Body Ratio & Upper Wick Ratio)
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

        // Gate 8: Circuit Limit Room Gate (Requires >= 2.0% headroom to Upper Circuit)
        if (parameters != null && parameters.containsKey("upperCircuit")) {
            try {
                double upperCircuit = Double.parseDouble(parameters.get("upperCircuit"));
                if (upperCircuit > close) {
                    double circuitRoomPct = ((upperCircuit - close) / close) * 100.0;
                    if (circuitRoomPct < 2.0) {
                        lights.add(TrafficLight.RED);
                        failed.add("CIRCUIT_ROOM_LESS_THAN_2_PCT");
                    } else {
                        passed.add("CIRCUIT_ROOM_OK");
                    }
                }
            } catch (Exception ignored) {
            }
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

        // 6. Proposal Generation: Stop Loss and Target Calculation (rounded to exchange tick size)
        double rawStopLoss = firstCandleLow * 0.995;
        double stopLoss = PriceTickUtil.roundStopLoss(rawStopLoss, false, 0.05);
        double riskPerShare = close - stopLoss;
        if (riskPerShare <= 0.0) {
            return StrategyEvaluation.notQualified(score, passed, List.of("INVALID_RISK_PER_SHARE"), "INVALID_RISK_PER_SHARE");
        }
        double rawTargetPrice = close + (TARGET_1_R_MULTIPLE * riskPerShare);
        double targetPrice = PriceTickUtil.roundTarget(rawTargetPrice, false, 0.05);

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
