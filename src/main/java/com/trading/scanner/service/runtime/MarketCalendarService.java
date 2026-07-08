package com.trading.scanner.service.runtime;

import com.trading.scanner.calendar.NseHolidayCalendar;
import com.trading.scanner.config.RuntimeAutomationProperties;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.ExchangeHoliday;
import com.trading.scanner.repository.ExchangeHolidayRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class MarketCalendarService {

    private static final String EXCHANGE_NSE = "NSE";
    private static final String SOURCE_OFFICIAL_NSE = "OFFICIAL_NSE";
    private static final String UPDATED_BY_SYSTEM = "system";

    private static final String KEY_LAST_OFFICIAL_REFRESH_MONTH = "calendar.last.official.refresh.month";
    private static final String KEY_LAST_OFFICIAL_REFRESH_AT = "calendar.last.official.refresh.at";
    private static final String KEY_LAST_OFFICIAL_REFRESH_STATUS = "calendar.last.official.refresh.status";
    private static final String KEY_LAST_OFFICIAL_REFRESH_MESSAGE = "calendar.last.official.refresh.message";

    private static final Pattern NSE_DATE_DESCRIPTION_PATTERN = Pattern.compile(
            "\"DATE\"\\s*:\\s*\"([0-9]{1,2}-[A-Za-z]{3}-[0-9]{2,4})\"\\s*,\\s*\"DAY\"\\s*:\\s*\"[^\"]+\"\\s*,\\s*\"DESCRIPTION\"\\s*:\\s*\"([^\"]+)\"");

    private static final DateTimeFormatter NSE_DATE_FORMAT_SHORT = DateTimeFormatter.ofPattern("d-MMM-yy",
            Locale.ENGLISH);
    private static final DateTimeFormatter NSE_DATE_FORMAT_LONG = DateTimeFormatter.ofPattern("d-MMM-yyyy",
            Locale.ENGLISH);

    private final NseHolidayCalendar nseHolidayCalendar;
    private final ExchangeHolidayRepository exchangeHolidayRepository;
    private final RuntimeAutomationProperties runtimeAutomationProperties;
    private final RuntimeSettingService runtimeSettingService;
    private final TimeProvider timeProvider;

    @Transactional(readOnly = true)
    public List<ExchangeHoliday> holidays(LocalDate fromDate, LocalDate toDate) {
        return exchangeHolidayRepository.findByExchangeAndTradingDateBetweenOrderByTradingDateAsc(
                EXCHANGE_NSE,
                fromDate,
                toDate);
    }

    @Transactional(readOnly = true)
    public long holidayCount() {
        return exchangeHolidayRepository.countByExchange(EXCHANGE_NSE);
    }

    @Transactional
    public NseHolidayCalendar.HolidayRefreshResult refreshFromStaticAuthority() {
        return nseHolidayCalendar.refreshPersistedHolidaysFromStaticAuthority();
    }

    @Transactional
    public OfficialHolidayRefreshResult refreshFromOfficialNseSource() {
        String pageContent = fetchOfficialNseHolidayPage();
        Map<LocalDate, String> parsedHolidays = parseOfficialNseHolidayPage(pageContent);

        int inserted = 0;
        int updated = 0;
        LocalDateTime now = timeProvider.nowDateTime();

        for (Map.Entry<LocalDate, String> entry : parsedHolidays.entrySet()) {
            LocalDate tradingDate = entry.getKey();
            String description = entry.getValue();

            Optional<ExchangeHoliday> existing = exchangeHolidayRepository.findByExchangeAndTradingDate(EXCHANGE_NSE,
                    tradingDate);

            if (existing.isPresent()) {
                ExchangeHoliday holiday = existing.get();
                boolean changed = false;

                if (!description.equals(holiday.getDescription())) {
                    holiday.setDescription(description);
                    changed = true;
                }

                if (!SOURCE_OFFICIAL_NSE.equals(holiday.getSource())) {
                    holiday.setSource(SOURCE_OFFICIAL_NSE);
                    changed = true;
                }

                if (changed) {
                    holiday.setUpdatedAt(now);
                    exchangeHolidayRepository.save(holiday);
                    updated++;
                }
            } else {
                exchangeHolidayRepository.save(ExchangeHoliday.builder()
                        .exchange(EXCHANGE_NSE)
                        .tradingDate(tradingDate)
                        .description(description)
                        .source(SOURCE_OFFICIAL_NSE)
                        .createdAt(now)
                        .updatedAt(now)
                        .build());
                inserted++;
            }
        }

        return new OfficialHolidayRefreshResult(
                parsedHolidays.size(),
                inserted,
                updated,
                "Exchange holidays refreshed from official NSE source");
    }

    @Transactional
    public ScheduledCalendarRefreshResult refreshFromOfficialSourceIfDue(LocalDateTime currentDateTime) {
        if (!runtimeAutomationProperties.getCalendar().isAutoRefresh()) {
            return new ScheduledCalendarRefreshResult(
                    "NO_ACTION",
                    0,
                    0,
                    0,
                    "Skipped calendar refresh because auto-refresh is disabled");
        }

        LocalDate currentDate = currentDateTime.toLocalDate();
        LocalTime currentTime = currentDateTime.toLocalTime();
        DayOfWeek configuredRefreshDay = configuredRefreshDay();
        LocalTime configuredTime = LocalTime.parse(runtimeAutomationProperties.getCalendar().getRefreshCheckTime());

        if (currentDate.getDayOfWeek() != configuredRefreshDay) {
            return new ScheduledCalendarRefreshResult(
                    "NO_ACTION",
                    0,
                    0,
                    0,
                    "Skipped calendar refresh because today is not the configured refresh day");
        }

        if (!isLastConfiguredRefreshDayOfMonth(currentDate)) {
            return new ScheduledCalendarRefreshResult(
                    "NO_ACTION",
                    0,
                    0,
                    0,
                    "Skipped calendar refresh because this is not the last configured refresh day of the month");
        }

        if (currentTime.isBefore(configuredTime)) {
            return new ScheduledCalendarRefreshResult(
                    "NO_ACTION",
                    0,
                    0,
                    0,
                    "Skipped calendar refresh because current time is before configured refresh time");
        }

        String refreshMonthKey = String.format("%04d-%02d", currentDate.getYear(), currentDate.getMonthValue());
        String lastRefreshMonth = runtimeSettingService.getString(KEY_LAST_OFFICIAL_REFRESH_MONTH, "");

        if (refreshMonthKey.equals(lastRefreshMonth)) {
            return new ScheduledCalendarRefreshResult(
                    "NO_ACTION",
                    0,
                    0,
                    0,
                    "Skipped calendar refresh because this month was already refreshed");
        }

        try {
            OfficialHolidayRefreshResult result = refreshFromOfficialNseSource();

            runtimeSettingService.upsert(
                    KEY_LAST_OFFICIAL_REFRESH_MONTH,
                    refreshMonthKey,
                    "STRING",
                    "Last successfully refreshed official holiday month",
                    UPDATED_BY_SYSTEM);
            runtimeSettingService.upsert(
                    KEY_LAST_OFFICIAL_REFRESH_AT,
                    currentDateTime.toString(),
                    "DATETIME",
                    "Last official holiday refresh timestamp",
                    UPDATED_BY_SYSTEM);
            runtimeSettingService.upsert(
                    KEY_LAST_OFFICIAL_REFRESH_STATUS,
                    "SUCCESS",
                    "STRING",
                    "Last official holiday refresh status",
                    UPDATED_BY_SYSTEM);
            runtimeSettingService.upsert(
                    KEY_LAST_OFFICIAL_REFRESH_MESSAGE,
                    result.message(),
                    "STRING",
                    "Last official holiday refresh message",
                    UPDATED_BY_SYSTEM);

            return new ScheduledCalendarRefreshResult(
                    "REFRESHED",
                    result.parsedHolidays(),
                    result.insertedHolidays(),
                    result.updatedHolidays(),
                    "Calendar refreshed from official NSE source");
        } catch (Exception ex) {
            runtimeSettingService.upsert(
                    KEY_LAST_OFFICIAL_REFRESH_AT,
                    currentDateTime.toString(),
                    "DATETIME",
                    "Last official holiday refresh timestamp",
                    UPDATED_BY_SYSTEM);
            runtimeSettingService.upsert(
                    KEY_LAST_OFFICIAL_REFRESH_STATUS,
                    "FAILED",
                    "STRING",
                    "Last official holiday refresh status",
                    UPDATED_BY_SYSTEM);
            runtimeSettingService.upsert(
                    KEY_LAST_OFFICIAL_REFRESH_MESSAGE,
                    ex.getMessage(),
                    "STRING",
                    "Last official holiday refresh message",
                    UPDATED_BY_SYSTEM);
            throw ex;
        }
    }

    protected String fetchOfficialNseHolidayPage() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(runtimeAutomationProperties.getCalendar().getOfficialNseUrl()))
                    .timeout(Duration.ofMillis(runtimeAutomationProperties.getCalendar().getRefreshTimeoutMs()))
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .GET()
                    .build();

            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Official NSE holiday source returned HTTP " + response.statusCode());
            }

            return response.body();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to fetch official NSE holiday source", ex);
        }
    }

    protected Map<LocalDate, String> parseOfficialNseHolidayPage(String pageContent) {
        Map<LocalDate, String> holidays = new LinkedHashMap<>();
        Matcher matcher = NSE_DATE_DESCRIPTION_PATTERN.matcher(pageContent);

        while (matcher.find()) {
            LocalDate tradingDate = parseNseDate(matcher.group(1));
            String description = matcher.group(2).trim();

            if (!holidays.containsKey(tradingDate)) {
                holidays.put(tradingDate, description);
            }
        }

        if (holidays.isEmpty()) {
            throw new IllegalStateException("No exchange holidays could be parsed from official NSE source");
        }

        return holidays;
    }

    private DayOfWeek configuredRefreshDay() {
        return DayOfWeek.valueOf(runtimeAutomationProperties.getCalendar().getRefreshDay().toUpperCase(Locale.ENGLISH));
    }

    private boolean isLastConfiguredRefreshDayOfMonth(LocalDate date) {
        return date.plusDays(7).getMonth() != date.getMonth();
    }

    private LocalDate parseNseDate(String rawDate) {
        if (rawDate.length() == 9) {
            return LocalDate.parse(rawDate, NSE_DATE_FORMAT_SHORT);
        }
        return LocalDate.parse(rawDate, NSE_DATE_FORMAT_LONG);
    }

    public record OfficialHolidayRefreshResult(
            int parsedHolidays,
            int insertedHolidays,
            int updatedHolidays,
            String message) {
    }

    public record ScheduledCalendarRefreshResult(
            String action,
            int parsedHolidays,
            int insertedHolidays,
            int updatedHolidays,
            String message) {
    }
}