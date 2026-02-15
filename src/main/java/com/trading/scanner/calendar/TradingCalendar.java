package com.trading.scanner.calendar;

import java.time.LocalDate;

/**
 * An interface for querying trading day information and performing calendar-aware date arithmetic.
 */
public interface TradingCalendar {

    /**
     * Gets the type of session for a specific date.
     *
     * @param date the date to check.
     * @return the {@link SessionType} for that date.
     */
    SessionType getSession(LocalDate date);

    /**
     * Checks if a given date is a trading day (either a regular or special session).
     *
     * @param date the date to check.
     * @return true if it is a trading day, false otherwise.
     */
    boolean isTradingDay(LocalDate date);

    /**
     * Finds the next trading day after the given date.
     *
     * @param date the date from which to start searching.
     * @return the first trading day strictly after the given date.
     */
    LocalDate nextTradingDay(LocalDate date);

    /**
     * Finds the previous trading day before the given date.
     *
     * @param date the date from which to start searching.
     * @return the first trading day strictly before the given date.
     */
    LocalDate previousTradingDay(LocalDate date);

    /**
     * Adds or subtracts a number of trading days from a given date.
     *
     * @param date the starting date.
     * @param days the number of trading days to add (if positive) or subtract (if negative).
     * @return the resulting date after the addition or subtraction.
     * @throws IllegalArgumentException if days is zero and the given date is not a trading day.
     */
    LocalDate addTradingDays(LocalDate date, int days);

    /**
     * Counts the number of trading days between two dates.
     * The count is exclusive of the start date and inclusive of the end date.
     *
     * @param startExclusive the start date (exclusive).
     * @param endInclusive the end date (inclusive).
     * @return the number of trading days. Returns a negative number if the end date is before the start date.
     */
    int tradingDaysBetween(LocalDate startExclusive, LocalDate endInclusive);
}
