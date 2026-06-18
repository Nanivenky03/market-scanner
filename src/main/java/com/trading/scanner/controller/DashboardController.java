package com.trading.scanner.controller;

import com.trading.scanner.config.AppInfo;
import com.trading.scanner.config.ExchangeConfiguration;
import com.trading.scanner.repository.ScanResultRepository;
import com.trading.scanner.repository.StockPriceRepository;
import com.trading.scanner.repository.StockUniverseRepository;
import com.trading.scanner.service.state.ExecutionStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Controller
@Profile("dashboard")
@RequiredArgsConstructor
public class DashboardController {

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
