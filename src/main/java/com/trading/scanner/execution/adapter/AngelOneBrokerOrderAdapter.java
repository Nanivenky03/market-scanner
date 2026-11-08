package com.trading.scanner.execution.adapter;

import com.trading.scanner.execution.adapter.model.OrderModifyRequest;
import com.trading.scanner.execution.adapter.model.OrderRequest;
import com.trading.scanner.execution.adapter.model.OrderResponse;
import com.trading.scanner.risk.model.PortfolioMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Live Broker Order Adapter for Angel One SmartAPI.
 * Retained as a disabled placeholder in V2.4. Real capital execution
 * will be activated in V5.0/V6.0 under strict dry-run guards.
 */
@Component
@Slf4j
public class AngelOneBrokerOrderAdapter implements BrokerOrderAdapter {

    @Override
    public PortfolioMode getSupportedMode() {
        return PortfolioMode.LIVE;
    }

    @Override
    public OrderResponse placeOrder(OrderRequest request) {
        log.warn("[SAFETY GUARD] Live broker order placement rejected. Live execution adapter disabled in V2.4 simulation engine.");
        return OrderResponse.rejected("Live trading disabled in V2.4 simulation order engine");
    }

    @Override
    public OrderResponse modifyOrder(OrderModifyRequest request) {
        log.warn("[SAFETY GUARD] Live broker order modification rejected. Live execution adapter disabled in V2.4 simulation engine.");
        return OrderResponse.rejected("Live trading disabled in V2.4 simulation order engine");
    }

    @Override
    public OrderResponse cancelOrder(String brokerOrderId, String symbol) {
        log.warn("[SAFETY GUARD] Live broker order cancellation rejected. Live execution adapter disabled in V2.4 simulation engine.");
        return OrderResponse.rejected("Live trading disabled in V2.4 simulation order engine");
    }

    @Override
    public Optional<OrderResponse> getOrderStatus(String brokerOrderId) {
        return Optional.empty();
    }
}
