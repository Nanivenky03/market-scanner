package com.trading.scanner.config;

import com.trading.scanner.calendar.DefaultTradingCalendar;
import com.trading.scanner.calendar.NseHolidayCalendar;
import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.repository.EmergencyClosureRepository;
import com.trading.scanner.repository.ExchangeHolidayRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CalendarConfiguration {

    @Bean
    public NseHolidayCalendar nseHolidayCalendar(
            EmergencyClosureRepository emergencyClosureRepository,
            ExchangeHolidayRepository exchangeHolidayRepository,
            TimeProvider timeProvider) {
        return new NseHolidayCalendar(
                emergencyClosureRepository,
                exchangeHolidayRepository,
                timeProvider);
    }

    @Bean
    public TradingCalendar tradingCalendar(NseHolidayCalendar nseHolidayCalendar) {
        return new DefaultTradingCalendar(nseHolidayCalendar);
    }
}