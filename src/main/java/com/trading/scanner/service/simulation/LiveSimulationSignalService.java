package com.trading.scanner.service.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.LiveSimulationSignal;
import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.model.StockPrice;
import com.trading.scanner.repository.LiveSimulationSignalRepository;
import com.trading.scanner.repository.MarketCandleRepository;
import com.trading.scanner.repository.StockPriceRepository;
import com.trading.scanner.strategy.ScoreDecision;
import com.trading.scanner.strategy.StrategyCatalogService;
import com.trading.scanner.strategy.StrategyScoringModels;
import com.trading.scanner.strategy.StrategyScoringService;
import com.trading.scanner.strategy.StrategyTimeframe;
import com.trading.scanner.strategy.StrategyYamlDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class LiveSimulationSignalService {

    private final LiveSimulationSignalRepository liveSimulationSignalRepository;
    private final MarketCandleRepository marketCandleRepository;
    private final StockPriceRepository stockPriceRepository;
    private final StrategyCatalogService strategyCatalogService;
    private final StrategyScoringService strategyScoringService;
    private final TimeProvider timeProvider;
    private final ObjectMapper objectMapper;

    @Transactional
    public void processFinalizedDerivedCandle(MarketCandle candle) {
        if (candle == null || !Boolean.TRUE.equals(candle.getIsFinalized())) {
            return;
        }

        StrategyTimeframe strategyTimeframe = mapStrategyTimeframe(candle.getTimeframe());
        if (strategyTimeframe == null) {
            return;
        }

        List<StrategyYamlDefinition> matchingStrategies = strategyCatalogService.simulationEnabled().stream()
                .filter(def -> def.timeframe() == strategyTimeframe)
                .toList();

        for (StrategyYamlDefinition strategy : matchingStrategies) {
            EvaluationSnapshot snapshot = buildEvaluationSnapshot(strategy, candle);

            boolean pendingHandled = false;
            if (Boolean.TRUE.equals(strategy.nextCandleConfirmationRequired())) {
                pendingHandled = processPendingCandidate(strategy, candle, snapshot);
            }

            if (snapshot != null && !pendingHandled) {
                persistCurrentSignal(strategy, candle, snapshot);
            }
        }
    }

    @Transactional(readOnly = true)
    public List<LiveSimulationSignal> recentSignals(int limit) {
        return liveSimulationSignalRepository.findRecent(limit);
    }

    @Transactional
    public ClearSignalsResult clearSignals() {
        int removed = liveSimulationSignalRepository.findAll().size();
        liveSimulationSignalRepository.deleteAll();
        return new ClearSignalsResult(removed, "Cleared live simulation signals");
    }

    private boolean processPendingCandidate(
            StrategyYamlDefinition strategy,
            MarketCandle currentCandle,
            EvaluationSnapshot currentSnapshot) {
        Optional<LiveSimulationSignal> pendingOpt = liveSimulationSignalRepository
                .findTopByStrategyIdAndSymbolAndTimeframeAndLifecycleStatusOrderByCandleTimeDesc(
                        strategy.strategyId(),
                        currentCandle.getSymbol(),
                        currentCandle.getTimeframe().name(),
                        "CANDIDATE");

        if (pendingOpt.isEmpty()) {
            return false;
        }

        LiveSimulationSignal pending = pendingOpt.get();

        if (!pending.getCandleTime().isBefore(currentCandle.getCandleTime())) {
            return false;
        }

        boolean confirmed = currentSnapshot != null
                && currentSnapshot.closePrice() >= pending.getClosePrice()
                && currentSnapshot.closeStrength() >= 0.55;

        pending.setDecisionCandleTime(currentCandle.getCandleTime());
        pending.setConfirmationDecision(confirmed ? "CONFIRMED" : "REJECTED");
        pending.setLifecycleStatus(confirmed ? "CONFIRMED" : "REJECTED");
        pending.setConfirmationContext(toJson(Map.of(
                "decisionCandleTime", currentCandle.getCandleTime().toString(),
                "decisionClosePrice", currentCandle.getClosePrice(),
                "decisionUsedSnapshot", currentSnapshot != null,
                "confirmed", confirmed)));
        pending.setUpdatedAt(timeProvider.nowDateTime());

        liveSimulationSignalRepository.save(pending);
        return true;
    }

    private void persistCurrentSignal(
            StrategyYamlDefinition strategy,
            MarketCandle candle,
            EvaluationSnapshot snapshot) {
        boolean confirmationRequired = Boolean.TRUE.equals(strategy.nextCandleConfirmationRequired());
        String lifecycleStatus = confirmationRequired ? "CANDIDATE" : "CONFIRMED";

        Optional<LiveSimulationSignal> existingOpt = liveSimulationSignalRepository
                .findByStrategyIdAndSymbolAndTimeframeAndCandleTime(
                        strategy.strategyId(),
                        candle.getSymbol(),
                        candle.getTimeframe().name(),
                        candle.getCandleTime());

        LiveSimulationSignal signal = existingOpt.orElseGet(() -> LiveSimulationSignal.builder()
                .strategyId(strategy.strategyId())
                .strategyVersion(strategy.version())
                .timeframe(candle.getTimeframe().name())
                .symbol(candle.getSymbol())
                .exchange(candle.getExchange())
                .candleTime(candle.getCandleTime())
                .signalDate(candle.getCandleTime().toLocalDate())
                .createdAt(timeProvider.nowDateTime())
                .build());

        signal.setLifecycleStatus(lifecycleStatus);
        signal.setConfirmationRequired(confirmationRequired);
        signal.setScore(snapshot.scoreResult().totalScore());
        signal.setClosePrice(snapshot.closePrice());
        signal.setPreviousDayHigh(snapshot.previousDayHigh());
        signal.setBreakoutPercent(snapshot.breakoutPercent());
        signal.setVolumeRatio(snapshot.volumeRatio());
        signal.setRsi(snapshot.rsi());
        signal.setVwap(snapshot.vwap());
        signal.setCloseStrength(snapshot.closeStrength());
        signal.setSignalContext(toJson(Map.of(
                "decision", snapshot.scoreResult().decision().name(),
                "factorResults", snapshot.scoreResult().factors(),
                "candleTime", candle.getCandleTime().toString())));
        signal.setDecisionCandleTime(confirmationRequired ? null : candle.getCandleTime());
        signal.setConfirmationDecision(confirmationRequired ? null : "IMMEDIATE");
        signal.setUpdatedAt(timeProvider.nowDateTime());

        liveSimulationSignalRepository.save(signal);
    }

    private EvaluationSnapshot buildEvaluationSnapshot(StrategyYamlDefinition strategy, MarketCandle candle) {
        List<MarketCandle> candles = marketCandleRepository
                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                        candle.getSymbol(),
                        candle.getExchange(),
                        candle.getTimeframe(),
                        candle.getCandleTime().minusDays(10),
                        candle.getCandleTime());

        if (candles.size() < 20) {
            return null;
        }

        List<StockPrice> dailyPrices = stockPriceRepository.findBySymbolAndDateBetweenOrderByDateAsc(
                candle.getSymbol(),
                candle.getCandleTime().toLocalDate().minusDays(10),
                candle.getCandleTime().toLocalDate());

        if (dailyPrices.isEmpty()) {
            return null;
        }

        StockPrice previousDay = findPreviousTradingDayPrice(dailyPrices, candle.getCandleTime().toLocalDate());
        if (previousDay == null || previousDay.getHighPrice() == null || previousDay.getClosePrice() == null) {
            return null;
        }

        if (previousDay.getClosePrice() <= 50.0) {
            return null;
        }

        MarketCandle current = candles.get(candles.size() - 1);
        double previousDayHigh = previousDay.getHighPrice();
        double close = current.getClosePrice();

        if (close <= previousDayHigh) {
            return null;
        }

        double breakoutRatio = (close - previousDayHigh) / previousDayHigh;
        if (breakoutRatio > strategy.breakout().maxGap()) {
            return null;
        }

        Double volumeRatio = computeVolumeRatio(candles, candles.size() - 1, 20);
        if (volumeRatio == null || volumeRatio < strategy.breakout().volumeMultiplierMatch()) {
            return null;
        }

        Double rsi = computeRsi(candles, candles.size() - 1, strategy.breakout().rsiPeriod());
        if (rsi == null || rsi < strategy.breakout().rsiThresholdMatch()) {
            return null;
        }

        Double vwap = computeIntradayVwap(candles, candles.size() - 1);
        if (vwap == null || close <= vwap) {
            return null;
        }

        double closeStrength = computeCloseStrength(current);
        if (closeStrength < 0.70) {
            return null;
        }

        double breakoutPercent = breakoutRatio * 100.0;

        StrategyScoringModels.StrategyScoreResult scoreResult = strategyScoringService.score(
                strategy,
                Map.of(
                        "breakoutPercent", breakoutPercent,
                        "volumeRatio", volumeRatio,
                        "rsi", rsi));

        if (scoreResult.decision() != ScoreDecision.INVEST) {
            return null;
        }

        return new EvaluationSnapshot(
                close,
                previousDayHigh,
                breakoutPercent,
                volumeRatio,
                rsi,
                vwap,
                closeStrength,
                scoreResult);
    }

    private StrategyTimeframe mapStrategyTimeframe(CandleTimeframe timeframe) {
        return switch (timeframe) {
            case FIVE_MINUTE -> StrategyTimeframe.FIVE_MINUTE;
            case FIFTEEN_MINUTE -> StrategyTimeframe.FIFTEEN_MINUTE;
            default -> null;
        };
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

        return currentVolume / (total / count);
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

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{\"error\":\"serialization_failed\"}";
        }
    }

    private record EvaluationSnapshot(
            double closePrice,
            double previousDayHigh,
            double breakoutPercent,
            double volumeRatio,
            double rsi,
            double vwap,
            double closeStrength,
            StrategyScoringModels.StrategyScoreResult scoreResult) {
    }

    public record ClearSignalsResult(
            int removed,
            String message) {
    }
}