package com.trading.scanner.strategy.replay;

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

import java.time.LocalDate;
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
        this(30000.0, LocalTime.of(10, 0));
    }

    public StrategyPerformanceReplayRunner(double initialCapital) {
        this(initialCapital, LocalTime.of(10, 0));
    }

    public StrategyPerformanceReplayRunner(double initialCapital, LocalTime entryWindowEnd) {
        this.initialCapital = initialCapital;
        this.entryWindowEnd = entryWindowEnd;
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

    public DailyReplayReport replayDay(Strategy strategy, StrategyPositionHandler positionHandler,
                                      HistoricalMarketDay day, double startingCapital) {
        LocalDate tradingDate = LocalDate.parse(day.getTradingDate());

        // Initialize production Risk Engine and production Position Manager Service
        RiskEngine riskEngine = new RiskEngine();
        riskEngine.initializePortfolio(PortfolioMode.PAPER, startingCapital, tradingDate);
        PositionManagerService positionManager = new PositionManagerService(riskEngine, List.of(positionHandler));

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

            // Evaluate all 9 stocks at this minute
            for (String symbol : day.getDailyContexts().keySet()) {
                MarketCandle current1m = day.get1mCandle(symbol, time);
                if (current1m == null) continue;
                double currentLtp = current1m.getClosePrice();

                List<MarketCandle> candles5m = day.get5mCandlesUpTo(symbol, time);
                MarketCandle latest5m = candles5m.isEmpty() ? null : candles5m.get(candles5m.size() - 1);
                Double latest5mAtr = (latest5m != null && latest5m.getAtr14() != null) ? latest5m.getAtr14() : 5.0;

                // -------------------------------------------------------------
                // 1. POSITION MANAGEMENT: Evaluate active positions via PositionManagerService
                // -------------------------------------------------------------
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
                    handleReplayAction(action1, posBefore, trade, firstTick, current1m, completedTrades, activeTrades);

                    // Tick 2: Check adverse stop loss extreme if still open
                    if (activeTrades.containsKey(symbol)) {
                        posBefore = positionManager.getPosition(PortfolioMode.PAPER, symbol);
                        PositionAction action2 = positionManager.processTick(PortfolioMode.PAPER, symbol, secondTick, time);
                        handleReplayAction(action2, posBefore, trade, secondTick, current1m, completedTrades, activeTrades);
                    }

                    // Candle Close: Evaluate structural close / wind-down if still open
                    if (activeTrades.containsKey(symbol)) {
                        posBefore = positionManager.getPosition(PortfolioMode.PAPER, symbol);
                        PositionAction action3 = positionManager.processMinuteCandleClose(PortfolioMode.PAPER, symbol, current1m, latest5mAtr, time);
                        handleReplayAction(action3, posBefore, trade, currentLtp, current1m, completedTrades, activeTrades);
                    }
                }

                // -------------------------------------------------------------
                // 2. STRATEGY ENTRY: Evaluate new breakout proposals
                // -------------------------------------------------------------
                boolean canEnter = positionManager.getPosition(PortfolioMode.PAPER, symbol) == null
                        && !tradedSymbolsToday.contains(symbol)
                        && !time.isBefore(entryWindowStart)
                        && !time.isAfter(entryWindowEnd)
                        && (time.getMinute() % 5 == 0);

                if (canEnter) {
                    DailyStockContext daily = day.getDailyContext(symbol);
                    if (daily == null || Boolean.TRUE.equals(daily.getSkipToday())) {
                        continue;
                    }

                    List<MarketCandle> candles15m = day.get15mCandlesUpTo(symbol, time);
                    StockContext stockContext = new StockContext(symbol, "NSE", tradingDate, daily,
                            Collections.emptyList(), candles5m, candles15m, false, 2.5);

                    StrategyEvaluation eval = strategy.evaluate(stockContext, marketState, Collections.emptyMap());
                    if (!eval.isQualified()) {
                        if (eval.failedConditions().contains("NIFTY_VWAP_FILTER")) {
                            macroFilterRejections++;
                            rejectedSignals.add(RejectedSignalAudit.builder()
                                    .tradingDate(day.getTradingDate())
                                    .time(time)
                                    .symbol(symbol)
                                    .price(currentLtp)
                                    .rejectionStage("STRATEGY_GATE")
                                    .reason("NIFTY_BELOW_VWAP")
                                    .details(String.format("Nifty=%.1f < VWAP=%.1f (dist=%.2f%%)", niftyPrice, niftyVwap, niftyDistPct))
                                    .build());
                        }
                    } else {
                        totalSignals++;
                        StrategyProposal proposal = eval.proposal();
                        RiskEvaluationResult riskRes = riskEngine.evaluateProposal(proposal, PortfolioMode.PAPER);

                        if (riskRes.approved() && riskRes.allocatedQuantity() >= 2) {
                            int qty = riskRes.allocatedQuantity();
                            tradedSymbolsToday.add(symbol);

                            PositionSnapshot pos = new PositionSnapshot(
                                    completedTrades.size() + activeTrades.size() + 1,
                                    strategy.getStrategyId(), symbol, "NSE", "LONG",
                                    qty, qty, proposal.entryPrice(), proposal.initialStopLoss(),
                                    proposal.initialTarget(), proposal.structuralInvalidationLevel(),
                                    false, proposal.entryPrice(), current1m.getCandleTime());

                            // Directly register position into production PositionManagerService
                            positionManager.registerPosition(PortfolioMode.PAPER, pos);

                            double entryTurnover = qty * proposal.entryPrice();
                            double buyCharges = BrokerChargesCalculator.calculateBuyLegCharges(entryTurnover);

                            ReplayTrade trade = ReplayTrade.builder()
                                    .tradeId(symbol + "-" + day.getTradingDate() + "-" + time)
                                    .symbol(symbol)
                                    .direction("BUY")
                                    .entryTime(current1m.getCandleTime())
                                    .entryPrice(proposal.entryPrice())
                                    .quantity(qty)
                                    .initialStopLoss(proposal.initialStopLoss())
                                    .target1Price(proposal.initialTarget())
                                    .grossPnl(0.0)
                                    .charges(buyCharges)
                                    .netPnl(-buyCharges)
                                    .build();
                            activeTrades.put(symbol, trade);

                        } else {
                            String reason = riskRes.rejectionReason();
                            if ("EXCESSIVE_FRICTION_DRAG".equals(reason)) {
                                frictionRejections++;
                            } else if ("PORTFOLIO_OPEN_RISK_CAP_EXCEEDED".equals(reason)
                                    || "MAX_CONCURRENT_POSITIONS_REACHED".equals(reason)) {
                                riskCapRejections++;
                            }

                            rejectedSignals.add(RejectedSignalAudit.builder()
                                    .tradingDate(day.getTradingDate())
                                    .time(time)
                                    .symbol(symbol)
                                    .price(proposal.entryPrice())
                                    .rejectionStage("RISK_ENGINE")
                                    .reason(reason)
                                    .details(String.format("1R Risk=Rs.%.1f, Stop=Rs.%.2f, Target=Rs.%.2f",
                                            (proposal.entryPrice() - proposal.initialStopLoss()), proposal.initialStopLoss(), proposal.initialTarget()))
                                    .build());
                        }
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
                                    List<ReplayTrade> completedTrades, Map<String, ReplayTrade> activeTrades) {
        if (action == null || trade == null || posBefore == null) {
            return;
        }
        if (action.type() == PositionAction.ActionType.PARTIAL_EXIT) {
            int partialQty = posBefore.currentQuantity() / 2;
            if (partialQty > 0) {
                double sellTurnover = partialQty * executedPrice;
                double gross = (executedPrice - posBefore.entryPrice()) * partialQty;
                double sellCharges = BrokerChargesCalculator.calculateSellLegCharges(sellTurnover);
                double net = gross - sellCharges;

                trade.setTarget1Hit(true);
                trade.setTarget1ExitPrice(executedPrice);
                trade.setTarget1ExitTime(current1m.getCandleTime());
                trade.setTarget1Quantity(partialQty);
                trade.setGrossPnl(trade.getGrossPnl() + gross);
                trade.setCharges(trade.getCharges() + sellCharges);
                trade.setNetPnl(trade.getNetPnl() + net);
            }
        } else if (action.type() == PositionAction.ActionType.FULL_EXIT) {
            int exitQty = posBefore.currentQuantity();
            double sellTurnover = exitQty * executedPrice;
            double gross = (executedPrice - posBefore.entryPrice()) * exitQty;
            double sellCharges = BrokerChargesCalculator.calculateSellLegCharges(sellTurnover);
            double net = gross - sellCharges;

            trade.setFinalExitPrice(executedPrice);
            trade.setFinalExitTime(current1m.getCandleTime());
            trade.setFinalExitQuantity(exitQty);
            trade.setExitReason(action.reason());
            trade.setGrossPnl(trade.getGrossPnl() + gross);
            trade.setCharges(trade.getCharges() + sellCharges);
            trade.setNetPnl(trade.getNetPnl() + net);

            completedTrades.add(trade);
            activeTrades.remove(posBefore.symbol());
        }
    }
}
