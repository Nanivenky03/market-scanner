package com.trading.scanner.service.runtime;

import com.trading.scanner.service.data.EodReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ScheduledJobAlertServiceTest {

    private RuntimeAlertService runtimeAlertService;
    private ScheduledJobAlertService service;

    @BeforeEach
    void setUp() {
        runtimeAlertService = mock(RuntimeAlertService.class);

        service = new ScheduledJobAlertService(
                runtimeAlertService);
    }

    @Test
    void reportSuccess_shouldDelegateSuccessfulExecution() {
        service.reportSuccess(
                "housekeeping",
                "Housekeeping completed",
                "details");

        verify(runtimeAlertService)
                .reportScheduledJobResult(
                        "housekeeping",
                        true,
                        "Housekeeping completed",
                        "details");
    }

    @Test
    void reportFailure_shouldDelegateFailedExecution() {
        IllegalStateException error = new IllegalStateException(
                "database unavailable");

        service.reportFailure(
                "eod-reconciliation",
                error);

        verify(runtimeAlertService)
                .reportScheduledJobResult(
                        eq("eod-reconciliation"),
                        eq(false),
                        eq("database unavailable"),
                        anyMap());
    }

    @Test
    void reportResult_shouldFailWhenEodHasPartialSymbols() {
        EodReconciliationService.ReconciliationBatchResult result = new EodReconciliationService.ReconciliationBatchResult(
                LocalDate.of(2026, 8, 27),
                5,
                3,
                1,
                1,
                "EOD reconciliation completed with partial symbols");

        service.reportResult(
                "eod-reconciliation",
                result);

        verify(runtimeAlertService)
                .reportScheduledJobResult(
                        eq("eod-reconciliation"),
                        eq(false),
                        eq("EOD reconciliation completed with partial symbols"),
                        anyMap());
    }

    @Test
    void reportResult_shouldSucceedWhenAllEodSymbolsPass() {
        EodReconciliationService.ReconciliationBatchResult result = new EodReconciliationService.ReconciliationBatchResult(
                LocalDate.of(2026, 8, 27),
                5,
                4,
                1,
                0,
                "EOD reconciliation completed");

        service.reportResult(
                "eod-reconciliation",
                result);

        verify(runtimeAlertService)
                .reportScheduledJobResult(
                        eq("eod-reconciliation"),
                        eq(true),
                        eq("EOD reconciliation completed"),
                        anyMap());
    }
}
