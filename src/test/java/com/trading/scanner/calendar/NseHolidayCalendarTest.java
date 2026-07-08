package com.trading.scanner.calendar;

import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.ExchangeHoliday;
import com.trading.scanner.repository.EmergencyClosureRepository;
import com.trading.scanner.repository.ExchangeHolidayRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NseHolidayCalendarTest {

    @Mock
    private EmergencyClosureRepository emergencyClosureRepository;

    @Mock
    private ExchangeHolidayRepository exchangeHolidayRepository;

    @Mock
    private TimeProvider timeProvider;

    private NseHolidayCalendar nseHolidayCalendar;

    @BeforeEach
    void setUp() {
        nseHolidayCalendar = new NseHolidayCalendar(
                emergencyClosureRepository,
                exchangeHolidayRepository,
                timeProvider);
    }

    @Test
    void isHoliday_shouldUsePersistedHolidayAuthority() {
        LocalDate holiday = LocalDate.of(2026, 1, 26);

        when(exchangeHolidayRepository.existsByExchangeAndTradingDate("NSE", holiday)).thenReturn(true);

        boolean result = nseHolidayCalendar.isHoliday(holiday);

        assertTrue(result);
        verify(exchangeHolidayRepository, times(1))
                .existsByExchangeAndTradingDate("NSE", holiday);
    }

    @Test
    void refreshPersistedHolidaysFromStaticAuthority_shouldInsertMissingKnownHolidays() {
        when(exchangeHolidayRepository.findByExchangeOrderByTradingDateAsc("NSE")).thenReturn(List.of());
        when(timeProvider.nowDateTime()).thenReturn(LocalDateTime.of(2026, 7, 8, 9, 0));

        NseHolidayCalendar.HolidayRefreshResult result = nseHolidayCalendar
                .refreshPersistedHolidaysFromStaticAuthority();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ExchangeHoliday>> captor = ArgumentCaptor.forClass(List.class);
        verify(exchangeHolidayRepository, times(1)).saveAll(captor.capture());

        List<ExchangeHoliday> saved = captor.getValue();

        assertTrue(saved.size() > 0);
        assertEquals("NSE", saved.get(0).getExchange());
        assertEquals("STATIC_BOOTSTRAP", saved.get(0).getSource());
        assertEquals(saved.size(), result.insertedHolidays());
    }
}