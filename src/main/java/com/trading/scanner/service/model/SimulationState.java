package com.trading.scanner.model;

import com.trading.scanner.config.LocalDateConverter;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Simulation State Entity
 * 
 * Persists simulation timeline state across app restarts
 * 
 * Single row table (id = 1)
 * 
 * Simulation date = baseDate + offsetDays
 */
@Entity
@Table(name = "simulation_state")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimulationState {
    
    @Id
    private Integer id; // Always 1 (single row table)
    
    @Column(name = "base_date", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = LocalDateConverter.class)
    private LocalDate baseDate;
    
    @Column(name = "offset_days", nullable = false)
    private Integer offsetDays;
    
    /**
     * Get current simulated date
     */
    public LocalDate getCurrentDate() {
        return baseDate.plusDays(offsetDays);
    }
    
    /**
     * Advance simulation by one day (forward-only)
     */
    public void advanceDay() {
        this.offsetDays += 1;
    }
}
