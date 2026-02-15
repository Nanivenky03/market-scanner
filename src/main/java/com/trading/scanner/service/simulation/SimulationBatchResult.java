package com.trading.scanner.service.simulation;

import java.util.List;

public record SimulationBatchResult(
    int cyclesRequested,
    int cyclesCompleted,
    long totalDurationMs,
    List<SimulationCycleResult> cycleResults
) {}
