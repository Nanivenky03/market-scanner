package com.trading.scanner.execution.adapter.model;

public enum OrderStatusType {
    NEW,
    SUBMITTED,
    PENDING_TRIGGER,
    TRIGGERED,
    FILLED,
    CANCELLED,
    REJECTED,
    EXPIRED
}
