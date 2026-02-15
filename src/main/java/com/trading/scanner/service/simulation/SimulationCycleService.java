package com.trading.scanner.service.simulation;

import com.trading.scanner.config.ExchangeClock;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.ScanExecutionState;
import com.trading.scanner.model.ScanExecutionState.ExecutionMode;
import com.trading.scanner.model.SimulationState;
import com.trading.scanner.repository.SimulationStateRepository;
import com.trading.scanner.service.data.DataIngestionService;
import com.trading.scanner.service.scanner.ScannerEngine;
import com.trading.scanner.service.state.ExecutionStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class SimulationCycleService {

    private static final int MAX_CYCLING_LOCK_HOURS = 6;

    private final SimulationStateRepository simulationStateRepository;
    private final ExchangeClock exchangeClock;
    private final DataIngestionService dataIngestionService;
    private final ScannerEngine scannerEngine;
    private final ExecutionStateService executionStateService;
    private final TimeProvider timeProvider;

    /**
     * Executes a single, complete simulation cycle for one trading day.
     * This includes advancing the simulation date, running data ingestion, and executing the scanner.
     * <p>
     * <b>Atomicity Guarantees:</b>
     * This method is transactional. A failure at any step will roll back changes to
     * {@link SimulationState} and {@link ScanExecutionState} made within this cycle.
     * <p>
     * <b>Atomicity Exceptions (By Design):</b>
     * Per-stock data ingestion within {@link DataIngestionService} runs in its own
     * {@code REQUIRES_NEW} transaction. This means that if the cycle fails after some stocks have
     * been ingested, those stock prices WILL remain in the database. This is an intentional
     * design choice to allow for resumable/incremental ingestion.
     *
     * @return A {@link SimulationCycleResult} summarizing the outcome of the cycle.
     */
    // This transaction ensures that the advancement of SimulationState and the update of
    // ScanExecutionState are consistent. Ingestion commits are independent by design.
    @Transactional
    public SimulationCycleResult executeOneCycle() {
        long startTime = System.currentTimeMillis();
        String cycleId = UUID.randomUUID().toString().substring(0, 8);

        // 1. Advance State
        SimulationState state = simulationStateRepository.findById(1)
            .orElseThrow(() -> new IllegalStateException("Simulation state not initialized"));
        state.advanceDay();
        simulationStateRepository.save(state);

        // 2. Invalidate Clock Cache
        exchangeClock.invalidateSimulationCache();
        LocalDate cycleDate = exchangeClock.today();
        int tradingOffset = state.getTradingOffset();
        
        log.info("CYCLE_START cycleId={} offset={} date={}", cycleId, tradingOffset, cycleDate);

        // 3. Run Daily Ingestion
        dataIngestionService.ingestDailyData(ExecutionMode.MANUAL);
        ScanExecutionState cycleState = executionStateService.getTodayState();
        int ingestedCount = cycleState.getStocksIngested() != null ? cycleState.getStocksIngested() : 0;
        log.info("CYCLE_INGEST_COMPLETE cycleId={} offset={} ingested={}", cycleId, tradingOffset, ingestedCount);

        // 4. Run Daily Scan
        scannerEngine.executeDailyScan();
        cycleState = executionStateService.getTodayState(); // Re-fetch state after scan
        int signalsCount = cycleState.getSignalsGenerated() != null ? cycleState.getSignalsGenerated() : 0;
        log.info("CYCLE_SCAN_COMPLETE cycleId={} offset={} signals={}", cycleId, tradingOffset, signalsCount);
        
        long durationMs = System.currentTimeMillis() - startTime;
        log.info("CYCLE_END cycleId={} offset={} durationMs={}", cycleId, durationMs);
        
        return new SimulationCycleResult(tradingOffset, cycleDate, ingestedCount, signalsCount, durationMs, true, null);
    }

    public SimulationBatchResult executeCycles(int days) {
        long batchStartTime = System.currentTimeMillis();
        List<SimulationCycleResult> results = new ArrayList<>();
        
        // Set cycling flag to prevent concurrent runs
        SimulationState state = simulationStateRepository.findById(1)
            .orElseThrow(() -> new IllegalStateException("Simulation state not initialized"));
        
        if (state.isCycling()) {
            LocalDateTime cyclingStartedAt = state.getCyclingStartedAt();
            if (cyclingStartedAt != null &&
                Duration.between(cyclingStartedAt, timeProvider.nowDateTime()).toHours() > MAX_CYCLING_LOCK_HOURS) {
                log.warn("Stale simulation cycle lock detected ({} hours old). Clearing automatically.", MAX_CYCLING_LOCK_HOURS);
                // Self-heal by clearing the stale lock
            } else {
                throw new IllegalStateException("A multi-day simulation cycle is already in progress since " + state.getCyclingStartedAt());
            }
        }
        
        state.setCycling(true);
        state.setCyclingStartedAt(timeProvider.nowDateTime());
        simulationStateRepository.save(state);

        int completedCycles = 0;
        try {
            for (int i = 0; i < days; i++) {
                try {
                    SimulationCycleResult result = executeOneCycle();
                    results.add(result);
                    completedCycles++;
                } catch (Exception e) {
                    log.error("Simulation cycle failed on day {}/{}. Halting batch.", i + 1, days, e);
                    // Stop batch on first failure
                    break;
                }
            }
        } finally {
            // Always clear the cycling flag
            state = simulationStateRepository.findById(1)
                .orElseThrow(() -> new IllegalStateException("Simulation state not initialized"));
            state.setCycling(false);
            state.setCyclingStartedAt(null);
            simulationStateRepository.save(state);
        }
        
        long totalDurationMs = System.currentTimeMillis() - batchStartTime;
        return new SimulationBatchResult(days, completedCycles, totalDurationMs, results);
    }
}
