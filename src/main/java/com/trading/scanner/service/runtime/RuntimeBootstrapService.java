package com.trading.scanner.service.runtime;

import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.config.RuntimeAutomationProperties;
import com.trading.scanner.config.StockUniverseSeeder;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.config.provider.AngelOneProperties;
import com.trading.scanner.model.InstrumentMaster;
import com.trading.scanner.model.StockUniverse;
import com.trading.scanner.model.WorkflowStatus;
import com.trading.scanner.repository.InstrumentMasterRepository;
import com.trading.scanner.repository.StockUniverseRepository;
import com.trading.scanner.service.data.EodDataEntryService;
import com.trading.scanner.service.instrument.AngelOneInstrumentCatalogSyncService;
import com.trading.scanner.service.instrument.InstrumentMasterSyncService;
import com.trading.scanner.service.workflow.WorkflowStatusService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class RuntimeBootstrapService implements ApplicationRunner {

    private final RuntimeAutomationProperties runtimeAutomationProperties;
    private final StockUniverseSeeder stockUniverseSeeder;
    private final InstrumentMasterSyncService instrumentMasterSyncService;
    private final AngelOneInstrumentCatalogSyncService catalogSyncService;
    private final AngelOneProperties angelOneProperties;
    private final RuntimeSettingService runtimeSettingService;
    private final WorkflowStatusService workflowStatusService;
    private final StockUniverseRepository stockUniverseRepository;
    private final InstrumentMasterRepository instrumentMasterRepository;
    private final EodDataEntryService eodDataEntryService;
    private final TradingCalendar tradingCalendar;
    private final TimeProvider timeProvider;
    private final RuntimeAlertService runtimeAlertService;

    private final Object bootstrapLock = new Object();

    @Autowired
    public RuntimeBootstrapService(
            RuntimeAutomationProperties runtimeAutomationProperties,
            StockUniverseSeeder stockUniverseSeeder,
            InstrumentMasterSyncService instrumentMasterSyncService,
            AngelOneInstrumentCatalogSyncService catalogSyncService,
            AngelOneProperties angelOneProperties,
            RuntimeSettingService runtimeSettingService,
            WorkflowStatusService workflowStatusService,
            StockUniverseRepository stockUniverseRepository,
            InstrumentMasterRepository instrumentMasterRepository,
            EodDataEntryService eodDataEntryService,
            TradingCalendar tradingCalendar,
            TimeProvider timeProvider,
            @Lazy RuntimeAlertService runtimeAlertService) {

        this.runtimeAutomationProperties = runtimeAutomationProperties;
        this.stockUniverseSeeder = stockUniverseSeeder;
        this.instrumentMasterSyncService = instrumentMasterSyncService;
        this.catalogSyncService = catalogSyncService;
        this.angelOneProperties = angelOneProperties;
        this.runtimeSettingService = runtimeSettingService;
        this.workflowStatusService = workflowStatusService;
        this.stockUniverseRepository = stockUniverseRepository;
        this.instrumentMasterRepository = instrumentMasterRepository;
        this.eodDataEntryService = eodDataEntryService;
        this.tradingCalendar = tradingCalendar;
        this.timeProvider = timeProvider;
        this.runtimeAlertService = runtimeAlertService;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        bootstrapIfNeeded();
    }

    public BootstrapResult bootstrapIfNeeded() {
        synchronized (bootstrapLock) {
            log.info("Starting runtime bootstrap 6-stage sequence");

            stepInstrumentMasterSync();
            stepMarketReferenceSetup();
            stepStockUniverseSeed();
            stepRuntimeSettingSync();
            stepEodDataReadiness();
            stepRuntimeBootstrapComplete();

            log.info("Runtime bootstrap 6-stage sequence finished successfully");
            return new BootstrapResult(0, 0, "Runtime bootstrap 6-stage sequence completed");
        }
    }

    private void stepInstrumentMasterSync() {
        runStage(WorkflowStatusService.INSTRUMENT_MASTER_SYNC, () -> {
            if (!angelOneProperties.enabled()) {
                throw new IllegalStateException("Angel One provider is disabled");
            }
            AngelOneInstrumentCatalogSyncService.CatalogSyncResult result = catalogSyncService.syncNseCatalog();
            if (result == null || result.importedRecords() == 0 || !result.niftyImported()) {
                throw new IllegalStateException("Instrument catalog sync failed or incomplete");
            }
            runtimeAlertService.resolveInstrumentMasterStartupFailure();
            return "Imported " + result.importedRecords() + " instruments";
        });
    }

    private void stepMarketReferenceSetup() {
        runStage(WorkflowStatusService.MARKET_REFERENCE_SETUP, () -> {
            int changed = instrumentMasterSyncService.ensureRequiredMarketReferences();
            return "Market references verified. changed=" + changed;
        });
    }

    private void stepStockUniverseSeed() {
        runStage(WorkflowStatusService.STOCK_UNIVERSE_SEED, () -> {
            StockUniverseSeeder.SeedResult result = stockUniverseSeeder.seedIfNeeded();
            return "Stock universe verified. inserted=" + result.inserted();
        });
    }

    private void stepRuntimeSettingSync() {
        runStage(WorkflowStatusService.RUNTIME_SETTING_SYNC, () -> {
            int seeded = runtimeSettingService.ensureRequiredSettingsExist();
            return "Runtime settings verified. newlySeeded=" + seeded;
        });
    }

    private void stepEodDataReadiness() {
        runStage(WorkflowStatusService.EOD_DATA_READINESS, () -> {
            List<StockUniverse> activeUniverse = stockUniverseRepository.findByIsActiveTrueOrderBySymbolAsc();
            LocalDate prevDay = tradingCalendar.previousTradingDay(timeProvider.today());
            int seeded = eodDataEntryService.ensureInitialBaselineIfEmpty(activeUniverse, prevDay);
            return "EOD data readiness verified. baselineSeeded=" + seeded;
        });
    }

    private void stepRuntimeBootstrapComplete() {
        runStage(WorkflowStatusService.RUNTIME_BOOTSTRAP_COMPLETE, () -> {
            List<StockUniverse> activeUniverse = stockUniverseRepository.findByIsActiveTrueOrderBySymbolAsc();
            if (activeUniverse.isEmpty()) {
                throw new IllegalStateException("Stock universe is empty");
            }
            List<String> missingTokens = new ArrayList<>();
            for (StockUniverse stock : activeUniverse) {
                String exchange = stock.getExchange() != null ? stock.getExchange().name() : "NSE";
                InstrumentMaster inst = instrumentMasterRepository.findBySymbolAndExchange(stock.getSymbol(), exchange).orElse(null);
                if (inst == null || inst.getBrokerToken() == null || inst.getBrokerToken().isBlank()) {
                    missingTokens.add(stock.getSymbol());
                }
            }
            InstrumentMaster nifty = instrumentMasterRepository.findBySymbolAndExchange("NIFTY", "NSE").orElse(null);
            if (nifty == null || nifty.getBrokerToken() == null || nifty.getBrokerToken().isBlank()) {
                missingTokens.add("NIFTY");
            }
            if (!missingTokens.isEmpty()) {
                throw new IllegalStateException("Missing broker tokens for: " + String.join(", ", missingTokens));
            }
            return "Runtime bootstrap fully complete. activeSymbols=" + activeUniverse.size();
        });
    }

    private void runStage(String stageName, StageAction action) {
        WorkflowStatus workflow = workflowStatusService.prepareForStartup(stageName);
        if (workflow.getStatus() == WorkflowStatus.Status.SUCCESS) {
            log.info("Startup stage [{}] already SUCCESS", stageName);
            return;
        }

        WorkflowStatus running = workflowStatusService.markRunning(workflow.getWorkflowId());
        try {
            String message = action.execute();
            workflowStatusService.markSuccess(running.getWorkflowId(), message);
            log.info("Startup stage [{}] completed: {}", stageName, message);
        } catch (Exception ex) {
            workflowStatusService.markFailed(running.getWorkflowId(), "Startup stage failed: " + stageName, ex);
            if (WorkflowStatusService.INSTRUMENT_MASTER_SYNC.equals(stageName)) {
                runtimeAlertService.reportInstrumentMasterStartupFailure(running.getWorkflowId(), running.getAttemptCount(), ex);
            }
            log.error("Startup stage [{}] failed: {}", stageName, ex.getMessage(), ex);
            throw new IllegalStateException("Startup stage [" + stageName + "] failed", ex);
        }
    }

    public record BootstrapResult(
            int universeInserted,
            int instrumentMasterChanged,
            String message) {
    }

    @FunctionalInterface
    private interface StageAction {
        String execute() throws Exception;
    }
}