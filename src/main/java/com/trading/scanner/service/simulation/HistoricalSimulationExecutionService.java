package com.trading.scanner.service.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.model.SimulationRunGroup;
import com.trading.scanner.model.SimulationStatus;
import com.trading.scanner.model.SimulationTrade;
import com.trading.scanner.model.SimulationTradeResult;
import com.trading.scanner.model.SimulationTradeStatus;
import com.trading.scanner.model.SimulationVariant;
import com.trading.scanner.model.StockPrice;
import com.trading.scanner.model.StockUniverse;
import com.trading.scanner.repository.MarketCandleRepository;
import com.trading.scanner.repository.SimulationRunGroupRepository;
import com.trading.scanner.repository.SimulationTradeRepository;
import com.trading.scanner.repository.SimulationVariantRepository;
import com.trading.scanner.repository.StockPriceRepository;
import com.trading.scanner.repository.StockUniverseRepository;
import com.trading.scanner.service.engine.BreakoutSignalEvaluator;
import com.trading.scanner.strategy.StrategyCatalogService;
import com.trading.scanner.strategy.StrategyScoringModels.StrategyScoreResult;
import com.trading.scanner.strategy.StrategyTimeframe;
import com.trading.scanner.strategy.StrategyYamlDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class HistoricalSimulationExecutionService {

    private final SimulationRunGroupRepository runGroupRepository;
    private final SimulationVariantRepository variantRepository;
    private final SimulationTradeRepository tradeRepository;
    private final StockUniverseRepository stockUniverseRepository;
    private final MarketCandleRepository marketCandleRepository;
    private final StockPriceRepository stockPriceRepository;
    private final StrategyCatalogService strategyCatalogService;
    private final BreakoutSignalEvaluator breakoutSignalEvaluator;
    private final TimeProvider timeProvider;
    private final ObjectMapper objectMapper;

    @Transactional
    public ExecuteRunGroupResult executeRunGroup(Integer runGroupId) {
        SimulationRunGroup runGroup = runGroupRepository.findById(runGroupId)
                .orElseThrow(() -> new IllegalArgumentException("Simulation run group not found: " + runGroupId));

        List<SimulationVariant> variants = variantRepository.findByRunGroupIdOrderByIdAsc(runGroupId);
        if (variants.isEmpty()) {
            throw new IllegalArgumentException("No variants found for run group: " + runGroupId);
        }

        int completedVariants = 0;
        int failedVariants = 0;
        int totalTrades = 0;
        List<VariantExecutionResult> results = new ArrayList<>();

        runGroup.setStatus(SimulationStatus.RUNNING);
        runGroupRepository.save(runGroup);

        for (SimulationVariant variant : variants) {
            try {
                VariantExecutionResult result = executeVariant(runGroup, variant);
                results.add(result);
                totalTrades += result.tradeCount();
                completedVariants++;

                variant.setStatus(SimulationStatus.COMPLETED);
                variantRepository.save(variant);
            } catch (Exception ex) {
                failedVariants++;
                variant.setStatus(SimulationStatus.FAILED);
                variantRepository.save(variant);

                results.add(new VariantExecutionResult(
                        variant.getId(),
                        variant.getVariantName(),
                        0,
                        0.0,
                        0.0,
                        "FAILED: " + ex.getMessage()));
            }
        }

        runGroup.setStatus(failedVariants > 0 ? SimulationStatus.FAILED : SimulationStatus.COMPLETED);
        runGroupRepository.save(runGroup);

        return new ExecuteRunGroupResult(
                runGroupId,
                variants.size(),
                completedVariants,
                failedVariants,
                totalTrades,
                results,
                "Historical simulation execution completed");
    }

    @Transactional(readOnly = true)
    public List<SimulationTrade> tradesForVariant(Integer variantId) {
        return tradeRepository.findByVariantIdOrderBySignalDateAsc(variantId);
    }

    private VariantExecutionResult executeVariant(SimulationRunGroup runGroup, SimulationVariant variant) {
        List<SimulationTrade> existingTrades = tradeRepository.findByVariantIdOrderBySignalDateAsc(variant.getId());
        if (!existingTrades.isEmpty()) {
            tradeRepository.deleteAll(existingTrades);
        }

        StrategyYamlDefinition strategy = strategyCatalogService.getRequired(variant.getStrategyId());
        CandleTimeframe candleTimeframe = mapTimeframe(strategy.timeframe());

        List<StockUniverse> activeUniverse = stockUniverseRepository.findByIsActiveTrueOrderBySymbolAsc();

        int tradeCount = 0;
        double grossPnl = 0.0;
        double netPnl = 0.0;

        for (StockUniverse stock : activeUniverse) {
            SymbolExecutionResult symbolResult = executeSymbol(runGroup, variant, strategy, candleTimeframe, stock);
            tradeCount += symbolResult.tradeCount();
            grossPnl += symbolResult.grossPnl();
            netPnl += symbolResult.netPnl();
        }

        return new VariantExecutionResult(
                variant.getId(),
                variant.getVariantName(),
                tradeCount,
                grossPnl,
                netPnl,
                "Variant execution completed");
    }

    private SymbolExecutionResult executeSymbol(
            SimulationRunGroup runGroup,
            SimulationVariant variant,
            StrategyYamlDefinition strategy,
            CandleTimeframe candleTimeframe,
            StockUniverse stock) {
        List<MarketCandle> candles = marketCandleRepository
                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                        stock.getSymbol(),
                        stock.getExchange().name(),
                        candleTimeframe,
                        runGroup.getFromDate().atStartOfDay(),
                        runGroup.getToDate().atTime(23, 59));

        if (candles.size() < 21) {
            return new SymbolExecutionResult(0, 0.0, 0.0);
        }

        List<StockPrice> dailyPrices = stockPriceRepository.findBySymbolAndDateBetweenOrderByDateAsc(
                stock.getSymbol(),
                runGroup.getFromDate().minusDays(10),
                runGroup.getToDate());

        if (dailyPrices.isEmpty()) {
            return new SymbolExecutionResult(0, 0.0, 0.0);
        }

        int tradeCount = 0;
        double grossPnl = 0.0;
        double netPnl = 0.0;

        for (int i = 20; i < candles.size() - 1; i++) {
            List<MarketCandle> candlesUpToSignal = candles.subList(0, i + 1);

            BreakoutSignalEvaluator.EvaluationSnapshot snapshot = breakoutSignalEvaluator.evaluate(strategy,
                    candlesUpToSignal, dailyPrices);

            if (snapshot == null) {
                continue;
            }

            MarketCandle signalCandle = candles.get(i);
            MarketCandle exitCandle = candles.get(i + 1);

            double entryPrice = signalCandle.getClosePrice();
            int quantity = Math.max(1, (int) Math.floor(variant.getCapitalPerTrade() / entryPrice));
            double exitPrice = exitCandle.getClosePrice();

            double tradeGrossPnl = (exitPrice - entryPrice) * quantity;
            double tradeNetPnl = tradeGrossPnl
                    - (variant.getChargePerTrade() != null ? variant.getChargePerTrade() : 0.0);

            SimulationTradeResult tradeResult = tradeNetPnl > 0 ? SimulationTradeResult.WIN
                    : tradeNetPnl < 0 ? SimulationTradeResult.LOSS
                            : SimulationTradeResult.FLAT;

            SimulationTrade trade = SimulationTrade.builder()
                    .variantId(variant.getId())
                    .symbol(stock.getSymbol())
                    .signalDate(signalCandle.getCandleTime().toLocalDate())
                    .entryDate(signalCandle.getCandleTime().toLocalDate())
                    .entryPrice(entryPrice)
                    .exitDate(exitCandle.getCandleTime().toLocalDate())
                    .exitPrice(exitPrice)
                    .quantity(quantity)
                    .grossPnl(tradeGrossPnl)
                    .netPnl(tradeNetPnl)
                    .tradeStatus(SimulationTradeStatus.CLOSED)
                    .tradeResult(tradeResult)
                    .exitReason("NEXT_CANDLE_EXIT")
                    .createdAt(timeProvider.nowDateTime())
                    .build();

            tradeRepository.save(trade);

            tradeCount++;
            grossPnl += tradeGrossPnl;
            netPnl += tradeNetPnl;
        }

        return new SymbolExecutionResult(tradeCount, grossPnl, netPnl);
    }

    private CandleTimeframe mapTimeframe(StrategyTimeframe timeframe) {
        return switch (timeframe) {
            case FIVE_MINUTE -> CandleTimeframe.FIVE_MINUTE;
            case FIFTEEN_MINUTE -> CandleTimeframe.FIFTEEN_MINUTE;
            default -> throw new IllegalArgumentException(
                    "Unsupported strategy timeframe for historical execution: " + timeframe);
        };
    }

    private String toJson(StrategyScoreResult scoreResult) {
        try {
            return objectMapper.writeValueAsString(scoreResult);
        } catch (Exception ex) {
            return "{\"error\":\"serialization_failed\"}";
        }
    }

    public record ExecuteRunGroupResult(
            Integer runGroupId,
            int variantCount,
            int completedVariantCount,
            int failedVariantCount,
            int totalTrades,
            List<VariantExecutionResult> variants,
            String message) {
    }

    public record VariantExecutionResult(
            Integer variantId,
            String variantName,
            int tradeCount,
            double grossPnl,
            double netPnl,
            String message) {
    }

    private record SymbolExecutionResult(
            int tradeCount,
            double grossPnl,
            double netPnl) {
    }
}