package com.trading.scanner.service.state;

import com.trading.scanner.config.ExchangeConfiguration;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.ScanExecutionState;
import com.trading.scanner.model.ScanExecutionState.DataSourceStatus;
import com.trading.scanner.model.ScanExecutionState.ExecutionMode;
import com.trading.scanner.model.ScanExecutionState.ExecutionStatus;
import com.trading.scanner.repository.ScanExecutionStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionStateService {

    private final ScanExecutionStateRepository stateRepository;
    private final ExchangeConfiguration config;
    private final TimeProvider timeProvider;

    public ScanExecutionState getOrCreateState(LocalDate tradingDate) {
        return stateRepository.findByTradingDate(tradingDate)
            .orElseGet(() -> {
                ScanExecutionState state = ScanExecutionState.builder()
                    .tradingDate(tradingDate)
                    .ingestionStatus(ExecutionStatus.PENDING)
                    .scanStatus(ExecutionStatus.PENDING)
                    .dataSourceStatus(DataSourceStatus.UNKNOWN)
                    .build();
                return stateRepository.save(state);
            });
    }

    public boolean canIngestForDate(LocalDate tradingDate) {
        ScanExecutionState state = getOrCreateState(tradingDate);
        return state.getIngestionStatus() == ExecutionStatus.PENDING ||
            state.getIngestionStatus() == ExecutionStatus.FAILED;
    }

    public void startIngestionForDate(LocalDate tradingDate, ExecutionMode mode) {
        ScanExecutionState state = getOrCreateState(tradingDate);
        state.startIngestion(mode, timeProvider.nowDateTime());
        stateRepository.save(state);
    }

    public void completeIngestionForDate(LocalDate tradingDate, int stocksIngested, DataSourceStatus sourceStatus) {
        ScanExecutionState state = getOrCreateState(tradingDate);
        state.completeIngestion(stocksIngested, sourceStatus, timeProvider.nowDateTime());
        stateRepository.save(state);
    }

    public void completeIngestionNoDataForDate(LocalDate tradingDate, DataSourceStatus sourceStatus) {
        ScanExecutionState state = getOrCreateState(tradingDate);
        state.completeIngestionNoData(sourceStatus, timeProvider.nowDateTime());
        stateRepository.save(state);
    }

    public boolean canScanForDate(LocalDate tradingDate) {
        ScanExecutionState state = getOrCreateState(tradingDate);

        if (state.getIngestionStatus() != ExecutionStatus.SUCCESS || !state.hasData()) {
            return false;
        }

        return state.getScanStatus() == ExecutionStatus.PENDING ||
            state.getScanStatus() == ExecutionStatus.FAILED;
    }

    public void startScanForDate(LocalDate tradingDate) {
        ScanExecutionState state = getOrCreateState(tradingDate);
        state.startScan(timeProvider.nowDateTime());
        stateRepository.save(state);
    }

    public void completeScanForDate(LocalDate tradingDate, int signalsGenerated) {
        ScanExecutionState state = getOrCreateState(tradingDate);
        state.completeScan(signalsGenerated, timeProvider.nowDateTime());
        stateRepository.save(state);
    }

    // --- Legacy Transactional Methods ---

    @Transactional
    public ScanExecutionState getOrCreateTodayState() {
        return getOrCreateState(config.getTodayInExchangeZone());
    }

    public boolean canIngestToday() {
        return canIngestForDate(config.getTodayInExchangeZone());
    }

    public boolean canScanToday() {
        return canScanForDate(config.getTodayInExchangeZone());
    }

    @Transactional
    public void startIngestionToday(ExecutionMode mode) {
        startIngestionForDate(config.getTodayInExchangeZone(), mode);
    }

    @Transactional
    public void completeIngestionToday(int stocksIngested, DataSourceStatus sourceStatus) {
        completeIngestionForDate(config.getTodayInExchangeZone(), stocksIngested, sourceStatus);
    }

    @Transactional
    public void completeIngestionNoDataToday(DataSourceStatus sourceStatus) {
        completeIngestionNoDataForDate(config.getTodayInExchangeZone(), sourceStatus);
    }

    @Transactional
    public void failIngestionToday(String errorMessage, DataSourceStatus sourceStatus) {
        ScanExecutionState state = getOrCreateTodayState();
        state.failIngestion(errorMessage, sourceStatus, timeProvider.nowDateTime());
        stateRepository.save(state);
    }

    @Transactional
    public void startScanToday() {
        startScanForDate(config.getTodayInExchangeZone());
    }

    @Transactional
    public void completeScanToday(int signalsGenerated) {
        completeScanForDate(config.getTodayInExchangeZone(), signalsGenerated);
    }

    @Transactional(readOnly = true)
    public ScanExecutionState getTodayState() {
        return getOrCreateTodayState();
    }
}
