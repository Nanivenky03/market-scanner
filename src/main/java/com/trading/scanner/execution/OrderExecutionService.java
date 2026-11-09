package com.trading.scanner.execution;

import com.trading.scanner.execution.adapter.BrokerOrderAdapter;
import com.trading.scanner.execution.adapter.model.OrderModifyRequest;
import com.trading.scanner.execution.adapter.model.OrderRequest;
import com.trading.scanner.execution.adapter.model.OrderResponse;
import com.trading.scanner.execution.lock.StripedSymbolLockManager;
import com.trading.scanner.model.*;
import com.trading.scanner.position.PositionManagerService;
import com.trading.scanner.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import com.trading.scanner.risk.BrokerChargesCalculator;
import com.trading.scanner.risk.RiskEngine;
import com.trading.scanner.risk.model.PortfolioMode;
import com.trading.scanner.risk.model.RiskEvaluationResult;
import com.trading.scanner.strategy.fchb.FchbStrategy;
import com.trading.scanner.strategy.model.PositionSnapshot;
import com.trading.scanner.strategy.model.StrategyProposal;
import com.trading.scanner.util.PriceTickUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Unified Core Order Execution Service.
 * Decoupled from broker APIs via BrokerOrderAdapter implementations.
 * Coordinates pre-trade risk validation, dual-leg order placement (Entry + Resting SL-M),
 * Target 1 partial exits, tactical cutoff exits (Modify-to-Market), and async minute rollover
 * trailing stop flushes with strict symbol-level concurrency locking.
 */
@Service
@Slf4j
public class OrderExecutionService {

    private final RiskEngine riskEngine;
    private final PositionManagerService positionManagerService;
    private final StripedSymbolLockManager lockManager;
    private final StrategyConfigRepository strategyConfigRepository;
    private final SimulatedPositionRepository simulatedPositionRepository;
    private final PositionRepository positionRepository;
    private final SimulatedOrderRepository simulatedOrderRepository;
    private final OrderRepository orderRepository;
    private final CapitalRepository capitalRepository;
    private final SimulationCapitalRepository simulationCapitalRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<PortfolioMode, BrokerOrderAdapter> adaptersByMode = new ConcurrentHashMap<>();

    @Autowired
    public OrderExecutionService(
            RiskEngine riskEngine,
            PositionManagerService positionManagerService,
            StripedSymbolLockManager lockManager,
            StrategyConfigRepository strategyConfigRepository,
            SimulatedPositionRepository simulatedPositionRepository,
            PositionRepository positionRepository,
            SimulatedOrderRepository simulatedOrderRepository,
            OrderRepository orderRepository,
            List<BrokerOrderAdapter> adapters,
            @Autowired(required = false) CapitalRepository capitalRepository,
            @Autowired(required = false) SimulationCapitalRepository simulationCapitalRepository
    ) {
        this.riskEngine = riskEngine;
        this.positionManagerService = positionManagerService;
        this.lockManager = lockManager;
        this.strategyConfigRepository = strategyConfigRepository;
        this.simulatedPositionRepository = simulatedPositionRepository;
        this.positionRepository = positionRepository;
        this.simulatedOrderRepository = simulatedOrderRepository;
        this.orderRepository = orderRepository;
        this.capitalRepository = capitalRepository;
        this.simulationCapitalRepository = simulationCapitalRepository;

        if (adapters != null) {
            for (BrokerOrderAdapter adapter : adapters) {
                adaptersByMode.put(adapter.getSupportedMode(), adapter);
            }
        }
    }

    public OrderExecutionService(
            RiskEngine riskEngine,
            PositionManagerService positionManagerService,
            StripedSymbolLockManager lockManager,
            StrategyConfigRepository strategyConfigRepository,
            SimulatedPositionRepository simulatedPositionRepository,
            PositionRepository positionRepository,
            SimulatedOrderRepository simulatedOrderRepository,
            OrderRepository orderRepository,
            List<BrokerOrderAdapter> adapters
    ) {
        this(riskEngine, positionManagerService, lockManager, strategyConfigRepository,
                simulatedPositionRepository, positionRepository, simulatedOrderRepository,
                orderRepository, adapters, null, null);
    }

    public record ExecutionResult(
            boolean accepted,
            String symbol,
            String direction,
            Integer executedQuantity,
            Double executedPrice,
            Double structuralStopLoss,
            Double reAnchoredTarget1,
            String rejectionReason,
            String brokerOrderId,
            String restingSlOrderId
    ) {
        public static ExecutionResult rejected(String symbol, String reason) {
            return new ExecutionResult(false, symbol, null, null, null, null, null, reason, null, null);
        }

        public static ExecutionResult filled(
                String symbol,
                String direction,
                int quantity,
                double fillPrice,
                double stopLoss,
                double target1,
                String brokerOrderId,
                String restingSlOrderId
        ) {
            return new ExecutionResult(true, symbol, direction, quantity, fillPrice, stopLoss, target1, null, brokerOrderId, restingSlOrderId);
        }
    }

    /**
     * Resolves the configured execution mode for a strategy from database configuration.
     * Defaults to PAPER mode if unconfigured or invalid.
     */
    public PortfolioMode resolveExecutionMode(Integer strategyId) {
        if (strategyId == null) {
            return PortfolioMode.PAPER;
        }
        return strategyConfigRepository.findById(strategyId)
                .map(cfg -> {
                    if (Boolean.FALSE.equals(cfg.getIsActive())) {
                        return null; // Strategy disabled
                    }
                    String mode = cfg.getExecutionMode();
                    if ("LIVE".equalsIgnoreCase(mode)) {
                        return PortfolioMode.LIVE;
                    }
                    return PortfolioMode.PAPER;
                })
                .orElse(PortfolioMode.PAPER);
    }

    /**
     * Executes the candidate StrategyProposal through the atomic execution pipeline.
     */
    public ExecutionResult processCandidateProposal(StrategyProposal proposal) {
        if (proposal == null || proposal.symbol() == null) {
            return ExecutionResult.rejected(null, "NULL_PROPOSAL");
        }

        PortfolioMode mode = resolveExecutionMode(proposal.strategyId());
        if (mode == null) {
            return ExecutionResult.rejected(proposal.symbol(), "STRATEGY_DISABLED");
        }

        return processCandidateProposal(proposal, mode);
    }

    /**
     * Executes the candidate StrategyProposal with an explicit PortfolioMode.
     */
    public ExecutionResult processCandidateProposal(StrategyProposal proposal, PortfolioMode mode) {
        if (proposal == null || proposal.symbol() == null) {
            return ExecutionResult.rejected(null, "NULL_PROPOSAL");
        }

        BrokerOrderAdapter adapter = adaptersByMode.get(mode);
        if (adapter == null) {
            return ExecutionResult.rejected(proposal.symbol(), "NO_BROKER_ADAPTER_FOR_MODE_" + mode);
        }

        String symbol = proposal.symbol();
        return lockManager.executeWithLock(symbol, () -> {
            // 1. Evaluate candidate proposal against Pre-Trade Risk Gates
            RiskEvaluationResult riskEvaluation = riskEngine.evaluateProposal(proposal, mode);
            if (!riskEvaluation.approved()) {
                log.info("[{}] Proposal for symbol={} rejected by RiskEngine: {}",
                        mode, symbol, riskEvaluation.rejectionReason());
                return ExecutionResult.rejected(symbol, riskEvaluation.rejectionReason());
            }

            int approvedQty = riskEvaluation.allocatedQuantity();
            double tickPrice = proposal.entryPrice();
            double stopLoss = proposal.initialStopLoss();
            boolean isShort = proposal.direction() != null
                    && ("SHORT".equalsIgnoreCase(proposal.direction()) || "SELL".equalsIgnoreCase(proposal.direction()));
            String positionSide = isShort ? "SHORT" : "LONG";

            // 2. Leg 1: Submit Entry Market Order
            OrderRequest entryReq = OrderRequest.marketBuy(
                    symbol,
                    proposal.exchange(),
                    approvedQty,
                    tickPrice,
                    proposal.strategyId(),
                    null
            );

            OrderResponse entryResp = adapter.placeOrder(entryReq);
            if (!entryResp.success() || entryResp.filledQuantity() <= 0) {
                log.warn("[{}] Entry order rejected/failed for symbol={}: {}", mode, symbol, entryResp.message());
                return ExecutionResult.rejected(symbol, "ENTRY_ORDER_FAILED: " + entryResp.message());
            }

            double fillPrice = entryResp.averageFillPrice() != null ? entryResp.averageFillPrice() : tickPrice;
            int filledQty = entryResp.filledQuantity();

            // 3. Fixed Structural Stop & Fill-Anchored Target (+0.73R)
            double actualRiskPerShare = isShort
                    ? Math.max(0.01, stopLoss - fillPrice)
                    : Math.max(0.01, fillPrice - stopLoss);

            double rawTarget1 = isShort
                    ? fillPrice - (FchbStrategy.TARGET_1_R_MULTIPLE * actualRiskPerShare)
                    : fillPrice + (FchbStrategy.TARGET_1_R_MULTIPLE * actualRiskPerShare);
            double reAnchoredTarget1 = PriceTickUtil.roundTarget(rawTarget1, isShort, 0.05);

            // 4. Leg 2: Submit Resting Stop-Loss Market Order (SL-M) at Broker / Simulator
            OrderRequest restingSlReq = OrderRequest.restingStopLoss(
                    symbol,
                    proposal.exchange(),
                    filledQty,
                    stopLoss,
                    proposal.strategyId(),
                    null,
                    entryResp.dbOrderId()
            );

            OrderResponse restingSlResp = adapter.placeOrder(restingSlReq);
            String restingSlOrderId = restingSlResp.brokerOrderId();

            // 5. Register in-memory PositionSnapshot
            PositionSnapshot snapshot = new PositionSnapshot(
                    entryResp.dbOrderId(),
                    proposal.strategyId(),
                    symbol,
                    proposal.exchange() != null ? proposal.exchange() : "NSE",
                    positionSide,
                    filledQty,
                    filledQty,
                    fillPrice,
                    stopLoss,
                    reAnchoredTarget1,
                    proposal.structuralInvalidationLevel(),
                    false,
                    fillPrice,
                    fillPrice, // troughPrice initialized to fillPrice
                    LocalDateTime.now(),
                    restingSlOrderId
            );

            positionManagerService.registerPosition(mode, snapshot);

            String initialHistory = createInitialTrailingHistory(fillPrice, stopLoss);
            String marketCtx = serializeMarketContext(proposal);

            // 6. Persist initial position record to DB
            if (mode == PortfolioMode.PAPER) {
                SimulatedPosition simPos = SimulatedPosition.builder()
                        .signalId(null)
                        .strategyId(proposal.strategyId())
                        .symbol(symbol)
                        .exchange(proposal.exchange() != null ? proposal.exchange() : "NSE")
                        .productType("INTRADAY")
                        .positionSide(positionSide)
                        .initialQuantity(filledQty)
                        .currentQuantity(filledQty)
                        .entryPrice(fillPrice)
                        .initialStopLoss(stopLoss)
                        .updatedStopLoss(stopLoss)
                        .initialTarget(reAnchoredTarget1)
                        .updatedTarget(reAnchoredTarget1)
                        .peakPrice(fillPrice)
                        .troughPrice(fillPrice)
                        .mfePrice(fillPrice)
                        .mfeR(0.0)
                        .mfePercent(0.0)
                        .maePrice(fillPrice)
                        .maeR(0.0)
                        .maePercent(0.0)
                        .grossPnl(0.0)
                        .statutoryCharges(entryResp.statutoryCharges() != null ? entryResp.statutoryCharges() : 0.0)
                        .netPnl(-(entryResp.statutoryCharges() != null ? entryResp.statutoryCharges() : 0.0))
                        .restingSlOrderId(restingSlOrderId)
                        .brokerModel("KOTAK_NEO")
                        .positionStatus("OPEN")
                        .entryTime(LocalDateTime.now().toString())
                        .trailingHistory(initialHistory)
                        .marketContext(marketCtx)
                        .build();

                simulatedPositionRepository.save(simPos);
            } else {
                Position livePos = Position.builder()
                        .signalId(null)
                        .strategyId(proposal.strategyId())
                        .symbol(symbol)
                        .exchange(proposal.exchange() != null ? proposal.exchange() : "NSE")
                        .productType("INTRADAY")
                        .positionSide(positionSide)
                        .initialQuantity(filledQty)
                        .currentQuantity(filledQty)
                        .entryPrice(fillPrice)
                        .initialStopLoss(stopLoss)
                        .updatedStopLoss(stopLoss)
                        .initialTarget(reAnchoredTarget1)
                        .updatedTarget(reAnchoredTarget1)
                        .peakPrice(fillPrice)
                        .troughPrice(fillPrice)
                        .restingSlOrderId(restingSlOrderId)
                        .brokerModel("KOTAK_NEO")
                        .positionStatus("OPEN")
                        .entryTime(LocalDateTime.now().toString())
                        .marketContext(marketCtx)
                        .trailingHistory(initialHistory)
                        .build();

                positionRepository.save(livePos);
            }

            double entryTurnover = filledQty * fillPrice;
            com.trading.scanner.risk.BrokerChargesCalculator.ItemizedCharges buyCharges =
                    com.trading.scanner.risk.BrokerChargesCalculator.calculateItemizedCharges(entryTurnover, true);
            updateDailyCapitalOnTradeExecuted(mode, buyCharges.getBrokerageAndExchangeCharges(), buyCharges.getStatutoryTaxes());

            log.info("[{}] OrderExecutionService opened symbol={} side={} qty={} fillPrice={} SL={} T1={} restingSlId={}",
                    mode, symbol, positionSide, filledQty, fillPrice, stopLoss, reAnchoredTarget1, restingSlOrderId);

            return ExecutionResult.filled(
                    symbol,
                    positionSide,
                    filledQty,
                    fillPrice,
                    stopLoss,
                    reAnchoredTarget1,
                    entryResp.brokerOrderId(),
                    restingSlOrderId
            );
        });
    }

    /**
     * Executes Target 1 partial exit (50% position booked at market, SL moved to breakeven).
     */
    public boolean executeTarget1PartialExit(PortfolioMode mode, String symbol, double currentTickPrice) {
        if (symbol == null) return false;

        BrokerOrderAdapter adapter = adaptersByMode.get(mode);
        if (adapter == null) return false;

        return lockManager.executeWithLock(symbol, () -> {
            PositionSnapshot pos = positionManagerService.getPosition(mode, symbol);
            if (pos == null || pos.target1Booked() || pos.currentQuantity() <= 1) {
                return false;
            }

            int initialQty = pos.initialQuantity();
            int exitQty = (int) Math.round(initialQty * 0.5);
            exitQty = Math.max(1, Math.min(pos.currentQuantity() - 1, exitQty));
            int remainingQty = pos.currentQuantity() - exitQty;
            double breakevenSl = pos.entryPrice();

            // Step 1: Modify resting SL-M order to reduce quantity and move stop to Breakeven
            if (pos.restingSlOrderId() != null) {
                OrderModifyRequest modifyReq = OrderModifyRequest.reduceQuantityAndMoveStop(
                        pos.restingSlOrderId(),
                        symbol,
                        remainingQty,
                        breakevenSl
                );
                adapter.modifyOrder(modifyReq);
            }

            // Step 2: Place Market Sell order for 50% exit
            OrderRequest partialSellReq = OrderRequest.marketSell(
                    symbol,
                    pos.exchange(),
                    exitQty,
                    currentTickPrice,
                    pos.strategyId(),
                    null,
                    pos.positionId(),
                    "TARGET_1_BOOKED"
            );

            OrderResponse fillResp = adapter.placeOrder(partialSellReq);
            double fillPrice = fillResp.averageFillPrice() != null ? fillResp.averageFillPrice() : currentTickPrice;

            double partialPnl = (fillPrice - pos.entryPrice()) * exitQty;
            double partialTurnover = exitQty * fillPrice;
            double partialFriction = BrokerChargesCalculator.calculateSellLegCharges(partialTurnover);

            riskEngine.recordPartialExit(mode, partialPnl);
            riskEngine.recordFrictionIncurred(mode, partialFriction);
            com.trading.scanner.risk.BrokerChargesCalculator.ItemizedCharges partCharges =
                    com.trading.scanner.risk.BrokerChargesCalculator.calculateItemizedCharges(partialTurnover, false);
            updateDailyCapitalOnPartialExit(mode, partialPnl, partCharges.getBrokerageAndExchangeCharges(), partCharges.getStatutoryTaxes());

            // Step 3: Update in-memory PositionSnapshot
            double peak = Math.max(pos.peakPrice(), fillPrice);
            double trough = Math.min(pos.troughPrice() != null ? pos.troughPrice() : pos.entryPrice(), fillPrice);
            PositionSnapshot updated = new PositionSnapshot(
                    pos.positionId(),
                    pos.strategyId(),
                    symbol,
                    pos.exchange(),
                    pos.positionSide(),
                    pos.initialQuantity(),
                    remainingQty,
                    pos.entryPrice(),
                    breakevenSl,
                    pos.target1Price(),
                    pos.firstCandleHigh(),
                    true, // target1Booked = true
                    peak,
                    trough,
                    pos.entryTime(),
                    pos.restingSlOrderId()
            );

            positionManagerService.registerPosition(mode, updated);

            // Step 4: Update DB position record
            if (mode == PortfolioMode.PAPER) {
                simulatedPositionRepository.findBySymbolAndPositionStatus(symbol, "OPEN").ifPresent(dbPos -> {
                    dbPos.setCurrentQuantity(remainingQty);
                    dbPos.setUpdatedStopLoss(breakevenSl);
                    dbPos.setPeakPrice(peak);
                    dbPos.setTroughPrice(trough);
                    dbPos.setGrossPnl((dbPos.getGrossPnl() != null ? dbPos.getGrossPnl() : 0.0) + partialPnl);
                    dbPos.setStatutoryCharges((dbPos.getStatutoryCharges() != null ? dbPos.getStatutoryCharges() : 0.0) + partialFriction);
                    dbPos.setNetPnl(dbPos.getGrossPnl() - dbPos.getStatutoryCharges());
                    dbPos.setUpdatedAt(LocalDateTime.now().toString());

                    Map<String, Object> t1Event = new LinkedHashMap<>();
                    t1Event.put("timestamp", LocalDateTime.now().toString());
                    t1Event.put("event", "TARGET_1_BOOKED");
                    t1Event.put("fillPrice", fillPrice);
                    t1Event.put("stopLoss", breakevenSl);
                    t1Event.put("rMultiple", 0.73);
                    dbPos.setTrailingHistory(appendTrailingHistory(dbPos.getTrailingHistory(), t1Event));

                    simulatedPositionRepository.save(dbPos);
                });
            }

            log.info("[{}] Banked Target 1 for symbol={} exitQty={} fillPrice={} remainingQty={} SL=Breakeven ({})",
                    mode, symbol, exitQty, fillPrice, remainingQty, breakevenSl);

            return true;
        });
    }

    /**
     * Handles exchange/simulator-triggered stop-loss fill events.
     * Records fill, persists trade forensics, updates daily capital, and deregisters position.
     * Guaranteed single-flow: zero secondary orders.
     */
    public boolean handleRestingStopLossFilled(PortfolioMode mode, String symbol, OrderResponse fillResp) {
        if (symbol == null || fillResp == null) return false;
        return lockManager.executeWithLock(symbol, () -> {
            PositionSnapshot pos = positionManagerService.getPosition(mode, symbol);
            if (pos == null) {
                return false;
            }

            double fillPrice = fillResp.averageFillPrice() != null ? fillResp.averageFillPrice() : pos.currentStopLoss();
            completePositionClosure(mode, symbol, pos, fillPrice, "STOP_LOSS_TRIGGERED", pos.currentStopLoss());
            return true;
        });
    }

    /**
     * Executes tactical cutoff exits (14:45 wind-down, 14:55 hard close, VWAP cut, failed breakout)
     * using the single unified Modify-to-Market flow. Modifies existing resting SL order to MARKET.
     */
    public boolean executeTacticalExit(PortfolioMode mode, String symbol, double currentTickPrice, String exitReason) {
        if (symbol == null) return false;

        BrokerOrderAdapter adapter = adaptersByMode.get(mode);
        if (adapter == null) return false;

        return lockManager.executeWithLock(symbol, () -> {
            PositionSnapshot pos = positionManagerService.getPosition(mode, symbol);
            if (pos == null) {
                return false;
            }

            String restingSlId = pos.restingSlOrderId();
            OrderResponse exitFill = null;

            // Single Unified Flow: Modify resting SL-M order to MARKET
            if (restingSlId != null) {
                OrderModifyRequest modifyToMarket = OrderModifyRequest.modifyToMarket(
                        restingSlId,
                        symbol,
                        currentTickPrice,
                        exitReason
                );
                OrderResponse modResp = adapter.modifyOrder(modifyToMarket);
                if (modResp.success() && modResp.orderStatus() == com.trading.scanner.execution.adapter.model.OrderStatusType.FILLED) {
                    exitFill = modResp;
                } else {
                    log.warn("[{}] Modify-to-Market failed for restingSlId={} symbol={}: {}", mode, restingSlId, symbol, modResp.message());
                }
            }

            // Fallback ONLY if there was no resting SL ID recorded on the position
            if (exitFill == null && restingSlId == null) {
                OrderRequest marketSell = OrderRequest.marketSell(
                        symbol,
                        pos.exchange(),
                        pos.currentQuantity(),
                        currentTickPrice,
                        pos.strategyId(),
                        null,
                        pos.positionId(),
                        exitReason
                );
                exitFill = adapter.placeOrder(marketSell);
            }

            if (exitFill == null || !exitFill.success()) {
                log.error("[{}] Tactical exit execution failed for symbol={}", mode, symbol);
                return false;
            }

            double fillPrice = exitFill.averageFillPrice() != null ? exitFill.averageFillPrice() : currentTickPrice;
            completePositionClosure(mode, symbol, pos, fillPrice, exitReason, currentTickPrice);
            return true;
        });
    }

    /**
     * Authoritative position closure pipeline:
     * 1. Realizes P&L and itemized statutory charges
     * 2. Synchronizes RiskEngine
     * 3. Persists full forensics to PostgreSQL (positions / simulated_positions)
     * 4. Updates daily capital ledger (capital / simulation_capital)
     * 5. Purges position from RAM
     */
    private void completePositionClosure(PortfolioMode mode, String symbol, PositionSnapshot pos,
                                          double fillPrice, String exitReason, double triggerPrice) {
        double pnl = (fillPrice - pos.entryPrice()) * pos.currentQuantity();
        double turnover = pos.currentQuantity() * fillPrice;
        com.trading.scanner.risk.BrokerChargesCalculator.ItemizedCharges itemized =
                com.trading.scanner.risk.BrokerChargesCalculator.calculateItemizedCharges(turnover, false);
        double friction = itemized.totalCharges();

        riskEngine.recordPositionClosed(mode, symbol, pnl);
        riskEngine.recordFrictionIncurred(mode, friction);
        positionManagerService.deregisterPosition(mode, symbol);

        // Update DB position to CLOSED with full forensics
        if (mode == PortfolioMode.PAPER) {
            simulatedPositionRepository.findBySymbolAndPositionStatus(symbol, "OPEN").ifPresent(dbPos -> {
                double peak = Math.max(pos.peakPrice() != null ? pos.peakPrice() : pos.entryPrice(), fillPrice);
                double trough = Math.min(pos.troughPrice() != null ? pos.troughPrice() : pos.entryPrice(), fillPrice);
                double initialRisk = Math.max(0.01, pos.entryPrice() - (dbPos.getInitialStopLoss() != null ? dbPos.getInitialStopLoss() : pos.currentStopLoss()));
                double mfePrice = peak;
                double mfeR = (mfePrice - pos.entryPrice()) / initialRisk;
                double mfePct = ((mfePrice - pos.entryPrice()) / pos.entryPrice()) * 100.0;

                double maePrice = trough;
                double maeR = (trough - pos.entryPrice()) / initialRisk;
                double maePct = ((trough - pos.entryPrice()) / pos.entryPrice()) * 100.0;

                double totalGross = (dbPos.getGrossPnl() != null ? dbPos.getGrossPnl() : 0.0) + pnl;
                double totalCharges = (dbPos.getStatutoryCharges() != null ? dbPos.getStatutoryCharges() : 0.0) + friction;
                double netPnl = totalGross - totalCharges;

                String exitTime = LocalDateTime.now().toString();
                Integer durationSeconds = null;
                if (dbPos.getEntryTime() != null) {
                    try {
                        LocalDateTime entryLdt = LocalDateTime.parse(dbPos.getEntryTime());
                        LocalDateTime exitLdt = LocalDateTime.parse(exitTime);
                        durationSeconds = (int) java.time.Duration.between(entryLdt, exitLdt).getSeconds();
                    } catch (Exception ignored) {}
                }

                dbPos.setCurrentQuantity(0);
                dbPos.setExitPrice(fillPrice);
                dbPos.setExitTime(exitTime);
                dbPos.setExitReason(exitReason);
                dbPos.setPositionStatus("CLOSED");
                dbPos.setPeakPrice(peak);
                dbPos.setTroughPrice(trough);
                dbPos.setMfePrice(mfePrice);
                dbPos.setMfeR(mfeR);
                dbPos.setMfePercent(mfePct);
                dbPos.setMaePrice(maePrice);
                dbPos.setMaeR(maeR);
                dbPos.setMaePercent(maePct);
                dbPos.setHoldingDurationSeconds(durationSeconds);
                dbPos.setGrossPnl(totalGross);
                dbPos.setStatutoryCharges(totalCharges);
                dbPos.setNetPnl(netPnl);
                dbPos.setPnl(netPnl);
                dbPos.setPnlPercent((netPnl / (pos.entryPrice() * pos.initialQuantity())) * 100.0);
                dbPos.setExitTriggerPrice(triggerPrice);
                dbPos.setSlippage(Math.abs(triggerPrice - fillPrice));
                dbPos.setUpdatedAt(exitTime);

                Map<String, Object> exitEvent = new LinkedHashMap<>();
                exitEvent.put("timestamp", exitTime);
                exitEvent.put("event", exitReason);
                exitEvent.put("exitPrice", fillPrice);
                exitEvent.put("grossPnl", pnl);
                exitEvent.put("netPnl", pnl - friction);
                exitEvent.put("mfeR", mfeR);
                exitEvent.put("maeR", maeR);
                dbPos.setTrailingHistory(appendTrailingHistory(dbPos.getTrailingHistory(), exitEvent));

                simulatedPositionRepository.save(dbPos);
            });
        }

        updateDailyCapitalOnPositionClosed(mode, pnl, itemized.getBrokerageAndExchangeCharges(), itemized.getStatutoryTaxes());
        log.info("[{}] Executed tactical exit for symbol={} reason={} fillPrice={} PnL={} netPnL={}",
                mode, symbol, exitReason, fillPrice, pnl, pnl - friction);
    }

    private void updateDailyCapitalOnTradeExecuted(PortfolioMode mode, double charges, double taxes) {
        LocalDate today = LocalDate.now();
        String dateStr = today.toString();
        if (mode == PortfolioMode.PAPER && simulationCapitalRepository != null) {
            SimulationCapital cap = simulationCapitalRepository.findByTradingDate(dateStr)
                    .orElseGet(() -> createInitialSimulationCapital(dateStr));
            cap.setTotalTrades(cap.getTotalTrades() + 1);
            cap.setStatutoryCharges(cap.getStatutoryCharges() + charges);
            cap.setStatutoryTaxes(cap.getStatutoryTaxes() + taxes);
            double net = cap.getRealizedGrossPnl() - cap.getStatutoryCharges() - cap.getStatutoryTaxes();
            cap.setNetPnl(net);
            cap.setAvailableCapital(cap.getOpeningCapital() + net);
            cap.setClosingCapital(cap.getOpeningCapital() + net);
            cap.setUpdatedAt(LocalDateTime.now().toString());
            simulationCapitalRepository.save(cap);
        } else if (mode == PortfolioMode.LIVE && capitalRepository != null) {
            Capital cap = capitalRepository.findByTradingDate(dateStr)
                    .orElseGet(() -> createInitialLiveCapital(dateStr));
            cap.setTotalTrades(cap.getTotalTrades() + 1);
            cap.setStatutoryCharges(cap.getStatutoryCharges() + charges);
            cap.setStatutoryTaxes(cap.getStatutoryTaxes() + taxes);
            double net = cap.getRealizedGrossPnl() - cap.getStatutoryCharges() - cap.getStatutoryTaxes();
            cap.setNetPnl(net);
            cap.setAvailableCapital(cap.getOpeningCapital() + net);
            cap.setClosingCapital(cap.getOpeningCapital() + net);
            cap.setUpdatedAt(LocalDateTime.now().toString());
            capitalRepository.save(cap);
        }
    }

    private void updateDailyCapitalOnPartialExit(PortfolioMode mode, double partialGrossPnl, double charges, double taxes) {
        LocalDate today = LocalDate.now();
        String dateStr = today.toString();
        if (mode == PortfolioMode.PAPER && simulationCapitalRepository != null) {
            simulationCapitalRepository.findByTradingDate(dateStr).ifPresent(cap -> {
                cap.setRealizedGrossPnl(cap.getRealizedGrossPnl() + partialGrossPnl);
                cap.setStatutoryCharges(cap.getStatutoryCharges() + charges);
                cap.setStatutoryTaxes(cap.getStatutoryTaxes() + taxes);
                double net = cap.getRealizedGrossPnl() - cap.getStatutoryCharges() - cap.getStatutoryTaxes();
                cap.setNetPnl(net);
                cap.setAvailableCapital(cap.getOpeningCapital() + net);
                cap.setClosingCapital(cap.getOpeningCapital() + net);
                cap.setUpdatedAt(LocalDateTime.now().toString());
                simulationCapitalRepository.save(cap);
            });
        } else if (mode == PortfolioMode.LIVE && capitalRepository != null) {
            capitalRepository.findByTradingDate(dateStr).ifPresent(cap -> {
                cap.setRealizedGrossPnl(cap.getRealizedGrossPnl() + partialGrossPnl);
                cap.setStatutoryCharges(cap.getStatutoryCharges() + charges);
                cap.setStatutoryTaxes(cap.getStatutoryTaxes() + taxes);
                double net = cap.getRealizedGrossPnl() - cap.getStatutoryCharges() - cap.getStatutoryTaxes();
                cap.setNetPnl(net);
                cap.setAvailableCapital(cap.getOpeningCapital() + net);
                cap.setClosingCapital(cap.getOpeningCapital() + net);
                cap.setUpdatedAt(LocalDateTime.now().toString());
                capitalRepository.save(cap);
            });
        }
    }

    private void updateDailyCapitalOnPositionClosed(PortfolioMode mode, double grossPnl, double charges, double taxes) {
        LocalDate today = LocalDate.now();
        String dateStr = today.toString();
        if (mode == PortfolioMode.PAPER && simulationCapitalRepository != null) {
            simulationCapitalRepository.findByTradingDate(dateStr).ifPresent(cap -> {
                cap.setRealizedGrossPnl(cap.getRealizedGrossPnl() + grossPnl);
                cap.setStatutoryCharges(cap.getStatutoryCharges() + charges);
                cap.setStatutoryTaxes(cap.getStatutoryTaxes() + taxes);
                double net = cap.getRealizedGrossPnl() - cap.getStatutoryCharges() - cap.getStatutoryTaxes();
                cap.setNetPnl(net);
                cap.setAvailableCapital(cap.getOpeningCapital() + net);
                cap.setClosingCapital(cap.getOpeningCapital() + net);
                cap.setUpdatedAt(LocalDateTime.now().toString());
                simulationCapitalRepository.save(cap);
            });
        } else if (mode == PortfolioMode.LIVE && capitalRepository != null) {
            capitalRepository.findByTradingDate(dateStr).ifPresent(cap -> {
                cap.setRealizedGrossPnl(cap.getRealizedGrossPnl() + grossPnl);
                cap.setStatutoryCharges(cap.getStatutoryCharges() + charges);
                cap.setStatutoryTaxes(cap.getStatutoryTaxes() + taxes);
                double net = cap.getRealizedGrossPnl() - cap.getStatutoryCharges() - cap.getStatutoryTaxes();
                cap.setNetPnl(net);
                cap.setAvailableCapital(cap.getOpeningCapital() + net);
                cap.setClosingCapital(cap.getOpeningCapital() + net);
                cap.setUpdatedAt(LocalDateTime.now().toString());
                capitalRepository.save(cap);
            });
        }
    }

    private SimulationCapital createInitialSimulationCapital(String dateStr) {
        double opening = 50000.0;
        if (simulationCapitalRepository != null) {
            var prev = simulationCapitalRepository.findTopByOrderByTradingDateDesc();
            if (prev.isPresent() && prev.get().getClosingCapital() != null) {
                opening = prev.get().getClosingCapital();
            }
        }
        return SimulationCapital.builder()
                .tradingDate(dateStr)
                .portfolioMode("PAPER")
                .openingCapital(opening)
                .totalTrades(0)
                .realizedGrossPnl(0.0)
                .statutoryCharges(0.0)
                .statutoryTaxes(0.0)
                .availableCapital(opening)
                .netPnl(0.0)
                .closingCapital(opening)
                .createdAt(LocalDateTime.now().toString())
                .updatedAt(LocalDateTime.now().toString())
                .build();
    }

    private Capital createInitialLiveCapital(String dateStr) {
        double opening = 50000.0;
        if (capitalRepository != null) {
            var prev = capitalRepository.findTopByOrderByTradingDateDesc();
            if (prev.isPresent() && prev.get().getClosingCapital() != null) {
                opening = prev.get().getClosingCapital();
            }
        }
        return Capital.builder()
                .tradingDate(dateStr)
                .portfolioMode("LIVE")
                .openingCapital(opening)
                .totalTrades(0)
                .realizedGrossPnl(0.0)
                .statutoryCharges(0.0)
                .statutoryTaxes(0.0)
                .availableCapital(opening)
                .netPnl(0.0)
                .closingCapital(opening)
                .createdAt(LocalDateTime.now().toString())
                .updatedAt(LocalDateTime.now().toString())
                .build();
    }

    /**
     * Minute rollover flush: Synchronizes in-memory trailing stops with broker resting orders
     * and updates position telemetry in the database without blocking the live tick thread.
     */
    public void flushMinuteRolloverTrailingStops(PortfolioMode mode, LocalDate tradingDate, LocalTime currentTime) {
        BrokerOrderAdapter adapter = adaptersByMode.get(mode);
        if (adapter == null) return;

        Map<String, PositionSnapshot> active = positionManagerService.getActivePositions(mode);
        if (active.isEmpty()) return;

        for (PositionSnapshot pos : active.values()) {
            String symbol = pos.symbol();
            lockManager.executeWithLock(symbol, () -> {
                if (pos.restingSlOrderId() != null) {
                    OrderModifyRequest updateReq = OrderModifyRequest.updateTrailingStop(
                            pos.restingSlOrderId(),
                            symbol,
                            pos.currentStopLoss()
                    );
                    adapter.modifyOrder(updateReq);
                }

                if (mode == PortfolioMode.PAPER) {
                    simulatedPositionRepository.findBySymbolAndPositionStatus(symbol, "OPEN").ifPresent(dbPos -> {
                        boolean stopChanged = dbPos.getUpdatedStopLoss() == null ||
                                Double.compare(dbPos.getUpdatedStopLoss(), pos.currentStopLoss()) != 0;
                        dbPos.setUpdatedStopLoss(pos.currentStopLoss());
                        dbPos.setPeakPrice(pos.peakPrice());
                        dbPos.setTroughPrice(pos.troughPrice());
                        dbPos.setUpdatedAt(LocalDateTime.now().toString());

                        if (stopChanged) {
                            Map<String, Object> trailEvent = new LinkedHashMap<>();
                            trailEvent.put("timestamp", LocalDateTime.now().toString());
                            trailEvent.put("event", "TRAILING_STOP_UPDATE");
                            trailEvent.put("stopLoss", pos.currentStopLoss());
                            trailEvent.put("peakPrice", pos.peakPrice());
                            trailEvent.put("troughPrice", pos.troughPrice());
                            dbPos.setTrailingHistory(appendTrailingHistory(dbPos.getTrailingHistory(), trailEvent));
                        }

                        simulatedPositionRepository.save(dbPos);
                    });
                }
            });
        }
    }

    private String serializeMarketContext(StrategyProposal proposal) {
        if (proposal == null) return "{}";
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("strategyKey", proposal.strategyKey());
            map.put("direction", proposal.direction());
            map.put("confidenceScore", proposal.confidenceScore());
            map.put("entryPrice", proposal.entryPrice());
            map.put("initialStopLoss", proposal.initialStopLoss());
            map.put("initialTarget", proposal.initialTarget());
            map.put("structuralInvalidation", proposal.structuralInvalidationLevel());
            if (proposal.ruleContext() != null) {
                map.putAll(proposal.ruleContext());
            }
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            log.warn("Failed to serialize market context: {}", e.getMessage());
            return "{}";
        }
    }

    private String createInitialTrailingHistory(double fillPrice, double stopLoss) {
        try {
            List<Map<String, Object>> events = new ArrayList<>();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("timestamp", LocalDateTime.now().toString());
            entry.put("event", "ENTRY");
            entry.put("price", fillPrice);
            entry.put("stopLoss", stopLoss);
            entry.put("rMultiple", -1.0);
            events.add(entry);
            return objectMapper.writeValueAsString(events);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String appendTrailingHistory(String existingJson, Map<String, Object> newEvent) {
        try {
            List<Map<String, Object>> list;
            if (existingJson != null && !existingJson.isBlank() && !existingJson.equals("[]")) {
                list = objectMapper.readValue(existingJson, new TypeReference<List<Map<String, Object>>>() {});
            } else {
                list = new ArrayList<>();
            }
            list.add(newEvent);
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            return existingJson != null ? existingJson : "[]";
        }
    }
}
