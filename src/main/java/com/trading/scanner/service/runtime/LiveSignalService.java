package com.trading.scanner.service.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.LiveSimulationSignal;
import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.repository.LiveSimulationSignalRepository;
import com.trading.scanner.service.engine.BreakoutSignalEvaluator;
import com.trading.scanner.service.engine.BreakoutSignalGenerator;
import com.trading.scanner.service.engine.MarketContext;
import com.trading.scanner.service.engine.MarketContextBuilder;
import com.trading.scanner.service.engine.SymbolContext;
import com.trading.scanner.strategy.StrategyCatalogService;
import com.trading.scanner.strategy.StrategyScoringModels;
import com.trading.scanner.strategy.StrategyTimeframe;
import com.trading.scanner.strategy.StrategyYamlDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class LiveSignalService {

    private final LiveSimulationSignalRepository liveSimulationSignalRepository;
    private final StrategyCatalogService strategyCatalogService;
    private final TimeProvider timeProvider;
    private final ObjectMapper objectMapper;
    private final MarketContextBuilder marketContextBuilder;
    private final BreakoutSignalGenerator breakoutSignalGenerator;

    @Transactional
    public void processFinalizedDerivedCandle(MarketCandle candle) {
        if (candle == null || !Boolean.TRUE.equals(candle.getIsFinalized())) {
            return;
        }

        StrategyTimeframe strategyTimeframe = mapStrategyTimeframe(candle.getTimeframe());
        if (strategyTimeframe == null) {
            return;
        }

        MarketContext marketContext = marketContextBuilder.buildMarketContext(candle);
        SymbolContext symbolContext = marketContextBuilder.buildSymbolContext(candle);

        List<StrategyYamlDefinition> matchingStrategies = strategyCatalogService.liveSignalEligible().stream()
                .filter(def -> def.timeframe() == strategyTimeframe)
                .toList();

        for (StrategyYamlDefinition strategy : matchingStrategies) {
            EvaluationSnapshot snapshot = buildEvaluationSnapshot(strategy, candle, marketContext, symbolContext);

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
        return liveSimulationSignalRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, limit));
    }

    @Transactional
    public ClearSignalsResult clearSignals() {
        long removed = liveSimulationSignalRepository.count();
        liveSimulationSignalRepository.deleteAll();
        return new ClearSignalsResult(removed, "Cleared live signals");
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

    private EvaluationSnapshot buildEvaluationSnapshot(
            StrategyYamlDefinition strategy,
            MarketCandle candle,
            MarketContext marketContext,
            SymbolContext symbolContext) {
        Optional<BreakoutSignalEvaluator.EvaluationSnapshot> snapshotOpt = switch (candle.getTimeframe()) {
            case FIVE_MINUTE -> breakoutSignalGenerator.onFiveMinuteCandleClose(strategy, marketContext, symbolContext);
            case FIFTEEN_MINUTE ->
                breakoutSignalGenerator.onFifteenMinuteCandleClose(strategy, marketContext, symbolContext);
            default -> Optional.empty();
        };

        if (snapshotOpt.isEmpty()) {
            return null;
        }

        BreakoutSignalEvaluator.EvaluationSnapshot snapshot = snapshotOpt.get();

        return new EvaluationSnapshot(
                snapshot.closePrice(),
                snapshot.previousDayHigh(),
                snapshot.breakoutPercent(),
                snapshot.volumeRatio(),
                snapshot.rsi(),
                snapshot.vwap(),
                snapshot.closeStrength(),
                snapshot.scoreResult());
    }

    private StrategyTimeframe mapStrategyTimeframe(CandleTimeframe timeframe) {
        return switch (timeframe) {
            case FIVE_MINUTE -> StrategyTimeframe.FIVE_MINUTE;
            case FIFTEEN_MINUTE -> StrategyTimeframe.FIFTEEN_MINUTE;
            default -> null;
        };
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
            long removed,
            String message) {
    }
}