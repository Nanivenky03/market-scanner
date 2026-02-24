package com.trading.scanner.service.simulation;

import com.trading.scanner.calendar.TradingCalendar;
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
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
@RequiredArgsConstructor
public class SimulationCycleService {

    private static final int MAX_SAFE_RANGE = 2000;
    private final Object advanceLock = new Object();

    private final SimulationStateRepository simulationStateRepository;
    private final DataIngestionService dataIngestionService;
    private final ScannerEngine scannerEngine;
    private final ExecutionStateService executionStateService;
    private final TradingCalendar tradingCalendar;

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public SimulationBatchResult advanceSimulation(int days) {
        synchronized (advanceLock) {
            validateRequest(days);
            if (days == 0) {
                return new SimulationBatchResult(0, 0, 0, Collections.emptyList());
            }

            SimulationState state = simulationStateRepository.findById(1)
                .orElseThrow(() -> new IllegalStateException("Simulation state not initialized"));

            validateState(state);

            List<SimulationCycleResult> results = new ArrayList<>();
            LocalDate lastDate = tradingCalendar.addTradingDays(state.getBaseDate(), state.getTradingOffset());

            for (int i = 0; i < days; i++) {
                LocalDate nextDate = tradingCalendar.nextTradingDay(lastDate);
                SimulationCycleResult result = runSingleCycleInMemory(nextDate, state.getTradingOffset() + i + 1);

                if (!result.success()) {
                    throw new IllegalStateException(
                        "Cycle failed for date " + nextDate + ". Rolling back entire batch."
                    );
                }
                results.add(result);
                lastDate = nextDate;
            }

            state.setTradingOffset(state.getTradingOffset() + days);
            simulationStateRepository.save(state);

            return buildBatchResult(days, results);
        }
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void resetSimulation() {
        synchronized (advanceLock) {
            SimulationState state = simulationStateRepository.findById(1)
                .orElseThrow(() -> new IllegalStateException("Simulation state not initialized"));

            state.setTradingOffset(0);
            simulationStateRepository.save(state);
            log.warn("SIMULATION RESET to base date: {}", state.getBaseDate());
        }
    }

    private SimulationCycleResult runSingleCycleInMemory(LocalDate cycleDate, int targetOffset) {
        long startTime = System.currentTimeMillis();
        String cycleId = UUID.randomUUID().toString().substring(0, 8);
        log.info("CYCLE_START cycleId={} offset={} date={}", cycleId, targetOffset, cycleDate);

        dataIngestionService.ingestSimulatedDailyData(cycleDate, ExecutionMode.MANUAL);
        ScanExecutionState cycleState = executionStateService.getOrCreateState(cycleDate);
        int ingestedCount = cycleState.getStocksIngested() != null ? cycleState.getStocksIngested() : 0;
        log.info("CYCLE_INGEST_COMPLETE cycleId={} offset={} ingested={}", cycleId, targetOffset, ingestedCount);

        scannerEngine.executeScanForDate(cycleDate);
        cycleState = executionStateService.getOrCreateState(cycleDate); // Re-fetch state after scan
        int signalsCount = cycleState.getSignalsGenerated() != null ? cycleState.getSignalsGenerated() : 0;
        log.info("CYCLE_SCAN_COMPLETE cycleId={} offset={} signals={}", cycleId, targetOffset, signalsCount);

        long durationMs = System.currentTimeMillis() - startTime;
        log.info("CYCLE_END cycleId={} offset={} durationMs={}", cycleId, durationMs);

        return new SimulationCycleResult(targetOffset, cycleDate, ingestedCount, signalsCount, durationMs, true, null);
    }

    private void validateRequest(int days) {
        if (days < 0) {
            throw new IllegalArgumentException("Days must not be negative.");
        }
        if (days > MAX_SAFE_RANGE) {
            throw new IllegalArgumentException("Requested days " + days + " exceeds max safe range of " + MAX_SAFE_RANGE);
        }
    }

    private void validateState(SimulationState state) {
        // The isCycling flag is removed, but we could add other state validations here if needed.
    }

    private SimulationBatchResult buildBatchResult(int daysRequested, List<SimulationCycleResult> results) {
        long totalDurationMs = results.stream().mapToLong(SimulationCycleResult::durationMs).sum();
        return new SimulationBatchResult(daysRequested, results.size(), totalDurationMs, results);
    }
}


