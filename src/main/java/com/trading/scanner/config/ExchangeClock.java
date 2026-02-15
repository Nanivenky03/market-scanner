package com.trading.scanner.config;

import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.model.SimulationState;
import com.trading.scanner.repository.SimulationStateRepository;
import java.time.*;

/**
 * Central Time Authority for the Scanner System. This is the ONLY source of "now" in the system.
 * Production: Uses system clock.
 * Simulation: Dynamically calculates the date from the database, removing the need for app restarts.
 */
public class ExchangeClock implements TimeProvider {

    private static final LocalTime SIMULATION_TIME = LocalTime.of(9, 15);

    private final ZoneId exchangeZone;
    private final boolean simulationMode;

    // Production-only fields
    private final Clock systemClock;

    // Simulation-only fields
    private final SimulationStateRepository simulationStateRepository;
    private final TradingCalendar tradingCalendar;
    private volatile Integer cachedTradingOffset;
    private volatile LocalDate cachedToday;

    /**
     * Production constructor.
     * @param systemClock The system clock (e.g., Clock.systemUTC()).
     * @param exchangeZone The ZoneId of the exchange.
     */
    public ExchangeClock(Clock systemClock, ZoneId exchangeZone) {
        this.systemClock = systemClock;
        this.exchangeZone = exchangeZone;
        this.simulationMode = false;
        this.simulationStateRepository = null;
        this.tradingCalendar = null;
    }

    /**
     * Simulation constructor.
     * @param exchangeZone The ZoneId of the exchange.
     * @param repository The repository to fetch the current simulation state.
     * @param tradingCalendar The calendar to calculate trading day offsets.
     */
    public ExchangeClock(ZoneId exchangeZone, SimulationStateRepository repository, TradingCalendar tradingCalendar) {
        this.exchangeZone = exchangeZone;
        this.simulationStateRepository = repository;
        this.tradingCalendar = tradingCalendar;
        this.simulationMode = true;
        this.systemClock = null;
    }

    /**
     * Gets today's date according to the exchange's timezone.
     * In simulation mode, this uses a lightweight, explicit cache to avoid DB reads on every call.
     * The cache is invalidated when the simulation date is advanced.
     * In production mode, this is based on the system clock.
     *
     * @return The current {@link LocalDate}.
     */
    public LocalDate today() {
        if (simulationMode) {
            // If cache is valid, return immediately without hitting the database
            if (cachedTradingOffset != null && cachedToday != null) {
                return cachedToday;
            }

            // Cache is empty/invalid, so fetch state from DB and populate cache
            SimulationState state = simulationStateRepository.findById(1)
                .orElseThrow(() -> new IllegalStateException("Simulation state not found in database."));
            
            this.cachedToday = tradingCalendar.addTradingDays(
                state.getBaseDate(),
                state.getTradingOffset()
            );
            this.cachedTradingOffset = state.getTradingOffset();
            
            return this.cachedToday;
        } else {
            return LocalDate.now(systemClock.withZone(exchangeZone));
        }
    }

    /**
     * Gets the current date and time in the exchange's timezone.
     * In simulation mode, this returns the simulated date at a fixed time (09:15).
     * In production mode, this is based on the system clock.
     * @return The current {@link LocalDateTime}.
     */
    public LocalDateTime nowDateTime() {
        if (simulationMode) {
            return today().atTime(SIMULATION_TIME);
        } else {
            return LocalDateTime.now(systemClock.withZone(exchangeZone));
        }
    }

    /**
     * Gets the configured exchange timezone.
     * @return The exchange {@link ZoneId}.
     */
    public ZoneId getExchangeZone() {
        return exchangeZone;
    }

    /**
     * Invalidates the simulation date cache. This must be called after any
     * state change in simulation mode (e.g., advance-day, reset).
     */
    public void invalidateSimulationCache() {
        this.cachedTradingOffset = null;
        this.cachedToday = null;
    }
}
