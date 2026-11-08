package com.trading.scanner.execution;

import com.trading.scanner.execution.adapter.BrokerOrderAdapter;
import com.trading.scanner.execution.adapter.SimulatedOrderAdapter;
import com.trading.scanner.execution.lock.StripedSymbolLockManager;
import com.trading.scanner.model.SimulatedOrder;
import com.trading.scanner.model.SimulatedPosition;
import com.trading.scanner.model.StrategyConfig;
import com.trading.scanner.position.PositionManagerService;
import com.trading.scanner.repository.*;
import com.trading.scanner.risk.RiskEngine;
import com.trading.scanner.risk.model.PortfolioMode;
import com.trading.scanner.strategy.fchb.FchbPositionHandler;
import com.trading.scanner.strategy.model.PositionSnapshot;
import com.trading.scanner.strategy.model.StrategyProposal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderExecutionServiceTest {

    @Mock
    private StrategyConfigRepository strategyConfigRepository;

    @Mock
    private SimulatedPositionRepository simulatedPositionRepository;

    @Mock
    private PositionRepository positionRepository;

    @Mock
    private SimulatedOrderRepository simulatedOrderRepository;

    @Mock
    private OrderRepository orderRepository;

    private RiskEngine riskEngine;
    private PositionManagerService positionManagerService;
    private StripedSymbolLockManager lockManager;
    private SimulatedOrderAdapter simulatedOrderAdapter;
    private OrderExecutionService executionService;

    private final LocalDate today = LocalDate.of(2026, 10, 11);

    @BeforeEach
    void setUp() {
        riskEngine = new RiskEngine();
        riskEngine.initializePortfolio(PortfolioMode.PAPER, 50000.0, today);

        FchbPositionHandler handler = new FchbPositionHandler();
        positionManagerService = new PositionManagerService(riskEngine, List.of(handler));
        lockManager = new StripedSymbolLockManager();

        when(simulatedOrderRepository.save(any(SimulatedOrder.class))).thenAnswer(inv -> {
            SimulatedOrder order = inv.getArgument(0);
            if (order.getId() == null) order.setId(201);
            return order;
        });

        simulatedOrderAdapter = new SimulatedOrderAdapter(simulatedOrderRepository);

        executionService = new OrderExecutionService(
                riskEngine,
                positionManagerService,
                lockManager,
                strategyConfigRepository,
                simulatedPositionRepository,
                positionRepository,
                simulatedOrderRepository,
                orderRepository,
                List.of(simulatedOrderAdapter)
        );
    }

    private StrategyProposal createProposal(String symbol, double entry, double stop, double target) {
        return new StrategyProposal(
                symbol,
                "NSE",
                2,
                "FCHB",
                "LONG",
                entry,
                stop,
                target,
                entry - 2.0,
                100.0,
                Map.of("yellowCount", 0L)
        );
    }

    @Test
    @DisplayName("Executes complete 2-leg entry: Fills Market Buy, places resting SL-M, registers position in RAM & DB")
    void processCandidateProposal_shouldExecuteDualLegsAndRegisterPosition() {
        StrategyConfig config = StrategyConfig.builder()
                .id(2)
                .strategyKey("FCHB")
                .executionMode("PAPER")
                .isActive(true)
                .build();
        when(strategyConfigRepository.findById(2)).thenReturn(Optional.of(config));

        when(simulatedPositionRepository.save(any(SimulatedPosition.class))).thenAnswer(inv -> inv.getArgument(0));

        StrategyProposal proposal = createProposal("INFY", 1500.0, 1485.0, 1515.0);
        OrderExecutionService.ExecutionResult result = executionService.processCandidateProposal(proposal);

        assertTrue(result.accepted());
        assertEquals("INFY", result.symbol());
        assertEquals("LONG", result.direction());
        assertTrue(result.executedQuantity() > 0);
        // Fill price = 1500.0 + 0.05 slippage
        assertEquals(1500.05, result.executedPrice());
        assertNotNull(result.brokerOrderId());
        assertNotNull(result.restingSlOrderId());

        // Verify position registered in memory
        PositionSnapshot pos = positionManagerService.getPosition(PortfolioMode.PAPER, "INFY");
        assertNotNull(pos);
        assertEquals(1500.05, pos.entryPrice());
        assertEquals(1485.0, pos.currentStopLoss());
        assertEquals(result.restingSlOrderId(), pos.restingSlOrderId());

        // Verify resting SL-M order is in adapter queue
        assertTrue(simulatedOrderAdapter.getActiveRestingOrders().containsKey(result.restingSlOrderId()));
    }

    @Test
    @DisplayName("Executes Target 1: Modifies resting SL-M to breakeven, fills 50% market sell, and updates position")
    void executeTarget1PartialExit_shouldModifySlAndBookProfit() {
        StrategyConfig config = StrategyConfig.builder().id(2).executionMode("PAPER").isActive(true).build();
        when(strategyConfigRepository.findById(2)).thenReturn(Optional.of(config));
        when(simulatedPositionRepository.save(any(SimulatedPosition.class))).thenAnswer(inv -> inv.getArgument(0));

        StrategyProposal proposal = createProposal("TCS", 3000.0, 2970.0, 3030.0);
        OrderExecutionService.ExecutionResult result = executionService.processCandidateProposal(proposal);
        assertTrue(result.accepted());

        int initialQty = result.executedQuantity();
        double entryPrice = result.executedPrice();

        // Target 1 hit at 3022.0
        boolean t1Success = executionService.executeTarget1PartialExit(PortfolioMode.PAPER, "TCS", 3022.0);
        assertTrue(t1Success);

        PositionSnapshot pos = positionManagerService.getPosition(PortfolioMode.PAPER, "TCS");
        assertTrue(pos.target1Booked());
        assertTrue(pos.currentQuantity() < initialQty);
        assertEquals(entryPrice, pos.currentStopLoss()); // SL moved to breakeven

        // Resting SL-M in adapter queue must now reflect reduced qty and breakeven stop
        SimulatedOrderAdapter.RestingOrderState resting = simulatedOrderAdapter.getActiveRestingOrders().get(pos.restingSlOrderId());
        assertNotNull(resting);
        assertEquals(pos.currentQuantity(), resting.quantity());
        assertEquals(entryPrice, resting.triggerPrice());
    }

    @Test
    @DisplayName("Executes tactical exit via Modify-to-Market (Pattern A), eliminating reverse position hazard")
    void executeTacticalExit_modifyToMarket_shouldClosePositionCleanly() {
        StrategyConfig config = StrategyConfig.builder().id(2).executionMode("PAPER").isActive(true).build();
        when(strategyConfigRepository.findById(2)).thenReturn(Optional.of(config));
        when(simulatedPositionRepository.save(any(SimulatedPosition.class))).thenAnswer(inv -> inv.getArgument(0));

        SimulatedPosition mockOpenDbPos = SimulatedPosition.builder()
                .symbol("RELIANCE")
                .positionStatus("OPEN")
                .entryPrice(2500.05)
                .currentQuantity(20)
                .initialQuantity(20)
                .build();
        when(simulatedPositionRepository.findBySymbolAndPositionStatus("RELIANCE", "OPEN")).thenReturn(Optional.of(mockOpenDbPos));

        StrategyProposal proposal = createProposal("RELIANCE", 2500.0, 2480.0, 2520.0);
        OrderExecutionService.ExecutionResult result = executionService.processCandidateProposal(proposal);
        assertTrue(result.accepted());

        String restingSlId = result.restingSlOrderId();
        assertTrue(simulatedOrderAdapter.getActiveRestingOrders().containsKey(restingSlId));

        // 14:45 Wind-down cutoff exit at 2515.0
        boolean exited = executionService.executeTacticalExit(PortfolioMode.PAPER, "RELIANCE", 2515.0, "WIND_DOWN_1445");
        assertTrue(exited);

        // Position removed from resting orders
        assertFalse(simulatedOrderAdapter.getActiveRestingOrders().containsKey(restingSlId));
        // Closed in memory
        assertNull(positionManagerService.getPosition(PortfolioMode.PAPER, "RELIANCE"));
        // DB position marked CLOSED
        assertEquals("CLOSED", mockOpenDbPos.getPositionStatus());
        assertEquals("WIND_DOWN_1445", mockOpenDbPos.getExitReason());
        assertNotNull(mockOpenDbPos.getGrossPnl());
        assertNotNull(mockOpenDbPos.getNetPnl());
    }

    @Test
    @DisplayName("Minute rollover trailing stop flush updates resting order trigger price")
    void flushMinuteRolloverTrailingStops_shouldUpdateRestingOrderTriggerPrice() {
        StrategyConfig config = StrategyConfig.builder().id(2).executionMode("PAPER").isActive(true).build();
        when(strategyConfigRepository.findById(2)).thenReturn(Optional.of(config));
        when(simulatedPositionRepository.save(any(SimulatedPosition.class))).thenAnswer(inv -> inv.getArgument(0));

        StrategyProposal proposal = createProposal("HDFCBANK", 1600.0, 1580.0, 1620.0);
        OrderExecutionService.ExecutionResult result = executionService.processCandidateProposal(proposal);
        assertTrue(result.accepted());

        // Simulate tick that moved trailing stop up to 1590.0 in RAM
        PositionSnapshot pos = positionManagerService.getPosition(PortfolioMode.PAPER, "HDFCBANK");
        PositionSnapshot ratcheted = new PositionSnapshot(
                pos.positionId(),
                pos.strategyId(),
                pos.symbol(),
                pos.exchange(),
                pos.positionSide(),
                pos.initialQuantity(),
                pos.currentQuantity(),
                pos.entryPrice(),
                1590.0, // Trailing stop moved up
                pos.target1Price(),
                pos.firstCandleHigh(),
                pos.target1Booked(),
                1610.0, // Peak price reached
                pos.entryTime(),
                pos.restingSlOrderId()
        );
        positionManagerService.registerPosition(PortfolioMode.PAPER, ratcheted);

        // Run minute rollover flush
        executionService.flushMinuteRolloverTrailingStops(PortfolioMode.PAPER, today, LocalTime.of(10, 0));

        // Resting order in adapter must now have trigger price = 1590.0
        SimulatedOrderAdapter.RestingOrderState resting = simulatedOrderAdapter.getActiveRestingOrders().get(pos.restingSlOrderId());
        assertEquals(1590.0, resting.triggerPrice());
    }
}
