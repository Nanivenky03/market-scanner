package com.trading.scanner.service.scanner;

import com.trading.scanner.model.ScanResult;
import com.trading.scanner.model.ScannerRun;
import com.trading.scanner.model.StockPrice;
import com.trading.scanner.model.StockUniverse;
import com.trading.scanner.repository.ScanResultRepository;
import com.trading.scanner.repository.ScannerRunRepository;
import com.trading.scanner.repository.StockPriceRepository;
import com.trading.scanner.repository.StockUniverseRepository;
import com.trading.scanner.service.data.DataQualityService;
import com.trading.scanner.service.indicators.IndicatorBundle;
import com.trading.scanner.service.indicators.IndicatorService;
import com.trading.scanner.service.scanner.rules.ScannerRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yaml.snakeyaml.Yaml;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Scanner Engine - Orchestration Only
 * 
 * ARCHITECTURE PRINCIPLE: This class does NOT contain business logic.
 * It orchestrates the pipeline:
 * 
 * MarketDataLoader → IndicatorService → RuleEngine → SignalStore
 * 
 * Responsibilities:
 * 1. Load price data
 * 2. Normalize ordering (CRITICAL INVARIANT: oldest → newest)
 * 3. Validate data quality
 * 4. Compute indicators (delegates to IndicatorService)
 * 5. Evaluate rules (delegates to ScannerRule implementations)
 * 6. Store results
 * 
 * This design enables future backtesting without refactoring.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScannerEngine {
    
    private final StockUniverseRepository universeRepository;
    private final StockPriceRepository priceRepository;
    private final ScanResultRepository scanResultRepository;
    private final ScannerRunRepository scannerRunRepository;
    private final IndicatorService indicatorService;
    private final DataQualityService dataQualityService;
    private final List<ScannerRule> rules;
    
    @Value("${scanner.config.file:classpath:scanner-config.yaml}")
    private String configFile;
    
    private Map<String, Object> config;
    
    @PostConstruct
    public void loadConfig() {
        try (InputStream is = getClass().getClassLoader()
                .getResourceAsStream("scanner-config.yaml")) {
            
            if (is == null) {
                log.error("Scanner config file not found");
                return;
            }
            
            Yaml yaml = new Yaml();
            config = yaml.load(is);
            log.info("Scanner configuration loaded successfully");
            
        } catch (Exception e) {
            log.error("Failed to load scanner configuration: {}", e.getMessage());
        }
    }
    
    /**
     * Execute complete scan of all active stocks.
     * 
     * Pipeline execution:
     * 1. Load universe
     * 2. For each stock:
     *    - Load & normalize price data
     *    - Validate data quality
     *    - Compute indicators
     *    - Evaluate rules
     * 3. Store all results
     * 4. Log execution
     */
    @Transactional
    public void executeDailyScan() {
        long startTime = System.currentTimeMillis();
        
        log.info("========================================");
        log.info("Starting Daily Scanner Run");
        log.info("========================================");
        
        List<StockUniverse> activeStocks = universeRepository.findByIsActiveTrue();
        log.info("Active stocks in universe: {}", activeStocks.size());
        
        if (activeStocks.isEmpty()) {
            log.warn("No active stocks found in universe");
            return;
        }
        
        int stocksScanned = 0;
        int stocksFlagged = 0;
        int stocksSkipped = 0;
        List<String> errors = new ArrayList<>();
        List<ScanResult> allResults = new ArrayList<>();
        
        for (StockUniverse stock : activeStocks) {
            try {
                List<ScanResult> results = scanStock(stock.getSymbol());
                
                if (results == null) {
                    stocksSkipped++;
                    continue;  // Data quality issue
                }
                
                if (!results.isEmpty()) {
                    allResults.addAll(results);
                    stocksFlagged++;
                }
                
                stocksScanned++;
                
            } catch (Exception e) {
                log.error("Error scanning {}: {}", stock.getSymbol(), e.getMessage(), e);
                errors.add(stock.getSymbol() + ": " + e.getMessage());
                stocksSkipped++;
            }
        }
        
        // Save all results
        if (!allResults.isEmpty()) {
            scanResultRepository.saveAll(allResults);
            log.info("Saved {} scan results to database", allResults.size());
        }
        
        // Log scanner run
        long executionTime = System.currentTimeMillis() - startTime;
        
        ScannerRun run = ScannerRun.builder()
            .runDate(LocalDate.now())
            .status(errors.isEmpty() ? "SUCCESS" : "PARTIAL")
            .stocksScanned(stocksScanned)
            .stocksFlagged(stocksFlagged)
            .errors(errors.isEmpty() ? null : String.join("; ", errors))
            .executionTimeMs(executionTime)
            .build();
        
        scannerRunRepository.save(run);
        
        log.info("========================================");
        log.info("Scanner Run Complete");
        log.info("Stocks Scanned: {}", stocksScanned);
        log.info("Stocks Flagged: {}", stocksFlagged);
        log.info("Stocks Skipped: {}", stocksSkipped);
        log.info("Execution Time: {}ms", executionTime);
        log.info("========================================");
        
        // Log results summary
        logResultsSummary(allResults);
    }
    
    /**
     * Scan a single stock through the pipeline.
     * 
     * @return List of ScanResults, or null if data quality issues
     */
    private List<ScanResult> scanStock(String symbol) {
        List<ScanResult> results = new ArrayList<>();
        
        // ========================================
        // STEP 1: Load Price Data
        // ========================================
        List<StockPrice> prices = priceRepository.findBySymbolOrderByDateDesc(symbol);
        
        if (prices.size() < 250) {
            log.debug("Insufficient data for {}: only {} days", symbol, prices.size());
            return null;
        }
        
        // ========================================
        // STEP 2: CRITICAL - Normalize Price Ordering
        // System Invariant: oldest → newest
        // ========================================
        Collections.reverse(prices);  // DESC → ASC (oldest → newest)
        
        // ========================================
        // STEP 3: Data Quality Validation
        // ========================================
        DataQualityService.ValidationReport validation = 
            dataQualityService.validate(prices);
        
        if (!validation.isValid()) {
            log.warn("Data quality issues for {}: {}", symbol, validation.getErrors());
            return null;  // Skip stocks with bad data
        }
        
        if (validation.hasWarnings()) {
            log.debug("Data quality warnings for {}: {}", symbol, validation.getWarnings());
        }
        
        // ========================================
        // STEP 4: Compute Indicators ONCE
        // ========================================
        IndicatorBundle indicators = indicatorService.computeIndicators(prices);
        
        if (indicators == null || !indicators.isValid()) {
            log.warn("Failed to compute indicators for {}", symbol);
            return null;
        }
        
        // ========================================
        // STEP 5: Evaluate Rules
        // ========================================
        Map<String, Object> scannerConfig = (Map<String, Object>) config.get("scanner");
        Map<String, Object> rulesConfig = (Map<String, Object>) scannerConfig.get("rules");
        
        String mode = (String) scannerConfig.get("mode");
        Map<String, Object> modeConfig = (Map<String, Object>) rulesConfig.get(mode);
        
        // Add gap filter config
        modeConfig.putIfAbsent("max_gap_percent", 5.0);
        
        for (ScannerRule rule : rules) {
            if (!rule.isEnabled()) {
                continue;
            }
            
            ScanResult result = rule.evaluate(prices, indicators, modeConfig);
            if (result != null) {
                results.add(result);
            }
        }
        
        return results;
    }
    
    /**
     * Log summary of scan results
     */
    private void logResultsSummary(List<ScanResult> results) {
        if (results.isEmpty()) {
            log.info("No signals detected in this scan");
            return;
        }
        
        log.info("\n" + "=".repeat(50));
        log.info("SCAN RESULTS SUMMARY");
        log.info("=".repeat(50));
        
        // Group by classification
        Map<String, List<ScanResult>> byClassification = new java.util.HashMap<>();
        for (ScanResult result : results) {
            byClassification.computeIfAbsent(result.getClassification(), k -> new ArrayList<>())
                .add(result);
        }
        
        for (Map.Entry<String, List<ScanResult>> entry : byClassification.entrySet()) {
            log.info("\n{}: {} signals", entry.getKey(), entry.getValue().size());
            
            // Group by confidence
            Map<String, Long> byConfidence = entry.getValue().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                    ScanResult::getConfidence,
                    java.util.stream.Collectors.counting()
                ));
            
            byConfidence.forEach((confidence, count) -> 
                log.info("  {} confidence: {}", confidence, count));
            
            // List symbols
            log.info("  Symbols:");
            entry.getValue().forEach(r -> 
                log.info("    {} ({})", r.getSymbol(), r.getConfidence()));
        }
        
        log.info("=".repeat(50) + "\n");
    }
    
    /**
     * Get today's scan results
     */
    public List<ScanResult> getTodaysResults() {
        return scanResultRepository.findByScanDateOrderByConfidenceDesc(LocalDate.now());
    }
}
