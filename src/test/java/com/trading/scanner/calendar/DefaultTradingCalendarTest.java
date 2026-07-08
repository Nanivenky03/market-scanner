package com.trading.scanner.calendar;

import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.repository.EmergencyClosureRepository;
import com.trading.scanner.repository.ExchangeHolidayRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultTradingCalendarTest {

    @Mock
    private EmergencyClosureRepository emergencyClosureRepository;

    @Mock
    private ExchangeHolidayRepository exchangeHolidayRepository;

    @Mock
    private TimeProvider timeProvider;

    private TradingCalendar tradingCalendar;

    @BeforeEach
    void setUp() {
        NseHolidayCalendar holidayCalendar = new NseHolidayCalendar(
                emergencyClosureRepository,
                exchangeHolidayRepository,
                timeProvider);
        tradingCalendar = new DefaultTradingCalendar(holidayCalendar);
    }

    @Test
    void isTradingDay_shouldReturnFalseForWeekend() {
        LocalDate saturday = LocalDate.of(2026, 7, 4);

        boolean tradingDay = tradingCalendar.isTradingDay(saturday);

        assertFalse(tradingDay);
    }

    @Test
    void isTradingDay_shouldReturnFalseForKnownHoliday() {
        LocalDate republicDay = LocalDate.of(2026, 1, 26);

        when(exchangeHolidayRepository.existsByExchangeAndTradingDate("NSE", republicDay)).thenReturn(true);

        boolean tradingDay = tradingCalendar.isTradingDay(republicDay);

        assertFalse(tradingDay);
    }

    @Test
    void isTradingDay_shouldReturnFalseForEmergencyClosure() {
        LocalDate tradingDay = LocalDate.of(2026, 7, 1);

        when(emergencyClosureRepository.existsByDate(tradingDay)).thenReturn(true);

        boolean result = tradingCalendar.isTradingDay(tradingDay);

        assertFalse(result);
    }

    @Test
    void isTradingDay_shouldReturnTrueForNormalWeekday() {
        LocalDate normalDay = LocalDate.of(2026, 7, 1);

        when(exchangeHolidayRepository.existsByExchangeAndTradingDate("NSE", normalDay)).thenReturn(false);
        when(emergencyClosureRepository.existsByDate(normalDay)).thenReturn(false);

        boolean result = tradingCalendar.isTradingDay(normalDay);

        assertTrue(result);
    }

    @Test
    void nextTradingDay_shouldSkipWeekendAndHoliday() {
        LocalDate friday = LocalDate.of(2026, 1, 23);
        LocalDate republicDay = LocalDate.of(2026, 1, 26);

        when(exchangeHolidayRepository.existsByExchangeAndTradingDate(eq("NSE"), any(LocalDate.class)))
                .thenReturn(false);
        when(exchangeHolidayRepository.existsByExchangeAndTradingDate("NSE", republicDay)).thenReturn(true);

        LocalDate nextTradingDay = tradingCalendar.nextTradingDay(friday);

        assertEquals(LocalDate.of(2026, 1, 27), nextTradingDay);
    }

    @Test
    void previousTradingDay_shouldSkipWeekendAndHoliday() {
        LocalDate tuesday = LocalDate.of(2026, 1, 27);
        LocalDate republicDay = LocalDate.of(2026, 1, 26);

        when(exchangeHolidayRepository.existsByExchangeAndTradingDate(eq("NSE"), any(LocalDate.class)))
                .thenReturn(false);
        when(exchangeHolidayRepository.existsByExchangeAndTradingDate("NSE", republicDay)).thenReturn(true);

        LocalDate previousTradingDay = tradingCalendar.previousTradingDay(tuesday);

        assertEquals(LocalDate.of(2026, 1, 23), previousTradingDay);
    }

    @Test
    void addTradingDays_shouldMoveAcrossWeekendAndHoliday() {
        LocalDate friday = LocalDate.of(2026, 1, 23);
        LocalDate republicDay = LocalDate.of(2026, 1, 26);

        when(exchangeHolidayRepository.existsByExchangeAndTradingDate(eq("NSE"), any(LocalDate.class)))
                .thenReturn(false);
        when(exchangeHolidayRepository.existsByExchangeAndTradingDate("NSE", republicDay)).thenReturn(true);

        LocalDate result = tradingCalendar.addTradingDays(friday, 1);

        assertEquals(LocalDate.of(2026, 1, 27), result);
    }
}