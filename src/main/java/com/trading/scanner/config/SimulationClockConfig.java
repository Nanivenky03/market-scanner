package com.trading.scanner.config;

import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.config.simulation.SimulationProperties;
import com.trading.scanner.model.SimulationState;
import com.trading.scanner.repository.SimulationStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
@EnableConfigurationProperties(SimulationProperties.class)
public class SimulationClockConfig {
    
    private final SimulationStateRepository simulationStateRepository;
    private final TradingCalendar tradingCalendar;
    private final SimulationProperties simulationProperties;
    
    @Value("${exchange.timezone:Asia/Kolkata}")
    private String timezoneId;
    
    @Bean
    public ExchangeClock exchangeClock() {
        ZoneId exchangeZone = ZoneId.of(timezoneId);

        // Get or create simulation state so ExchangeClock can read the persisted trading offset.
        SimulationState state = simulationStateRepository.findById(1)
            .orElseGet(() -> {
                SimulationState newState = new SimulationState();
                newState.setId(1);
                newState.setBaseDate(simulationProperties.getBaseDate());
                newState.setTradingOffset(0);
                SimulationState saved = simulationStateRepository.save(newState);
                log.info("Initialized simulation state: baseDate={}, tradingOffset=0", simulationProperties.getBaseDate());
                return saved;
            });

        // Validate that the base date is a valid trading day
        if (!tradingCalendar.isTradingDay(state.getBaseDate())) {
            throw new IllegalStateException(String.format(
                "Simulation baseDate %s is not a trading day. Fix configuration or database before starting.",
                state.getBaseDate()
            ));
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
