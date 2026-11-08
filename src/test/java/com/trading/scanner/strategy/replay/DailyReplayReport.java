package com.trading.scanner.strategy.replay;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Daily financial, execution, and signal audit summary for a single replay day.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyReplayReport {
    private String tradingDate;
    private String scenarioTag;
    private String scenarioDescription;
    private double startingCapital;
    private double endingCapital;
    private double grossPnl;
    private double totalCharges;
    private double netPnl;

    private int totalSignals;
    private int executedTrades;
    private int winTrades;
    private int lossTrades;

    @Builder.Default
    private List<ReplayTrade> trades = new ArrayList<>();

    @Builder.Default
    private List<RejectedSignalAudit> rejectedSignals = new ArrayList<>();

    private int macroFilterRejections;
    private int frictionRejections;
    private int riskCapRejections;
}
