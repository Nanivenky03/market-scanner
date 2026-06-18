package com.trading.scanner.service.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.*;
import com.trading.scanner.repository.*;
import com.trading.scanner.strategy.*;
import com.trading.scanner.strategy.StrategyScoringModels.StrategyScoreResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class HistoricalSimulationExecutionService {

    private final SimulationRunGroupRepository runGroupRepository;
    private final SimulationVariantRepository variantRepository;
    private final SimulationTradeRepository tradeRepository;
    private final MarketCandleRepository marketCandleRepository;
    private final StockPriceRepository stockPriceRepository;
    private final StockUniverseRepository stockUniverseRepository;
    private final StrategyCatalogService strategyCatalogService;
    private final StrategyScoringService strategyScoringService;
    private final TimeProvider timeProvider;
    private final ObjectMapper objectMapper;

    @Transactional
    public ExecuteRunGroupResult executeRunGroup(Integer runGroupId) {
        SimulationRunGroup group = runGroupRepository.findById(runGroupId)
                .orElseThrow(() -> new IllegalArgumentException("Simulation run group not found: " + runGroupId));

        List<SimulationVariant> variants = variantRepository.findByRunGroupIdOrderByIdAsc(runGroupId);
        if (variants.isEmpty()) {
            throw new IllegalArgumentException("No simulation variants found for run group: " + runGroupId);
        }

        group.setStatus(SimulationStatus.RUNNING);
        group.setCompletedAt(null);
        runGroupRepository.save(group);

        int variantsExecuted = 0;
        int variantsFailed = 0;
        int tradesCreated = 0;

        for (SimulationVariant variant : variants) {
            try {
                tradesCreated += executeVariant(group, variant);
                variantsExecuted++;
            } catch (Exception ex) {
                log.warn("Historical simulation variant execution failed for variantId={} strategyId={}: {}",
                        variant.getId(), variant.getStrategyId(), ex.getMessage(), ex);

                variant.setStatus(SimulationStatus.FAILED);
                variant.setCompletedAt(timeProvider.nowDateTime());
                variant.setNotes("Execution failed: " + ex.getMessage());
                variantRepository.save(variant);
                variantsFailed++;
            }
        }

        group.setCompletedAt(timeProvider.nowDateTime());
        group.setStatus(variantsFailed > 0 ? SimulationStatus.FAILED : SimulationStatus.COMPLETED);
        runGroupRepository.save(group);

        return new ExecuteRunGroupResult(
                group.getId(),
                variantsExecuted,
                variantsFailed,
                tradesCreated,
                "Historical simulation execution completed");
    }

    @Transactional(readOnly = true)
    public List<SimulationTrade> tradesForVariant(Integer variantId) {
        return tradeRepository.findByVariantIdOrderBySignalDateAsc(variantId);
    }

    @Transactional(readOnly = true)
    public RunGroupSummary summarizeRunGroup(Integer runGroupId) {
        SimulationRunGroup group = runGroupRepository.findById(runGroupId)
                .orElseThrow(() -> new IllegalArgumentException("Simulation run group not found: " + runGroupId));

        List<SimulationVariant> variants = variantRepository.findByRunGroupIdOrderByIdAsc(runGroupId);

        int totalTrades = variants.stream().mapToInt(v -> safeInt(v.getTotalTrades())).sum();
        int totalWins = variants.stream().mapToInt(v -> safeInt(v.getWinningTrades())).sum();
        int totalLosses = variants.stream().mapToInt(v -> safeInt(v.getLosingTrades())).sum();
        double grossPnl = variants.stream().mapToDouble(v -> safeDouble(v.getGrossPnl())).sum();
        double netPnl = variants.stream().mapToDouble(v -> safeDouble(v.getNetPnl())).sum();

        List<VariantSummary> variantSummaries = variants.stream()
                .map(v -> new VariantSummary(
                        v.getId(),
                        v.getStrategyId(),
                        v.getStrategyVersion(),
                        v.getStrategyDisplayName(),
                        v.getTimeframe().name(),
                        v.getStatus().name(),
                        safeInt(v.getTotalTrades()),
                        safeInt(v.getWinningTrades()),
                        safeInt(v.getLosingTrades()),
                        winRate(safeInt(v.getWinningTrades()), safeInt(v.getTotalTrades())),
                        safeDouble(v.getGrossPnl()),
                        safeDouble(v.getNetPnl()),
                        averagePerTrade(safeDouble(v.getNetPnl()), safeInt(v.getTotalTrades()))))
                .toList();

        return new RunGroupSummary(
                group.getId(),
                group.getName(),
                group.getStatus().name(),
                totalTrades,
                totalWins,
                totalLosses,
                winRate(totalWins, totalTrades),
                grossPnl,
                netPnl,
                variantSummaries);
    }

    private int executeVariant(SimulationRunGroup group, SimulationVariant variant) {
        StrategyYamlDefinition strategy = strategyCatalogService.getRequired(variant.getStrategyId());

        if (!Boolean.TRUE.equals(variant.getSimulationEnabled())
                || !Boolean.TRUE.equals(strategy.simulationEnabled())) {
            variant.setStatus(SimulationStatus.CANCELLED);
            variant.setCompletedAt(timeProvider.nowDateTime());
            variant.setNotes("Variant skipped because simulation is disabled");
            variantRepository.save(variant);
            return 0;
        }

        CandleTimeframe candleTimeframe = resolveCandleTimeframe(strategy.timeframe());

        variant.setStatus(SimulationStatus.RUNNING);
        variant.setCompletedAt(null);
        variant.setTotalTrades(0);
        variant.setWinningTrades(0);
        variant.setLosingTrades(0);
        variant.setGrossPnl(0.0);
        variant.setNetPnl(0.0);
        variantRepository.save(variant);

        tradeRepository.deleteByVariantId(variant.getId());

        List<StockUniverse> activeUniverse = stockUniverseRepository.findByIsActiveTrueOrderBySymbolAsc();

        int totalTrades = 0;
        int wins = 0;
        int losses = 0;
        double grossPnl = 0.0;
        double netPnl = 0.0;

        for (StockUniverse stock : activeUniverse) {
            SymbolExecutionResult result = executeSymbol(group, variant, strategy, candleTimeframe, stock.getSymbol());
            totalTrades += result.trades();
            wins += result.wins();
            losses += result.losses();
            grossPnl += result.grossPnl();
            netPnl += result.netPnl();
        }

        variant.setTotalTrades(totalTrades);
        variant.setWinningTrades(wins);
        variant.setLosingTrades(losses);
        variant.setGrossPnl(grossPnl);
        variant.setNetPnl(netPnl);
        variant.setCompletedAt(timeProvider.nowDateTime());
        variant.setStatus(SimulationStatus.COMPLETED);
        variant.setNotes("Historical simulation completed");
        variantRepository.save(variant);

        return totalTrades;
    }

    private SymbolExecutionResult executeSymbol(
            SimulationRunGroup group,
            SimulationVariant variant,
            StrategyYamlDefinition strategy,
            CandleTimeframe candleTimeframe,
            String symbol) {
        List<MarketCandle> candles = marketCandleRepository
                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                        symbol,
                        "NSE",
                        candleTimeframe,
                        group.getFromDate().atStartOfDay(),
                        group.getToDate().atTime(23, 59));

        if (candles.size() < 25) {
            return SymbolExecutionResult.empty();
        }

        List<StockPrice> dailyPrices = stockPriceRepository.findBySymbolAndDateBetweenOrderByDateAsc(
                symbol,
                group.getFromDate().minusDays(10),
                group.getToDate());

        if (dailyPrices.isEmpty()) {
            return SymbolExecutionResult.empty();
        }

        Set<LocalDate> tradedDays = new HashSet<>();

        int trades = 0;
        int wins = 0;
        int losses = 0;
        double grossPnl = 0.0;
        double netPnl = 0.0;

        for (int i = 20; i < candles.size() - 1; i++) {
            MarketCandle signalCandle = candles.get(i);
            MarketCandle entryCandle = candles.get(i + 1);

            LocalDate signalDate = signalCandle.getCandleTime().toLocalDate();

            if (signalDate.isBefore(group.getFromDate()) || signalDate.isAfter(group.getToDate())) {
                continue;
            }

            if (tradedDays.contains(signalDate)) {
                continue;
            }

            if (!signalDate.equals(entryCandle.getCandleTime().toLocalDate())) {
                continue;
            }

            StockPrice previousDaily = findPreviousTradingDayPrice(dailyPrices, signalDate);
            if (previousDaily == null || previousDaily.getHighPrice() == null
                    || previousDaily.getClosePrice() == null) {
                continue;
            }

            if (previousDaily.getClosePrice() <= 50.0) {
                continue;
            }

            double previousDayHigh = previousDaily.getHighPrice();
            double signalClose = signalCandle.getClosePrice();

            if (signalClose <= previousDayHigh) {
                continue;
            }

            double breakoutRatio = (signalClose - previousDayHigh) / previousDayHigh;
            if (breakoutRatio > strategy.breakout().maxGap()) {
                continue;
            }

            Double volumeRatio = computeVolumeRatio(candles, i, 20);
            if (volumeRatio == null || volumeRatio < strategy.breakout().volumeMultiplierMatch()) {
                continue;
            }

            Double rsi = computeRsi(candles, i, strategy.breakout().rsiPeriod());
            if (rsi == null || rsi < strategy.breakout().rsiThresholdMatch()) {
                continue;
            }

            Double vwap = computeIntradayVwap(candles, i);
            if (vwap == null || signalClose <= vwap) {
                continue;
            }

            double closeStrength = computeCloseStrength(signalCandle);
            if (closeStrength < 0.70) {
                continue;
            }

            double breakoutPercent = breakoutRatio * 100.0;

            StrategyScoreResult scoreResult = strategyScoringService.score(
                    strategy,
                    Map.of(
                            "breakoutPercent", breakoutPercent,
                            "volumeRatio", volumeRatio,
                            "rsi", rsi));

            if (scoreResult.decision() != ScoreDecision.INVEST) {
                continue;
            }

            double entryPrice = entryCandle.getOpenPrice();
            double stopLoss = signalCandle.getLowPrice() * 0.995;
            double riskPerShare = entryPrice - stopLoss;

            if (riskPerShare <= 0) {
                continue;
            }

            int quantity = Math.max(1, (int) Math.floor(variant.getCapitalPerTrade() / entryPrice));
            double initialTarget = entryPrice + (riskPerShare * 1.2);

            ExitResult exit = simulateExit(candles, i + 1, stopLoss, initialTarget);

            double fees = variant.getChargePerTrade() != null ? variant.getChargePerTrade() : 0.0;
            double gross = (exit.exitPrice() - entryPrice) * quantity;
            double net = gross - fees;

            SimulationTradeResult tradeResult = gross > 0 ? SimulationTradeResult.WIN
                    : gross < 0 ? SimulationTradeResult.LOSS : SimulationTradeResult.FLAT;

            if (tradeResult == SimulationTradeResult.WIN) {
                wins++;
            } else if (tradeResult == SimulationTradeResult.LOSS) {
                losses++;
            }

            grossPnl += gross;
            netPnl += net;
            trades++;
            tradedDays.add(signalDate);

            SimulationTrade trade = SimulationTrade.builder()
                    .variantId(variant.getId())
                    .symbol(symbol)
                    .signalDate(signalDate)
                    .entryDate(entryCandle.getCandleTime().toLocalDate())
                    .exitDate(exit.exitDate())
                    .entryPrice(entryPrice)
                    .exitPrice(exit.exitPrice())
                    .quantity(quantity)
                    .initialStopLoss(stopLoss)
                    .finalStopLoss(stopLoss)
                    .initialTarget(initialTarget)
                    .finalTarget(initialTarget)
                    .dayClosePrice(exit.dayClosePrice())
                    .confidence(scoreResult.totalScore())
                    .grossPnl(gross)
                    .netPnl(net)
                    .fees(fees)
                    .tradeStatus(SimulationTradeStatus.CLOSED)
                    .tradeResult(tradeResult)
                    .exitReason(exit.reason())
                    .entryContext(toJson(buildEntryContext(
                            signalCandle,
                            entryCandle,
                            previousDayHigh,
                            breakoutPercent,
                            volumeRatio,
                            rsi,
                            vwap,
                            closeStrength)))
                    .exitContext(toJson(Map.of(
                            "exitCandleTime", exit.exitCandleTime().toString(),
                            "exitReason", exit.reason(),
                            "exitPrice", exit.exitPrice(),
                            "dayClosePrice", exit.dayClosePrice())))
                    .evaluationContext(toJson(Map.of(
                            "timeframe", candleTimeframe.name(),
                            "score", scoreResult.totalScore(),
                            "decision", scoreResult.decision().name(),
                            "factorResults", scoreResult.factors())))
                    .createdAt(timeProvider.nowDateTime())
                    .build();

            tradeRepository.save(trade);
        }

        return new SymbolExecutionResult(trades, wins, losses, grossPnl, netPnl);
    }

    private CandleTimeframe resolveCandleTimeframe(StrategyTimeframe timeframe) {
        return switch (timeframe) {
            case FIVE_MINUTE -> CandleTimeframe.FIVE_MINUTE;
            case FIFTEEN_MINUTE -> CandleTimeframe.FIFTEEN_MINUTE;
            default ->
                throw new IllegalArgumentException("Unsupported timeframe for historical execution: " + timeframe);
        };
    }

    private ExitResult simulateExit(List<MarketCandle> candles, int entryIndex, double stopLoss, double target) {
        MarketCandle entryCandle = candles.get(entryIndex);
        LocalDate tradeDate = entryCandle.getCandleTime().toLocalDate();

        MarketCandle lastSameDayCandle = entryCandle;
        for (int i = entryIndex; i < candles.size(); i++) {
            MarketCandle candle = candles.get(i);
            if (!tradeDate.equals(candle.getCandleTime().toLocalDate())) {
                break;
            }

            lastSameDayCandle = candle;

            if (candle.getLowPrice() <= stopLoss) {
                return new ExitResult(
                        stopLoss,
                        candle.getCandleTime().toLocalDate(),
                        candle.getCandleTime(),
                        "STOP_LOSS",
                        lastSameDayCandle.getClosePrice());
            }

            if (candle.getHighPrice() >= target) {
                return new ExitResult(
                        target,
                        candle.getCandleTime().toLocalDate(),
                        candle.getCandleTime(),
                        "TARGET_HIT",
                        lastSameDayCandle.getClosePrice());
            }
        }

        return new ExitResult(
                lastSameDayCandle.getClosePrice(),
                lastSameDayCandle.getCandleTime().toLocalDate(),
                lastSameDayCandle.getCandleTime(),
                "END_OF_DAY",
                lastSameDayCandle.getClosePrice());
    }

    private StockPrice findPreviousTradingDayPrice(List<StockPrice> prices, LocalDate currentDate) {
        StockPrice previous = null;
        for (StockPrice price : prices) {
            if (price.getDate().isBefore(currentDate)) {
                previous = price;
            } else {
                break;
            }
        }
        return previous;
    }

    private Double computeVolumeRatio(List<MarketCandle> candles, int currentIndex, int window) {
        if (currentIndex < window) {
            return null;
        }

        long currentVolume = candles.get(currentIndex).getVolume() != null ? candles.get(currentIndex).getVolume() : 0L;
        if (currentVolume <= 0) {
            return null;
        }

        double total = 0.0;
        int count = 0;

        for (int i = currentIndex - window; i < currentIndex; i++) {
            Long volume = candles.get(i).getVolume();
            if (volume != null) {
                total += volume;
                count++;
            }
        }

        if (count == 0 || total <= 0.0) {
            return null;
        }

        double average = total / count;
        return currentVolume / average;
    }

    private Double computeRsi(List<MarketCandle> candles, int currentIndex, int period) {
        if (currentIndex < period) {
            return null;
        }

        double gain = 0.0;
        double loss = 0.0;

        for (int i = currentIndex - period + 1; i <= currentIndex; i++) {
            double change = candles.get(i).getClosePrice() - candles.get(i - 1).getClosePrice();
            if (change > 0) {
                gain += change;
            } else {
                loss += Math.abs(change);
            }
        }

        double avgGain = gain / period;
        double avgLoss = loss / period;

        if (avgLoss == 0.0) {
            return 100.0;
        }

        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    private Double computeIntradayVwap(List<MarketCandle> candles, int currentIndex) {
        LocalDate currentDate = candles.get(currentIndex).getCandleTime().toLocalDate();

        double totalPriceVolume = 0.0;
        long totalVolume = 0L;

        for (int i = 0; i <= currentIndex; i++) {
            MarketCandle candle = candles.get(i);

            if (!currentDate.equals(candle.getCandleTime().toLocalDate())) {
                continue;
            }

            if (candle.getVolume() == null || candle.getVolume() <= 0) {
                continue;
            }

            double typicalPrice = (candle.getHighPrice() + candle.getLowPrice() + candle.getClosePrice()) / 3.0;
            totalPriceVolume += typicalPrice * candle.getVolume();
            totalVolume += candle.getVolume();
        }

        if (totalVolume == 0L) {
            return null;
        }

        return totalPriceVolume / totalVolume;
    }

    private double computeCloseStrength(MarketCandle candle) {
        double range = candle.getHighPrice() - candle.getLowPrice();
        if (range <= 0.0) {
            return 0.5;
        }
        return (candle.getClosePrice() - candle.getLowPrice()) / range;
    }

    private Map<String, Object> buildEntryContext(
            MarketCandle signalCandle,
            MarketCandle entryCandle,
            double previousDayHigh,
            double breakoutPercent,
            Double volumeRatio,
            Double rsi,
            Double vwap,
            double closeStrength) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("signalCandleTime", signalCandle.getCandleTime().toString());
        context.put("entryCandleTime", entryCandle.getCandleTime().toString());
        context.put("signalOpen", signalCandle.getOpenPrice());
        context.put("signalHigh", signalCandle.getHighPrice());
        context.put("signalLow", signalCandle.getLowPrice());
        context.put("signalClose", signalCandle.getClosePrice());
        context.put("signalVolume", signalCandle.getVolume());
        context.put("previousDayHigh", previousDayHigh);
        context.put("breakoutPercent", breakoutPercent);
        context.put("volumeRatio", volumeRatio);
        context.put("rsi", rsi);
        context.put("vwap", vwap);
        context.put("closeStrength", closeStrength);
        return context;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{\"error\":\"serialization_failed\"}";
        }
    }

    private int safeInt(Integer value) {
        return value != null ? value : 0;
    }

    private double safeDouble(Double value) {
        return value != null ? value : 0.0;
    }

    private double winRate(int wins, int trades) {
        if (trades == 0) {
            return 0.0;
        }
        return (wins * 100.0) / trades;
    }

    private double averagePerTrade(double pnl, int trades) {
        if (trades == 0) {
            return 0.0;
        }
        return pnl / trades;
    }

    private record ExitResult(
            double exitPrice,
            LocalDate exitDate,
            LocalDateTime exitCandleTime,
            String reason,
            double dayClosePrice) {
    }

    private record SymbolExecutionResult(
            int trades,
            int wins,
            int losses,
            double grossPnl,
            double netPnl) {
        static SymbolExecutionResult empty() {
            return new SymbolExecutionResult(0, 0, 0, 0.0, 0.0);
        }
    }

    public record ExecuteRunGroupResult(
            Integer runGroupId,
            int variantsExecuted,
            int variantsFailed,
            int tradesCreated,
            String message) {
    }

    public record VariantSummary(
            Integer variantId,
            String strategyId,
            String strategyVersion,
            String strategyDisplayName,
            String timeframe,
            String status,
            int totalTrades,
            int winningTrades,
            int losingTrades,
            double winRate,
            double grossPnl,
            double netPnl,
            double averageNetPnlPerTrade) {
    }

    public record RunGroupSummary(
            Integer runGroupId,
            String runGroupName,
            String status,
            int totalTrades,
            int totalWins,
            int totalLosses,
            double overallWinRate,
            double grossPnl,
            double netPnl,
            List<VariantSummary> variants) {
    }
}