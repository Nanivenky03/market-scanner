package com.trading.scanner.config;

import com.trading.scanner.calendar.DefaultTradingCalendar;
import com.trading.scanner.calendar.NseHolidayCalendar;
import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.repository.EmergencyClosureRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for creating and exposing trading calendar related beans.
 */
@Configuration
public class CalendarConfiguration {

    @Bean
    public NseHolidayCalendar nseHolidayCalendar(
        EmergencyClosureRepository emergencyClosureRepository,
        TimeProvider timeProvider
    ) {
        return new NseHolidayCalendar(emergencyClosureRepository, timeProvider);
    }

    @Bean
    public TradingCalendar tradingCalendar(NseHolidayCalendar nseHolidayCalendar) {
        return new DefaultTradingCalendar(nseHolidayCalendar);
    }
}
