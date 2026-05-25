package com.trading.scanner.service.state;

import com.trading.scanner.config.ExchangeConfiguration;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.ScanExecutionState;
import com.trading.scanner.model.ScanExecutionState.DataSourceStatus;
import com.trading.scanner.model.ScanExecutionState.ExecutionMode;
import com.trading.scanner.model.ScanExecutionState.ExecutionStatus;
import com.trading.scanner.repository.ScanExecutionStateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExecutionStateServiceTest {

    @Mock
    private ScanExecutionStateRepository stateRepository;

    @Mock
    private ExchangeConfiguration config;

    @Mock
    private TimeProvider timeProvider;

    @InjectMocks
    private ExecutionStateService executionStateService;

    @Test
    void getOrCreateState_shouldCreatePendingStateWhenMissing() {
        LocalDate tradingDate = LocalDate.of(2024, 4, 5);

        when(stateRepository.findByTradingDate(tradingDate)).thenReturn(Optional.empty());
        when(stateRepository.save(any(ScanExecutionState.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ScanExecutionState state = executionStateService.getOrCreateState(tradingDate);

        assertNotNull(state);
        assertEquals(tradingDate, state.getTradingDate());
        assertEquals(ExecutionStatus.PENDING, state.getIngestionStatus());
        assertEquals(ExecutionStatus.PENDING, state.getScanStatus());
        assertEquals(DataSourceStatus.UNKNOWN, state.getDataSourceStatus());

        verify(stateRepository).save(any(ScanExecutionState.class));
    }

    @Test
    void canScanForDate_shouldReturnTrueWhenIngestionSucceededAndDataExists() {
        LocalDate tradingDate = LocalDate.of(2024, 4, 5);

        ScanExecutionState state = ScanExecutionState.builder()
                .tradingDate(tradingDate)
                .ingestionStatus(ExecutionStatus.SUCCESS)
                .scanStatus(ExecutionStatus.PENDING)
                .stocksIngested(50)
               .dataSourceStatus(DataSourceStatus.HEALTHY)
                .build();

        when(stateRepository.findByTradingDate(tradingDate)).thenReturn(Optional.of(state));

        assertTrue(executionStateService.canScanForDate(tradingDate));
    }

    @Test
    void canScanForDate_shouldReturnFalseWhenNoDataExists() {
        LocalDate tradingDate = LocalDate.of(2024, 4, 5);

        ScanExecutionState state = ScanExecutionState.builder()
                .tradingDate(tradingDate)
                .ingestionStatus(ExecutionStatus.SUCCESS_NO_DATA)
                .scanStatus(ExecutionStatus.PENDING)
                .stocksIngested(0)
                .dataSourceStatus(DataSourceStatus.NO_DATA)
                .build();

        when(stateRepository.findByTradingDate(tradingDate)).thenReturn(Optional.of(state));

        assertFalse(executionStateService.canScanForDate(tradingDate));
    }

    @Test
    void completeIngestionForDate_shouldUpdateStateCorrectly() {
        LocalDate tradingDate = LocalDate.of(2024, 4, 5);
        LocalDateTime now = LocalDateTime.of(2024, 4, 5, 9, 15);

        ScanExecutionState state = ScanExecutionState.builder()
                .tradingDate(tradingDate)
                .ingestionStatus(ExecutionStatus.IN_PROGRESS)
                .scanStatus(ExecutionStatus.PENDING)
                .build();

        when(stateRepository.findByTradingDate(tradingDate)).thenReturn(Optional.of(state));
        when(timeProvider.nowDateTime()).thenReturn(now);
        when(stateRepository.save(any(ScanExecutionState.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        executionStateService.completeIngestionForDate(tradingDate, 50, DataSourceStatus.HEALTHY);

        ArgumentCaptor<ScanExecutionState> captor = ArgumentCaptor.forClass(ScanExecutionState.class);
        verify(stateRepository).save(captor.capture());

        ScanExecutionState saved = captor.getValue();
        assertEquals(ExecutionStatus.SUCCESS, saved.getIngestionStatus());
        assertEquals(50, saved.getStocksIngested());
        assertEquals(DataSourceStatus.HEALTHY, saved.getDataSourceStatus());
        assertEquals(now, saved.getLastIngestionTime());
    }

    @Test
    void failIngestionForDate_shouldMarkFailure() {
        LocalDate tradingDate = LocalDate.of(2024, 4, 5);
        LocalDateTime now = LocalDateTime.of(2024, 4, 5, 9, 15);

        ScanExecutionState state = ScanExecutionState.builder()
                .tradingDate(tradingDate)
                .ingestionStatus(ExecutionStatus.IN_PROGRESS)
                .scanStatus(ExecutionStatus.PENDING)
                .build();

        when(stateRepository.findByTradingDate(tradingDate)).thenReturn(Optional.of(state));
        when(timeProvider.nowDateTime()).thenReturn(now);
        when(stateRepository.save(any(ScanExecutionState.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        executionStateService.failIngestionForDate(
                tradingDate,
                "provider unavailable",
                DataSourceStatus.UNAVAILABLE
        );

        ArgumentCaptor<ScanExecutionState> captor = ArgumentCaptor.forClass(ScanExecutionState.class);
        verify(stateRepository).save(captor.capture());

        ScanExecutionState saved = captor.getValue();
        assertEquals(ExecutionStatus.FAILED, saved.getIngestionStatus());
        assertEquals("provider unavailable", saved.getErrorMessage());
        assertEquals(DataSourceStatus.UNAVAILABLE, saved.getDataSourceStatus());
        assertEquals(now, saved.getLastIngestionTime());
    }
}