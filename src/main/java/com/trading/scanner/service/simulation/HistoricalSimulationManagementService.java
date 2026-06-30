package com.trading.scanner.service.simulation;

import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.SimulationRunGroup;
import com.trading.scanner.model.SimulationStatus;
import com.trading.scanner.model.SimulationTrade;
import com.trading.scanner.model.SimulationTradeResult;
import com.trading.scanner.model.SimulationVariant;
import com.trading.scanner.repository.SimulationRunGroupRepository;
import com.trading.scanner.repository.SimulationTradeRepository;
import com.trading.scanner.repository.SimulationVariantRepository;
import com.trading.scanner.service.runtime.RuleExecutionPolicyService;
import com.trading.scanner.strategy.StrategyCatalogService;
import com.trading.scanner.strategy.StrategyYamlDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class HistoricalSimulationManagementService {

        private final SimulationRunGroupRepository runGroupRepository;
        private final SimulationVariantRepository variantRepository;
        private final SimulationTradeRepository tradeRepository;
        private final StrategyCatalogService strategyCatalogService;
        private final RuleExecutionPolicyService ruleExecutionPolicyService;
        private final TimeProvider timeProvider;

        @Transactional
        public CreateRunGroupResult createRunGroup(CreateRunGroupRequest request) {
                List<StrategyYamlDefinition> selectedStrategies = (request.strategyIds() == null
                                || request.strategyIds().isEmpty())
                                                ? strategyCatalogService.historicalEligible()
                                                : request.strategyIds().stream()
                                                                .map(strategyCatalogService::getRequired)
                                                                .filter(ruleExecutionPolicyService::allowHistoricalSimulation)
                                                                .toList();

                if (selectedStrategies.isEmpty()) {
                        throw new IllegalArgumentException(
                                        "No historical-eligible strategies found for run group creation");
                }

                SimulationRunGroup group = SimulationRunGroup.builder()
                                .name(request.name())
                                .description(request.description())
                                .fromDate(request.fromDate())
                                .toDate(request.toDate())
                                .createdAt(timeProvider.nowDateTime())
                                .status(SimulationStatus.CREATED)
                                .notes(request.notes())
                                .createdBy(request.createdBy())
                                .build();

                group = runGroupRepository.save(group);

                for (StrategyYamlDefinition def : selectedStrategies) {
                        SimulationVariant variant = SimulationVariant.builder()
                                        .runGroupId(group.getId())
                                        .strategyId(def.strategyId())
                                        .strategyVersion(def.version())
                                        .strategyDisplayName(def.displayName())
                                        .strategyStatus(def.status())
                                        .timeframe(def.timeframe())
                                        .variantKey(def.strategyId() + "__base")
                                        .variantName(def.displayName() + " Base")
                                        .simulationEnabled(ruleExecutionPolicyService.allowHistoricalSimulation(def))
                                        .liveEnabled(ruleExecutionPolicyService.allowRealExecution(def))
                                        .capitalPerTrade(request.capitalPerTrade())
                                        .slippageBps(request.slippageBps())
                                        .chargePerTrade(request.chargePerTrade())
                                        .minTradingDays(request.minTradingDays())
                                        .minTradeCount(request.minTradeCount())
                                        .createdAt(timeProvider.nowDateTime())
                                        .status(SimulationStatus.CREATED)
                                        .notes("Base variant created from strategy catalog")
                                        .build();

                        variantRepository.save(variant);
                }

                List<SimulationVariant> variants = variantRepository.findByRunGroupIdOrderByIdAsc(group.getId());

                return new CreateRunGroupResult(group, variants);
        }

        @Transactional(readOnly = true)
        public List<SimulationRunGroup> allRunGroups() {
                return runGroupRepository.findAll();
        }

        @Transactional(readOnly = true)
        public List<SimulationVariant> variantsForGroup(Integer runGroupId) {
                return variantRepository.findByRunGroupIdOrderByIdAsc(runGroupId);
        }

        @Transactional(readOnly = true)
        public RunGroupReport reportForGroup(Integer runGroupId) {
                SimulationRunGroup group = runGroupRepository.findById(runGroupId)
                                .orElseThrow(() -> new IllegalArgumentException(
                                                "Simulation run group not found: " + runGroupId));

                List<SimulationVariant> variants = variantRepository.findByRunGroupIdOrderByIdAsc(runGroupId);

                List<VariantReport> variantReports = variants.stream()
                                .map(variant -> summarizeVariant(variant,
                                                tradeRepository.findByVariantIdOrderBySignalDateAsc(variant.getId())))
                                .toList();

                int totalTrades = variantReports.stream().mapToInt(VariantReport::tradeCount).sum();
                int winningTrades = variantReports.stream().mapToInt(VariantReport::winningTrades).sum();
                int losingTrades = variantReports.stream().mapToInt(VariantReport::losingTrades).sum();
                int flatTrades = variantReports.stream().mapToInt(VariantReport::flatTrades).sum();

                double grossPnl = variantReports.stream().mapToDouble(VariantReport::grossPnl).sum();
                double netPnl = variantReports.stream().mapToDouble(VariantReport::netPnl).sum();

                double averageNetPnlPerTrade = totalTrades == 0 ? 0.0 : netPnl / totalTrades;
                double winRatePercent = totalTrades == 0 ? 0.0 : (winningTrades * 100.0) / totalTrades;

                int completedVariantCount = (int) variantReports.stream()
                                .filter(v -> v.status() == SimulationStatus.COMPLETED)
                                .count();

                int failedVariantCount = (int) variantReports.stream()
                                .filter(v -> v.status() == SimulationStatus.FAILED)
                                .count();

                String bestVariant = variantReports.stream()
                                .max(Comparator.comparingDouble(VariantReport::netPnl))
                                .map(VariantReport::variantName)
                                .orElse(null);

                return new RunGroupReport(
                                group.getId(),
                                group.getName(),
                                group.getDescription(),
                                group.getFromDate(),
                                group.getToDate(),
                                group.getStatus(),
                                variants.size(),
                                completedVariantCount,
                                failedVariantCount,
                                totalTrades,
                                winningTrades,
                                losingTrades,
                                flatTrades,
                                grossPnl,
                                netPnl,
                                averageNetPnlPerTrade,
                                winRatePercent,
                                bestVariant,
                                variantReports);
        }

        @Transactional(readOnly = true)
        public List<VariantReport> compareVariants(Integer runGroupId) {
                return reportForGroup(runGroupId).variants().stream()
                                .sorted(Comparator
                                                .comparingDouble(VariantReport::netPnl).reversed()
                                                .thenComparing(VariantReport::variantName))
                                .toList();
        }

        private VariantReport summarizeVariant(SimulationVariant variant, List<SimulationTrade> trades) {
                int tradeCount = trades.size();
                int winningTrades = 0;
                int losingTrades = 0;
                int flatTrades = 0;

                double grossPnl = 0.0;
                double netPnl = 0.0;
                double totalWinningNet = 0.0;
                double totalLosingNet = 0.0;
                double bestTradeNet = 0.0;
                double worstTradeNet = 0.0;

                boolean first = true;

                for (SimulationTrade trade : trades) {
                        double tradeGross = trade.getGrossPnl() != null ? trade.getGrossPnl() : 0.0;
                        double tradeNet = trade.getNetPnl() != null ? trade.getNetPnl() : 0.0;

                        grossPnl += tradeGross;
                        netPnl += tradeNet;

                        if (first) {
                                bestTradeNet = tradeNet;
                                worstTradeNet = tradeNet;
                                first = false;
                        } else {
                                bestTradeNet = Math.max(bestTradeNet, tradeNet);
                                worstTradeNet = Math.min(worstTradeNet, tradeNet);
                        }

                        if (trade.getTradeResult() == SimulationTradeResult.WIN) {
                                winningTrades++;
                                totalWinningNet += tradeNet;
                        } else if (trade.getTradeResult() == SimulationTradeResult.LOSS) {
                                losingTrades++;
                                totalLosingNet += tradeNet;
                        } else {
                                flatTrades++;
                        }
                }

                double winRatePercent = tradeCount == 0 ? 0.0 : (winningTrades * 100.0) / tradeCount;
                double averageGrossPnlPerTrade = tradeCount == 0 ? 0.0 : grossPnl / tradeCount;
                double averageNetPnlPerTrade = tradeCount == 0 ? 0.0 : netPnl / tradeCount;
                double averageWinningTrade = winningTrades == 0 ? 0.0 : totalWinningNet / winningTrades;
                double averageLosingTrade = losingTrades == 0 ? 0.0 : totalLosingNet / losingTrades;
                double expectancyPerTrade = averageNetPnlPerTrade;

                return new VariantReport(
                                variant.getId(),
                                variant.getVariantKey(),
                                variant.getVariantName(),
                                variant.getStrategyId(),
                                variant.getStrategyVersion(),
                                variant.getStrategyDisplayName(),
                                variant.getTimeframe().name(),
                                variant.getStatus(),
                                tradeCount,
                                winningTrades,
                                losingTrades,
                                flatTrades,
                                winRatePercent,
                                grossPnl,
                                netPnl,
                                averageGrossPnlPerTrade,
                                averageNetPnlPerTrade,
                                averageWinningTrade,
                                averageLosingTrade,
                                expectancyPerTrade,
                                bestTradeNet,
                                worstTradeNet);
        }

        public record CreateRunGroupRequest(
                        String name,
                        String description,
                        LocalDate fromDate,
                        LocalDate toDate,
                        Double capitalPerTrade,
                        Integer slippageBps,
                        Double chargePerTrade,
                        Integer minTradingDays,
                        Integer minTradeCount,
                        String notes,
                        String createdBy,
                        List<String> strategyIds) {
        }

        public record CreateRunGroupResult(
                        SimulationRunGroup runGroup,
                        List<SimulationVariant> variants) {
        }

        public record VariantReport(
                        Integer variantId,
                        String variantKey,
                        String variantName,
                        String strategyId,
                        String strategyVersion,
                        String strategyDisplayName,
                        String timeframe,
                        SimulationStatus status,
                        int tradeCount,
                        int winningTrades,
                        int losingTrades,
                        int flatTrades,
                        double winRatePercent,
                        double grossPnl,
                        double netPnl,
                        double averageGrossPnlPerTrade,
                        double averageNetPnlPerTrade,
                        double averageWinningTrade,
                        double averageLosingTrade,
                        double expectancyPerTrade,
                        double bestTradeNet,
                        double worstTradeNet) {
        }

        public record RunGroupReport(
                        Integer runGroupId,
                        String name,
                        String description,
                        LocalDate fromDate,
                        LocalDate toDate,
                        SimulationStatus status,
                        int variantCount,
                        int completedVariantCount,
                        int failedVariantCount,
                        int totalTrades,
                        int winningTrades,
                        int losingTrades,
                        int flatTrades,
                        double grossPnl,
                        double netPnl,
                        double averageNetPnlPerTrade,
                        double winRatePercent,
                        String bestVariant,
                        List<VariantReport> variants) {
        }
}