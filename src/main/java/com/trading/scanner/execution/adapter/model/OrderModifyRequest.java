package com.trading.scanner.execution.adapter.model;

import lombok.Builder;

import java.time.LocalDateTime;

@Builder(toBuilder = true)
public record OrderModifyRequest(
        String brokerOrderId,
        String symbol,
        Integer newQuantity,
        Double newPrice,
        Double newTriggerPrice,
        String newOrderType,     // e.g. "MARKET" for Modify-to-Market
        Double currentTickPrice, // Current market price at modification time
        String clientTag,
        LocalDateTime modifiedAt
) {
    public static OrderModifyRequest reduceQuantityAndMoveStop(
            String brokerOrderId,
            String symbol,
            int newQuantity,
            double newTriggerPrice
    ) {
        return OrderModifyRequest.builder()
                .brokerOrderId(brokerOrderId)
                .symbol(symbol)
                .newQuantity(newQuantity)
                .newTriggerPrice(newTriggerPrice)
                .modifiedAt(LocalDateTime.now())
                .build();
    }

    public static OrderModifyRequest updateTrailingStop(
            String brokerOrderId,
            String symbol,
            double newTriggerPrice
    ) {
        return OrderModifyRequest.builder()
                .brokerOrderId(brokerOrderId)
                .symbol(symbol)
                .newTriggerPrice(newTriggerPrice)
                .modifiedAt(LocalDateTime.now())
                .build();
    }

    public static OrderModifyRequest modifyToMarket(
            String brokerOrderId,
            String symbol,
            Double currentTickPrice,
            String reason
    ) {
        return OrderModifyRequest.builder()
                .brokerOrderId(brokerOrderId)
                .symbol(symbol)
                .newOrderType("MARKET")
                .newPrice(currentTickPrice)
                .currentTickPrice(currentTickPrice)
                .clientTag(reason)
                .modifiedAt(LocalDateTime.now())
                .build();
    }
}
