package com.trading.scanner.execution.adapter;

import com.trading.scanner.execution.adapter.model.OrderModifyRequest;
import com.trading.scanner.execution.adapter.model.OrderRequest;
import com.trading.scanner.execution.adapter.model.OrderResponse;
import com.trading.scanner.risk.model.PortfolioMode;

import java.util.Optional;

/**
 * Broker-agnostic abstraction for order lifecycle operations.
 * Decouples order creation, modification, cancellation, and execution status
 * from concrete broker APIs (e.g. Angel One SmartAPI, Kotak Neo, Shoonya)
 * and in-memory simulation adapters.
 */
public interface BrokerOrderAdapter {

    /**
     * Identifies whether this adapter services PAPER simulation or LIVE trading.
     */
    PortfolioMode getSupportedMode();

    /**
     * Submits an order (Market, Limit, or Resting Stop-Loss Market).
     */
    OrderResponse placeOrder(OrderRequest request);

    /**
     * Modifies an existing order (e.g. price/qty updates, trailing stop ratchet,
     * or Modify-to-Market execution).
     */
    OrderResponse modifyOrder(OrderModifyRequest request);

    /**
     * Cancels an existing resting order.
     */
    OrderResponse cancelOrder(String brokerOrderId, String symbol);

    /**
     * Queries order status by broker order ID.
     */
    Optional<OrderResponse> getOrderStatus(String brokerOrderId);
}
