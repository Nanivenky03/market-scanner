package com.trading.scanner.service.state;

import com.trading.scanner.config.ExchangeConfiguration;
import com.trading.scanner.model.ScanExecutionState;
import com.trading.scanner.model.ScanExecutionState.ExecutionStatus;
import com.trading.scanner.model.ScanExecutionState.DataSourceStatus;
import com.trading.scanner.model.ScanExecutionState.ExecutionMode;
import com.trading.scanner.repository.ScanExecutionStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionStateService {
    
    private final ScanExecutionStateRepository stateRepository;
    private final ExchangeConfiguration config;
    
    @Transactional
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
    
    @Transactional
    public ScanExecutionState getOrCreateTodayState() {
        LocalDate today = config.getTodayInExchangeZone();
        return getOrCreateState(today);
    }
    
    public boolean canIngest(LocalDate tradingDate) {
        ScanExecutionState state = getOrCreateState(tradingDate);
        return state.getIngestionStatus() == ExecutionStatus.PENDING ||
               state.getIngestionStatus() == ExecutionStatus.FAILED;
    }
    
    public boolean canIngestToday() {
        return canIngest(config.getTodayInExchangeZone());
    }
    
    public boolean canScan(LocalDate tradingDate) {
        ScanExecutionState state = getOrCreateState(tradingDate);
        
        if (state.getIngestionStatus() != ExecutionStatus.SUCCESS || !state.hasData()) {
            return false;
        }
        
        return state.getScanStatus() == ExecutionStatus.PENDING ||
               state.getScanStatus() == ExecutionStatus.FAILED;
    }
    
    public boolean canScanToday() {
        return canScan(config.getTodayInExchangeZone());
    }
    
    @Transactional
    public void startIngestionToday(ExecutionMode mode) {
        ScanExecutionState state = getOrCreateTodayState();
        state.startIngestion(mode);
        stateRepository.save(state);
    }
    
    @Transactional
    public void completeIngestionToday(int stocksIngested, DataSourceStatus sourceStatus) {
        ScanExecutionState state = getOrCreateTodayState();
        state.completeIngestion(stocksIngested, sourceStatus);
        stateRepository.save(state);
    }
    
    @Transactional
    public void completeIngestionNoDataToday(DataSourceStatus sourceStatus) {
        ScanExecutionState state = getOrCreateTodayState();
        state.completeIngestionNoData(sourceStatus);
        stateRepository.save(state);
    }
    
    @Transactional
    public void failIngestionToday(String errorMessage, DataSourceStatus sourceStatus) {
        ScanExecutionState state = getOrCreateTodayState();
        state.failIngestion(errorMessage, sourceStatus);
        stateRepository.save(state);
    }
    
    @Transactional
    public void startScanToday() {
        ScanExecutionState state = getOrCreateTodayState();
        state.startScan();
        stateRepository.save(state);
    }
    
    @Transactional
    public void completeScanToday(int signalsGenerated) {
        ScanExecutionState state = getOrCreateTodayState();
        state.completeScan(signalsGenerated);
        stateRepository.save(state);
    }
    
    @Transactional(readOnly = true)
    public ScanExecutionState getTodayState() {
        return getOrCreateTodayState();
    }
}
