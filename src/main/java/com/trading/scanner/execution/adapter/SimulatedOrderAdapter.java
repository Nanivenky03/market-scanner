package com.trading.scanner.execution.adapter;

import com.trading.scanner.execution.adapter.model.OrderModifyRequest;
import com.trading.scanner.execution.adapter.model.OrderRequest;
import com.trading.scanner.execution.adapter.model.OrderResponse;
import com.trading.scanner.execution.adapter.model.OrderStatusType;
import com.trading.scanner.model.SimulatedOrder;
import com.trading.scanner.repository.SimulatedOrderRepository;
import com.trading.scanner.risk.BrokerChargesCalculator;
import com.trading.scanner.risk.model.PortfolioMode;
import com.trading.scanner.util.PriceTickUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory simulated broker adapter for PAPER trading mode.
 * Replicates live broker order management, including:
 * - Market orders with 1-tick slippage and Kotak Neo statutory charges.
 * - Resting Stop-Loss Market (SL-M) orders.
 * - Atomic Modify-to-Market transformations (preventing reverse position race conditions).
 * - Immediate persistence into the simulated_orders database table.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SimulatedOrderAdapter implements BrokerOrderAdapter {

    private final SimulatedOrderRepository simulatedOrderRepository;
    private final AtomicLong orderSequence = new AtomicLong(0);

    public record RestingOrderState(
            String brokerOrderId,
            Integer dbOrderId,
            String symbol,
            String exchange,
            String transactionType,
            int quantity,
            double triggerPrice,
            Integer strategyId,
            Integer signalId,
            Integer parentOrderId,
            LocalDateTime placedAt
    ) {}

    private final Map<String, RestingOrderState> restingOrders = new ConcurrentHashMap<>();

    @Override
    public PortfolioMode getSupportedMode() {
        return PortfolioMode.PAPER;
    }

    @Override
    public OrderResponse placeOrder(OrderRequest request) {
        if (request == null || request.symbol() == null) {
            return OrderResponse.rejected("Invalid order request: null proposal or symbol");
        }

        String brokerOrderId = generateBrokerOrderId();
        String orderType = request.orderType() != null ? request.orderType().toUpperCase(Locale.ROOT) : "MARKET";

        if ("STOPLOSS_MARKET".equals(orderType) || "SL-M".equals(orderType)) {
            return placeRestingStopLossOrder(request, brokerOrderId);
        } else {
            return placeMarketOrder(request, brokerOrderId);
        }
    }

    private OrderResponse placeMarketOrder(OrderRequest request, String brokerOrderId) {
        boolean isBuy = "BUY".equalsIgnoreCase(request.transactionType());
        double tickPrice = request.triggerTickPrice() != null ? request.triggerTickPrice()
                : (request.price() != null ? request.price() : 100.0);

        // 1-tick realistic slippage (0.05 on NSE)
        double slippage = 0.05;
        double fillPrice = isBuy
                ? PriceTickUtil.roundTarget(tickPrice + slippage, false, 0.05)
                : PriceTickUtil.roundStopLoss(tickPrice - slippage, false, 0.05);

        double turnover = fillPrice * request.quantity();
        double charges = isBuy
                ? BrokerChargesCalculator.calculateBuyLegCharges(turnover)
                : BrokerChargesCalculator.calculateSellLegCharges(turnover);

        double slippagePerShare = slippage;
        double totalSlippage = slippagePerShare * request.quantity();
        double riskPerShare = (request.price() != null && request.triggerPrice() != null)
                ? Math.abs(request.price() - request.triggerPrice()) : 0.0;
        double riskBudgetAllocated = riskPerShare * request.quantity();
        String orderTag = request.clientTag() != null ? request.clientTag()
                : (isBuy ? "ENTRY_MARKET_BUY" : "EXIT_MARKET_SELL");

        SimulatedOrder dbOrder = SimulatedOrder.builder()
                .signalId(request.signalId())
                .parentOrderId(request.parentOrderId())
                .strategyId(request.strategyId() != null ? request.strategyId() : 2)
                .symbol(request.symbol())
                .exchange(request.exchange() != null ? request.exchange() : "NSE")
                .transactionType(request.transactionType())
                .orderType("MARKET")
                .productType(request.productType() != null ? request.productType() : "INTRADAY")
                .price(request.price())
                .quantity(request.quantity())
                .averageFillPrice(fillPrice)
                .filledQuantity(request.quantity())
                .triggerTickPrice(tickPrice)
                .statutoryCharges(charges)
                .slippagePerShare(slippagePerShare)
                .totalSlippage(totalSlippage)
                .riskPerShare(riskPerShare)
                .riskBudgetAllocated(riskBudgetAllocated)
                .orderTag(orderTag)
                .executionLatencyMs(15) // Simulated exchange turnaround
                .orderStatus("FILLED")
                .brokerOrderId(brokerOrderId)
                .uniqueOrderId(brokerOrderId)
                .placedAt(LocalDateTime.now().toString())
                .filledAt(LocalDateTime.now().toString())
                .build();

        SimulatedOrder saved = simulatedOrderRepository.save(dbOrder);
        log.info("[PAPER] Filled MARKET {} order for symbol={} qty={} fillPrice={} charges={} orderId={}",
                request.transactionType(), request.symbol(), request.quantity(), fillPrice, charges, brokerOrderId);

        return OrderResponse.filled(
                brokerOrderId,
                saved.getId(),
                request.quantity(),
                fillPrice,
                slippage,
                charges
        );
    }

    private OrderResponse placeRestingStopLossOrder(OrderRequest request, String brokerOrderId) {
        double triggerPrice = request.triggerPrice() != null ? request.triggerPrice() : 0.0;
        double riskPerShare = request.price() != null ? Math.abs(request.price() - triggerPrice) : 0.0;
        double riskBudgetAllocated = riskPerShare * request.quantity();
        String orderTag = request.clientTag() != null ? request.clientTag() : "RESTING_SL_M";

        SimulatedOrder dbOrder = SimulatedOrder.builder()
                .signalId(request.signalId())
                .parentOrderId(request.parentOrderId())
                .strategyId(request.strategyId() != null ? request.strategyId() : 2)
                .symbol(request.symbol())
                .exchange(request.exchange() != null ? request.exchange() : "NSE")
                .transactionType(request.transactionType())
                .orderType("STOPLOSS_MARKET")
                .productType(request.productType() != null ? request.productType() : "INTRADAY")
                .triggerPrice(triggerPrice)
                .quantity(request.quantity())
                .filledQuantity(0)
                .riskPerShare(riskPerShare)
                .riskBudgetAllocated(riskBudgetAllocated)
                .orderTag(orderTag)
                .orderStatus("PENDING_TRIGGER")
                .brokerOrderId(brokerOrderId)
                .uniqueOrderId(brokerOrderId)
                .placedAt(LocalDateTime.now().toString())
                .build();

        SimulatedOrder saved = simulatedOrderRepository.save(dbOrder);

        RestingOrderState restingState = new RestingOrderState(
                brokerOrderId,
                saved.getId(),
                request.symbol(),
                request.exchange(),
                request.transactionType(),
                request.quantity(),
                triggerPrice,
                request.strategyId(),
                request.signalId(),
                request.parentOrderId(),
                LocalDateTime.now()
        );
        restingOrders.put(brokerOrderId, restingState);

        log.info("[PAPER] Placed resting SL-M order for symbol={} qty={} triggerPrice={} orderId={}",
                request.symbol(), request.quantity(), triggerPrice, brokerOrderId);

        return OrderResponse.placed(
                brokerOrderId,
                saved.getId(),
                OrderStatusType.PENDING_TRIGGER,
                "Resting SL-M order registered"
        );
    }

    @Override
    public OrderResponse modifyOrder(OrderModifyRequest request) {
        if (request == null || request.brokerOrderId() == null) {
            return OrderResponse.rejected("Invalid modify request: missing order ID");
        }

        RestingOrderState existing = restingOrders.get(request.brokerOrderId());
        if (existing == null) {
            return OrderResponse.rejected("Order " + request.brokerOrderId() + " not found or already terminal");
        }

        // Pattern A: Modify-to-Market Transformation
        if ("MARKET".equalsIgnoreCase(request.newOrderType())) {
            return executeModifyToMarket(existing, request);
        }

        // Price or Quantity update
        int updatedQty = request.newQuantity() != null ? request.newQuantity() : existing.quantity();
        double updatedTrigger = request.newTriggerPrice() != null ? request.newTriggerPrice() : existing.triggerPrice();

        RestingOrderState updatedState = new RestingOrderState(
                existing.brokerOrderId(),
                existing.dbOrderId(),
                existing.symbol(),
                existing.exchange(),
                existing.transactionType(),
                updatedQty,
                updatedTrigger,
                existing.strategyId(),
                existing.signalId(),
                existing.parentOrderId(),
                existing.placedAt()
        );
        restingOrders.put(existing.brokerOrderId(), updatedState);

        if (existing.dbOrderId() != null) {
            simulatedOrderRepository.findById(existing.dbOrderId()).ifPresent(order -> {
                order.setQuantity(updatedQty);
                order.setTriggerPrice(updatedTrigger);
                order.setUpdatedAt(LocalDateTime.now().toString());
                simulatedOrderRepository.save(order);
            });
        }

        log.info("[PAPER] Modified resting SL-M orderId={} symbol={} newQty={} newTrigger={}",
                existing.brokerOrderId(), existing.symbol(), updatedQty, updatedTrigger);

        return OrderResponse.modified(existing.brokerOrderId(), existing.dbOrderId(), "Resting order modified");
    }

    private OrderResponse executeModifyToMarket(RestingOrderState existing, OrderModifyRequest request) {
        restingOrders.remove(existing.brokerOrderId());

        double tickPrice = request.currentTickPrice() != null ? request.currentTickPrice() : existing.triggerPrice();
        double slippage = 0.05;
        double fillPrice = PriceTickUtil.roundStopLoss(tickPrice - slippage, false, 0.05);

        double turnover = fillPrice * existing.quantity();
        double charges = BrokerChargesCalculator.calculateSellLegCharges(turnover);

        if (existing.dbOrderId() != null) {
            double slippagePerShare = slippage;
            double totalSlippage = slippagePerShare * existing.quantity();
            String orderTag = request.clientTag() != null ? request.clientTag() : "MODIFY_TO_MARKET_FILL";

            simulatedOrderRepository.findById(existing.dbOrderId()).ifPresent(order -> {
                order.setOrderType("MARKET");
                order.setAverageFillPrice(fillPrice);
                order.setFilledQuantity(existing.quantity());
                order.setTriggerTickPrice(tickPrice);
                order.setStatutoryCharges(charges);
                order.setSlippagePerShare(slippagePerShare);
                order.setTotalSlippage(totalSlippage);
                order.setOrderTag(orderTag);
                order.setOrderStatus("FILLED");
                order.setFilledAt(LocalDateTime.now().toString());
                order.setUpdatedAt(LocalDateTime.now().toString());
                simulatedOrderRepository.save(order);
            });
        }

        log.info("[PAPER] Converted resting SL-M to MARKET fill for symbol={} qty={} fillPrice={} orderId={}",
                existing.symbol(), existing.quantity(), fillPrice, existing.brokerOrderId());

        return OrderResponse.filled(
                existing.brokerOrderId(),
                existing.dbOrderId(),
                existing.quantity(),
                fillPrice,
                slippage,
                charges
        );
    }

    @Override
    public OrderResponse cancelOrder(String brokerOrderId, String symbol) {
        RestingOrderState existing = restingOrders.remove(brokerOrderId);
        if (existing == null) {
            return OrderResponse.rejected("Order " + brokerOrderId + " not found or already terminal");
        }

        if (existing.dbOrderId() != null) {
            simulatedOrderRepository.findById(existing.dbOrderId()).ifPresent(order -> {
                order.setOrderStatus("CANCELLED");
                order.setCancelledAt(LocalDateTime.now().toString());
                order.setUpdatedAt(LocalDateTime.now().toString());
                simulatedOrderRepository.save(order);
            });
        }

        log.info("[PAPER] Cancelled resting orderId={} for symbol={}", brokerOrderId, symbol);
        return OrderResponse.cancelled(brokerOrderId, existing.dbOrderId());
    }

    @Override
    public Optional<OrderResponse> getOrderStatus(String brokerOrderId) {
        RestingOrderState resting = restingOrders.get(brokerOrderId);
        if (resting != null) {
            return Optional.of(OrderResponse.placed(
                    resting.brokerOrderId(),
                    resting.dbOrderId(),
                    OrderStatusType.PENDING_TRIGGER,
                    "Resting SL-M order pending"
            ));
        }

        return simulatedOrderRepository.findByBrokerOrderId(brokerOrderId).map(db -> {
            OrderStatusType type = "FILLED".equalsIgnoreCase(db.getOrderStatus()) ? OrderStatusType.FILLED
                    : ("CANCELLED".equalsIgnoreCase(db.getOrderStatus()) ? OrderStatusType.CANCELLED
                    : OrderStatusType.SUBMITTED);
            return OrderResponse.builder()
                    .success(true)
                    .brokerOrderId(db.getBrokerOrderId())
                    .dbOrderId(db.getId())
                    .orderStatus(type)
                    .filledQuantity(db.getFilledQuantity() != null ? db.getFilledQuantity() : 0)
                    .averageFillPrice(db.getAverageFillPrice())
                    .statutoryCharges(db.getStatutoryCharges() != null ? db.getStatutoryCharges() : 0.0)
                    .message("Order status: " + db.getOrderStatus())
                    .responseTime(LocalDateTime.now())
                    .build();
        });
    }

    /**
     * Checks all active resting stop-loss orders for a symbol against incoming tick LTP.
     * If trigger price is breached (LTP <= triggerPrice for Long SL), converts to filled market order.
     */
    public List<OrderResponse> checkRestingTriggers(String symbol, double ltp) {
        if (symbol == null || ltp <= 0.0) {
            return Collections.emptyList();
        }

        List<OrderResponse> triggeredFills = new ArrayList<>();
        for (Map.Entry<String, RestingOrderState> entry : restingOrders.entrySet()) {
            RestingOrderState state = entry.getValue();
            if (symbol.equalsIgnoreCase(state.symbol())) {
                // For long position stoploss: trigger when market price falls to or below trigger price
                if (ltp <= state.triggerPrice()) {
                    OrderModifyRequest modifyReq = OrderModifyRequest.modifyToMarket(
                            state.brokerOrderId(),
                            symbol,
                            ltp,
                            "STOP_LOSS_TRIGGERED"
                    );
                    OrderResponse fill = executeModifyToMarket(state, modifyReq);
                    triggeredFills.add(fill);
                }
            }
        }
        return triggeredFills;
    }

    public Map<String, RestingOrderState> getActiveRestingOrders() {
        return Collections.unmodifiableMap(restingOrders);
    }

    public void clearAllRestingOrders() {
        restingOrders.clear();
        log.info("[PAPER] Cleared all resting simulated orders");
    }

    /**
     * Rehydrates pending resting SL-M orders from the database into in-memory restingOrders map.
     * Essential for zero-state loss on mid-day crash recovery.
     */
    public int rehydratePendingOrdersFromDb() {
        List<SimulatedOrder> pendingOrders = simulatedOrderRepository.findByOrderStatus("PENDING_TRIGGER");
        int count = 0;
        for (SimulatedOrder order : pendingOrders) {
            if (order.getBrokerOrderId() != null && !restingOrders.containsKey(order.getBrokerOrderId())) {
                LocalDateTime placedAt = null;
                if (order.getPlacedAt() != null) {
                    try {
                        placedAt = LocalDateTime.parse(order.getPlacedAt());
                    } catch (Exception ignored) {
                        placedAt = LocalDateTime.now();
                    }
                }
                RestingOrderState state = new RestingOrderState(
                        order.getBrokerOrderId(),
                        order.getId(),
                        order.getSymbol(),
                        order.getExchange(),
                        order.getTransactionType(),
                        order.getQuantity() != null ? order.getQuantity() : 0,
                        order.getTriggerPrice() != null ? order.getTriggerPrice() : 0.0,
                        order.getStrategyId(),
                        order.getSignalId(),
                        order.getParentOrderId(),
                        placedAt != null ? placedAt : LocalDateTime.now()
                );
                restingOrders.put(order.getBrokerOrderId(), state);
                count++;
            }
        }
        log.info("[PAPER] Rehydrated {} pending resting orders from database into memory", count);
        return count;
    }

    private String generateBrokerOrderId() {
        return "SIM_ORD_" + System.currentTimeMillis() + "_" + orderSequence.incrementAndGet();
    }
}
