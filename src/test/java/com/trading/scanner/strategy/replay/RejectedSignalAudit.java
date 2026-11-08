package com.trading.scanner.strategy.replay;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

/**
 * Audit record of a strategy proposal or candle trigger that was rejected or ignored.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RejectedSignalAudit {
    private String tradingDate;
    private LocalTime time;
    private String symbol;
    private double price;
    private String rejectionStage; // STRATEGY_GATE or RISK_ENGINE
    private String reason;
    private String details;
}
