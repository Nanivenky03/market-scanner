package com.trading.scanner.strategy.replay;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Audit record of an executed trade during a historical replay session.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReplayTrade {
    private String tradeId;
    private String symbol;
    private String direction;
    private LocalDateTime entryTime;
    private double entryPrice;
    private int quantity;
    private double initialStopLoss;
    private double target1Price;

    private boolean target1Hit;
    private Double target1ExitPrice;
    private LocalDateTime target1ExitTime;
    private int target1Quantity;

    private Double finalExitPrice;
    private LocalDateTime finalExitTime;
    private int finalExitQuantity;
    private String exitReason;

    private double grossPnl;
    private double charges;
    private double netPnl;

    public boolean isProfitable() {
        return netPnl > 0.0;
    }
}
