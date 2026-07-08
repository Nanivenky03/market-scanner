package com.trading.scanner.calendar;

import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.EmergencyClosure;
import com.trading.scanner.model.ExchangeHoliday;
import com.trading.scanner.repository.EmergencyClosureRepository;
import com.trading.scanner.repository.ExchangeHolidayRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages the holiday and special session calendar for the National Stock
 * Exchange (NSE) of India.
 * Static holiday data is used only as bootstrap seed; runtime holiday truth is
 * persisted in DB.
 */
@RequiredArgsConstructor
public class NseHolidayCalendar {

    private static final String EXCHANGE_NSE = "NSE";
    private static final String SOURCE_STATIC_BOOTSTRAP = "STATIC_BOOTSTRAP";

    // Source: NSE Website
    private static final Set<LocalDate> HOLIDAYS_2023 = Set.of(
            LocalDate.of(2023, 1, 26),
            LocalDate.of(2023, 3, 7),
            LocalDate.of(2023, 3, 30),
            LocalDate.of(2023, 4, 4),
            LocalDate.of(2023, 4, 7),
            LocalDate.of(2023, 4, 14),
            LocalDate.of(2023, 5, 1),
            LocalDate.of(2023, 6, 28),
            LocalDate.of(2023, 8, 15),
            LocalDate.of(2023, 9, 19),
            LocalDate.of(2023, 10, 2),
            LocalDate.of(2023, 10, 24),
            LocalDate.of(2023, 11, 14),
            LocalDate.of(2023, 11, 27),
            LocalDate.of(2023, 12, 25));

    private static final Set<LocalDate> HOLIDAYS_2024 = Set.of(
            LocalDate.of(2024, 1, 26),
            LocalDate.of(2024, 3, 8),
            LocalDate.of(2024, 3, 25),
            LocalDate.of(2024, 3, 29),
            LocalDate.of(2024, 4, 11),
            LocalDate.of(2024, 4, 17),
            LocalDate.of(2024, 5, 1),
            LocalDate.of(2024, 6, 17),
            LocalDate.of(2024, 7, 17),
            LocalDate.of(2024, 8, 15),
            LocalDate.of(2024, 10, 2),
            LocalDate.of(2024, 11, 1),
            LocalDate.of(2024, 11, 15),
            LocalDate.of(2024, 12, 25));

    private static final Set<LocalDate> HOLIDAYS_2025 = Set.of(
            LocalDate.of(2025, 2, 26),
            LocalDate.of(2025, 3, 14),
            LocalDate.of(2025, 3, 31),
            LocalDate.of(2025, 4, 10),
            LocalDate.of(2025, 4, 14),
            LocalDate.of(2025, 4, 18),
            LocalDate.of(2025, 5, 1),
            LocalDate.of(2025, 8, 15),
            LocalDate.of(2025, 8, 27),
            LocalDate.of(2025, 10, 2),
            LocalDate.of(2025, 10, 21),
            LocalDate.of(2025, 10, 22),
            LocalDate.of(2025, 11, 5),
            LocalDate.of(2025, 12, 25));

    private static final Set<LocalDate> HOLIDAYS_2026 = Set.of(
            LocalDate.of(2026, 1, 26),
            LocalDate.of(2026, 3, 3),
            LocalDate.of(2026, 3, 26),
            LocalDate.of(2026, 3, 31),
            LocalDate.of(2026, 4, 3),
            LocalDate.of(2026, 4, 14),
            LocalDate.of(2026, 5, 1),
            LocalDate.of(2026, 6, 26),
            LocalDate.of(2026, 10, 2),
            LocalDate.of(2026, 10, 12),
            LocalDate.of(2026, 11, 9),
            LocalDate.of(2026, 11, 27),
            LocalDate.of(2026, 12, 25));

    public static final Set<LocalDate> HOLIDAYS = Collections.unmodifiableSet(
            Stream.of(HOLIDAYS_2023, HOLIDAYS_2024, HOLIDAYS_2025, HOLIDAYS_2026)
                    .flatMap(Set::stream)
                    .collect(Collectors.toSet()));

    public static final Set<LocalDate> SPECIAL_SESSIONS = Collections.unmodifiableSet(Set.of());

    private final EmergencyClosureRepository emergencyClosureRepository;
    private final ExchangeHolidayRepository exchangeHolidayRepository;
    private final TimeProvider timeProvider;

    @PostConstruct
    void bootstrapPersistedHolidays() {
        refreshPersistedHolidaysFromStaticAuthority();
    }

    public HolidayRefreshResult refreshPersistedHolidaysFromStaticAuthority() {
        List<ExchangeHoliday> existing = exchangeHolidayRepository.findByExchangeOrderByTradingDateAsc(EXCHANGE_NSE);
        Set<LocalDate> existingDates = new HashSet<>();

        for (int i = 0; i < existing.size(); i++) {
            existingDates.add(existing.get(i).getTradingDate());
        }

        List<ExchangeHoliday> missing = new ArrayList<>();
        LocalDateTime now = timeProvider.nowDateTime();

        for (LocalDate tradingDate : HOLIDAYS) {
            if (!existingDates.contains(tradingDate)) {
                missing.add(ExchangeHoliday.builder()
                        .exchange(EXCHANGE_NSE)
                        .tradingDate(tradingDate)
                        .description("NSE trading holiday")
                        .source(SOURCE_STATIC_BOOTSTRAP)
                        .createdAt(now)
                        .updatedAt(now)
                        .build());
            }
        }

        if (!missing.isEmpty()) {
            exchangeHolidayRepository.saveAll(missing);
        }

        return new HolidayRefreshResult(
                HOLIDAYS.size(),
                missing.size(),
                "Persisted NSE holidays refreshed from static authority");
    }

    public boolean isHoliday(LocalDate date) {
        return exchangeHolidayRepository.existsByExchangeAndTradingDate(EXCHANGE_NSE, date);
    }

    public boolean isSpecialSession(LocalDate date) {
        return SPECIAL_SESSIONS.contains(date);
    }

    public boolean isEmergencyClosure(LocalDate date) {
        return emergencyClosureRepository.existsByDate(date);
    }

    public void markEmergencyClosure(LocalDate date, String reason, LocalDateTime createdAt) {
        if (isEmergencyClosure(date)) {
            return;
        }

        EmergencyClosure closure = EmergencyClosure.builder()
                .date(date)
                .reason(reason)
                .createdAt(createdAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .build();

        emergencyClosureRepository.save(closure);
    }

    public void clearEmergencyClosure(LocalDate date) {
        emergencyClosureRepository.deleteByDate(date);
    }

    public record HolidayRefreshResult(
            int totalKnownHolidays,
            int insertedHolidays,
            String message) {
    }
}