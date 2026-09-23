package com.trading.scanner.service.simulation;

import java.time.LocalDate;

/**
 * @deprecated Legacy simulation model.
 */
@Deprecated
public record SimulationCycleResult(
    int tradingOffset,
    LocalDate cycleDate,
    int stocksIngested,
    int signalsGenerated,
    long durationMs,
    boolean success,
    String failureReason
) {}
