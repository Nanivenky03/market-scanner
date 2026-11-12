package com.trading.scanner.execution;

import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.execution.adapter.SimulatedOrderAdapter;
import com.trading.scanner.model.RuntimeSetting;
import com.trading.scanner.model.SimulatedOrder;
import com.trading.scanner.model.SimulatedPosition;
import com.trading.scanner.model.StrategyConfig;
import com.trading.scanner.position.PositionManagerService;
import com.trading.scanner.repository.RuntimeSettingRepository;
import com.trading.scanner.repository.SimulatedOrderRepository;
import com.trading.scanner.repository.SimulatedPositionRepository;
import com.trading.scanner.repository.StrategyConfigRepository;
import com.trading.scanner.risk.RiskEngine;
import com.trading.scanner.risk.model.PortfolioMode;
import com.trading.scanner.risk.model.PortfolioState;
import com.trading.scanner.strategy.cache.StockContextCache;
import com.trading.scanner.strategy.model.PositionSnapshot;
import com.trading.scanner.strategy.model.StockContext;
import com.trading.scanner.model.SimulationCapital;
import com.trading.scanner.repository.CapitalRepository;
import com.trading.scanner.repository.SimulationCapitalRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Service orchestrating paper trading simulation administration, dynamic capital compounding,
 * two-tier state reset (intraday vs total), and performance analytics.
 */
@Service
@Slf4j
public class SimulationManagementService {

    public static final String SETTING_INITIAL_CAPITAL = "simulation.initial.capital";
    public static final double DEFAULT_BASELINE_CAPITAL = 50000.0;

    private final RuntimeSettingRepository runtimeSettingRepository;
    private final SimulatedPositionRepository simulatedPositionRepository;
    private final SimulatedOrderRepository simulatedOrderRepository;
    private final StrategyConfigRepository strategyConfigRepository;
    private final RiskEngine riskEngine;
    private final PositionManagerService positionManagerService;
    private final SimulatedOrderAdapter simulatedOrderAdapter;
    private final StockContextCache stockContextCache;
    private final TimeProvider timeProvider;
    private final SimulationCapitalRepository simulationCapitalRepository;
    private final CapitalRepository capitalRepository;

    @Autowired
    public SimulationManagementService(
            RuntimeSettingRepository runtimeSettingRepository,
            SimulatedPositionRepository simulatedPositionRepository,
            SimulatedOrderRepository simulatedOrderRepository,
            StrategyConfigRepository strategyConfigRepository,
            RiskEngine riskEngine,
            PositionManagerService positionManagerService,
            SimulatedOrderAdapter simulatedOrderAdapter,
            StockContextCache stockContextCache,
            TimeProvider timeProvider,
            @Autowired(required = false) SimulationCapitalRepository simulationCapitalRepository,
            @Autowired(required = false) CapitalRepository capitalRepository) {
        this.runtimeSettingRepository = runtimeSettingRepository;
        this.simulatedPositionRepository = simulatedPositionRepository;
        this.simulatedOrderRepository = simulatedOrderRepository;
        this.strategyConfigRepository = strategyConfigRepository;
        this.riskEngine = riskEngine;
        this.positionManagerService = positionManagerService;
        this.simulatedOrderAdapter = simulatedOrderAdapter;
        this.stockContextCache = stockContextCache;
        this.timeProvider = timeProvider;
        this.simulationCapitalRepository = simulationCapitalRepository;
        this.capitalRepository = capitalRepository;
    }

    public SimulationManagementService(
            RuntimeSettingRepository runtimeSettingRepository,
            SimulatedPositionRepository simulatedPositionRepository,
            SimulatedOrderRepository simulatedOrderRepository,
            StrategyConfigRepository strategyConfigRepository,
            RiskEngine riskEngine,
            PositionManagerService positionManagerService,
            SimulatedOrderAdapter simulatedOrderAdapter,
            StockContextCache stockContextCache,
            TimeProvider timeProvider) {
        this(runtimeSettingRepository, simulatedPositionRepository, simulatedOrderRepository,
                strategyConfigRepository, riskEngine, positionManagerService, simulatedOrderAdapter,
                stockContextCache, timeProvider, null, null);
    }

    public record SimulationCapitalResponse(
            double baselineCapital,
            double startingCapitalToday,
            double currentCompoundedCapital,
            double realizedPnlToday,
            double cumulativeRealizedPnl,
            double cumulativeReturnPercent,
            LocalDate tradingDate
    ) {}

    public record SimulationResetResponse(
            String status,
            String scope,
            String message,
            double resultingCapital,
            LocalDate tradingDate
    ) {}

    public record OpenPositionResponse(
            Integer positionId,
            Integer strategyId,
            String symbol,
            String exchange,
            String positionSide,
            int initialQuantity,
            int currentQuantity,
            double entryPrice,
            double currentLtp,
            double unrealizedPnl,
            double unrealizedPnlPercent,
            double stopLoss,
            Double target1Price,
            boolean target1Booked,
            Double peakPrice,
            Double troughPrice,
            String entryTime,
            String restingSlOrderId
    ) {}

    public record ClosedTradeResponse(
            Integer id,
            String symbol,
            String exchange,
            String positionSide,
            int quantity,
            double entryPrice,
            Double exitPrice,
            double grossPnl,
            double statutoryCharges,
            double netPnl,
            String entryTime,
            String exitTime,
            String exitReason
    ) {}

    public record SimulationOrderResponse(
            Integer id,
            String uniqueOrderId,
            String brokerOrderId,
            Integer strategyId,
            String symbol,
            String exchange,
            String transactionType,
            String orderType,
            int quantity,
            Integer filledQuantity,
            Double price,
            Double triggerPrice,
            Double averageFillPrice,
            Double slippagePerShare,
            Double totalSlippage,
            Double statutoryCharges,
            String orderStatus,
            String orderTag,
            String placedAt,
            String filledAt
    ) {}

    public record SimulationPerformanceResponse(
            String period,
            double initialCapital,
            double currentCapital,
            double netRealizedPnl,
            double returnPercent,
            int totalTrades,
            int winningTrades,
            int losingTrades,
            int breakevenTrades,
            double winRatePercent,
            double grossPnl,
            double totalStatutoryCharges,
            double profitFactor,
            double averageWin,
            double averageLoss,
            double winLossRatio,
            Double maxMfeR,
            Double maxMaeR,
            Long averageHoldingDurationSeconds,
            TodayPerformanceSummary todaySummary
    ) {
        public record TodayPerformanceSummary(
                int tradesCount,
                double grossPnl,
                double friction,
                double netPnl,
                int openPositionsCount
        ) {}
    }

    public record StrategySimulationResponse(
            Integer strategyId,
            String strategyKey,
            String strategyName,
            String description,
            Integer priority,
            String executionMode,
            Double allocatedCapital,
            Double riskPerTradeInr,
            Integer maxDailyTrades,
            Integer maxDailyLosses,
            boolean isActive,
            int todayTradesCount,
            int todayLossCount,
            boolean coolingOff
    ) {}

    /**
     * Retrieves the persistent initial baseline capital (defaults to 50,000.0).
     */
    public double getBaselineCapital() {
        return runtimeSettingRepository.findByNameAndIsActiveTrue(SETTING_INITIAL_CAPITAL)
                .map(RuntimeSetting::getValue)
                .filter(v -> v != null && !v.isBlank())
                .map(Double::parseDouble)
                .orElse(DEFAULT_BASELINE_CAPITAL);
    }

    /**
     * Configures the initial baseline capital and syncs RiskEngine.
     */
    @Transactional
    public SimulationCapitalResponse setInitialCapital(double amount) {
        if (amount <= 0.0) {
            throw new IllegalArgumentException("Capital amount must be strictly greater than 0");
        }

        RuntimeSetting setting = runtimeSettingRepository.findByName(SETTING_INITIAL_CAPITAL)
                .orElseGet(() -> RuntimeSetting.builder()
                        .name(SETTING_INITIAL_CAPITAL)
                        .valueType("DECIMAL")
                        .description("Initial baseline starting capital for paper trading simulation")
                        .isActive(true)
                        .build());
        setting.setValue(String.valueOf(amount));
        setting.setUpdatedAt(LocalDateTime.now().toString());
        runtimeSettingRepository.save(setting);

        if (simulationCapitalRepository != null) {
            LocalDate today = timeProvider.today();
            String todayPrefix = today.toString();
            String nowStr = LocalDateTime.now().toString();
            SimulationCapital cap = simulationCapitalRepository.findByTradingDate(todayPrefix).orElseGet(() ->
                    SimulationCapital.builder()
                            .tradingDate(todayPrefix)
                            .portfolioMode("PAPER")
                            .totalTrades(0)
                            .realizedGrossPnl(0.0)
                            .statutoryCharges(0.0)
                            .statutoryTaxes(0.0)
                            .netPnl(0.0)
                            .createdAt(nowStr)
                            .updatedAt(nowStr)
                            .build()
            );
            cap.setOpeningCapital(amount);
            double net = cap.getNetPnl() != null ? cap.getNetPnl() : 0.0;
            cap.setAvailableCapital(amount + net);
            cap.setClosingCapital(amount + net);
            cap.setUpdatedAt(nowStr);
            simulationCapitalRepository.save(cap);
        }

        log.info("[SIMULATION] Configured baseline capital to {}", amount);
        syncCompoundedCapital();
        return getCapitalStatus();
    }

    /**
     * Gets current capital status including today's starting capital and compounded capital.
     */
    public SimulationCapitalResponse getCapitalStatus() {
        LocalDate today = timeProvider.today();
        String todayPrefix = today.toString();
        double baseline = getBaselineCapital();

        if (simulationCapitalRepository != null) {
            Optional<SimulationCapital> todayOpt = simulationCapitalRepository.findByTradingDate(todayPrefix);
            if (todayOpt.isPresent()) {
                SimulationCapital cap = todayOpt.get();
                double startingCapitalToday = cap.getOpeningCapital();
                double currentCompoundedCapital = cap.getAvailableCapital();
                double todayNetPnl = cap.getNetPnl() != null ? cap.getNetPnl() : 0.0;
                double cumulativeRealizedPnl = (startingCapitalToday - baseline) + todayNetPnl;
                double cumulativeReturn = baseline > 0.0 ? (cumulativeRealizedPnl / baseline) * 100.0 : 0.0;
                return new SimulationCapitalResponse(
                        baseline,
                        startingCapitalToday,
                        currentCompoundedCapital,
                        todayNetPnl,
                        cumulativeRealizedPnl,
                        cumulativeReturn,
                        today
                );
            }
        }

        List<SimulatedPosition> allClosed = simulatedPositionRepository.findByPositionStatus("CLOSED");

        double priorNetPnl = 0.0;
        double todayNetPnl = 0.0;

        for (SimulatedPosition pos : allClosed) {
            double net = pos.getNetPnl() != null ? pos.getNetPnl() : 0.0;
            if (pos.getEntryTime() != null && pos.getEntryTime().startsWith(todayPrefix)) {
                todayNetPnl += net;
            } else {
                priorNetPnl += net;
            }
        }

        double startingCapitalToday = baseline + priorNetPnl;
        double currentCompoundedCapital = startingCapitalToday + todayNetPnl;
        double cumulativeRealizedPnl = priorNetPnl + todayNetPnl;
        double cumulativeReturn = baseline > 0.0 ? (cumulativeRealizedPnl / baseline) * 100.0 : 0.0;

        return new SimulationCapitalResponse(
                baseline,
                startingCapitalToday,
                currentCompoundedCapital,
                todayNetPnl,
                cumulativeRealizedPnl,
                cumulativeReturn,
                today
        );
    }

    /**
     * Synchronizes in-memory RiskEngine portfolio state with the calculated compounded capital.
     */
    public void syncCompoundedCapital() {
        LocalDate today = timeProvider.today();
        String todayPrefix = today.toString();
        double baseline = getBaselineCapital();

        if (simulationCapitalRepository != null) {
            SimulationCapital cap = simulationCapitalRepository.findByTradingDate(todayPrefix).orElseGet(() -> {
                Optional<SimulationCapital> prev = simulationCapitalRepository.findTopByTradingDateLessThanOrderByTradingDateDesc(todayPrefix);
                double morningCap = prev.map(p -> p.getClosingCapital() != null ? p.getClosingCapital()
                        : (p.getOpeningCapital() + (p.getNetPnl() != null ? p.getNetPnl() : 0.0))).orElse(baseline);
                String nowStr = LocalDateTime.now().toString();
                SimulationCapital newRec = SimulationCapital.builder()
                        .tradingDate(todayPrefix)
                        .portfolioMode("PAPER")
                        .openingCapital(morningCap)
                        .availableCapital(morningCap)
                        .closingCapital(morningCap)
                        .totalTrades(0)
                        .realizedGrossPnl(0.0)
                        .statutoryCharges(0.0)
                        .statutoryTaxes(0.0)
                        .netPnl(0.0)
                        .createdAt(nowStr)
                        .updatedAt(nowStr)
                        .build();
                return simulationCapitalRepository.save(newRec);
            });
            riskEngine.initializePortfolio(PortfolioMode.PAPER, cap.getOpeningCapital(), today);
            log.info("[SIMULATION] Synced RiskEngine PAPER portfolio from simulation_capital: startingCapital={} available={}",
                    cap.getOpeningCapital(), cap.getAvailableCapital());
            return;
        }

        SimulationCapitalResponse status = getCapitalStatus();
        riskEngine.initializePortfolio(PortfolioMode.PAPER, status.startingCapitalToday(), status.tradingDate());
        log.info("[SIMULATION] Synced RiskEngine PAPER portfolio: startingCapital={} currentCompounded={}",
                status.startingCapitalToday(), status.currentCompoundedCapital());
    }

    /**
     * Two-tier simulation reset (TODAY vs TOTAL).
     */
    @Transactional
    public SimulationResetResponse resetSimulation(String scope) {
        LocalDate today = timeProvider.today();
        String todayPrefix = today.toString();
        String normalizedScope = scope != null ? scope.trim().toUpperCase(Locale.ROOT) : "TODAY";

        if ("TODAY".equals(normalizedScope)) {
            // 1. Clear in-memory active positions for PAPER
            positionManagerService.clearPositionsForMode(PortfolioMode.PAPER);

            // 2. Clear resting SL orders
            simulatedOrderAdapter.clearAllRestingOrders();

            // 3. Delete today's simulation orders and positions
            simulatedOrderRepository.deleteByPlacedAtStartingWith(todayPrefix);
            simulatedPositionRepository.deleteByEntryTimeStartingWith(todayPrefix);

            // 4. Delete today's simulation capital ledger entry
            if (simulationCapitalRepository != null) {
                simulationCapitalRepository.deleteByTradingDate(todayPrefix);
            }

            // 5. Recalculate today's starting capital from history prior to today
            double baseline = getBaselineCapital();
            double morningCapital;
            if (simulationCapitalRepository != null) {
                Optional<SimulationCapital> prev = simulationCapitalRepository.findTopByTradingDateLessThanOrderByTradingDateDesc(todayPrefix);
                morningCapital = prev.map(p -> p.getClosingCapital() != null ? p.getClosingCapital()
                        : (p.getOpeningCapital() + (p.getNetPnl() != null ? p.getNetPnl() : 0.0))).orElse(baseline);
                String nowStr = LocalDateTime.now().toString();
                SimulationCapital newRec = SimulationCapital.builder()
                        .tradingDate(todayPrefix)
                        .portfolioMode("PAPER")
                        .openingCapital(morningCapital)
                        .availableCapital(morningCapital)
                        .closingCapital(morningCapital)
                        .totalTrades(0)
                        .realizedGrossPnl(0.0)
                        .statutoryCharges(0.0)
                        .statutoryTaxes(0.0)
                        .netPnl(0.0)
                        .createdAt(nowStr)
                        .updatedAt(nowStr)
                        .build();
                simulationCapitalRepository.save(newRec);
            } else {
                List<SimulatedPosition> remaining = simulatedPositionRepository.findByPositionStatus("CLOSED");
                double priorNetPnl = remaining.stream()
                        .mapToDouble(p -> p.getNetPnl() != null ? p.getNetPnl() : 0.0)
                        .sum();
                morningCapital = baseline + priorNetPnl;
            }

            riskEngine.initializePortfolio(PortfolioMode.PAPER, morningCapital, today);

            log.info("[SIMULATION] Today's simulation reset completed. Restored morning capital={}", morningCapital);
            return new SimulationResetResponse(
                    "SUCCESS",
                    "TODAY",
                    "Today's simulation reset successfully. Open positions cleared, today's trades purged, and capital restored to morning balance.",
                    morningCapital,
                    today
            );
        } else if ("TOTAL".equals(normalizedScope)) {
            // Full simulation wipeout
            positionManagerService.clearPositionsForMode(PortfolioMode.PAPER);
            simulatedOrderAdapter.clearAllRestingOrders();
            simulatedOrderRepository.deleteAll();
            simulatedPositionRepository.deleteAll();

            if (simulationCapitalRepository != null) {
                simulationCapitalRepository.deleteAll();
            }

            double baseline = getBaselineCapital();
            if (simulationCapitalRepository != null) {
                String nowStr = LocalDateTime.now().toString();
                SimulationCapital fresh = SimulationCapital.builder()
                        .tradingDate(todayPrefix)
                        .portfolioMode("PAPER")
                        .openingCapital(baseline)
                        .availableCapital(baseline)
                        .closingCapital(baseline)
                        .totalTrades(0)
                        .realizedGrossPnl(0.0)
                        .statutoryCharges(0.0)
                        .statutoryTaxes(0.0)
                        .netPnl(0.0)
                        .createdAt(nowStr)
                        .updatedAt(nowStr)
                        .build();
                simulationCapitalRepository.save(fresh);
            }
            riskEngine.initializePortfolio(PortfolioMode.PAPER, baseline, today);

            log.info("[SIMULATION] TOTAL simulation wiped. Reset capital to baseline={}", baseline);
            return new SimulationResetResponse(
                    "SUCCESS",
                    "TOTAL",
                    "Total simulation wiped out. All orders and positions deleted, capital reset to baseline of " + baseline,
                    baseline,
                    today
            );
        } else {
            throw new IllegalArgumentException("Invalid reset scope: " + scope + ". Must be 'TODAY' or 'TOTAL'");
        }
    }

    /**
     * Retrieves all open positions today enriched with live LTP, unrealized PnL, MFE, and MAE.
     */
    public List<OpenPositionResponse> getOpenPositionsToday() {
        LocalDate today = timeProvider.today();
        Map<String, PositionSnapshot> active = positionManagerService.getActivePositions(PortfolioMode.PAPER);
        if (active.isEmpty()) {
            return Collections.emptyList();
        }

        List<OpenPositionResponse> result = new ArrayList<>();
        for (PositionSnapshot pos : active.values()) {
            double ltp = pos.entryPrice();
            Optional<StockContext> optContext = stockContextCache.getStockContext(pos.symbol(), pos.exchange(), today);
            if (optContext.isPresent() && optContext.get().currentLtp() != null && optContext.get().currentLtp() > 0.0) {
                ltp = optContext.get().currentLtp();
            }

            double unrealizedPnl = (ltp - pos.entryPrice()) * pos.currentQuantity();
            double unrealizedPct = ((ltp - pos.entryPrice()) / pos.entryPrice()) * 100.0;

            result.add(new OpenPositionResponse(
                    pos.positionId(),
                    pos.strategyId(),
                    pos.symbol(),
                    pos.exchange(),
                    pos.positionSide(),
                    pos.initialQuantity(),
                    pos.currentQuantity(),
                    pos.entryPrice(),
                    ltp,
                    unrealizedPnl,
                    unrealizedPct,
                    pos.currentStopLoss(),
                    pos.target1Price(),
                    pos.target1Booked(),
                    pos.peakPrice(),
                    pos.troughPrice(),
                    pos.entryTime() != null ? pos.entryTime().toString() : null,
                    pos.restingSlOrderId()
            ));
        }

        return result;
    }

    /**
     * Retrieves all closed positions executed today for the dashboard trades ledger.
     */
    public List<ClosedTradeResponse> getTodayClosedTrades() {
        LocalDate today = timeProvider.today();
        String todayPrefix = today.toString();
        List<SimulatedPosition> closed = simulatedPositionRepository.findByPositionStatusAndEntryTimeStartingWith("CLOSED", todayPrefix);

        List<ClosedTradeResponse> result = new ArrayList<>();
        for (SimulatedPosition pos : closed) {
            double gross = pos.getGrossPnl() != null ? pos.getGrossPnl() : 0.0;
            double charges = pos.getStatutoryCharges() != null ? pos.getStatutoryCharges() : 0.0;
            double net = pos.getNetPnl() != null ? pos.getNetPnl() : (gross - charges);

            result.add(new ClosedTradeResponse(
                    pos.getId(),
                    pos.getSymbol(),
                    pos.getExchange(),
                    pos.getPositionSide(),
                    pos.getInitialQuantity() != null ? pos.getInitialQuantity() : 0,
                    pos.getEntryPrice() != null ? pos.getEntryPrice() : 0.0,
                    pos.getExitPrice(),
                    gross,
                    charges,
                    net,
                    pos.getEntryTime(),
                    pos.getExitTime(),
                    pos.getExitReason()
            ));
        }

        result.sort(Comparator.comparing(ClosedTradeResponse::exitTime, Comparator.nullsLast(Comparator.reverseOrder())));
        return result;
    }

    /**
     * Retrieves orders ledger with optional single date or date range filtering.
     */
    public List<SimulationOrderResponse> getOrders(String dateStr, String fromDate, String toDate) {
        List<SimulatedOrder> orders;

        if (fromDate != null && !fromDate.isBlank() && toDate != null && !toDate.isBlank()) {
            orders = simulatedOrderRepository.findByPlacedAtBetween(fromDate + "T00:00:00", toDate + "T23:59:59");
        } else if (dateStr != null && !dateStr.isBlank()) {
            orders = simulatedOrderRepository.findByPlacedAtStartingWith(dateStr);
        } else {
            String todayStr = timeProvider.today().toString();
            orders = simulatedOrderRepository.findByPlacedAtStartingWith(todayStr);
        }

        List<SimulationOrderResponse> responses = new ArrayList<>();
        for (SimulatedOrder o : orders) {
            responses.add(toOrderResponse(o));
        }

        // Merge active resting SL orders if querying today
        String filterDate = (dateStr != null && !dateStr.isBlank()) ? dateStr : timeProvider.today().toString();
        if (filterDate.equals(timeProvider.today().toString())) {
            Map<String, SimulatedOrderAdapter.RestingOrderState> resting = simulatedOrderAdapter.getActiveRestingOrders();
            for (SimulatedOrderAdapter.RestingOrderState r : resting.values()) {
                // If not already in DB list with PENDING_TRIGGER
                boolean alreadyPresent = responses.stream()
                        .anyMatch(resp -> r.brokerOrderId().equals(resp.brokerOrderId()));
                if (!alreadyPresent) {
                    responses.add(new SimulationOrderResponse(
                            r.dbOrderId(),
                            r.brokerOrderId(),
                            r.brokerOrderId(),
                            r.strategyId(),
                            r.symbol(),
                            r.exchange(),
                            r.transactionType(),
                            "STOPLOSS_MARKET",
                            r.quantity(),
                            0,
                            null,
                            r.triggerPrice(),
                            null,
                            null,
                            null,
                            0.0,
                            "PENDING_TRIGGER",
                            "RESTING_SL_M",
                            r.placedAt().toString(),
                            null
                    ));
                }
            }
        }

        responses.sort(Comparator.comparing(SimulationOrderResponse::placedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return responses;
    }

    private SimulationOrderResponse toOrderResponse(SimulatedOrder o) {
        return new SimulationOrderResponse(
                o.getId(),
                o.getUniqueOrderId(),
                o.getBrokerOrderId(),
                o.getStrategyId(),
                o.getSymbol(),
                o.getExchange(),
                o.getTransactionType(),
                o.getOrderType(),
                o.getQuantity(),
                o.getFilledQuantity(),
                o.getPrice(),
                o.getTriggerPrice(),
                o.getAverageFillPrice(),
                o.getSlippagePerShare(),
                o.getTotalSlippage(),
                o.getStatutoryCharges() != null ? o.getStatutoryCharges() : 0.0,
                o.getOrderStatus(),
                o.getOrderTag(),
                o.getPlacedAt(),
                o.getFilledAt()
        );
    }

    /**
     * Calculates quantitative trading performance metrics (Lifetime or custom Date Range).
     */
    public SimulationPerformanceResponse getPerformance(String fromDate, String toDate) {
        LocalDate today = timeProvider.today();
        String todayPrefix = today.toString();
        double baseline = getBaselineCapital();

        List<SimulatedPosition> positions;
        String periodLabel;

        if (fromDate != null && !fromDate.isBlank() && toDate != null && !toDate.isBlank()) {
            positions = simulatedPositionRepository.findByEntryTimeBetween(fromDate + "T00:00:00", toDate + "T23:59:59");
            periodLabel = fromDate + " to " + toDate;
        } else {
            positions = simulatedPositionRepository.findAll();
            periodLabel = "LIFETIME";
        }

        int totalTrades = 0;
        int winningTrades = 0;
        int losingTrades = 0;
        int breakevenTrades = 0;

        double sumGrossPnl = 0.0;
        double sumCharges = 0.0;
        double sumNetPnl = 0.0;

        double grossWins = 0.0;
        double grossLosses = 0.0;

        Double maxMfeR = null;
        Double maxMaeR = null;
        long totalDurationSeconds = 0;
        int durationCount = 0;

        // Today's summary tracking
        int todayTrades = 0;
        double todayGross = 0.0;
        double todayCharges = 0.0;
        double todayNet = 0.0;

        for (SimulatedPosition pos : positions) {
            if (!"CLOSED".equalsIgnoreCase(pos.getPositionStatus())) {
                continue;
            }

            totalTrades++;
            double gross = pos.getGrossPnl() != null ? pos.getGrossPnl() : 0.0;
            double charges = pos.getStatutoryCharges() != null ? pos.getStatutoryCharges() : 0.0;
            double net = pos.getNetPnl() != null ? pos.getNetPnl() : (gross - charges);

            sumGrossPnl += gross;
            sumCharges += charges;
            sumNetPnl += net;

            if (net > 0.0) {
                winningTrades++;
                grossWins += gross;
            } else if (net < 0.0) {
                losingTrades++;
                grossLosses += Math.abs(gross);
            } else {
                breakevenTrades++;
            }

            if (pos.getMfeR() != null) {
                maxMfeR = maxMfeR == null ? pos.getMfeR() : Math.max(maxMfeR, pos.getMfeR());
            }
            if (pos.getMaeR() != null) {
                maxMaeR = maxMaeR == null ? pos.getMaeR() : Math.min(maxMaeR, pos.getMaeR());
            }
            if (pos.getHoldingDurationSeconds() != null) {
                totalDurationSeconds += pos.getHoldingDurationSeconds();
                durationCount++;
            }

            if (pos.getEntryTime() != null && pos.getEntryTime().startsWith(todayPrefix)) {
                todayTrades++;
                todayGross += gross;
                todayCharges += charges;
                todayNet += net;
            }
        }

        double winRate = totalTrades > 0 ? (winningTrades * 100.0 / totalTrades) : 0.0;
        double profitFactor = grossLosses > 0.0 ? (grossWins / grossLosses) : (grossWins > 0.0 ? 999.0 : 0.0);
        double avgWin = winningTrades > 0 ? (grossWins / winningTrades) : 0.0;
        double avgLoss = losingTrades > 0 ? (grossLosses / losingTrades) : 0.0;
        double winLossRatio = avgLoss > 0.0 ? (avgWin / avgLoss) : (avgWin > 0.0 ? 999.0 : 0.0);

        double currentCompounded = baseline + sumNetPnl;
        double returnPercent = baseline > 0.0 ? ((currentCompounded - baseline) / baseline) * 100.0 : 0.0;
        Long avgDuration = durationCount > 0 ? (totalDurationSeconds / durationCount) : null;

        int openCount = positionManagerService.getActivePositions(PortfolioMode.PAPER).size();
        SimulationPerformanceResponse.TodayPerformanceSummary todaySummary =
                new SimulationPerformanceResponse.TodayPerformanceSummary(todayTrades, todayGross, todayCharges, todayNet, openCount);

        return new SimulationPerformanceResponse(
                periodLabel,
                baseline,
                currentCompounded,
                sumNetPnl,
                returnPercent,
                totalTrades,
                winningTrades,
                losingTrades,
                breakevenTrades,
                winRate,
                sumGrossPnl,
                sumCharges,
                profitFactor,
                avgWin,
                avgLoss,
                winLossRatio,
                maxMfeR,
                maxMaeR,
                avgDuration,
                todaySummary
        );
    }

    /**
     * Retrieves all strategies registered in strategy_config with their simulation status.
     */
    public List<StrategySimulationResponse> getStrategies() {
        List<StrategyConfig> configs = strategyConfigRepository.findAll();
        PortfolioState paperPortfolio = riskEngine.getPortfolioState(PortfolioMode.PAPER);

        List<StrategySimulationResponse> responses = new ArrayList<>();
        for (StrategyConfig cfg : configs) {
            int tradeCount = paperPortfolio != null ? paperPortfolio.getTradeCountForStrategy(cfg.getId()) : 0;
            int lossCount = paperPortfolio != null ? paperPortfolio.getLossCountForStrategy(cfg.getId()) : 0;

            boolean coolingOff = (cfg.getMaxDailyLosses() != null && lossCount >= cfg.getMaxDailyLosses())
                    || (cfg.getMaxDailyTrades() != null && tradeCount >= cfg.getMaxDailyTrades());

            responses.add(new StrategySimulationResponse(
                    cfg.getId(),
                    cfg.getStrategyKey(),
                    cfg.getStrategyName(),
                    cfg.getDescription(),
                    cfg.getPriority(),
                    cfg.getExecutionMode(),
                    cfg.getAllocatedCapital(),
                    cfg.getRiskPerTradeInr(),
                    cfg.getMaxDailyTrades(),
                    cfg.getMaxDailyLosses(),
                    Boolean.TRUE.equals(cfg.getIsActive()),
                    tradeCount,
                    lossCount,
                    coolingOff
            ));
        }

        return responses;
    }

    /**
     * Toggles the execution mode for a strategy.
     */
    @Transactional
    public StrategySimulationResponse updateStrategyMode(Integer strategyId, String modeStr) {
        if (strategyId == null || modeStr == null) {
            throw new IllegalArgumentException("Strategy ID and execution mode must not be null");
        }

        String mode = modeStr.trim().toUpperCase(Locale.ROOT);
        if (!List.of("PAPER", "LIVE", "DISABLED").contains(mode)) {
            throw new IllegalArgumentException("Invalid execution mode: " + modeStr + ". Allowed: PAPER, LIVE, DISABLED");
        }

        StrategyConfig cfg = strategyConfigRepository.findById(strategyId)
                .orElseThrow(() -> new NoSuchElementException("Strategy configuration not found for id: " + strategyId));

        cfg.setExecutionMode(mode);
        cfg.setIsActive(!"DISABLED".equals(mode));
        cfg.setUpdatedAt(LocalDateTime.now().toString());
        StrategyConfig saved = strategyConfigRepository.save(cfg);

        log.info("[SIMULATION] Updated strategy id={} key={} executionMode={} isActive={}",
                saved.getId(), saved.getStrategyKey(), saved.getExecutionMode(), saved.getIsActive());

        PortfolioState paperPortfolio = riskEngine.getPortfolioState(PortfolioMode.PAPER);
        int tradeCount = paperPortfolio != null ? paperPortfolio.getTradeCountForStrategy(saved.getId()) : 0;
        int lossCount = paperPortfolio != null ? paperPortfolio.getLossCountForStrategy(saved.getId()) : 0;
        boolean coolingOff = (saved.getMaxDailyLosses() != null && lossCount >= saved.getMaxDailyLosses())
                || (saved.getMaxDailyTrades() != null && tradeCount >= saved.getMaxDailyTrades());

        return new StrategySimulationResponse(
                saved.getId(),
                saved.getStrategyKey(),
                saved.getStrategyName(),
                saved.getDescription(),
                saved.getPriority(),
                saved.getExecutionMode(),
                saved.getAllocatedCapital(),
                saved.getRiskPerTradeInr(),
                saved.getMaxDailyTrades(),
                saved.getMaxDailyLosses(),
                Boolean.TRUE.equals(saved.getIsActive()),
                tradeCount,
                lossCount,
                coolingOff
        );
    }
}
