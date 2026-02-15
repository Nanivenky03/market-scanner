package com.trading.scanner.calendar;

import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.EmergencyClosure;
import com.trading.scanner.repository.EmergencyClosureRepository;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages the holiday and special session calendar for the National Stock Exchange (NSE) of India.
 * This class holds static holiday data and supports dynamic, persistent marking of emergency closures.
 */
@RequiredArgsConstructor
public class NseHolidayCalendar {

    // Source: NSE Website
    private static final Set<LocalDate> HOLIDAYS_2023 = Set.of(
        LocalDate.of(2023, 1, 26), // Republic Day
        LocalDate.of(2023, 3, 7),  // Holi
        LocalDate.of(2023, 3, 30), // Ram Navami
        LocalDate.of(2023, 4, 4),  // Mahavir Jayanti
        LocalDate.of(2023, 4, 7),  // Good Friday
        LocalDate.of(2023, 4, 14), // Dr. Baba Saheb Ambedkar Jayanti
        LocalDate.of(2023, 5, 1),  // Maharashtra Day
        LocalDate.of(2023, 6, 28), // Bakri Id
        LocalDate.of(2023, 8, 15), // Independence Day
        LocalDate.of(2023, 9, 19), // Ganesh Chaturthi
        LocalDate.of(2023, 10, 2), // Mahatma Gandhi Jayanti
        LocalDate.of(2023, 10, 24),// Dussehra
        LocalDate.of(2023, 11, 14),// Diwali-Balipratipada
        LocalDate.of(2023, 11, 27),// Gurunanak Jayanti
        LocalDate.of(2023, 12, 25) // Christmas
    );

    private static final Set<LocalDate> HOLIDAYS_2024 = Set.of(
        LocalDate.of(2024, 1, 26), // Republic Day
        LocalDate.of(2024, 3, 8),  // Mahashivratri
        LocalDate.of(2024, 3, 25), // Holi
        LocalDate.of(2024, 3, 29), // Good Friday
        LocalDate.of(2024, 4, 11), // Id-Ul-Fitr
        LocalDate.of(2024, 4, 17), // Shri Ram Navmi
        LocalDate.of(2024, 5, 1),  // Maharashtra Day
        LocalDate.of(2024, 6, 17), // Bakri Id
        LocalDate.of(2024, 7, 17), // Moharram
        LocalDate.of(2024, 8, 15), // Independence Day
        LocalDate.of(2024, 10, 2), // Mahatma Gandhi Jayanti
        LocalDate.of(2024, 11, 1), // Diwali Laxmi Pujan
        LocalDate.of(2024, 11, 15),// Gurunanak Jayanti
        LocalDate.of(2024, 12, 25) // Christmas
    );

    private static final Set<LocalDate> HOLIDAYS_2025 = Set.of(
        LocalDate.of(2025, 2, 26), // Mahashivratri
        LocalDate.of(2025, 3, 14), // Holi
        LocalDate.of(2025, 3, 31), // Id-Ul-Fitr
        LocalDate.of(2025, 4, 10), // Shri Mahavir Jayanti
        LocalDate.of(2025, 4, 14), // Dr. Baba Saheb Ambedkar Jayanti
        LocalDate.of(2025, 4, 18), // Good Friday
        LocalDate.of(2025, 5, 1),  // Maharashtra Day
        LocalDate.of(2025, 8, 15), // Independence Day
        LocalDate.of(2025, 8, 27), // Ganesh Chaturthi
        LocalDate.of(2025, 10, 2), // Mahatma Gandhi Jayanti
        LocalDate.of(2025, 10, 21),// Diwali Laxmi Pujan
        LocalDate.of(2025, 10, 22),// Diwali-Balipratipada
        LocalDate.of(2025, 11, 5), // Guru Nanak Jayanti
        LocalDate.of(2025, 12, 25) // Christmas
    );

    private static final Set<LocalDate> HOLIDAYS_2026 = Set.of(
        LocalDate.of(2026, 1, 26), // Republic Day
        LocalDate.of(2026, 3, 3),  // Holi
        LocalDate.of(2026, 3, 26), // Shri Ram Navami
        LocalDate.of(2026, 3, 31), // Shri Mahavir Jayanti
        LocalDate.of(2026, 4, 3),  // Good Friday
        LocalDate.of(2026, 4, 14), // Dr. Baba Saheb Ambedkar Jayanti
        LocalDate.of(2026, 5, 1),  // Maharashtra Day
        LocalDate.of(2026, 6, 26), // Muharram
        // Independence Day (Aug 15) is a Saturday
        LocalDate.of(2026, 10, 2), // Mahatma Gandhi Jayanti
        LocalDate.of(2026, 10, 12),// Dussehra
        // Diwali (Nov 8) is a Sunday
        LocalDate.of(2026, 11, 9), // Diwali-Balipratipada
        LocalDate.of(2026, 11, 27),// Guru Nanak Jayanti
        LocalDate.of(2026, 12, 25) // Christmas
    );

    /** A combined, unmodifiable set of all known holidays. */
    public static final Set<LocalDate> HOLIDAYS = Collections.unmodifiableSet(
        Stream.of(HOLIDAYS_2023, HOLIDAYS_2024, HOLIDAYS_2025, HOLIDAYS_2026)
            .flatMap(Set::stream)
            .collect(Collectors.toSet())
    );

    /** A set of dates with special trading sessions (e.g., Muhurat Trading). Empty for now. */
    public static final Set<LocalDate> SPECIAL_SESSIONS = Collections.unmodifiableSet(Set.of());

    private final EmergencyClosureRepository emergencyClosureRepository;
    private final TimeProvider timeProvider;

    /**
     * Checks if a given date is a scheduled public holiday.
     */
    public boolean isHoliday(LocalDate date) {
        return HOLIDAYS.contains(date);
    }

    /**
     * Checks if a given date has a special trading session.
     */
    public boolean isSpecialSession(LocalDate date) {
        return SPECIAL_SESSIONS.contains(date);
    }

    /**
     * Checks if a given date was marked as an emergency closure in the database.
     */
    public boolean isEmergencyClosure(LocalDate date) {
        return emergencyClosureRepository.existsByDate(date);
    }

    /**
     * Dynamically and persistently marks a date as an emergency market closure.
     * The public API is preserved, so the reason is marked as "Unspecified".
     */
    public void markEmergencyClosure(LocalDate date) {
        if (isEmergencyClosure(date)) {
            return; // Already marked
        }
        EmergencyClosure closure = EmergencyClosure.builder()
            .date(date)
            .reason("Unspecified")
            .createdAt(timeProvider.nowDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
            .build();
        emergencyClosureRepository.save(closure);
    }

    /**
     * Clears a previously marked emergency closure from the database.
     */
    public void clearEmergencyClosure(LocalDate date) {
        emergencyClosureRepository.deleteByDate(date);
    }
}
