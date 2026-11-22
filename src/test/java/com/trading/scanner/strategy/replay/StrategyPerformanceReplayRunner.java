package com.trading.scanner.strategy.replay;

import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.execution.LiveStrategyExecutionConductor;
import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.DailyStockContext;
import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.model.SimulatedPosition;
import com.trading.scanner.model.VolumeTimeWindowBaseline;
import com.trading.scanner.position.PositionManagerService;
import com.trading.scanner.repository.DailyStockContextRepository;
import com.trading.scanner.repository.MarketCandleRepository;
import com.trading.scanner.repository.StockPriceRepository;
import com.trading.scanner.repository.VolumeDailyBaselineRepository;
import com.trading.scanner.repository.VolumeTimeWindowBaselineRepository;
import com.trading.scanner.risk.BrokerChargesCalculator;
import com.trading.scanner.risk.model.PortfolioMode;
import com.trading.scanner.service.engine.VolumeBaselineService;
import com.trading.scanner.strategy.Strategy;
import com.trading.scanner.strategy.StrategyPositionHandler;
import com.trading.scanner.strategy.cache.StockContextCache;
import com.trading.scanner.strategy.engine.MasterMarketFilterService;
import com.trading.scanner.strategy.fchb.FchbStrategy;
import com.trading.scanner.strategy.model.*;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Replays historical days through the SAME production classes that run live: the candle-finalized event
 * handler of {@link LiveStrategyExecutionConductor}, {@link StockContextCache}, {@link MasterMarketFilterService},
 * {@link FchbStrategy}, the position handler, RiskEngine, PositionManagerService and OrderExecutionService.
 *
 * Only the clock, the candle source (historical rows instead of the websocket) and the persistence layer
 * (in-memory repositories) are replaced. There is no replay-specific strategy, volX, market-state, entry or
 * exit logic here: any behaviour difference from live must be fixed in the production class, not here.
 */
public class StrategyPerformanceReplayRunner {

    /** Order in which a 1M bar's extremes are presented as ticks. Live tick order inside a bar is unknowable. */
    public enum IntrabarOrder { ADVERSE_FIRST, FAVORABLE_FIRST }

    /** One 5M entry decision, offered to an optional observer for diagnostics (funnels, audits). */
    public record Decision(LocalDateTime decisionTime, String symbol, StockContext context,
                           MarketState marketState, StrategyEvaluation evaluation) {}

    private static final LocalTime SESSION_START = LocalTime.of(9, 15);
    private static final LocalTime SESSION_END = LocalTime.of(15, 30);
    private static final LocalTime HARD_CLOSE_SWEEP = LocalTime.of(14, 55);
    private static final String EXCHANGE = "NSE";
    private static final String BENCHMARK = "NIFTY";

    private final double initialCapital;
    private final IntrabarOrder intrabarOrder;
    private Consumer<Decision> decisionObserver;

    public StrategyPerformanceReplayRunner() {
        this(50000.0);
    }

    public StrategyPerformanceReplayRunner(double initialCapital) {
        this(initialCapital, IntrabarOrder.ADVERSE_FIRST);
    }

    public StrategyPerformanceReplayRunner(double initialCapital, IntrabarOrder intrabarOrder) {
        this.initialCapital = initialCapital;
        this.intrabarOrder = intrabarOrder;
        String brokerProp = System.getProperty("broker", "KOTAK_NEO");
        BrokerChargesCalculator.setActiveBroker("ANGEL_ONE".equalsIgnoreCase(brokerProp)
                ? BrokerChargesCalculator.BrokerModel.ANGEL_ONE
                : BrokerChargesCalculator.BrokerModel.KOTAK_NEO);
    }

    public void setDecisionObserver(Consumer<Decision> decisionObserver) {
        this.decisionObserver = decisionObserver;
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

    /** Mutable replay clock handed to every production class that asks for the time. */
    private static final class ReplayClock implements TimeProvider {
        private final LocalDate date;
        private volatile LocalDateTime now;

        ReplayClock(LocalDate date) {
            this.date = date;
            this.now = date.atTime(SESSION_START);
        }

        void set(LocalDateTime now) {
            this.now = now;
        }

        @Override
        public LocalDate today() {
            return date;
        }

        @Override
        public LocalDateTime nowDateTime() {
            return now;
        }
    }

    public DailyReplayReport replayDay(Strategy strategy, StrategyPositionHandler positionHandler,
                                      HistoricalMarketDay day, double startingCapital) {
        if (!(strategy instanceof FchbStrategy fchb)) {
            throw new IllegalArgumentException("Replay drives the production FCHB conductor; got " + strategy);
        }
        LocalDate tradingDate = LocalDate.parse(day.getTradingDate());
        ReplayClock clock = new ReplayClock(tradingDate);

        TestReplayExecutionContext exec = new TestReplayExecutionContext(positionHandler, startingCapital, tradingDate);
        PositionManagerService positions = exec.getPositionManagerService();

        TradingCalendar calendar = Mockito.mock(TradingCalendar.class);
        when(calendar.isTradingDay(any())).thenReturn(true);

        StockContextCache cache = new StockContextCache(
                Mockito.mock(MarketCandleRepository.class), Mockito.mock(DailyStockContextRepository.class));
        cache.setVolumeBaselineService(baselineService(day, calendar, clock));
        for (DailyStockContext daily : day.getDailyContexts().values()) {
            cache.updateDailyContext(daily);
        }

        MasterMarketFilterService marketFilter = new MasterMarketFilterService(
                Mockito.mock(MarketCandleRepository.class), clock, cache);

        LiveStrategyExecutionConductor conductor = new LiveStrategyExecutionConductor(
                cache, fchb, marketFilter, exec.getOrderExecutionService(), positions,
                exec.getOrderAdapter(), exec.getLockManager(), clock, calendar);

        Map<LocalTime, List<MarketCandle>> oneMin = byStartTime(day.getCandles1m());
        Map<LocalTime, List<MarketCandle>> fiveMin = byStartTime(day.getCandles5m());
        Map<LocalTime, List<MarketCandle>> fifteenMin = byStartTime(day.getCandles15m());

        Map<String, Double> lastClose = new HashMap<>();
        Map<String, LocalDateTime> entryTimes = new HashMap<>();
        Map<String, LocalDateTime> exitTimes = new HashMap<>();
        Set<String> target1Seen = new HashSet<>();
        Map<String, Double> peakSeen = new HashMap<>();

        for (LocalTime minute = SESSION_START; minute.isBefore(SESSION_END); minute = minute.plusMinutes(1)) {
            LocalDateTime minuteStart = tradingDate.atTime(minute);
            List<MarketCandle> candlesThisMinute = sortedBenchmarkFirst(oneMin.get(minute));

            // 1. Ticks inside the minute drive stop-loss triggers, Target 1 and peak tracking (production path).
            for (MarketCandle c : candlesThisMinute) {
                if (positions.getPosition(PortfolioMode.PAPER, c.getSymbol()) == null) {
                    continue;
                }
                double adverse = c.getLowPrice();
                double favorable = c.getHighPrice();
                double first = intrabarOrder == IntrabarOrder.ADVERSE_FIRST ? adverse : favorable;
                double second = intrabarOrder == IntrabarOrder.ADVERSE_FIRST ? favorable : adverse;
                conductor.processIncomingTick(c.getSymbol(), EXCHANGE, first, minuteStart);
                conductor.processIncomingTick(c.getSymbol(), EXCHANGE, second, minuteStart);
                notePositionState(positions, c.getSymbol(), target1Seen, peakSeen);
            }

            // 2. The minute closes: candles finalize in production order (1M, then 5M, then 15M) per symbol,
            //    the benchmark first (it is queued ahead of stocks live), then stocks alphabetically.
            LocalTime close = minute.plusMinutes(1);
            LocalDateTime closeTime = tradingDate.atTime(close);
            clock.set(closeTime);

            boolean fiveBoundary = close.getMinute() % 5 == 0;
            boolean fifteenBoundary = close.getMinute() % 15 == 0;
            Map<String, MarketCandle> oneNow = bySymbol(candlesThisMinute);
            Map<String, MarketCandle> fiveNow = fiveBoundary ? bySymbol(fiveMin.get(close.minusMinutes(5))) : Map.of();
            Map<String, MarketCandle> fifteenNow = fifteenBoundary ? bySymbol(fifteenMin.get(close.minusMinutes(15))) : Map.of();

            Set<String> symbolsThisMinute = new TreeSet<>(benchmarkFirstComparator());
            symbolsThisMinute.addAll(oneNow.keySet());
            symbolsThisMinute.addAll(fiveNow.keySet());
            symbolsThisMinute.addAll(fifteenNow.keySet());

            for (String symbol : symbolsThisMinute) {
                boolean isIndex = BENCHMARK.equals(symbol);
                MarketCandle c1 = oneNow.get(symbol);
                if (c1 != null) {
                    lastClose.put(symbol, c1.getClosePrice());
                    cache.onCandleFinalized(c1);
                    conductor.onCandleFinalized(new CandleFinalizedEvent(
                            symbol, EXCHANGE, CandleTimeframe.ONE_MINUTE, c1.getCandleTime(), c1, isIndex));
                    notePositionState(positions, symbol, target1Seen, peakSeen);
                }
                MarketCandle c5 = fiveNow.get(symbol);
                if (c5 != null) {
                    cache.onCandleFinalized(c5);
                    if (decisionObserver != null && !isIndex) {
                        observeDecision(cache, marketFilter, fchb, tradingDate, closeTime, symbol);
                    }
                    boolean hadPosition = positions.getPosition(PortfolioMode.PAPER, symbol) != null;
                    conductor.onCandleFinalized(new CandleFinalizedEvent(
                            symbol, EXCHANGE, CandleTimeframe.FIVE_MINUTE, c5.getCandleTime(), c5, isIndex));
                    if (!hadPosition && positions.getPosition(PortfolioMode.PAPER, symbol) != null) {
                        entryTimes.put(symbol, closeTime);
                    }
                }
                MarketCandle c15 = fifteenNow.get(symbol);
                if (c15 != null) {
                    cache.onCandleFinalized(c15);
                }
            }

            // Record exits observed this minute (position was open before the minute, gone now).
            for (String symbol : entryTimes.keySet()) {
                if (!exitTimes.containsKey(symbol) && positions.getPosition(PortfolioMode.PAPER, symbol) == null) {
                    exitTimes.put(symbol, closeTime);
                }
            }

            // 3. End-of-day hard close sweep (same rule as MarketScheduler.scheduledEodHardCloseSweep).
            if (close.equals(HARD_CLOSE_SWEEP)) {
                for (PositionSnapshot open : new ArrayList<>(positions.getActivePositions(PortfolioMode.PAPER).values())) {
                    double price = lastClose.getOrDefault(open.symbol(), open.entryPrice());
                    exec.executeTacticalExit(open.symbol(), price, "SCHEDULED_EOD_HARD_CLOSE_SWEEP");
                    exitTimes.putIfAbsent(open.symbol(), closeTime);
                }
            }
        }

        return buildReport(day, startingCapital, exec, entryTimes, exitTimes, target1Seen, peakSeen);
    }

    private void observeDecision(StockContextCache cache, MasterMarketFilterService marketFilter, FchbStrategy fchb,
                                 LocalDate tradingDate, LocalDateTime decisionTime, String symbol) {
        Optional<StockContext> context = cache.getStockContext(symbol, EXCHANGE, tradingDate);
        if (context.isEmpty()) {
            return;
        }
        MarketState state = marketFilter.evaluateCurrentState(tradingDate);
        StrategyEvaluation evaluation = fchb.evaluate(context.get(), state, Collections.emptyMap());
        decisionObserver.accept(new Decision(decisionTime, symbol, context.get(), state, evaluation));
    }

    private void notePositionState(PositionManagerService positions, String symbol,
                                   Set<String> target1Seen, Map<String, Double> peakSeen) {
        PositionSnapshot snap = positions.getPosition(PortfolioMode.PAPER, symbol);
        if (snap == null) {
            return;
        }
        if (snap.target1Booked()) {
            target1Seen.add(symbol);
        }
        if (snap.peakPrice() != null) {
            peakSeen.merge(symbol, snap.peakPrice(), Math::max);
        }
    }

    private DailyReplayReport buildReport(HistoricalMarketDay day, double startingCapital,
                                          TestReplayExecutionContext exec,
                                          Map<String, LocalDateTime> entryTimes,
                                          Map<String, LocalDateTime> exitTimes,
                                          Set<String> target1Seen, Map<String, Double> peakSeen) {
        List<ReplayTrade> trades = new ArrayList<>();
        double gross = 0.0;
        double charges = 0.0;
        double net = 0.0;
        int wins = 0;
        int losses = 0;

        List<SimulatedPosition> closed = new ArrayList<>(exec.getCompletedPositions());
        closed.sort(Comparator.comparing(SimulatedPosition::getSymbol));
        for (SimulatedPosition p : closed) {
            double g = p.getGrossPnl() != null ? p.getGrossPnl() : 0.0;
            double ch = p.getStatutoryCharges() != null ? p.getStatutoryCharges() : 0.0;
            double n = p.getNetPnl() != null ? p.getNetPnl() : g - ch;
            trades.add(ReplayTrade.builder()
                    .tradeId(p.getSymbol() + "-" + day.getTradingDate())
                    .symbol(p.getSymbol())
                    .direction("BUY")
                    .entryTime(entryTimes.get(p.getSymbol()))
                    .entryPrice(p.getEntryPrice() != null ? p.getEntryPrice() : 0.0)
                    .quantity(p.getInitialQuantity() != null ? p.getInitialQuantity() : 0)
                    .initialStopLoss(p.getInitialStopLoss() != null ? p.getInitialStopLoss() : 0.0)
                    .target1Price(p.getInitialTarget() != null ? p.getInitialTarget() : 0.0)
                    .target1Hit(target1Seen.contains(p.getSymbol()))
                    .finalExitPrice(p.getExitPrice())
                    .finalExitTime(exitTimes.get(p.getSymbol()))
                    .exitReason(p.getExitReason())
                    .grossPnl(g)
                    .charges(ch)
                    .netPnl(n)
                    .peakPrice(peakSeen.get(p.getSymbol()))
                    .troughPrice(p.getTroughPrice())
                    .build());
            gross += g;
            charges += ch;
            net += n;
            if (n > 0.0) {
                wins++;
            } else {
                losses++;
            }
        }

        return DailyReplayReport.builder()
                .tradingDate(day.getTradingDate())
                .scenarioTag(day.getScenarioTag())
                .scenarioDescription(day.getScenarioDescription())
                .startingCapital(startingCapital)
                .endingCapital(startingCapital + net)
                .grossPnl(gross)
                .totalCharges(charges)
                .netPnl(net)
                .totalSignals(trades.size())
                .executedTrades(trades.size())
                .winTrades(wins)
                .lossTrades(losses)
                .trades(trades)
                .rejectedSignals(new ArrayList<>())
                .build();
    }

    private VolumeBaselineService baselineService(HistoricalMarketDay day, TradingCalendar calendar, TimeProvider clock) {
        VolumeTimeWindowBaselineRepository repo = Mockito.mock(VolumeTimeWindowBaselineRepository.class);
        when(repo.findBySymbolAndExchangeAndTradingDateAndSessionMinute(anyString(), anyString(), any(), anyInt()))
                .thenAnswer(inv -> {
                    Long avg = day.getVolumeBaseline(inv.getArgument(0), inv.getArgument(3));
                    return avg == null
                            ? Optional.empty()
                            : Optional.of(VolumeTimeWindowBaseline.builder().avgCumulativeVolume20(avg).build());
                });
        return new VolumeBaselineService(
                Mockito.mock(DailyStockContextRepository.class),
                Mockito.mock(StockPriceRepository.class),
                Mockito.mock(MarketCandleRepository.class),
                Mockito.mock(VolumeDailyBaselineRepository.class),
                repo, calendar, clock);
    }

    private static Map<LocalTime, List<MarketCandle>> byStartTime(List<MarketCandle> candles) {
        Map<LocalTime, List<MarketCandle>> map = new HashMap<>();
        for (MarketCandle c : candles) {
            map.computeIfAbsent(c.getCandleTime().toLocalTime(), k -> new ArrayList<>()).add(c);
        }
        return map;
    }

    private static Map<String, MarketCandle> bySymbol(List<MarketCandle> candles) {
        Map<String, MarketCandle> map = new HashMap<>();
        if (candles != null) {
            candles.forEach(c -> map.put(c.getSymbol(), c));
        }
        return map;
    }

    private static List<MarketCandle> sortedBenchmarkFirst(List<MarketCandle> candles) {
        if (candles == null) {
            return List.of();
        }
        List<MarketCandle> sorted = new ArrayList<>(candles);
        sorted.sort(Comparator.comparing(MarketCandle::getSymbol, benchmarkFirstComparator()));
        return sorted;
    }

    private static Comparator<String> benchmarkFirstComparator() {
        return (a, b) -> {
            if (a.equals(b)) {
                return 0;
            }
            if (BENCHMARK.equals(a)) {
                return -1;
            }
            if (BENCHMARK.equals(b)) {
                return 1;
            }
            return a.compareTo(b);
        };
    }
}
