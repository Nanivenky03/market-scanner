package com.trading.scanner.service;

import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.model.ScanResult;
import com.trading.scanner.model.SignalOutcome;
import com.trading.scanner.model.StockPrice;
import com.trading.scanner.repository.SignalOutcomeRepository;
import com.trading.scanner.repository.StockPriceRepository;
import com.trading.scanner.repository.ScanResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.List;

/**
 * Forward Return Engine - Deterministic outcome computation
 * 
 * Computes forward returns for fixed horizons {5, 10, 20}
 * Append-only, idempotent, transaction-bound
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ForwardReturnEngine {
    
    private final ScanResultRepository scanResultRepository;
    private final SignalOutcomeRepository signalOutcomeRepository;
    private final StockPriceRepository stockPriceRepository;
    private final TradingCalendar tradingCalendar;
    
    // Hardcoded horizons for v1.9
    private static final int[] HORIZONS = {5, 10, 20};
    
    /**
     * Compute eligible outcomes for current simulation date
     * Must be called within simulation transaction
     */
    public void computeEligibleOutcomes(LocalDate currentSimulationDate) {
        log.debug("Computing forward outcomes for simulation date: {}", currentSimulationDate);
        
        for (int horizon : HORIZONS) {
            computeOutcomesForHorizon(currentSimulationDate, horizon);
        }
    }
    
    private void computeOutcomesForHorizon(LocalDate currentDate, int horizon) {
        // Calculate cutoff date: signals where scan_date <= currentDate - horizon
        LocalDate cutoffDate = tradingCalendar.addTradingDays(currentDate, -horizon);
        
        // Find signals eligible for this horizon
        List<Integer> eligibleSignalIds = signalOutcomeRepository.findEligibleSignalIds(horizon, cutoffDate);
        
        if (eligibleSignalIds.isEmpty()) {
            log.trace("No eligible signals for horizon {} on date {}", horizon, currentDate);
            return;
        }
        
        log.debug("Processing {} eligible signals for horizon {} (cutoff: {})", 
                 eligibleSignalIds.size(), horizon, cutoffDate);
        
        for (Integer signalId : eligibleSignalIds) {
            try {
                computeOutcome(signalId, horizon, currentDate);
            } catch (Exception e) {
                log.error("Failed to compute outcome for signal {} horizon {}", signalId, horizon, e);
                // Continue processing other signals - don't fail entire batch
            }
        }
    }
    
    private void computeOutcome(Integer signalId, int horizon, LocalDate currentDate) {
        // Load signal (ScanResult is the signal entity)
        ScanResult signal = scanResultRepository.findById(signalId)
            .orElseThrow(() -> new IllegalStateException("Signal not found: " + signalId));
        
        // Entry price = close price on signal date
        StockPrice entryPrice = stockPriceRepository
            .findBySymbolAndDate(signal.getSymbol(), signal.getScanDate())
            .orElse(null);
        
        if (entryPrice == null || entryPrice.getClosePrice() == null) {
            log.trace("Missing entry price for signal {} on {}", signalId, signal.getScanDate());
            return;
        }
        
        // Exit date = signal date + horizon trading days
        LocalDate exitDate = tradingCalendar.addTradingDays(signal.getScanDate(), horizon);
        
        // Exit price = close price on exit date
        StockPrice exitPrice = stockPriceRepository
            .findBySymbolAndDate(signal.getSymbol(), exitDate)
            .orElse(null);
        
        if (entryPrice == null || exitPrice == null) {
            return;
        }
        
        if (exitPrice.getClosePrice() == null) {
            log.trace("Missing exit price for signal {} on {}", signalId, exitDate);
            return;
        }
        
        // Compute forward return
        Double entry = entryPrice.getClosePrice();
        Double exit = exitPrice.getClosePrice();
        Double forwardReturn = (exit - entry) / entry;
        
        // Create outcome record
        SignalOutcome outcome = SignalOutcome.builder()
            .signalId(signalId)
            .horizonDays(horizon)
            .entryPrice(entry)
            .exitPrice(exit)
            .forwardReturn(forwardReturn)
            .computedAt(currentDate.atStartOfDay())
            .build();
        
        // Save - unique constraint prevents duplicates
        signalOutcomeRepository.save(outcome);
        
        log.trace("Computed outcome for signal {} horizon {}: return={}", 
                 signalId, horizon, forwardReturn);
    }
}
