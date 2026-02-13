package com.trading.scanner.service.scanner;

import com.trading.scanner.config.ExchangeConfiguration;
import com.trading.scanner.model.ScanResult;
import com.trading.scanner.model.ScannerRun;
import com.trading.scanner.model.StockPrice;
import com.trading.scanner.model.StockUniverse;
import com.trading.scanner.repository.ScanResultRepository;
import com.trading.scanner.repository.ScannerRunRepository;
import com.trading.scanner.repository.StockPriceRepository;
import com.trading.scanner.repository.StockUniverseRepository;
import com.trading.scanner.service.indicators.IndicatorBundle;
import com.trading.scanner.service.indicators.IndicatorService;
import com.trading.scanner.service.scanner.rules.ScannerRule;
import com.trading.scanner.service.state.ExecutionStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScannerEngine {
    
    private final List<ScannerRule> rules;
    private final StockPriceRepository priceRepository;
    private final StockUniverseRepository universeRepository;
    private final ScanResultRepository resultRepository;
    private final ScannerRunRepository runRepository;
    private final IndicatorService indicatorService;
    private final ExecutionStateService executionStateService;
    private final ExchangeConfiguration config;
    
    @Value("${scanner.version:1.2.0-PRODUCTION}")
    private String scannerVersion;
    
    @Transactional
    public void executeDailyScan() {
        LocalDate today = config.getTodayInExchangeZone();
        
        if (!executionStateService.canScanToday()) {
            log.info("Cannot scan for {} - either no data or already scanned", today);
            return;
        }
        
        executionStateService.startScanToday();
        
        log.info("========================================");
        log.info("Starting Daily Scan for {}", today);
        log.info("========================================");
        
        List<StockUniverse> activeStocks = universeRepository.findByIsActiveTrue();
        
        int scannedCount = 0;
        int flaggedCount = 0;
        List<ScanResult> results = new ArrayList<>();
        
        for (StockUniverse stock : activeStocks) {
            try {
                List<StockPrice> prices = priceRepository.findBySymbolOrderByDateAsc(stock.getSymbol());
                
                if (prices.isEmpty()) {
                    log.debug("No price data for {}, skipping", stock.getSymbol());
                    continue;
                }
                
                scannedCount++;
                
                IndicatorBundle indicators = indicatorService.calculateIndicators(prices);
                
                for (ScannerRule rule : rules) {
                    if (rule.matches(stock.getSymbol(), prices, indicators)) {
                        
                        Double confidence = rule.getConfidence(stock.getSymbol(), prices, indicators);
                        String metadata = rule.getMetadata(stock.getSymbol(), prices, indicators);
                        
                        ScanResult result = ScanResult.builder()
                            .symbol(stock.getSymbol())
                            .scanDate(today)
                            .ruleName(rule.getRuleName())
                            .confidence(confidence)
                            .scannerVersion(scannerVersion)
                            .metadata(metadata)
                            .build();
                        
                        results.add(result);
                        flaggedCount++;
                        
                        log.info("SIGNAL: {} matched rule '{}' with confidence {:.2f}", 
                            stock.getSymbol(), rule.getRuleName(), confidence);
                    }
                }
                
            } catch (Exception e) {
                log.error("Error scanning {}: {}", stock.getSymbol(), e.getMessage());
            }
        }
        
        if (!results.isEmpty()) {
            resultRepository.saveAll(results);
        }
        
        ScannerRun run = ScannerRun.builder()
            .runDate(today)
            .stocksScanned(scannedCount)
            .stocksFlagged(flaggedCount)
            .status("SUCCESS")
            .build();
        
        runRepository.save(run);
        
        executionStateService.completeScanToday(flaggedCount);
        
        log.info("========================================");
        log.info("Scan Complete: {} stocks scanned, {} flagged", scannedCount, flaggedCount);
        log.info("========================================");
    }
}
