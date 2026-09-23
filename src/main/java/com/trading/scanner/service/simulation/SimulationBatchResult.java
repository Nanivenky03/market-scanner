package com.trading.scanner.service.simulation;

import java.util.List;

/**
 * @deprecated Legacy simulation model.
 */
@Deprecated
public record SimulationBatchResult(
    int cyclesRequested,
    int cyclesCompleted,
    long totalDurationMs,
    List<SimulationCycleResult> cycleResults
) {}
