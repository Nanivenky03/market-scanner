package com.trading.scanner.config;

import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.model.SimulationState;
import com.trading.scanner.repository.SimulationStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.*;

/**
 * Simulation Clock Configuration
 * 
 * Active when: spring.profiles.active=simulation
 * 
 * Uses controllable clock for accelerated testing
 */
@Slf4j
@Configuration
@Profile("simulation")
@RequiredArgsConstructor
public class SimulationClockConfig {
    
    private final SimulationStateRepository simulationStateRepository;
    private final TradingCalendar tradingCalendar;
    
    @Value("${exchange.timezone:Asia/Kolkata}")
    private String timezoneId;
    
    @Value("${simulation.baseDate:2023-01-01}")
    private String baseDate;
    
    @Bean
    public ExchangeClock exchangeClock() {
        ZoneId exchangeZone = ZoneId.of(timezoneId);

        // Get or create simulation state. This is done here to ensure the state exists
        // and to perform the one-time migration if needed. The ExchangeClock will
        // then fetch this state dynamically when today() is called.
        SimulationState state = simulationStateRepository.findById(1)
            .orElseGet(() -> {
                SimulationState newState = new SimulationState();
                newState.setId(1);
                newState.setBaseDate(LocalDate.parse(baseDate));
                newState.setOffsetDays(0);
                newState.setTradingOffset(0);
                SimulationState saved = simulationStateRepository.save(newState);
                log.info("Initialized simulation state: baseDate={}, tradingOffset=0", baseDate);
                return saved;
            });

        // Validate that the base date is a valid trading day
        if (!tradingCalendar.isTradingDay(state.getBaseDate())) {
            throw new IllegalStateException(String.format(
                "Simulation baseDate %s is not a trading day. Fix configuration or database before starting.",
                state.getBaseDate()
            ));
        }

        // Recover from an interrupted simulation batch run
        if (state.isCycling()) {
            log.warn("Recovering from an interrupted simulation batch. Clearing 'isCycling' flag.");
            state.setCycling(false);
            state.setCyclingStartedAt(null);
            simulationStateRepository.save(state);
        }

        // One-time migration from calendar day offset to trading day offset
        if (state.getTradingOffset() == 0 && state.getOffsetDays() != 0) {
            LocalDate currentCalendarDate = state.getBaseDate().plusDays(state.getOffsetDays());
            int computedTradingOffset = tradingCalendar.tradingDaysBetween(
                state.getBaseDate(),
                currentCalendarDate
            );

            // Safety guard for migration logic
            if (computedTradingOffset < 0) {
                throw new IllegalStateException("Computed negative tradingOffset during migration. Manual intervention required.");
            }

            state.setTradingOffset(computedTradingOffset);
            simulationStateRepository.save(state);
            log.info("Migrated simulation offset: calendar day offset {} → trading day offset {}",
                state.getOffsetDays(), computedTradingOffset);
        }

        log.info("SIMULATION MODE: ExchangeClock configured for dynamic date calculation.");

        // Return the dynamic, simulation-aware ExchangeClock
        return new ExchangeClock(
            exchangeZone,
            simulationStateRepository,
            tradingCalendar
        );
    }
}
