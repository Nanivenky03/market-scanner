package com.trading.scanner.config;

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
    
    @Value("${exchange.timezone:Asia/Kolkata}")
    private String timezoneId;
    
    @Value("${simulation.baseDate:2023-01-01}")
    private String baseDate;
    
    @Bean
    public ExchangeClock exchangeClock() {
        ZoneId exchangeZone = ZoneId.of(timezoneId);
        
        // Get or create simulation state
        SimulationState state = simulationStateRepository.findById(1)
            .orElseGet(() -> {
                SimulationState newState = new SimulationState();
                newState.setId(1);
                newState.setBaseDate(LocalDate.parse(baseDate));
                newState.setOffsetDays(0);
                SimulationState saved = simulationStateRepository.save(newState);
                log.info("Initialized simulation state: baseDate={}, offsetDays=0", baseDate);
                return saved;
            });
        
        // Create fixed clock at current simulation date
        LocalDate simulatedDate = state.getBaseDate().plusDays(state.getOffsetDays());
        LocalDateTime simulatedDateTime = simulatedDate.atTime(9, 15); // Market open time
        ZonedDateTime zonedDateTime = simulatedDateTime.atZone(exchangeZone);
        Instant fixedInstant = zonedDateTime.toInstant();
        
        Clock fixedClock = Clock.fixed(fixedInstant, ZoneId.of("UTC"));
        
        log.info("SIMULATION MODE: Using fixed clock at {}", simulatedDate);
        log.info("SIMULATION MODE: Base date: {}, Offset: {} days", state.getBaseDate(), state.getOffsetDays());
        
        return new ExchangeClock(fixedClock, exchangeZone);
    }
}
