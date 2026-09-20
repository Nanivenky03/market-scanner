package com.trading.scanner.service.runtime;

import com.trading.scanner.config.RuntimeAutomationProperties;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.config.provider.AngelOneProperties;
import com.trading.scanner.service.instrument.AngelOneInstrumentCatalogSyncService;
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

        private final AngelOneInstrumentCatalogSyncService catalogSyncService;

        private final InstrumentMasterSyncService instrumentMasterSyncService;

        private final InstrumentTokenSyncService instrumentTokenSyncService;

        private final RuntimeReadinessService runtimeReadinessService;

        private final RuntimeAutomationService runtimeAutomationService;

        private final RuntimeAutomationProperties runtimeAutomationProperties;

        private final AngelOneProperties angelOneProperties;

        private final RuntimeSettingService runtimeSettingService;

        private final TimeProvider timeProvider;

        private final Object workflowLock = new Object();

        public WorkflowResult refreshFoundation() {
                synchronized (workflowLock) {
                        LocalDate workflowDate = timeProvider.today();

                        if (isComplete(FOUNDATION, workflowDate)) {
                                return skipped(
                                                FOUNDATION,
                                                workflowDate,
                                                "Foundation refresh already completed");
                        }

                        mark(
                                        FOUNDATION,
                                        workflowDate,
                                        STATUS_IN_PROGRESS,
                                        "Instrument catalog, calendar, universe, "
                                                        + "required references, and tokens refresh started");

                        try {
                                AngelOneInstrumentCatalogSyncService.CatalogSyncResult catalogResult;

                                if (!angelOneProperties.enabled()) {
                                        String message = "Foundation failed because Angel One provider is disabled";

                                        mark(
                                                        FOUNDATION,
                                                        workflowDate,
                                                        STATUS_FAILED,
                                                        message);

                                        return failed(
                                                        FOUNDATION,
                                                        workflowDate,
                                                        message);
                                }

                                catalogResult = catalogSyncService.syncNseCatalog();

                                if (catalogResult == null) {
                                        throw new IllegalStateException(
                                                        "SmartAPI catalog synchronization returned null");
                                }

                                if (!catalogResult.niftyImported()) {
                                        throw new IllegalStateException(
                                                        "SmartAPI catalog did not contain NIFTY/NSE");
                                }

                                if (catalogResult.importedRecords() == 0) {
                                        throw new IllegalStateException(
                                                        "SmartAPI catalog synchronization imported no records");
                                }

                                try {
                                        marketCalendarService
                                                        .refreshFromOfficialSourceIfDue(
                                                                        timeProvider.nowDateTime());
                                } catch (Exception ex) {
                                        log.error(
                                                        "Official NSE holiday refresh failed during foundation. "
                                                                        + "workflowDate={}, reason={}",
                                                        workflowDate,
                                                        safeMessage(ex),
                                                        ex);

                                        throw new IllegalStateException(
                                                        "Official NSE holiday refresh failed",
                                                        ex);
                                }

                                                                int requiredReferenceChanges = instrumentMasterSyncService
                                                .ensureRequiredMarketReferences();

                                InstrumentTokenSyncService.TokenSyncResult tokenResult = instrumentTokenSyncService
                                                .syncActiveInstruments();

                                if (tokenResult == null) {
                                        throw new IllegalStateException(
                                                        "Token synchronization returned null");
                                }

                                if (tokenResult.failed() > 0) {
                                        throw new IllegalStateException(
                                                        "Foundation token synchronization failed. "
                                                                        + "failed="
                                                                        + tokenResult.failed()
                                                                        + ", unmapped="
                                                                        + tokenResult.unmappedSymbols());
                                }

                                String message = "Foundation refresh completed. "
                                                + "catalogImported="
                                                + catalogResult.importedRecords()
                                                + ", catalogInserted="
                                                + catalogResult.inserted()
                                                + ", catalogUpdated="
                                                + catalogResult.updated()
                                                + ", requiredReferenceChanges="
                                                + requiredReferenceChanges
                                                + ", tokenMapped="
                                                + tokenResult.mapped();

                                mark(
                                                FOUNDATION,
                                                workflowDate,
                                                STATUS_COMPLETE,
                                                message);

                                return complete(
                                                FOUNDATION,
                                                workflowDate,
                                                message);

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

        public WorkflowResult verifyReadiness() {
                synchronized (workflowLock) {
                        LocalDate workflowDate = timeProvider.today();

                        if (isComplete(READINESS, workflowDate)) {
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

                        boolean ready = readiness != null
                                        && readiness.bootstrapReady()
                                        && readiness.readyForHistoricalData()
                                        && readiness.readyForLiveRuntime();

                        if (!ready) {
                                String message = readiness == null
                                                ? "Readiness verification failed because readiness status was null"
                                                : "Readiness verification failed. "
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

                        String message = "Historical bootstrap and base-data readiness verified";

                        mark(
                                        READINESS,
                                        workflowDate,
                                        STATUS_COMPLETE,
                                        message);

                        return complete(
                                        READINESS,
                                        workflowDate,
                                        message);
                }
        }

        public WorkflowResult refreshFinalInstruments() {
                synchronized (workflowLock) {
                        LocalDate workflowDate = timeProvider.today();

                        WorkflowResult readiness = verifyReadiness();

                        if (!STATUS_COMPLETE.equals(readiness.status())
                                        && !"SKIPPED".equals(readiness.status())) {

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
                                        "Final SmartAPI instrument catalog and broker-token refresh started");

                        try {
                                AngelOneInstrumentCatalogSyncService.CatalogSyncResult catalogResult = null;

                                if (angelOneProperties.enabled()) {
                                        catalogResult = catalogSyncService.syncNseCatalog();

                                        if (!catalogResult.niftyImported()) {
                                                throw new IllegalStateException(
                                                                "Final instrument refresh could not resolve NIFTY/NSE");
                                        }
                                }

                                                                int requiredReferenceChanges = instrumentMasterSyncService
                                                .ensureRequiredMarketReferences();

                                InstrumentTokenSyncService.TokenSyncResult tokenResult = instrumentTokenSyncService
                                                .syncActiveInstruments();

                                if (tokenResult == null
                                                || tokenResult.failed() > 0) {

                                        String message = "Final token refresh failed. failed="
                                                        + (tokenResult == null
                                                                        ? "unknown"
                                                                        : tokenResult.failed());

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

                                String message = "Final instrument refresh completed. "
                                                + "catalogImported="
                                                + (catalogResult == null
                                                                ? 0
                                                                : catalogResult.importedRecords())
                                                + ", requiredReferenceChanges="
                                                + requiredReferenceChanges
                                                + ", tokenMapped="
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

                        WorkflowResult finalRefresh = refreshFinalInstruments();

                        if (!STATUS_COMPLETE.equals(finalRefresh.status())
                                        && !"SKIPPED".equals(finalRefresh.status())) {

                                return failed(
                                                LIVE_START,
                                                workflowDate,
                                                "Live runtime blocked because final instrument refresh failed");
                        }

                        RuntimeReadinessService.ReadinessStatus readiness = runtimeReadinessService.status();

                        if (readiness == null
                                        || !readiness.bootstrapReady()
                                        || !readiness.readyForLiveRuntime()) {

                                return failed(
                                                LIVE_START,
                                                workflowDate,
                                                "Live runtime blocked because readiness is incomplete");
                        }

                        if (isComplete(LIVE_START, workflowDate)) {
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

                                if (!result.websocketConnected()) {
                                        mark(
                                                        LIVE_START,
                                                        workflowDate,
                                                        STATUS_FAILED,
                                                        result.message());

                                        return failed(
                                                        LIVE_START,
                                                        workflowDate,
                                                        result.message());
                                }

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
                                message == null
                                                ? ""
                                                : message,
                                SYSTEM_USER);

                runtimeSettingService.upsert(
                                stage + ".message",
                                message == null
                                                ? ""
                                                : message,
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
                return throwable == null
                                || throwable.getMessage() == null
                                || throwable.getMessage().isBlank()
                                                ? "Unknown error"
                                                : throwable.getMessage();
        }

        public record WorkflowResult(
                        String stage,
                        LocalDate workflowDate,
                        String status,
                        String message) {
        }
}