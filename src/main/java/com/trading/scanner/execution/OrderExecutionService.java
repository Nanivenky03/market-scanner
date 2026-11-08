package com.trading.scanner.execution;

import com.trading.scanner.execution.adapter.BrokerOrderAdapter;
import com.trading.scanner.execution.adapter.model.OrderModifyRequest;
import com.trading.scanner.execution.adapter.model.OrderRequest;
import com.trading.scanner.execution.adapter.model.OrderResponse;
import com.trading.scanner.execution.lock.StripedSymbolLockManager;
import com.trading.scanner.model.*;
import com.trading.scanner.position.PositionManagerService;
import com.trading.scanner.repository.*;
import com.trading.scanner.risk.BrokerChargesCalculator;
import com.trading.scanner.risk.RiskEngine;
import com.trading.scanner.risk.model.PortfolioMode;
import com.trading.scanner.risk.model.RiskEvaluationResult;
import com.trading.scanner.strategy.fchb.FchbStrategy;
import com.trading.scanner.strategy.model.PositionSnapshot;
import com.trading.scanner.strategy.model.StrategyProposal;
import com.trading.scanner.util.PriceTickUtil;
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

    private final Map<PortfolioMode, BrokerOrderAdapter> adaptersByMode = new ConcurrentHashMap<>();

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
        this.riskEngine = riskEngine;
        this.positionManagerService = positionManagerService;
        this.lockManager = lockManager;
        this.strategyConfigRepository = strategyConfigRepository;
        this.simulatedPositionRepository = simulatedPositionRepository;
        this.positionRepository = positionRepository;
        this.simulatedOrderRepository = simulatedOrderRepository;
        this.orderRepository = orderRepository;

        if (adapters != null) {
            for (BrokerOrderAdapter adapter : adapters) {
                adaptersByMode.put(adapter.getSupportedMode(), adapter);
            }
        }
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
                    LocalDateTime.now(),
                    restingSlOrderId
            );

            positionManagerService.registerPosition(mode, snapshot);

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
                        .trailingHistory("[]")
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
                        .restingSlOrderId(restingSlOrderId)
                        .brokerModel("KOTAK_NEO")
                        .positionStatus("OPEN")
                        .entryTime(LocalDateTime.now().toString())
                        .build();

                positionRepository.save(livePos);
            }

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

            // Step 3: Update in-memory PositionSnapshot
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
                    Math.max(pos.peakPrice(), fillPrice),
                    pos.entryTime(),
                    pos.restingSlOrderId()
            );

            positionManagerService.registerPosition(mode, updated);

            // Step 4: Update DB position record
            if (mode == PortfolioMode.PAPER) {
                simulatedPositionRepository.findBySymbolAndPositionStatus(symbol, "OPEN").ifPresent(dbPos -> {
                    dbPos.setCurrentQuantity(remainingQty);
                    dbPos.setUpdatedStopLoss(breakevenSl);
                    dbPos.setGrossPnl((dbPos.getGrossPnl() != null ? dbPos.getGrossPnl() : 0.0) + partialPnl);
                    dbPos.setStatutoryCharges((dbPos.getStatutoryCharges() != null ? dbPos.getStatutoryCharges() : 0.0) + partialFriction);
                    dbPos.setNetPnl(dbPos.getGrossPnl() - dbPos.getStatutoryCharges());
                    dbPos.setUpdatedAt(LocalDateTime.now().toString());
                    simulatedPositionRepository.save(dbPos);
                });
            }

            log.info("[{}] Banked Target 1 for symbol={} exitQty={} fillPrice={} remainingQty={} SL=Breakeven ({})",
                    mode, symbol, exitQty, fillPrice, remainingQty, breakevenSl);

            return true;
        });
    }

    /**
     * Executes tactical cutoff exits (14:45 wind-down, 5M breakout failure, VWAP cut)
     * using the atomic Modify-to-Market pattern (Pattern A) with Cancel-Confirm-Market fallback (Pattern B).
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

            // Pattern A: Attempt atomic Modify-to-Market on resting SL-M order
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
                }
            }

            // Pattern B: Fallback if Modify-to-Market was not supported or resting order already cancelled
            if (exitFill == null) {
                if (restingSlId != null) {
                    adapter.cancelOrder(restingSlId, symbol);
                }
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

            double fillPrice = exitFill != null && exitFill.averageFillPrice() != null
                    ? exitFill.averageFillPrice() : currentTickPrice;

            double pnl = (fillPrice - pos.entryPrice()) * pos.currentQuantity();
            double turnover = pos.currentQuantity() * fillPrice;
            double friction = BrokerChargesCalculator.calculateSellLegCharges(turnover);

            riskEngine.recordPositionClosed(mode, symbol, pnl);
            riskEngine.recordFrictionIncurred(mode, friction);
            positionManagerService.deregisterPosition(mode, symbol);

            // Update DB position to CLOSED with full forensics
            if (mode == PortfolioMode.PAPER) {
                simulatedPositionRepository.findBySymbolAndPositionStatus(symbol, "OPEN").ifPresent(dbPos -> {
                    double peak = Math.max(dbPos.getPeakPrice() != null ? dbPos.getPeakPrice() : pos.entryPrice(), fillPrice);
                    double initialRisk = Math.max(0.01, pos.entryPrice() - pos.currentStopLoss());
                    double mfePrice = peak;
                    double mfeR = (mfePrice - pos.entryPrice()) / initialRisk;
                    double mfePct = ((mfePrice - pos.entryPrice()) / pos.entryPrice()) * 100.0;

                    double totalGross = (dbPos.getGrossPnl() != null ? dbPos.getGrossPnl() : 0.0) + pnl;
                    double totalCharges = (dbPos.getStatutoryCharges() != null ? dbPos.getStatutoryCharges() : 0.0) + friction;
                    double netPnl = totalGross - totalCharges;

                    dbPos.setCurrentQuantity(0);
                    dbPos.setExitPrice(fillPrice);
                    dbPos.setExitTime(LocalDateTime.now().toString());
                    dbPos.setExitReason(exitReason);
                    dbPos.setPositionStatus("CLOSED");
                    dbPos.setPeakPrice(peak);
                    dbPos.setMfePrice(mfePrice);
                    dbPos.setMfeR(mfeR);
                    dbPos.setMfePercent(mfePct);
                    dbPos.setGrossPnl(totalGross);
                    dbPos.setStatutoryCharges(totalCharges);
                    dbPos.setNetPnl(netPnl);
                    dbPos.setPnl(netPnl);
                    dbPos.setPnlPercent((netPnl / (pos.entryPrice() * pos.initialQuantity())) * 100.0);
                    dbPos.setExitTriggerPrice(currentTickPrice);
                    dbPos.setSlippage(Math.abs(currentTickPrice - fillPrice));
                    dbPos.setUpdatedAt(LocalDateTime.now().toString());

                    simulatedPositionRepository.save(dbPos);
                });
            }

            log.info("[{}] Executed tactical exit for symbol={} reason={} fillPrice={} PnL={} netPnL={}",
                    mode, symbol, exitReason, fillPrice, pnl, pnl - friction);

            return true;
        });
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
                        dbPos.setUpdatedStopLoss(pos.currentStopLoss());
                        dbPos.setPeakPrice(pos.peakPrice());
                        dbPos.setUpdatedAt(LocalDateTime.now().toString());
                        simulatedPositionRepository.save(dbPos);
                    });
                }
            });
        }
    }
}
