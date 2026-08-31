package com.trading.scanner.service.runtime;

import com.trading.scanner.service.data.EodReconciliationService;
import com.trading.scanner.service.data.HistoricalBootstrapService;
import com.trading.scanner.service.instrument.InstrumentTokenSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ScheduledJobAlertService {

    private final RuntimeAlertService runtimeAlertService;

    public RuntimeAlertService.ScheduledJobAlertResult reportSuccess(
            String jobKey,
            String message,
            Object details) {

        return runtimeAlertService.reportScheduledJobResult(
                jobKey,
                true,
                message,
                details);
    }

    public RuntimeAlertService.ScheduledJobAlertResult reportFailure(
            String jobKey,
            Throwable error) {

        String message = error == null
                ? "Scheduled job failed"
                : error.getMessage();

        String safeMessage = message == null
                ? "Scheduled job failed"
                : message;

        return runtimeAlertService.reportScheduledJobResult(
                jobKey,
                false,
                safeMessage,
                Map.of(
                        "exceptionType",
                        error == null
                                ? "UNKNOWN"
                                : error.getClass().getName(),
                        "error",
                        safeMessage));
    }

    public RuntimeAlertService.ScheduledJobAlertResult reportResult(
            String jobKey,
            Object result) {

        Assessment assessment = assess(result);

        return runtimeAlertService.reportScheduledJobResult(
                jobKey,
                assessment.success(),
                assessment.message(),
                assessment.details());
    }

    private Assessment assess(Object result) {
        if (result == null) {
            return new Assessment(
                    true,
                    "Scheduled job completed",
                    Map.of("result", "NO_RESULT"));
        }

        if (result instanceof PreMarketWorkflowService.WorkflowResult workflow) {
            boolean success = !"FAILED".equalsIgnoreCase(
                    workflow.status());

            return new Assessment(
                    success,
                    safeMessage(
                            workflow.message(),
                            "Pre-market workflow completed"),
                    Map.of(
                            "status",
                            safeValue(workflow.status()),
                            "stage",
                            safeValue(workflow.stage()),
                            "workflowDate",
                            safeValue(workflow.workflowDate()),
                            "message",
                            safeValue(workflow.message())));
        }

        if (result instanceof HistoricalBootstrapService.BootstrapResult bootstrap) {
            boolean success = bootstrap.processedSymbols() > 0
                    && bootstrap.failedSymbols() == 0;

            return new Assessment(
                    success,
                    safeMessage(
                            bootstrap.message(),
                            "Historical bootstrap completed"),
                    Map.of(
                            "targetDate",
                            safeValue(bootstrap.targetDate()),
                            "processedSymbols",
                            bootstrap.processedSymbols(),
                            "successfulSymbols",
                            bootstrap.successfulSymbols(),
                            "failedSymbols",
                            bootstrap.failedSymbols(),
                            "skippedSymbols",
                            bootstrap.skippedSymbols(),
                            "result",
                            bootstrap.toString()));
        }

        if (result instanceof EodReconciliationService.ReconciliationBatchResult eod) {
            boolean success = eod.symbolsProcessed() > 0
                    && eod.partialSymbols() == 0;

            return new Assessment(
                    success,
                    safeMessage(
                            eod.message(),
                            "EOD reconciliation completed"),
                    Map.of(
                            "tradingDate",
                            safeValue(eod.tradingDate()),
                            "symbolsProcessed",
                            eod.symbolsProcessed(),
                            "reconciledSymbols",
                            eod.reconciledSymbols(),
                            "repairedSymbols",
                            eod.repairedSymbols(),
                            "partialSymbols",
                            eod.partialSymbols(),
                            "result",
                            eod.toString()));
        }

        if (result instanceof InstrumentTokenSyncService.TokenSyncResult tokenSync) {
            boolean success = tokenSync.failed() == 0;

            return new Assessment(
                    success,
                    safeMessage(
                            tokenSync.message(),
                            "Instrument token synchronization completed"),
                    Map.of(
                            "mapped",
                            tokenSync.mapped(),
                            "failed",
                            tokenSync.failed(),
                            "result",
                            tokenSync.toString()));
        }

        if (result instanceof MarketCalendarService.ScheduledCalendarRefreshResult calendar) {
            boolean success = !"FAILED".equalsIgnoreCase(
                    calendar.action());

            return new Assessment(
                    success,
                    safeMessage(
                            calendar.message(),
                            "Calendar refresh completed"),
                    Map.of(
                            "action",
                            safeValue(calendar.action()),
                            "result",
                            calendar.toString()));
        }

        return new Assessment(
                true,
                "Scheduled job completed",
                Map.of("result", result.toString()));
    }

    private String safeMessage(
            String value,
            String fallback) {

        return value == null || value.isBlank()
                ? fallback
                : value;
    }

    private String safeValue(Object value) {
        return value == null
                ? ""
                : value.toString();
    }

    private record Assessment(
            boolean success,
            String message,
            Map<String, Object> details) {
    }
}
