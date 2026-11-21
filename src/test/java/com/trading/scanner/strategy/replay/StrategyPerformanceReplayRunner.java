package com.trading.scanner.strategy.replay;

import com.trading.scanner.execution.OrderExecutionService;
import com.trading.scanner.model.DailyStockContext;
import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.position.PositionManagerService;
import com.trading.scanner.risk.BrokerChargesCalculator;
import com.trading.scanner.risk.RiskEngine;
import com.trading.scanner.risk.model.PortfolioMode;
import com.trading.scanner.risk.model.RiskEvaluationResult;
import com.trading.scanner.strategy.Strategy;
import com.trading.scanner.strategy.StrategyPositionHandler;
import com.trading.scanner.strategy.model.*;
import com.trading.scanner.util.PriceTickUtil;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

/**
 * Universal In-Memory Replay Runner for evaluating any Strategy across the 8-day real market regime dataset.
 * Directly leverages production PositionManagerService, RiskEngine, and BrokerChargesCalculator.
 */
public class StrategyPerformanceReplayRunner {

    private final double initialCapital;
    private final LocalTime entryWindowStart = LocalTime.of(9, 30);
    private final LocalTime entryWindowEnd;
    private final LocalTime eodSquareOffTime = LocalTime.of(15, 25);

    public StrategyPerformanceReplayRunner() {
        this(50000.0, LocalTime.of(10, 15));
    }

    public StrategyPerformanceReplayRunner(double initialCapital) {
        this(initialCapital, LocalTime.of(10, 15));
    }

    public StrategyPerformanceReplayRunner(double initialCapital, LocalTime entryWindowEnd) {
        this.initialCapital = initialCapital;
        this.entryWindowEnd = entryWindowEnd;
        String brokerProp = System.getProperty("broker", "KOTAK_NEO");
        if ("ANGEL_ONE".equalsIgnoreCase(brokerProp)) {
            BrokerChargesCalculator.setActiveBroker(BrokerChargesCalculator.BrokerModel.ANGEL_ONE);
        } else {
            BrokerChargesCalculator.setActiveBroker(BrokerChargesCalculator.BrokerModel.KOTAK_NEO);
        }
    }

    public LocalTime getEntryWindowEnd() {
        return entryWindowEnd;
    }

    public MultiDayReplayReport replayAllDays(Strategy strategy, StrategyPositionHandler positionHandler) {
        MarketReplayFixtureLoader loader = new MarketReplayFixtureLoader();
        List<HistoricalMarketDay> days = loader.loadAllDays();

        double currentCapital = this.initialCapital;
        double peakCapital = this.initialCapital;
        double maxDrawdown = 0.0;

        List<DailyReplayReport> dailyReports = new ArrayList<>();
        int totalTrades = 0;
        int totalWins = 0;
        int totalLosses = 0;
        double totalGrossPnl = 0.0;
        double totalCharges = 0.0;
        double totalNetPnl = 0.0;

        for (HistoricalMarketDay day : days) {
            DailyReplayReport dayReport = replayDay(strategy, positionHandler, day, currentCapital);
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
        }

        double netReturnPct = (totalNetPnl / initialCapital) * 100.0;
        double winRatePct = totalTrades > 0 ? ((double) totalWins / totalTrades) * 100.0 : 0.0;
        double maxDrawdownPct = peakCapital > 0.0 ? (maxDrawdown / peakCapital) * 100.0 : 0.0;

        return MultiDayReplayReport.builder()
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
    }

    private record CandidateProposal(
            String symbol,
            StrategyProposal proposal,
            MarketCandle latest5m,
            MarketCandle current1m,
            double confidenceScore,
            double volX
    ) {}

    public DailyReplayReport replayDay(Strategy strategy, StrategyPositionHandler positionHandler,
                                      HistoricalMarketDay day, double startingCapital) {
        LocalDate tradingDate = LocalDate.parse(day.getTradingDate());

        // Initialize real production OrderExecutionService, RiskEngine, and PositionManagerService via isolated context
        TestReplayExecutionContext execContext = new TestReplayExecutionContext(positionHandler, startingCapital, tradingDate);
        PositionManagerService positionManager = execContext.getPositionManagerService();
        RiskEngine riskEngine = execContext.getRiskEngine();

        Map<String, ReplayTrade> activeTrades = new HashMap<>();
        List<ReplayTrade> completedTrades = new ArrayList<>();
        List<RejectedSignalAudit> rejectedSignals = new ArrayList<>();
        Set<String> tradedSymbolsToday = new HashSet<>();

        int totalSignals = 0;
        int macroFilterRejections = 0;
        int frictionRejections = 0;
        int riskCapRejections = 0;

        List<LocalTime> timeline = day.getDistinct1mTimes();
        double runningNiftySum = 0.0;
        int runningNiftyCount = 0;

        for (LocalTime time : timeline) {
            MarketCandle nifty1m = day.getNiftyCandle1m(time);
            if (nifty1m == null) {
                continue;
            }

            double tp = (nifty1m.getHighPrice() + nifty1m.getLowPrice() + nifty1m.getClosePrice()) / 3.0;
            runningNiftySum += tp;
            runningNiftyCount++;

            double niftyPrice = nifty1m.getClosePrice();
            double niftyVwap = nifty1m.getVwap() != null ? nifty1m.getVwap() : (runningNiftySum / runningNiftyCount);
            double niftyDistPct = ((niftyPrice - niftyVwap) / niftyVwap) * 100.0;
            boolean niftyAboveVwap = niftyPrice > niftyVwap;
            MarketState.MarketRegime regime = niftyAboveVwap ? MarketState.MarketRegime.BULLISH : MarketState.MarketRegime.BEARISH;
            MarketState marketState = new MarketState(tradingDate, nifty1m.getCandleTime(),
                    niftyPrice, niftyVwap, niftyDistPct, niftyAboveVwap, regime, true, "Historical Replay");

            // -------------------------------------------------------------
            // 1. POSITION MANAGEMENT: Evaluate active positions via PositionManagerService
            // -------------------------------------------------------------
            List<String> currentActiveSymbols = new ArrayList<>(activeTrades.keySet());
            for (String symbol : currentActiveSymbols) {
                MarketCandle current1m = day.get1mCandle(symbol, time);
                if (current1m == null) continue;
                double currentLtp = current1m.getClosePrice();

                List<MarketCandle> candles5m = day.get5mCandlesUpTo(symbol, time);
                MarketCandle latest5m = candles5m.isEmpty() ? null : candles5m.get(candles5m.size() - 1);
                Double latest5mAtr = (latest5m != null && latest5m.getAtr14() != null) ? latest5m.getAtr14() : 5.0;

                PositionSnapshot posBefore = positionManager.getPosition(PortfolioMode.PAPER, symbol);
                if (posBefore != null) {
                    ReplayTrade trade = activeTrades.get(symbol);
                    boolean isShort = "SHORT".equalsIgnoreCase(posBefore.positionSide()) || "SELL".equalsIgnoreCase(posBefore.positionSide());

                    double highPrice = current1m.getHighPrice() != null ? current1m.getHighPrice() : currentLtp;
                    double lowPrice = current1m.getLowPrice() != null ? current1m.getLowPrice() : currentLtp;

                    double firstTick = isShort ? lowPrice : highPrice;
                    double secondTick = isShort ? highPrice : lowPrice;

                    // Tick 1: Check favorable target / trail extreme
                    PositionAction action1 = positionManager.processTick(PortfolioMode.PAPER, symbol, firstTick, time);
                    handleReplayAction(action1, posBefore, trade, firstTick, current1m, completedTrades, activeTrades, execContext);

                    // Tick 2: Check adverse stop loss extreme if still open
                    if (activeTrades.containsKey(symbol)) {
                        posBefore = positionManager.getPosition(PortfolioMode.PAPER, symbol);
                        PositionAction action2 = positionManager.processTick(PortfolioMode.PAPER, symbol, secondTick, time);
                        handleReplayAction(action2, posBefore, trade, secondTick, current1m, completedTrades, activeTrades, execContext);
                    }

                    // Candle Close: Evaluate structural close / wind-down if still open
                    if (activeTrades.containsKey(symbol)) {
                        posBefore = positionManager.getPosition(PortfolioMode.PAPER, symbol);
                        PositionAction action3 = positionManager.processMinuteCandleClose(PortfolioMode.PAPER, symbol, current1m, latest5mAtr, time);
                        handleReplayAction(action3, posBefore, trade, currentLtp, current1m, completedTrades, activeTrades, execContext);
                    }
                }
            }

            // -------------------------------------------------------------
            // 2. STRATEGY ENTRY: Evaluate new breakout proposals on 5M boundary
            // -------------------------------------------------------------
            boolean isEntryMinute = !time.isBefore(entryWindowStart)
                    && !time.isAfter(entryWindowEnd)
                    && (time.getMinute() % 5 == 0);

            if (isEntryMinute) {
                List<CandidateProposal> candidates = new ArrayList<>();

                for (String symbol : day.getDailyContexts().keySet()) {
                    if (positionManager.getPosition(PortfolioMode.PAPER, symbol) != null || tradedSymbolsToday.contains(symbol)) {
                        continue;
                    }

                    DailyStockContext daily = day.getDailyContext(symbol);
                    if (daily == null || Boolean.TRUE.equals(daily.getSkipToday())) {
                        continue;
                    }

                    MarketCandle current1m = day.get1mCandle(symbol, time);
                    if (current1m == null) continue;

                    List<MarketCandle> candles5m = day.get5mCandlesUpTo(symbol, time);
                    if (candles5m.isEmpty()) continue;
                    MarketCandle latest5m = candles5m.get(candles5m.size() - 1);

                    List<MarketCandle> candles15m = day.get15mCandlesUpTo(symbol, time);

                    // Dynamic volX calculation using production VolumeBaselineService time-window baseline
                    int sessionMinute = (int) java.time.Duration.between(LocalTime.of(9, 15), time).toMinutes();
                    Long baselineAvg = day.getVolumeBaseline(symbol, sessionMinute);
                    Double volX = null;
                    if (baselineAvg != null && baselineAvg > 0) {
                        long cumVol = 0L;
                        for (MarketCandle c : day.get1mCandlesUpTo(symbol, time)) {
                            if (c.getVolume() != null) cumVol += c.getVolume();
                        }
                        volX = cumVol / (double) baselineAvg;
                    }
                    // Diagnostic S0 Rule: Never fall back to the old volX formula.
                    // If baselineAvg is missing/null, volX remains null and Gate 2 rejects via VOL_X_MISSING.

                    // Gate 8: Upper circuit limit room check
                    Map<String, String> parameters = new HashMap<>();
                    if (daily.getPrevDayClose() != null && daily.getPrevDayClose() > 0) {
                        double upperCircuit = PriceTickUtil.roundTarget(daily.getPrevDayClose() * 1.20, false, 0.05);
                        parameters.put("upperCircuit", String.valueOf(upperCircuit));
                    }

                    StockContext stockContext = new StockContext(symbol, "NSE", tradingDate, daily,
                            Collections.emptyList(), candles5m, candles15m, false, volX);

                    StrategyEvaluation eval = strategy.evaluate(stockContext, marketState, parameters);
                    if (!eval.isQualified()) {
                        if (eval.failedConditions().contains("NIFTY_VWAP_FILTER")) {
                            macroFilterRejections++;
                            rejectedSignals.add(RejectedSignalAudit.builder()
                                    .tradingDate(day.getTradingDate())
                                    .time(time)
                                    .symbol(symbol)
                                    .price(current1m.getClosePrice())
                                    .rejectionStage("STRATEGY_GATE")
                                    .reason("NIFTY_BELOW_VWAP")
                                    .details(String.format("Nifty=%.1f < VWAP=%.1f (dist=%.2f%%)", niftyPrice, niftyVwap, niftyDistPct))
                                    .build());
                        }
                    } else if (eval.proposal() != null) {
                        candidates.add(new CandidateProposal(symbol, eval.proposal(), latest5m, current1m, eval.confidenceScore(), volX));
                    }
                }

                // Sort candidates by latest 5M candle createdAt timestamp (matching live WebSocket arrival order)
                candidates.sort((c1, c2) -> {
                    LocalDateTime t1 = c1.latest5m().getCreatedAt();
                    LocalDateTime t2 = c2.latest5m().getCreatedAt();
                    if (t1 != null && t2 != null && !t1.equals(t2)) {
                        return t1.compareTo(t2);
                    }
                    return Double.compare(c2.volX(), c1.volX());
                });

                // Route candidates through real OrderExecutionService in sorted arrival order
                for (CandidateProposal cand : candidates) {
                    totalSignals++;
                    StrategyProposal proposal = cand.proposal();
                    String symbol = cand.symbol();
                    MarketCandle current1m = cand.current1m();

                    OrderExecutionService.ExecutionResult execRes = execContext.processCandidateProposal(proposal);

                    if (execRes.accepted() && execRes.executedQuantity() >= 2) {
                        int qty = execRes.executedQuantity();
                        double fillPrice = execRes.executedPrice();
                        double stopLoss = execRes.structuralStopLoss();
                        double target1 = execRes.reAnchoredTarget1();
                        tradedSymbolsToday.add(symbol);

                        double entryTurnover = qty * fillPrice;
                        double buyCharges = BrokerChargesCalculator.calculateBuyLegCharges(entryTurnover);

                        ReplayTrade trade = ReplayTrade.builder()
                                .tradeId(symbol + "-" + day.getTradingDate() + "-" + time)
                                .symbol(symbol)
                                .direction("BUY")
                                .entryTime(current1m.getCandleTime())
                                .entryPrice(fillPrice)
                                .quantity(qty)
                                .initialStopLoss(stopLoss)
                                .target1Price(target1)
                                .grossPnl(0.0)
                                .charges(buyCharges)
                                .netPnl(-buyCharges)
                                .build();
                        activeTrades.put(symbol, trade);

                    } else {
                        String reason = execRes.rejectionReason();
                        if ("EXCESSIVE_FRICTION_DRAG".equals(reason)) {
                            frictionRejections++;
                        } else if ("PORTFOLIO_OPEN_RISK_CAP_EXCEEDED".equals(reason)
                                || "MAX_CONCURRENT_POSITIONS_REACHED".equals(reason)
                                || "DAILY_LOSS_KILL_SWITCH_TRIGGERED".equals(reason)) {
                            riskCapRejections++;
                        }

                        rejectedSignals.add(RejectedSignalAudit.builder()
                                .tradingDate(day.getTradingDate())
                                .time(time)
                                .symbol(symbol)
                                .price(proposal.entryPrice())
                                .rejectionStage("ORDER_EXECUTION_ENGINE")
                                .reason(reason)
                                .details(String.format("1R Risk=Rs.%.1f, Stop=Rs.%.2f, Target=Rs.%.2f, volX=%.2f",
                                        (proposal.entryPrice() - proposal.initialStopLoss()), proposal.initialStopLoss(), proposal.initialTarget(), cand.volX()))
                                .build());
                    }
                }
            }
        }

        // Aggregate daily metrics
        double dayGross = 0.0;
        double dayCharges = 0.0;
        double dayNet = 0.0;
        int winCount = 0;
        int lossCount = 0;

        for (ReplayTrade t : completedTrades) {
            dayGross += t.getGrossPnl();
            dayCharges += t.getCharges();
            dayNet += t.getNetPnl();
            if (t.isProfitable()) {
                winCount++;
            } else {
                lossCount++;
            }
        }

        double endingCapital = startingCapital + dayNet;

        return DailyReplayReport.builder()
                .tradingDate(day.getTradingDate())
                .scenarioTag(day.getScenarioTag())
                .scenarioDescription(day.getScenarioDescription())
                .startingCapital(startingCapital)
                .endingCapital(endingCapital)
                .grossPnl(dayGross)
                .totalCharges(dayCharges)
                .netPnl(dayNet)
                .totalSignals(totalSignals)
                .executedTrades(completedTrades.size())
                .winTrades(winCount)
                .lossTrades(lossCount)
                .trades(completedTrades)
                .rejectedSignals(rejectedSignals)
                .macroFilterRejections(macroFilterRejections)
                .frictionRejections(frictionRejections)
                .riskCapRejections(riskCapRejections)
                .build();
    }

    private void handleReplayAction(PositionAction action, PositionSnapshot posBefore, ReplayTrade trade,
                                    double executedPrice, MarketCandle current1m,
                                    List<ReplayTrade> completedTrades, Map<String, ReplayTrade> activeTrades,
                                    TestReplayExecutionContext execContext) {
        if (action == null || trade == null || posBefore == null) {
            return;
        }
        if (action.type() == PositionAction.ActionType.PARTIAL_EXIT) {
            int initialQty = trade.getQuantity();
            int exitQty = (int) Math.round(initialQty * 0.5);
            exitQty = Math.max(1, Math.min(posBefore.currentQuantity() - 1, exitQty));

            execContext.executeTarget1PartialExit(trade.getSymbol(), executedPrice);

            if (exitQty > 0) {
                double sellTurnover = exitQty * executedPrice;
                double gross = (executedPrice - trade.getEntryPrice()) * exitQty;
                double sellCharges = BrokerChargesCalculator.calculateSellLegCharges(sellTurnover);
                double net = gross - sellCharges;

                trade.setTarget1Hit(true);
                trade.setTarget1ExitPrice(executedPrice);
                trade.setTarget1ExitTime(current1m.getCandleTime());
                trade.setTarget1Quantity(exitQty);
                trade.setGrossPnl(trade.getGrossPnl() + gross);
                trade.setCharges(trade.getCharges() + sellCharges);
                trade.setNetPnl(trade.getNetPnl() + net);
            }
        } else if (action.type() == PositionAction.ActionType.FULL_EXIT) {
            if (action.reason() != null && action.reason().contains("STOP_LOSS")) {
                execContext.handleRestingStopLossFilled(trade.getSymbol(), posBefore, executedPrice);
            } else {
                execContext.executeTacticalExit(trade.getSymbol(), executedPrice, action.reason());
            }

            int exitQty = posBefore.currentQuantity();
            double sellTurnover = exitQty * executedPrice;
            double gross = (executedPrice - trade.getEntryPrice()) * exitQty;
            double sellCharges = BrokerChargesCalculator.calculateSellLegCharges(sellTurnover);
            double net = gross - sellCharges;

            trade.setFinalExitPrice(executedPrice);
            trade.setFinalExitTime(current1m.getCandleTime());
            trade.setFinalExitQuantity(exitQty);
            trade.setExitReason(action.reason());
            double peak = posBefore.peakPrice() != null ? Math.max(posBefore.peakPrice(), executedPrice) : executedPrice;
            double trough = posBefore.troughPrice() != null ? Math.min(posBefore.troughPrice(), executedPrice) : executedPrice;
            trade.setPeakPrice(peak);
            trade.setTroughPrice(trough);
            trade.setGrossPnl(trade.getGrossPnl() + gross);
            trade.setCharges(trade.getCharges() + sellCharges);
            trade.setNetPnl(trade.getNetPnl() + net);

            completedTrades.add(trade);
            activeTrades.remove(posBefore.symbol());
        }
    }
}
