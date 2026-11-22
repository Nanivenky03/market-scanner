package com.trading.scanner.strategy.replay;

import com.trading.scanner.execution.OrderExecutionService;
import com.trading.scanner.execution.adapter.SimulatedOrderAdapter;
import com.trading.scanner.execution.adapter.model.OrderResponse;
import com.trading.scanner.execution.lock.StripedSymbolLockManager;
import com.trading.scanner.model.SimulatedOrder;
import com.trading.scanner.model.SimulatedPosition;
import com.trading.scanner.model.StrategyConfig;
import com.trading.scanner.position.PositionManagerService;
import com.trading.scanner.repository.*;
import com.trading.scanner.risk.BrokerChargesCalculator;
import com.trading.scanner.risk.RiskEngine;
import com.trading.scanner.risk.model.PortfolioMode;
import com.trading.scanner.strategy.StrategyPositionHandler;
import com.trading.scanner.strategy.model.PositionSnapshot;
import com.trading.scanner.strategy.model.StrategyProposal;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Isolated test execution context that wires the REAL production OrderExecutionService,
 * PositionManagerService, RiskEngine, and SimulatedOrderAdapter with in-memory repositories.
 * Guaranteed ZERO writes to PostgreSQL production tables.
 */
public class TestReplayExecutionContext {

    private final RiskEngine riskEngine;
    private final PositionManagerService positionManagerService;
    private final StripedSymbolLockManager lockManager;
    private final SimulatedOrderAdapter orderAdapter;
    private final OrderExecutionService orderExecutionService;

    private final Map<Integer, SimulatedOrder> simulatedOrders = new ConcurrentHashMap<>();
    private final Map<String, SimulatedPosition> simulatedPositionsBySymbol = new ConcurrentHashMap<>();
    private final List<SimulatedPosition> completedPositions = new ArrayList<>();
    private final AtomicInteger orderIdGen = new AtomicInteger(1000);
    private final AtomicInteger positionIdGen = new AtomicInteger(5000);

    public TestReplayExecutionContext(StrategyPositionHandler positionHandler, double startingCapital, LocalDate tradingDate) {
        // 1. Initialize real production RiskEngine with historical date
        this.riskEngine = new RiskEngine();
        this.riskEngine.initializePortfolio(PortfolioMode.PAPER, startingCapital, tradingDate);

        // 2. Initialize real production PositionManagerService
        this.positionManagerService = new PositionManagerService(this.riskEngine, List.of(positionHandler));

        // 3. Initialize real lock manager
        this.lockManager = new StripedSymbolLockManager();

        // 4. In-memory Mock Repositories
        SimulatedOrderRepository simulatedOrderRepo = Mockito.mock(SimulatedOrderRepository.class);
        when(simulatedOrderRepo.save(any(SimulatedOrder.class))).thenAnswer(inv -> {
            SimulatedOrder order = inv.getArgument(0);
            if (order.getId() == null) {
                order.setId(orderIdGen.incrementAndGet());
            }
            simulatedOrders.put(order.getId(), order);
            return order;
        });
        when(simulatedOrderRepo.findById(anyInt())).thenAnswer(inv -> {
            Integer id = inv.getArgument(0);
            return Optional.ofNullable(simulatedOrders.get(id));
        });

        SimulatedPositionRepository simulatedPosRepo = Mockito.mock(SimulatedPositionRepository.class);
        when(simulatedPosRepo.save(any(SimulatedPosition.class))).thenAnswer(inv -> {
            SimulatedPosition pos = inv.getArgument(0);
            if (pos.getId() == null) {
                pos.setId(positionIdGen.incrementAndGet());
            }
            simulatedPositionsBySymbol.put(pos.getSymbol(), pos);
            if ("CLOSED".equalsIgnoreCase(pos.getPositionStatus())) {
                if (!completedPositions.contains(pos)) {
                    completedPositions.add(pos);
                }
            }
            return pos;
        });
        when(simulatedPosRepo.findBySymbolAndPositionStatus(anyString(), anyString())).thenAnswer(inv -> {
            String symbol = inv.getArgument(0);
            String status = inv.getArgument(1);
            SimulatedPosition p = simulatedPositionsBySymbol.get(symbol);
            if (p != null && status.equalsIgnoreCase(p.getPositionStatus())) {
                return Optional.of(p);
            }
            return Optional.empty();
        });

        StrategyConfigRepository strategyConfigRepo = Mockito.mock(StrategyConfigRepository.class);
        StrategyConfig config = StrategyConfig.builder()
                .id(2)
                .strategyKey("FCHB")
                .executionMode("PAPER")
                .isActive(true)
                .build();
        when(strategyConfigRepo.findById(anyInt())).thenReturn(Optional.of(config));

        PositionRepository livePosRepo = Mockito.mock(PositionRepository.class);
        OrderRepository liveOrderRepo = Mockito.mock(OrderRepository.class);

        // 5. Initialize real production SimulatedOrderAdapter
        this.orderAdapter = new SimulatedOrderAdapter(simulatedOrderRepo);

        // 6. Initialize real production OrderExecutionService
        this.orderExecutionService = new OrderExecutionService(
                this.riskEngine,
                this.positionManagerService,
                this.lockManager,
                strategyConfigRepo,
                simulatedPosRepo,
                livePosRepo,
                simulatedOrderRepo,
                liveOrderRepo,
                List.of(this.orderAdapter)
        );
    }

    public StripedSymbolLockManager getLockManager() {
        return lockManager;
    }

    public OrderExecutionService getOrderExecutionService() {
        return orderExecutionService;
    }

    public PositionManagerService getPositionManagerService() {
        return positionManagerService;
    }

    public RiskEngine getRiskEngine() {
        return riskEngine;
    }

    public SimulatedOrderAdapter getOrderAdapter() {
        return orderAdapter;
    }

    public List<SimulatedPosition> getCompletedPositions() {
        return Collections.unmodifiableList(completedPositions);
    }

    public Map<String, SimulatedPosition> getSimulatedPositionsBySymbol() {
        return Collections.unmodifiableMap(simulatedPositionsBySymbol);
    }

    public OrderExecutionService.ExecutionResult processCandidateProposal(StrategyProposal proposal) {
        return orderExecutionService.processCandidateProposal(proposal, PortfolioMode.PAPER);
    }

    public boolean executeTarget1PartialExit(String symbol, double currentTickPrice) {
        return orderExecutionService.executeTarget1PartialExit(PortfolioMode.PAPER, symbol, currentTickPrice);
    }

    public boolean handleRestingStopLossFilled(String symbol, PositionSnapshot pos, double fillPrice) {
        double turnover = pos.currentQuantity() * fillPrice;
        double friction = BrokerChargesCalculator.calculateSellLegCharges(turnover);
        OrderResponse fillResp = OrderResponse.filled(
                pos.restingSlOrderId() != null ? pos.restingSlOrderId() : "SIM_SL_FILL",
                1,
                pos.currentQuantity(),
                fillPrice,
                0.05,
                friction
        );
        return orderExecutionService.handleRestingStopLossFilled(PortfolioMode.PAPER, symbol, fillResp);
    }

    public boolean executeTacticalExit(String symbol, double currentTickPrice, String exitReason) {
        return orderExecutionService.executeTacticalExit(PortfolioMode.PAPER, symbol, currentTickPrice, exitReason);
    }
}
