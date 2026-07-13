package com.trading.scanner.service.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private static final DateTimeFormatter NSE_DATE_FORMAT_SHORT = DateTimeFormatter.ofPattern("d-MMM-yy",
            Locale.ENGLISH);
    private static final DateTimeFormatter NSE_DATE_FORMAT_LONG = DateTimeFormatter.ofPattern("d-MMM-yyyy",
            Locale.ENGLISH);

    private final NseHolidayCalendar nseHolidayCalendar;
    private final ExchangeHolidayRepository exchangeHolidayRepository;
    private final RuntimeAutomationProperties runtimeAutomationProperties;
    private final RuntimeSettingService runtimeSettingService;
    private final TimeProvider timeProvider;
    private final ObjectMapper objectMapper;

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
        LocalDateTime now = timeProvider.nowDateTime();
        Map<LocalDate, String> parsedHolidays = new LinkedHashMap<>();

        parsedHolidays.putAll(parseOfficialNseTradingHolidayPayload(
                fetchOfficialNseTradingHolidayPayload(now.getYear())));

        if (now.getMonthValue() == 12) {
            parsedHolidays.putAll(parseOfficialNseTradingHolidayPayload(
                    fetchOfficialNseTradingHolidayPayload(now.getYear() + 1)));
        }

        int inserted = 0;
        int updated = 0;

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

    protected String fetchOfficialNseTradingHolidayPayload(int year) {
        try {
            String url = String.format(
                    runtimeAutomationProperties.getCalendar().getOfficialNseTradingApiUrlTemplate(),
                    year);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(runtimeAutomationProperties.getCalendar().getRefreshTimeoutMs()))
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Accept", "application/json,text/plain,*/*")
                    .GET()
                    .build();

            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Official NSE holiday API returned HTTP " + response.statusCode());
            }

            return response.body();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to fetch official NSE trading holiday API", ex);
        }
    }

    protected Map<LocalDate, String> parseOfficialNseTradingHolidayPayload(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            String segment = runtimeAutomationProperties.getCalendar().getOfficialNseTradingSegment();
            JsonNode segmentNode = root.path(segment);

            if (!segmentNode.isArray() || segmentNode.isEmpty()) {
                throw new IllegalStateException("No exchange holidays found for NSE segment " + segment);
            }

            Map<LocalDate, String> holidays = new LinkedHashMap<>();

            for (int i = 0; i < segmentNode.size(); i++) {
                JsonNode holidayNode = segmentNode.get(i);
                JsonNode tradingDateNode = holidayNode.get("tradingDate");
                JsonNode descriptionNode = holidayNode.get("description");

                if (tradingDateNode == null || descriptionNode == null) {
                    continue;
                }

                LocalDate tradingDate = parseNseTradingDate(tradingDateNode.asText());
                String description = descriptionNode.asText().trim();

                holidays.putIfAbsent(tradingDate, description);
            }

            if (holidays.isEmpty()) {
                throw new IllegalStateException(
                        "No exchange holidays could be parsed from official NSE trading holiday API");
            }

            return holidays;
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse official NSE trading holiday API payload", ex);
        }
    }

    private DayOfWeek configuredRefreshDay() {
        return DayOfWeek.valueOf(runtimeAutomationProperties.getCalendar().getRefreshDay().toUpperCase(Locale.ENGLISH));
    }

    private boolean isLastConfiguredRefreshDayOfMonth(LocalDate date) {
        return date.plusDays(7).getMonth() != date.getMonth();
    }

    private LocalDate parseNseTradingDate(String rawDate) {
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