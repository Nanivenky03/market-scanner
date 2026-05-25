package com.trading.scanner.calendar;

import com.trading.scanner.repository.EmergencyClosureRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultTradingCalendarTest {

    @Mock
    private EmergencyClosureRepository emergencyClosureRepository;

    private TradingCalendar tradingCalendar;

    @BeforeEach
    void setUp() {
        lenient().when(emergencyClosureRepository.existsByDate(any(LocalDate.class)))
                .thenReturn(false);

        NseHolidayCalendar holidayCalendar = new NseHolidayCalendar(emergencyClosureRepository);
        tradingCalendar = new DefaultTradingCalendar(holidayCalendar);
    }

    @Test
    void nextTradingDay_shouldSkipWeekend() {
        LocalDate friday = LocalDate.of(2024, 4, 5);

        LocalDate next = tradingCalendar.nextTradingDay(friday);

        assertEquals(LocalDate.of(2024, 4, 8), next);
    }

    @Test
    void previousTradingDay_shouldSkipWeekend() {
        LocalDate monday = LocalDate.of(2024, 4, 8);

        LocalDate previous = tradingCalendar.previousTradingDay(monday);

        assertEquals(LocalDate.of(2024, 4, 5), previous);
    }

    @Test
    void nextTradingDay_shouldSkipKnownHoliday() {
        // 2024-03-25 = Holi
        LocalDate beforeHoliday = LocalDate.of(2024, 3, 22);

        LocalDate next = tradingCalendar.nextTradingDay(beforeHoliday);

        assertEquals(LocalDate.of(2024, 3, 26), next);
    }

    @Test
    void addTradingDays_shouldMoveForwardByTradingDaysOnly() {
        LocalDate start = LocalDate.of(2024, 4, 5); // Friday

        LocalDate result = tradingCalendar.addTradingDays(start, 3);

        assertEquals(LocalDate.of(2024, 4, 10), result);
    }

    @Test
    void addTradingDays_shouldMoveBackwardByTradingDaysOnly() {
        LocalDate start = LocalDate.of(2024, 4, 10); // Wednesday

        LocalDate result = tradingCalendar.addTradingDays(start, -3);

        assertEquals(LocalDate.of(2024, 4, 5), result);
    }

    @Test
    void getSession_shouldReturnWeekend() {
        SessionType session = tradingCalendar.getSession(LocalDate.of(2024, 4, 6));

        assertEquals(SessionType.WEEKEND, session);
    }

    @Test
    void getSession_shouldReturnHoliday() {
        SessionType session = tradingCalendar.getSession(LocalDate.of(2024, 3, 25));

        assertEquals(SessionType.HOLIDAY, session);
    }

    @Test
    void isTradingDay_shouldReturnFalseForWeekend() {
        assertFalse(tradingCalendar.isTradingDay(LocalDate.of(2024, 4, 6)));
    }

    @Test
    void isTradingDay_shouldReturnFalseForKnownHoliday() {
        assertFalse(tradingCalendar.isTradingDay(LocalDate.of(2024, 3, 25)));
    }

    @Test
    void isTradingDay_shouldReturnTrueForNormalWeekday() {
        assertTrue(tradingCalendar.isTradingDay(LocalDate.of(2024, 4, 9)));
    }

    @Test
    void getSession_shouldReturnUnexpectedClosureWhenEmergencyClosureExists() {
        LocalDate emergencyDate = LocalDate.of(2024, 4, 9);

        when(emergencyClosureRepository.existsByDate(emergencyDate)).thenReturn(true);

        NseHolidayCalendar holidayCalendar = new NseHolidayCalendar(emergencyClosureRepository);
        TradingCalendar emergencyAwareCalendar = new DefaultTradingCalendar(holidayCalendar);

        SessionType session = emergencyAwareCalendar.getSession(emergencyDate);

        assertEquals(SessionType.UNEXPECTED_CLOSURE, session);
    }

    @Test
    void nextTradingDay_shouldSkipEmergencyClosure() {
        LocalDate emergencyDate = LocalDate.of(2024, 4, 9);

        when(emergencyClosureRepository.existsByDate(emergencyDate)).thenReturn(true);

        NseHolidayCalendar holidayCalendar = new NseHolidayCalendar(emergencyClosureRepository);
        TradingCalendar emergencyAwareCalendar = new DefaultTradingCalendar(holidayCalendar);

        LocalDate next = emergencyAwareCalendar.nextTradingDay(LocalDate.of(2024, 4, 8));

        assertEquals(LocalDate.of(2024, 4, 10), next);
    }
}