package com.trading.scanner.calendar;

/**
 * Represents the type of a trading session for a given day.
 */
public enum SessionType {
    /** A regular trading day. */
    TRADING,
    /** A special, often shorter, trading session (e.g., Muhurat Trading). */
    SPECIAL_SESSION,
    /** A scheduled public holiday where the market is closed. */
    HOLIDAY,
    /** An unscheduled closure of the market (e.g., due to technical issues or national events). */
    UNEXPECTED_CLOSURE,
    /** A Saturday or Sunday. */
    WEEKEND;

    /**
     * Checks if the session type is one where trading occurs.
     *
     * @return true if the session is {@link #TRADING} or {@link #SPECIAL_SESSION}, false otherwise.
     */
    public boolean isTradingSession() {
        return this == TRADING || this == SPECIAL_SESSION;
    }
}
