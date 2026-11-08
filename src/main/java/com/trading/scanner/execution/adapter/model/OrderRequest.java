package com.trading.scanner.execution.adapter.model;

import lombok.Builder;

import java.time.LocalDateTime;

@Builder(toBuilder = true)
public record OrderRequest(
        String symbol,
        String exchange,
        String transactionType,   // "BUY" or "SELL"
        String orderType,         // "MARKET", "LIMIT", "STOPLOSS_MARKET", "STOPLOSS_LIMIT"
        String productType,       // "INTRADAY" or "MIS"
        int quantity,
        Double price,             // Limit price (if applicable)
        Double triggerPrice,      // Stoploss trigger price (if applicable)
        Integer strategyId,
        Integer signalId,
        Integer parentOrderId,
        String clientTag,
        Double triggerTickPrice,  // Market LTP when order was triggered (for slippage tracking)
        LocalDateTime requestedAt
) {
    public static OrderRequest marketBuy(
            String symbol,
            String exchange,
            int quantity,
            Double currentTickPrice,
            Integer strategyId,
            Integer signalId
    ) {
        return OrderRequest.builder()
                .symbol(symbol)
                .exchange(exchange != null ? exchange : "NSE")
                .transactionType("BUY")
                .orderType("MARKET")
                .productType("INTRADAY")
                .quantity(quantity)
                .price(currentTickPrice)
                .triggerPrice(null)
                .strategyId(strategyId)
                .signalId(signalId)
                .triggerTickPrice(currentTickPrice)
                .requestedAt(LocalDateTime.now())
                .build();
    }

    public static OrderRequest restingStopLoss(
            String symbol,
            String exchange,
            int quantity,
            double triggerPrice,
            Integer strategyId,
            Integer signalId,
            Integer parentOrderId
    ) {
        return OrderRequest.builder()
                .symbol(symbol)
                .exchange(exchange != null ? exchange : "NSE")
                .transactionType("SELL")
                .orderType("STOPLOSS_MARKET")
                .productType("INTRADAY")
                .quantity(quantity)
                .price(null)
                .triggerPrice(triggerPrice)
                .strategyId(strategyId)
                .signalId(signalId)
                .parentOrderId(parentOrderId)
                .requestedAt(LocalDateTime.now())
                .build();
    }

    public static OrderRequest marketSell(
            String symbol,
            String exchange,
            int quantity,
            Double currentTickPrice,
            Integer strategyId,
            Integer signalId,
            Integer parentOrderId,
            String clientTag
    ) {
        return OrderRequest.builder()
                .symbol(symbol)
                .exchange(exchange != null ? exchange : "NSE")
                .transactionType("SELL")
                .orderType("MARKET")
                .productType("INTRADAY")
                .quantity(quantity)
                .price(currentTickPrice)
                .triggerPrice(null)
                .strategyId(strategyId)
                .signalId(signalId)
                .parentOrderId(parentOrderId)
                .clientTag(clientTag)
                .triggerTickPrice(currentTickPrice)
                .requestedAt(LocalDateTime.now())
                .build();
    }
}
