package com.trading.scanner.service.scanner;

import com.trading.scanner.config.BreakoutRuleProperties;
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
import com.trading.scanner.service.indicators.parameters.IndicatorParameters;
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
    // NOTE: This creates a temporary coupling. A future refactor might introduce a
    // parameter resolution service if more rules with different properties are added.
    private final BreakoutRuleProperties breakoutRuleProperties;
    
    @Value("${app.version}")
    private String scannerVersion;
    
    @Transactional
    public void executeDailyScan() {
        LocalDate today = config.getTodayInExchangeZone();
        executeScanLogic(today);
    }

    /**
     * Executes a scan for a specific date. This is intended for simulation use.
     * It is NOT transactional and expects the caller to manage the transaction.
     * @param scanDate The date to run the scan for.
     */
    public void executeScanForDate(LocalDate scanDate) {
        executeScanLogic(scanDate);
    }

    private void executeScanLogic(LocalDate scanDate) {
        if (!executionStateService.canScanForDate(scanDate)) {
            log.info("Cannot scan for {} - either no data or already scanned", scanDate);
            return;
        }

        executionStateService.startScanForDate(scanDate);

        log.info("========================================");
        log.info("Starting Scan for {}", scanDate);
        log.info("========================================");

        List<StockUniverse> activeStocks = universeRepository.findByIsActiveTrue();

        log.info("DEBUG_SCAN_SETUP rulesCount={} activeStocks={}", rules.size(), activeStocks.size());

        int scannedCount = 0;
        int flaggedCount = 0;
        List<ScanResult> results = new ArrayList<>();

        IndicatorParameters indicatorParameters = new IndicatorParameters(
            breakoutRuleProperties.rsiPeriod(),
            breakoutRuleProperties.smaShortPeriod(),
            breakoutRuleProperties.smaMediumPeriod(),
            breakoutRuleProperties.smaLongPeriod()
        );

        for (StockUniverse stock : activeStocks) {
            try {
                // Fetch prices up to and including the scan date
                List<StockPrice> prices = priceRepository.findBySymbolAndDateLessThanEqualOrderByDateAsc(stock.getSymbol(), scanDate);

                if (prices.isEmpty()) {
                    log.debug("No price data for {}, skipping", stock.getSymbol());
                    continue;
                }

                scannedCount++;

                log.info("DEBUG_EVAL symbol={} priceCount={}", stock.getSymbol(), prices.size());

                String firstDate = prices.get(0).getDate() != null ?
                    prices.get(0).getDate().toString() : "null";
                String lastDate = prices.get(prices.size() - 1).getDate() != null ?
                    prices.get(prices.size() - 1).getDate().toString() : "null";
                log.info("DATE_DEBUG symbol={} firstDate={} lastDate={} size={}",
                    stock.getSymbol(), firstDate, lastDate, prices.size());

                IndicatorBundle indicators = indicatorService.calculateIndicators(prices, indicatorParameters);

                log.info("DEBUG_INDICATORS symbol={} size={} hasRsi={} hasSma20={} hasAvgVol={}",
                    stock.getSymbol(), prices.size(), indicators.hasRsi(), indicators.hasSma20(), indicators.hasAvgVolume());

                for (ScannerRule rule : rules) {
                    boolean ruleMatches = rule.matches(stock.getSymbol(), prices, indicators);
                    if (ruleMatches) {
                        log.info("DEBUG_RULE_MATCHED symbol={} rule={}", stock.getSymbol(), rule.getRuleName());

                        Double confidence = rule.getConfidence(stock.getSymbol(), prices, indicators);
                        String metadata = rule.getMetadata(stock.getSymbol(), prices, indicators);

                        ScanResult result = ScanResult.builder()
                            .symbol(stock.getSymbol())
                            .scanDate(scanDate)
                            .ruleName(rule.getRuleName())
                            .ruleVersion(rule.getRuleVersion())
                            .parameterSnapshot(rule.getParameterSnapshot())
                            .confidence(confidence)
                            .scannerVersion(scannerVersion)
                            .metadata(metadata)
                            .build();

                        results.add(result);
                        flaggedCount++;

                        log.info("SIGNAL: {} matched rule '{}' with confidence {:.2f}",
                            stock.getSymbol(), rule.getRuleName(), confidence);
                    } else {
                        log.debug("DEBUG_NO_MATCH symbol={} rule={}", stock.getSymbol(), rule.getRuleName());
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
            .runDate(scanDate)
            .stocksScanned(scannedCount)
            .stocksFlagged(flaggedCount)
            .status("SUCCESS")
            .build();

        runRepository.save(run);

        executionStateService.completeScanForDate(scanDate, flaggedCount);

        log.info("========================================");
        log.info("Scan Complete: {} stocks scanned, {} flagged", scannedCount, flaggedCount);
        log.info("========================================");
    }
}
