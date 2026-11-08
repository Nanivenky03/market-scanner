package com.trading.scanner.execution.adapter.model;

import lombok.Builder;

import java.time.LocalDateTime;

@Builder(toBuilder = true)
public record OrderResponse(
        boolean success,
        String brokerOrderId,
        Integer dbOrderId,
        OrderStatusType orderStatus,
        int filledQuantity,
        Double averageFillPrice,
        Double slippage,
        Double statutoryCharges,
        String message,
        LocalDateTime responseTime
) {
    public static OrderResponse filled(
            String brokerOrderId,
            Integer dbOrderId,
            int filledQuantity,
            double averageFillPrice,
            double slippage,
            double statutoryCharges
    ) {
        return OrderResponse.builder()
                .success(true)
                .brokerOrderId(brokerOrderId)
                .dbOrderId(dbOrderId)
                .orderStatus(OrderStatusType.FILLED)
                .filledQuantity(filledQuantity)
                .averageFillPrice(averageFillPrice)
                .slippage(slippage)
                .statutoryCharges(statutoryCharges)
                .message("Order filled successfully")
                .responseTime(LocalDateTime.now())
                .build();
    }

    public static OrderResponse placed(
            String brokerOrderId,
            Integer dbOrderId,
            OrderStatusType status,
            String message
    ) {
        return OrderResponse.builder()
                .success(true)
                .brokerOrderId(brokerOrderId)
                .dbOrderId(dbOrderId)
                .orderStatus(status)
                .filledQuantity(0)
                .averageFillPrice(null)
                .slippage(0.0)
                .statutoryCharges(0.0)
                .message(message)
                .responseTime(LocalDateTime.now())
                .build();
    }

    public static OrderResponse modified(
            String brokerOrderId,
            Integer dbOrderId,
            String message
    ) {
        return OrderResponse.builder()
                .success(true)
                .brokerOrderId(brokerOrderId)
                .dbOrderId(dbOrderId)
                .orderStatus(OrderStatusType.SUBMITTED)
                .message(message)
                .responseTime(LocalDateTime.now())
                .build();
    }

    public static OrderResponse cancelled(String brokerOrderId, Integer dbOrderId) {
        return OrderResponse.builder()
                .success(true)
                .brokerOrderId(brokerOrderId)
                .dbOrderId(dbOrderId)
                .orderStatus(OrderStatusType.CANCELLED)
                .message("Order cancelled successfully")
                .responseTime(LocalDateTime.now())
                .build();
    }

    public static OrderResponse rejected(String reason) {
        return OrderResponse.builder()
                .success(false)
                .brokerOrderId(null)
                .dbOrderId(null)
                .orderStatus(OrderStatusType.REJECTED)
                .filledQuantity(0)
                .averageFillPrice(null)
                .slippage(0.0)
                .statutoryCharges(0.0)
                .message(reason)
                .responseTime(LocalDateTime.now())
                .build();
    }
}
