package com.trading.scanner.controller;

import com.trading.scanner.config.AppInfo;
import com.trading.scanner.config.ExchangeConfiguration;
import com.trading.scanner.model.ScanExecutionState.ExecutionMode;
import com.trading.scanner.repository.ScanResultRepository;
import com.trading.scanner.repository.StockPriceRepository;
import com.trading.scanner.repository.StockUniverseRepository;
import com.trading.scanner.service.data.DataIngestionService;
import com.trading.scanner.service.scanner.ScannerEngine;
import com.trading.scanner.service.state.ExecutionStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Controller
@RequiredArgsConstructor
public class DashboardController {

    private final DataIngestionService dataIngestionService;
    private final ScannerEngine scannerEngine;
    private final ExecutionStateService executionStateService;
    private final StockUniverseRepository universeRepository;
    private final StockPriceRepository priceRepository;
    private final ScanResultRepository resultRepository;
    private final ExchangeConfiguration config;
    private final AppInfo appInfo;

    @GetMapping("/")
    public String dashboard(Model model) {
        LocalDate today = config.getTodayInExchangeZone();

        long universeCount = universeRepository.findByIsActiveTrue().size();
        long priceCount = priceRepository.countAll();
        long signalCount = resultRepository.count();

        model.addAttribute("appName", appInfo.getName());
        model.addAttribute("appVersion", appInfo.getVersion());
        model.addAttribute("todayDate", today);
        model.addAttribute("exchangeTimezone", config.getExchangeZone().toString());
        model.addAttribute("universeCount", universeCount);
        model.addAttribute("priceCount", priceCount);
        model.addAttribute("signalCount", signalCount);
        model.addAttribute("canIngest", executionStateService.canIngestToday());
        model.addAttribute("canScan", executionStateService.canScanToday());
        model.addAttribute("recentSignals", resultRepository.findTop10ByOrderByScanDateDesc());

        return "dashboard";
    }

    @PostMapping("/ingest/historical")
    @ResponseBody
    public Map<String, Object> ingestHistorical(@RequestParam(defaultValue = "5") int years) {
        Map<String, Object> response = new HashMap<>();

        if (!config.isHistoricalReloadAllowed()) {
            response.put("success", false);
            response.put("message", "Historical reload requires BOTH config flags: " +
                "scanner.allowHistoricalReload=true AND scanner.historical.reload.confirm=true");
            return response;
        }

        try {
            log.info("Starting historical data ingestion ({} years) - MANUAL trigger", years);
            dataIngestionService.ingestHistoricalDataForUniverse(years);

            response.put("success", true);
            response.put("message", "Historical data ingestion completed");
        } catch (Exception e) {
            log.error("Historical ingestion failed: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Error: " + e.getMessage());
        }

        return response;
    }

    @PostMapping("/ingest/daily")
    @ResponseBody
    public Map<String, Object> ingestDaily() {
        Map<String, Object> response = new HashMap<>();

        try {
            log.info("Starting daily data ingestion - MANUAL trigger");
            dataIngestionService.ingestDailyData(ExecutionMode.MANUAL);

            response.put("success", true);
            response.put("message", "Daily ingestion completed");
        } catch (Exception e) {
            log.error("Daily ingestion failed: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Error: " + e.getMessage());
        }

        return response;
    }

    @PostMapping("/scan/execute")
    @ResponseBody
    public Map<String, Object> executeScan() {
        Map<String, Object> response = new HashMap<>();

        try {
            log.info("Starting scanner execution - MANUAL trigger");
            scannerEngine.executeDailyScan();

            response.put("success", true);
            response.put("message", "Scan completed successfully");
        } catch (Exception e) {
            log.error("Scan execution failed: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Error: " + e.getMessage());
        }

        return response;
    }

    @GetMapping("/status")
    @ResponseBody
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        LocalDate today = config.getTodayInExchangeZone();

        status.put("tradingDate", today.toString());
        status.put("exchangeTimezone", config.getExchangeZone().toString());
        status.put("canIngest", executionStateService.canIngestToday());
        status.put("canScan", executionStateService.canScanToday());
        status.put("universeSize", universeRepository.findByIsActiveTrue().size());
        status.put("totalPrices", priceRepository.countAll());
        status.put("totalSignals", resultRepository.count());

        return status;
    }

    @GetMapping("/health")
    @ResponseBody
    public Map<String, String> health() {
        Map<String, String> health = new HashMap<>();
        health.put("status", "UP");
        health.put("version", appInfo.getVersion());
        return health;
    }
}
