package com.trading.scanner.execution.adapter;

import com.trading.scanner.execution.adapter.model.*;
import com.trading.scanner.model.SimulatedOrder;
import com.trading.scanner.repository.SimulatedOrderRepository;
import com.trading.scanner.risk.model.PortfolioMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimulatedOrderAdapterTest {

    @Mock
    private SimulatedOrderRepository simulatedOrderRepository;

    private SimulatedOrderAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new SimulatedOrderAdapter(simulatedOrderRepository);
        lenient().when(simulatedOrderRepository.save(any(SimulatedOrder.class))).thenAnswer(invocation -> {
            SimulatedOrder order = invocation.getArgument(0);
            if (order.getId() == null) {
                order.setId(101);
            }
            return order;
        });
    }

    @Test
    @DisplayName("Identifies PAPER portfolio mode")
    void getSupportedMode_shouldReturnPaper() {
        assertEquals(PortfolioMode.PAPER, adapter.getSupportedMode());
    }

    @Test
    @DisplayName("Places MARKET buy order with 1-tick slippage and Kotak Neo statutory charges")
    void placeOrder_marketBuy_shouldFillWithSlippageAndCharges() {
        OrderRequest req = OrderRequest.marketBuy("INFY", "NSE", 100, 1500.0, 2, 1);

        OrderResponse resp = adapter.placeOrder(req);

        assertTrue(resp.success());
        assertEquals(OrderStatusType.FILLED, resp.orderStatus());
        assertEquals(100, resp.filledQuantity());
        // Buy slippage (+0.05) -> 1500.05
        assertEquals(1500.05, resp.averageFillPrice());
        assertEquals(0.05, resp.slippage());
        assertNotNull(resp.statutoryCharges());
        assertTrue(resp.statutoryCharges() > 0.0);
        assertNotNull(resp.brokerOrderId());
        assertTrue(resp.brokerOrderId().startsWith("SIM_ORD_"));
    }

    @Test
    @DisplayName("Places resting STOPLOSS_MARKET order and stores in resting order map")
    void placeOrder_restingStopLoss_shouldStoreInPendingState() {
        OrderRequest slReq = OrderRequest.restingStopLoss("INFY", "NSE", 100, 1485.0, 2, 1, 101);

        OrderResponse resp = adapter.placeOrder(slReq);

        assertTrue(resp.success());
        assertEquals(OrderStatusType.PENDING_TRIGGER, resp.orderStatus());
        assertEquals(0, resp.filledQuantity());

        assertTrue(adapter.getActiveRestingOrders().containsKey(resp.brokerOrderId()));
        SimulatedOrderAdapter.RestingOrderState resting = adapter.getActiveRestingOrders().get(resp.brokerOrderId());
        assertEquals("INFY", resting.symbol());
        assertEquals(1485.0, resting.triggerPrice());
        assertEquals(100, resting.quantity());
    }

    @Test
    @DisplayName("Modifies resting order trigger price and quantity")
    void modifyOrder_priceAndQuantity_shouldUpdateRestingState() {
        OrderRequest slReq = OrderRequest.restingStopLoss("TCS", "NSE", 50, 3400.0, 2, 1, 102);
        OrderResponse placed = adapter.placeOrder(slReq);
        String orderId = placed.brokerOrderId();

        OrderModifyRequest modifyReq = OrderModifyRequest.reduceQuantityAndMoveStop(orderId, "TCS", 25, 3450.0);
        OrderResponse modResp = adapter.modifyOrder(modifyReq);

        assertTrue(modResp.success());
        assertEquals(OrderStatusType.SUBMITTED, modResp.orderStatus());

        SimulatedOrderAdapter.RestingOrderState updated = adapter.getActiveRestingOrders().get(orderId);
        assertEquals(25, updated.quantity());
        assertEquals(3450.0, updated.triggerPrice());
    }

    @Test
    @DisplayName("Executes atomic Modify-to-Market transformation, removing resting order and filling at market")
    void modifyOrder_modifyToMarket_shouldExecuteImmediateFill() {
        OrderRequest slReq = OrderRequest.restingStopLoss("RELIANCE", "NSE", 40, 2450.0, 2, 1, 103);
        OrderResponse placed = adapter.placeOrder(slReq);
        String orderId = placed.brokerOrderId();

        OrderModifyRequest toMarket = OrderModifyRequest.modifyToMarket(orderId, "RELIANCE", 2480.0, "WIND_DOWN_1445");
        OrderResponse filledResp = adapter.modifyOrder(toMarket);

        assertTrue(filledResp.success());
        assertEquals(OrderStatusType.FILLED, filledResp.orderStatus());
        assertEquals(40, filledResp.filledQuantity());
        // Sell slippage (-0.05) -> 2479.95
        assertEquals(2479.95, filledResp.averageFillPrice());
        assertNotNull(filledResp.statutoryCharges());

        // Resting order must be completely removed to prevent reverse position double-fill
        assertFalse(adapter.getActiveRestingOrders().containsKey(orderId));
    }

    @Test
    @DisplayName("checkRestingTriggers automatically fills when incoming LTP breaches trigger price")
    void checkRestingTriggers_whenPriceBreachesTrigger_shouldConvertAndFill() {
        OrderRequest slReq = OrderRequest.restingStopLoss("HDFCBANK", "NSE", 80, 1600.0, 2, 1, 104);
        OrderResponse placed = adapter.placeOrder(slReq);
        String orderId = placed.brokerOrderId();

        // 1. Tick at 1605 (above SL) -> should not trigger
        List<OrderResponse> noTriggers = adapter.checkRestingTriggers("HDFCBANK", 1605.0);
        assertTrue(noTriggers.isEmpty());
        assertTrue(adapter.getActiveRestingOrders().containsKey(orderId));

        // 2. Tick at 1599.50 (drops below SL 1600) -> must trigger
        List<OrderResponse> triggered = adapter.checkRestingTriggers("HDFCBANK", 1599.50);
        assertEquals(1, triggered.size());
        assertEquals(OrderStatusType.FILLED, triggered.get(0).orderStatus());
        assertEquals(80, triggered.get(0).filledQuantity());

        // Order removed from resting map
        assertFalse(adapter.getActiveRestingOrders().containsKey(orderId));
    }
}
