package com.trading.scanner.model;

import com.trading.scanner.config.LocalDateConverter;
import com.trading.scanner.config.LocalDateTimeConverter;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Simulation State Entity
 * 
 * Persists simulation timeline state across app restarts
 * 
 * Single row table (id = 1)
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

    @Version
    private Integer version;
    
    @Column(name = "base_date", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = LocalDateConverter.class)
    private LocalDate baseDate;
    
    /**
     * @deprecated replaced by tradingOffset in v1.5 (trading-day aware simulation).
     */
    @Deprecated
    @Column(name = "offset_days", nullable = false)
    private Integer offsetDays;

    @Column(name = "trading_offset", nullable = false)
    private Integer tradingOffset;

    @Column(name = "is_cycling", nullable = false)
    private boolean isCycling = false;

    @Column(name = "cycling_started_at", columnDefinition = "TEXT")
    @Convert(converter = LocalDateTimeConverter.class)
    private LocalDateTime cyclingStartedAt;
    
    /**
     * This method is deprecated. The simulation date is now resolved dynamically.
     * @throws UnsupportedOperationException always
     * @deprecated Use {@link com.trading.scanner.config.ExchangeClock#today()} for simulation date resolution.
     */
    @Deprecated
    public LocalDate getCurrentDate() {
        throw new UnsupportedOperationException("Use ExchangeClock.today() for simulation date resolution.");
    }
    
    /**
     * Advances the simulation by one TRADING day (forward-only).
     */
    public void advanceDay() {
        this.tradingOffset += 1;
    }
}
