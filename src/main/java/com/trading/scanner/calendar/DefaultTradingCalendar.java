package com.trading.scanner.calendar;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Default implementation of the {@link TradingCalendar}.
 * It uses a provided holiday calendar to determine trading days and perform date arithmetic.
 */
public class DefaultTradingCalendar implements TradingCalendar {

    private final NseHolidayCalendar holidayCalendar;

    public DefaultTradingCalendar(NseHolidayCalendar holidayCalendar) {
        this.holidayCalendar = holidayCalendar;
    }

    @Override
    public SessionType getSession(LocalDate date) {
        if (holidayCalendar.isEmergencyClosure(date)) {
            return SessionType.UNEXPECTED_CLOSURE;
        }
        if (holidayCalendar.isSpecialSession(date)) {
            return SessionType.SPECIAL_SESSION;
        }
        if (holidayCalendar.isHoliday(date)) {
            return SessionType.HOLIDAY;
        }

        DayOfWeek day = date.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return SessionType.WEEKEND;
        }

        return SessionType.TRADING;
    }

    @Override
    public boolean isTradingDay(LocalDate date) {
        return getSession(date).isTradingSession();
    }

    @Override
    public LocalDate nextTradingDay(LocalDate date) {
        LocalDate nextDay = date.plusDays(1);
        // Safety break after 10 years to prevent infinite loops.
        LocalDate limit = date.plusYears(10);
        while (nextDay.isBefore(limit)) {
            if (isTradingDay(nextDay)) {
                return nextDay;
            }
            nextDay = nextDay.plusDays(1);
        }
        throw new IllegalStateException("Could not find next trading day within 10 years for date: " + date);
    }

    @Override
    public LocalDate previousTradingDay(LocalDate date) {
        LocalDate prevDay = date.minusDays(1);
        // Safety break after 10 years to prevent infinite loops.
        LocalDate limit = date.minusYears(10);
        while (prevDay.isAfter(limit)) {
            if (isTradingDay(prevDay)) {
                return prevDay;
            }
            prevDay = prevDay.minusDays(1);
        }
        throw new IllegalStateException("Could not find previous trading day within 10 years for date: " + date);
    }

    @Override
    public LocalDate addTradingDays(LocalDate date, int days) {
        if (days == 0) {
            if (!isTradingDay(date)) {
                throw new IllegalArgumentException("Cannot add zero days to a non-trading day: " + date);
            }
            return date;
        }

        // Hard limit to avoid excessively long calculations
        if (Math.abs(days) > 252 * 100) { // Approx 100 years of trading days
            throw new IllegalArgumentException("Cannot add/subtract more than 100 years of trading days.");
        }

        LocalDate result = date;
        if (days > 0) {
            for (int i = 0; i < days; i++) {
                result = nextTradingDay(result);
            }
        } else {
            for (int i = 0; i < Math.abs(days); i++) {
                result = previousTradingDay(result);
            }
        }
        return result;
    }

    @Override
    public int tradingDaysBetween(LocalDate startExclusive, LocalDate endInclusive) {
        if (startExclusive.isEqual(endInclusive)) {
            return 0;
        }

        // Hard limit to prevent performance issues with very large date ranges
        long dayDiff = ChronoUnit.DAYS.between(startExclusive, endInclusive);
        if (Math.abs(dayDiff) > 365 * 100) { // 100 years
            throw new IllegalArgumentException("Date range for tradingDaysBetween cannot exceed 100 years.");
        }

        if (endInclusive.isBefore(startExclusive)) {
            return -tradingDaysBetween(endInclusive, startExclusive);
        }

        int tradingDays = 0;
        LocalDate currentDate = startExclusive.plusDays(1);
        while (!currentDate.isAfter(endInclusive)) {
            if (isTradingDay(currentDate)) {
                tradingDays++;
            }
            currentDate = currentDate.plusDays(1);
        }
        return tradingDays;
    }
}
