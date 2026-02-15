package com.trading.scanner.config;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * An abstraction for time-related operations to ensure the application
 * uses a single, authoritative source of time, supporting both
 * production (system clock) and simulation (controlled clock) modes.
 */
public interface TimeProvider {
    /**
     * Gets the current date.
     * @return The current {@link LocalDate}.
     */
    LocalDate today();

    /**
     * Gets the current date and time.
     * @return The current {@link LocalDateTime}.
     */
    LocalDateTime nowDateTime();
}
