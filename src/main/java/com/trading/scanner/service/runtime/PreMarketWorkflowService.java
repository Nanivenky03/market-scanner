package com.trading.scanner.service.runtime;

import com.trading.scanner.config.RuntimeAutomationProperties;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.service.data.HistoricalBootstrapService;
import com.trading.scanner.service.instrument.InstrumentMasterSyncService;
import com.trading.scanner.service.instrument.InstrumentTokenSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
@Slf4j
public class PreMarketWorkflowService {

    private static final String STATUS_COMPLETE = "COMPLETE";
    private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    private static final String STATUS_FAILED = "FAILED";

    private static final String FOUNDATION = "runtime.premarket.foundation";

    private static final String BOOTSTRAP = "runtime.premarket.bootstrap";

    private static final String READINESS = "runtime.premarket.readiness";

    private static final String FINAL_INSTRUMENT_REFRESH = "runtime.premarket.final-instrument-refresh";

    private static final String LIVE_START = "runtime.premarket.live-start";

    private static final String SYSTEM_USER = "system";

    private final MarketCalendarService marketCalendarService;
    private final InstrumentMasterSyncService instrumentMasterSyncService;
    private final InstrumentTokenSyncService instrumentTokenSyncService;
    private final HistoricalBootstrapService historicalBootstrapService;
    private final RuntimeReadinessService runtimeReadinessService;
    private final RuntimeAutomationService runtimeAutomationService;
    private final RuntimeAutomationProperties runtimeAutomationProperties;
    private final RuntimeSettingService runtimeSettingService;
    private final TimeProvider timeProvider;

    private final Object workflowLock = new Object();

    public WorkflowResult refreshFoundation() {
        synchronized (workflowLock) {
            LocalDate workflowDate = timeProvider.today();

            if (isComplete(
                    FOUNDATION,
                    workflowDate)) {
                return skipped(
                        FOUNDATION,
                        workflowDate,
                        "Foundation refresh already completed");
            }

            mark(
                    FOUNDATION,
                    workflowDate,
                    STATUS_IN_PROGRESS,
                    "Calendar and instrument refresh started");

            try {
                marketCalendarService
                        .refreshFromOfficialSourceIfDue(
                                timeProvider.nowDateTime());

                int changed = instrumentMasterSyncService
                        .syncActiveUniverse();

                mark(
                        FOUNDATION,
                        workflowDate,
                        STATUS_COMPLETE,
                        "Foundation refresh completed; changed="
                                + changed);

                return complete(
                        FOUNDATION,
                        workflowDate,
                        "Calendar and active-universe instrument refresh completed");

            } catch (Exception ex) {
                mark(
                        FOUNDATION,
                        workflowDate,
                        STATUS_FAILED,
                        safeMessage(ex));

                return failed(
                        FOUNDATION,
                        workflowDate,
                        safeMessage(ex));
            }
        }
    }

    public WorkflowResult runHistoricalBootstrap() {
        synchronized (workflowLock) {
            LocalDate workflowDate = timeProvider.today();

            refreshFoundation();

            if (!isComplete(
                    FOUNDATION,
                    workflowDate)) {
                return failed(
                        BOOTSTRAP,
                        workflowDate,
                        "Historical bootstrap blocked because foundation refresh failed");
            }

            if (isComplete(
                    BOOTSTRAP,
                    workflowDate)) {
                return skipped(
                        BOOTSTRAP,
                        workflowDate,
                        "Historical bootstrap already completed");
            }

            mark(
                    BOOTSTRAP,
                    workflowDate,
                    STATUS_IN_PROGRESS,
                    "Historical bootstrap started");

            try {
                HistoricalBootstrapService.BootstrapResult result = historicalBootstrapService
                        .bootstrapActiveUniverse();

                boolean success = result.processedSymbols() > 0
                        && result.failedSymbols() == 0;

                if (!success) {
                    String message = "Historical bootstrap incomplete. processed="
                            + result.processedSymbols()
                            + ", failed="
                            + result.failedSymbols()
                            + ", skipped="
                            + result.skippedSymbols();

                    mark(
                            BOOTSTRAP,
                            workflowDate,
                            STATUS_FAILED,
                            message);

                    return failed(
                            BOOTSTRAP,
                            workflowDate,
                            message);
                }

                mark(
                        BOOTSTRAP,
                        workflowDate,
                        STATUS_COMPLETE,
                        result.message());

                return complete(
                        BOOTSTRAP,
                        workflowDate,
                        result.message());

            } catch (Exception ex) {
                mark(
                        BOOTSTRAP,
                        workflowDate,
                        STATUS_FAILED,
                        safeMessage(ex));

                return failed(
                        BOOTSTRAP,
                        workflowDate,
                        safeMessage(ex));
            }
        }
    }

    public WorkflowResult verifyReadiness() {
        synchronized (workflowLock) {
            LocalDate workflowDate = timeProvider.today();

            runHistoricalBootstrap();

            if (!isComplete(
                    BOOTSTRAP,
                    workflowDate)) {
                return failed(
                        READINESS,
                        workflowDate,
                        "Readiness verification blocked because bootstrap failed");
            }

            if (isComplete(
                    READINESS,
                    workflowDate)) {
                return skipped(
                        READINESS,
                        workflowDate,
                        "Readiness already verified");
            }

            mark(
                    READINESS,
                    workflowDate,
                    STATUS_IN_PROGRESS,
                    "Readiness verification started");

            RuntimeReadinessService.ReadinessStatus readiness = runtimeReadinessService.status();

            boolean ready = readiness.bootstrapReady()
                    && readiness.readyForHistoricalData()
                    && readiness.readyForLiveRuntime();

            if (!ready) {
                String message = "Readiness verification failed. "
                        + "bootstrapReady="
                        + readiness.bootstrapReady()
                        + ", readyForHistoricalData="
                        + readiness.readyForHistoricalData()
                        + ", readyForLiveRuntime="
                        + readiness.readyForLiveRuntime();

                mark(
                        READINESS,
                        workflowDate,
                        STATUS_FAILED,
                        message);

                return failed(
                        READINESS,
                        workflowDate,
                        message);
            }

            mark(
                    READINESS,
                    workflowDate,
                    STATUS_COMPLETE,
                    "Historical bootstrap and base-data readiness verified");

            return complete(
                    READINESS,
                    workflowDate,
                    "Historical bootstrap and base-data readiness verified");
        }
    }

    public WorkflowResult refreshFinalInstruments() {
        synchronized (workflowLock) {
            LocalDate workflowDate = timeProvider.today();

            verifyReadiness();

            if (!isComplete(
                    READINESS,
                    workflowDate)) {
                return failed(
                        FINAL_INSTRUMENT_REFRESH,
                        workflowDate,
                        "Final instrument refresh blocked because readiness failed");
            }

            if (isComplete(
                    FINAL_INSTRUMENT_REFRESH,
                    workflowDate)) {
                return skipped(
                        FINAL_INSTRUMENT_REFRESH,
                        workflowDate,
                        "Final instrument refresh already completed");
            }

            mark(
                    FINAL_INSTRUMENT_REFRESH,
                    workflowDate,
                    STATUS_IN_PROGRESS,
                    "Final instrument and token refresh started");

            try {
                int changed = instrumentMasterSyncService
                        .syncActiveUniverse();

                InstrumentTokenSyncService.TokenSyncResult tokenResult = instrumentTokenSyncService
                        .syncActiveInstruments();

                if (tokenResult.failed() > 0) {
                    String message = "Final token refresh failed. failed="
                            + tokenResult.failed();

                    mark(
                            FINAL_INSTRUMENT_REFRESH,
                            workflowDate,
                            STATUS_FAILED,
                            message);

                    return failed(
                            FINAL_INSTRUMENT_REFRESH,
                            workflowDate,
                            message);
                }

                String message = "Final instrument refresh completed; changed="
                        + changed
                        + ", mapped="
                        + tokenResult.mapped();

                mark(
                        FINAL_INSTRUMENT_REFRESH,
                        workflowDate,
                        STATUS_COMPLETE,
                        message);

                return complete(
                        FINAL_INSTRUMENT_REFRESH,
                        workflowDate,
                        message);

            } catch (Exception ex) {
                mark(
                        FINAL_INSTRUMENT_REFRESH,
                        workflowDate,
                        STATUS_FAILED,
                        safeMessage(ex));

                return failed(
                        FINAL_INSTRUMENT_REFRESH,
                        workflowDate,
                        safeMessage(ex));
            }
        }
    }

    public WorkflowResult startLiveRuntime() {
        synchronized (workflowLock) {
            LocalDate workflowDate = timeProvider.today();

            if (!runtimeAutomationProperties
                    .getLive()
                    .isAutoRun()) {
                return skipped(
                        LIVE_START,
                        workflowDate,
                        "Live runtime auto-run is disabled");
            }

            refreshFinalInstruments();

            if (!isComplete(
                    FINAL_INSTRUMENT_REFRESH,
                    workflowDate)) {
                return failed(
                        LIVE_START,
                        workflowDate,
                        "Live runtime blocked because final instrument refresh failed");
            }

            RuntimeReadinessService.ReadinessStatus readiness = runtimeReadinessService.status();

            if (!readiness.readyForLiveRuntime()) {
                return failed(
                        LIVE_START,
                        workflowDate,
                        "Live runtime blocked because readiness is incomplete");
            }

            if (isComplete(
                    LIVE_START,
                    workflowDate)) {
                return skipped(
                        LIVE_START,
                        workflowDate,
                        "Live runtime already started");
            }

            mark(
                    LIVE_START,
                    workflowDate,
                    STATUS_IN_PROGRESS,
                    "Live runtime start initiated");

            try {
                RuntimeAutomationService.RuntimeActionResult result = runtimeAutomationService
                        .connectAndSubscribe();

                mark(
                        LIVE_START,
                        workflowDate,
                        STATUS_COMPLETE,
                        result.message());

                return complete(
                        LIVE_START,
                        workflowDate,
                        result.message());

            } catch (Exception ex) {
                mark(
                        LIVE_START,
                        workflowDate,
                        STATUS_FAILED,
                        safeMessage(ex));

                return failed(
                        LIVE_START,
                        workflowDate,
                        safeMessage(ex));
            }
        }
    }

    private boolean isComplete(
            String stage,
            LocalDate date) {

        return date.toString().equals(
                runtimeSettingService.getString(
                        stage + ".date",
                        ""))
                && STATUS_COMPLETE.equalsIgnoreCase(
                        runtimeSettingService.getString(
                                stage + ".status",
                                ""));
    }

    private void mark(
            String stage,
            LocalDate date,
            String status,
            String message) {

        runtimeSettingService.upsert(
                stage + ".date",
                date.toString(),
                "DATE",
                "Pre-market workflow date",
                SYSTEM_USER);

        runtimeSettingService.upsert(
                stage + ".status",
                status,
                "STRING",
                message == null ? "" : message,
                SYSTEM_USER);

        runtimeSettingService.upsert(
                stage + ".message",
                message == null ? "" : message,
                "STRING",
                "Pre-market workflow message",
                SYSTEM_USER);
    }

    private WorkflowResult complete(
            String stage,
            LocalDate date,
            String message) {

        return new WorkflowResult(
                stage,
                date,
                STATUS_COMPLETE,
                message);
    }

    private WorkflowResult failed(
            String stage,
            LocalDate date,
            String message) {

        return new WorkflowResult(
                stage,
                date,
                STATUS_FAILED,
                message);
    }

    private WorkflowResult skipped(
            String stage,
            LocalDate date,
            String message) {

        return new WorkflowResult(
                stage,
                date,
                "SKIPPED",
                message);
    }

    private String safeMessage(Throwable throwable) {
        if (throwable == null
                || throwable.getMessage() == null
                || throwable.getMessage().isBlank()) {
            return "Unknown error";
        }

        return throwable.getMessage();
    }

    public record WorkflowResult(
            String stage,
            LocalDate workflowDate,
            String status,
            String message) {
    }
}
