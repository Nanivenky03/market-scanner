package com.trading.scanner.service.simulation;

import java.time.LocalDate;

public record SimulationCycleResult(
    int tradingOffset,
    LocalDate cycleDate,
    int stocksIngested,
    int signalsGenerated,
    long durationMs,
    boolean success,
    String failureReason
) {}
