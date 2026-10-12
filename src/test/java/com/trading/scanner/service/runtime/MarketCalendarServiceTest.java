package com.trading.scanner.service.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.scanner.calendar.NseHolidayCalendar;
import com.trading.scanner.config.RuntimeAutomationProperties;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.ExchangeHoliday;
import com.trading.scanner.repository.ExchangeHolidayRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.DayOfWeek;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MarketCalendarServiceTest {

        private static final int REAL_CM_FIXTURE_HOLIDAY_COUNT = 20;

        private NseHolidayCalendar nseHolidayCalendar;
        private ExchangeHolidayRepository exchangeHolidayRepository;
        private RuntimeAutomationProperties runtimeAutomationProperties;
        private RuntimeSettingService runtimeSettingService;
        private TimeProvider timeProvider;
        private MarketCalendarService marketCalendarService;

        @BeforeEach
        void setUp() {
                nseHolidayCalendar = mock(NseHolidayCalendar.class);
                exchangeHolidayRepository = mock(ExchangeHolidayRepository.class);
                runtimeAutomationProperties = new RuntimeAutomationProperties();
                runtimeSettingService = mock(RuntimeSettingService.class);
                timeProvider = mock(TimeProvider.class);

                marketCalendarService = spy(new MarketCalendarService(
                                nseHolidayCalendar,
                                exchangeHolidayRepository,
                                runtimeAutomationProperties,
                                runtimeSettingService,
                                timeProvider,
                                new ObjectMapper()));
        }

        @Test
        void holidays_shouldReturnPersistedRange() {
                LocalDate fromDate = LocalDate.of(2026, 1, 1);
                LocalDate toDate = LocalDate.of(2026, 12, 31);
                List<ExchangeHoliday> expected = List.of(
                                ExchangeHoliday.builder()
                                                .exchange("NSE")
                                                .tradingDate(LocalDate.of(2026, 1, 26))
                                                .description("Republic Day")
                                                .source("STATIC_BOOTSTRAP")
                                                .build());

                when(exchangeHolidayRepository.findByExchangeAndTradingDateBetweenOrderByTradingDateAsc("NSE", fromDate,
                                toDate))
                                .thenReturn(expected);

                List<ExchangeHoliday> result = marketCalendarService.holidays(fromDate, toDate);

                assertEquals(1, result.size());
                assertEquals(LocalDate.of(2026, 1, 26), result.get(0).getTradingDate());
        }

        @Test
        void holidayCount_shouldReturnPersistedCount() {
                when(exchangeHolidayRepository.countByExchange("NSE")).thenReturn(16L);

                long result = marketCalendarService.holidayCount();

                assertEquals(16L, result);
        }

        @Test
        void refreshFromStaticAuthority_shouldDelegateToCalendar() {
                NseHolidayCalendar.HolidayRefreshResult expected = new NseHolidayCalendar.HolidayRefreshResult(20, 2,
                                "Persisted NSE holidays refreshed from static authority");

                when(nseHolidayCalendar.refreshPersistedHolidaysFromStaticAuthority()).thenReturn(expected);

                NseHolidayCalendar.HolidayRefreshResult result = marketCalendarService.refreshFromStaticAuthority();

                assertEquals(20, result.totalKnownHolidays());
                assertEquals(2, result.insertedHolidays());
                verify(nseHolidayCalendar, times(1)).refreshPersistedHolidaysFromStaticAuthority();
        }

        @Test
        void refreshFromOfficialNseSource_shouldInsertParsedHolidays_fromRealFixture() throws Exception {
                when(timeProvider.nowDateTime()).thenReturn(LocalDateTime.of(2026, 1, 1, 9, 0));

                String payload = loadFixture("contracts/nse-holiday-trading-2026.json");
                doReturn(payload).when(marketCalendarService).fetchOfficialNseTradingHolidayPayload(2026);

                when(exchangeHolidayRepository.findByExchangeAndTradingDate(anyString(), any(LocalDate.class)))
                                .thenReturn(Optional.empty());

                MarketCalendarService.OfficialHolidayRefreshResult result = marketCalendarService
                                .refreshFromOfficialNseSource();

                ArgumentCaptor<ExchangeHoliday> captor = ArgumentCaptor.forClass(ExchangeHoliday.class);
                verify(exchangeHolidayRepository, times(REAL_CM_FIXTURE_HOLIDAY_COUNT)).save(captor.capture());

                List<ExchangeHoliday> saved = captor.getAllValues();

                assertEquals(REAL_CM_FIXTURE_HOLIDAY_COUNT, result.parsedHolidays());
                assertEquals(REAL_CM_FIXTURE_HOLIDAY_COUNT, result.insertedHolidays());
                assertEquals(0, result.updatedHolidays());
                assertEquals("NSE", saved.get(0).getExchange());
                assertEquals("OFFICIAL_NSE", saved.get(0).getSource());
        }

        @Test
        void refreshFromOfficialNseSource_shouldUpdateExistingHolidayWhenDescriptionChanges() throws Exception {
                when(timeProvider.nowDateTime()).thenReturn(LocalDateTime.of(2026, 1, 1, 9, 0));

                String payload = loadFixture("contracts/nse-holiday-trading-2026.json");
                doReturn(payload).when(marketCalendarService).fetchOfficialNseTradingHolidayPayload(2026);

                when(exchangeHolidayRepository.findByExchangeAndTradingDate(anyString(), any(LocalDate.class)))
                                .thenReturn(Optional.empty());

                ExchangeHoliday existing = ExchangeHoliday.builder()
                                .exchange("NSE")
                                .tradingDate(LocalDate.of(2026, 1, 26))
                                .description("Old Description")
                                .source("STATIC_BOOTSTRAP")
                                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                                .updatedAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                                .build();

                when(exchangeHolidayRepository.findByExchangeAndTradingDate("NSE", LocalDate.of(2026, 1, 26)))
                                .thenReturn(Optional.of(existing));

                MarketCalendarService.OfficialHolidayRefreshResult result = marketCalendarService
                                .refreshFromOfficialNseSource();

                verify(exchangeHolidayRepository, atLeastOnce()).save(existing);
                assertEquals("Republic Day", existing.getDescription());
                assertEquals("OFFICIAL_NSE", existing.getSource());
                assertEquals(1, result.updatedHolidays());
                assertEquals(REAL_CM_FIXTURE_HOLIDAY_COUNT - 1, result.insertedHolidays());
        }

        @Test
        void refreshFromOfficialNseSource_shouldSkipPastHolidays_whenRunningMidYear() throws Exception {
                // Running on July 8, 2026
                when(timeProvider.nowDateTime()).thenReturn(LocalDateTime.of(2026, 7, 8, 9, 0));

                String payload = loadFixture("contracts/nse-holiday-trading-2026.json");
                doReturn(payload).when(marketCalendarService).fetchOfficialNseTradingHolidayPayload(2026);

                when(exchangeHolidayRepository.findByExchangeAndTradingDate(anyString(), any(LocalDate.class)))
                                .thenReturn(Optional.empty());

                MarketCalendarService.OfficialHolidayRefreshResult result = marketCalendarService
                                .refreshFromOfficialNseSource();

                // Exactly 8 holidays occur after July 8, 2026 in the fixture
                assertEquals(8, result.insertedHolidays());
                assertEquals(REAL_CM_FIXTURE_HOLIDAY_COUNT, result.parsedHolidays());
        }

        @Test
        void refreshFromOfficialNseSource_shouldPruneObsoleteOrInvalidHolidays() throws Exception {
                when(timeProvider.nowDateTime()).thenReturn(LocalDateTime.of(2026, 7, 8, 9, 0));

                String payload = loadFixture("contracts/nse-holiday-trading-2026.json");
                doReturn(payload).when(marketCalendarService).fetchOfficialNseTradingHolidayPayload(2026);

                when(exchangeHolidayRepository.findByExchangeAndTradingDate(anyString(), any(LocalDate.class)))
                                .thenReturn(Optional.empty());

                ExchangeHoliday obsoleteHoliday = ExchangeHoliday.builder()
                                .exchange("NSE")
                                .tradingDate(LocalDate.of(2026, 10, 12))
                                .description("Old Mistaken Holiday")
                                .source("STATIC_BOOTSTRAP")
                                .build();

                when(exchangeHolidayRepository.findByExchangeAndTradingDateBetweenOrderByTradingDateAsc(
                                eq("NSE"),
                                eq(LocalDate.of(2026, 7, 8)),
                                eq(LocalDate.of(2026, 12, 31))))
                                .thenReturn(List.of(obsoleteHoliday));

                MarketCalendarService.OfficialHolidayRefreshResult result = marketCalendarService
                                .refreshFromOfficialNseSource();

                verify(exchangeHolidayRepository, times(1)).delete(obsoleteHoliday);
                assertTrue(result.message().contains("deleted=1"));
        }

        @Test
        void refreshFromOfficialNseSource_whenParsedCountTooLow_shouldAbortWithCircuitBreaker() throws Exception {
                when(timeProvider.nowDateTime()).thenReturn(LocalDateTime.of(2026, 7, 8, 9, 0));

                // Corrupted or incomplete payload returning only 2 holidays
                String smallPayload = """
                        {
                            "CM": [
                                { "tradingDate": "26-Jan-2026", "description": "Republic Day" },
                                { "tradingDate": "15-Aug-2026", "description": "Independence Day" }
                            ]
                        }
                        """;
                doReturn(smallPayload).when(marketCalendarService).fetchOfficialNseTradingHolidayPayload(2026);

                try {
                        marketCalendarService.refreshFromOfficialNseSource();
                } catch (IllegalStateException ex) {
                        assertTrue(ex.getMessage().contains("unexpectedly low"));
                }

                // Verify circuit breaker prevented any deletion or alteration in DB
                verify(exchangeHolidayRepository, never()).delete(any());
        }

        @Test
        void parseOfficialNseTradingHolidayPayload_shouldParseRealApiFixture() throws Exception {
                String payload = loadFixture("contracts/nse-holiday-trading-2026.json");

                var parsed = marketCalendarService.parseOfficialNseTradingHolidayPayload(payload);

                assertEquals(REAL_CM_FIXTURE_HOLIDAY_COUNT, parsed.size());
                assertTrue(parsed.containsKey(LocalDate.of(2026, 1, 26)));
                assertEquals("Republic Day", parsed.get(LocalDate.of(2026, 1, 26)));
                assertEquals("Municipal Corporation Election - Maharashtra", parsed.get(LocalDate.of(2026, 1, 15)));
        }

        @Test
        void refreshFromOfficialSourceIfDue_shouldSkipWhenAutoRefreshDisabled() {
                runtimeAutomationProperties.getCalendar().setAutoRefresh(false);
                LocalDateTime currentDateTime = LocalDateTime.of(2026, 7, 27, 7, 0);

                MarketCalendarService.ScheduledCalendarRefreshResult result = marketCalendarService
                                .refreshFromOfficialSourceIfDue(currentDateTime);

                assertEquals("NO_ACTION", result.action());
                assertTrue(result.message().contains("auto-refresh is disabled"));
        }

        @Test
        void refreshFromOfficialSourceIfDue_shouldSkipWhenWeekAlreadyRefreshed() {
                LocalDateTime currentDateTime = LocalDateTime.of(2026, 7, 27, 7, 0);

                when(runtimeSettingService.getString(
                                "calendar.last.official.refresh.week",
                                ""))
                                .thenReturn("2026-07-27");

                MarketCalendarService.ScheduledCalendarRefreshResult result = marketCalendarService
                                .refreshFromOfficialSourceIfDue(
                                                currentDateTime);

                assertEquals("NO_ACTION", result.action());
                assertTrue(result.message().contains("already refreshed"));
        }

        @Test
        void refreshFromOfficialSourceIfDue_shouldRefreshOnTuesdayIfWeekNotYetRefreshed() {
                // Tuesday morning boot when Monday was offline
                LocalDateTime tuesdayMorning = LocalDateTime.of(2026, 7, 28, 7, 0);

                when(runtimeSettingService.getString(
                                "calendar.last.official.refresh.week",
                                ""))
                                .thenReturn("");

                doReturn(new MarketCalendarService.OfficialHolidayRefreshResult(
                                12,
                                2,
                                1,
                                "Exchange holidays refreshed from official NSE source"))
                                .when(marketCalendarService)
                                .refreshFromOfficialNseSource();

                MarketCalendarService.ScheduledCalendarRefreshResult result = marketCalendarService
                                .refreshFromOfficialSourceIfDue(
                                                tuesdayMorning);

                assertEquals("REFRESHED", result.action());
                assertEquals(12, result.parsedHolidays());

                // Records Monday of current week as the key
                verify(runtimeSettingService, times(1)).upsert(
                                eq("calendar.last.official.refresh.week"),
                                eq("2026-07-27"),
                                eq("DATE"),
                                anyString(),
                                eq("system"));
        }

        @Test
        void refreshFromOfficialSourceIfDue_shouldRefreshOnMondayAtMorningMaintenance() {
                LocalDateTime mondayMorning = LocalDateTime.of(2026, 7, 27, 7, 0);

                when(runtimeSettingService.getString(
                                "calendar.last.official.refresh.week",
                                ""))
                                .thenReturn("");

                doReturn(new MarketCalendarService.OfficialHolidayRefreshResult(
                                12,
                                2,
                                1,
                                "Exchange holidays refreshed from official NSE source"))
                                .when(marketCalendarService)
                                .refreshFromOfficialNseSource();

                MarketCalendarService.ScheduledCalendarRefreshResult result = marketCalendarService
                                .refreshFromOfficialSourceIfDue(
                                                mondayMorning);

                assertEquals("REFRESHED", result.action());
                assertEquals(12, result.parsedHolidays());
                assertEquals(2, result.insertedHolidays());
                assertEquals(1, result.updatedHolidays());

                verify(runtimeSettingService, times(1)).upsert(
                                eq("calendar.last.official.refresh.week"),
                                eq("2026-07-27"),
                                eq("DATE"),
                                anyString(),
                                eq("system"));

                verify(runtimeSettingService, times(1)).upsert(
                                eq("calendar.last.official.refresh.status"),
                                eq("SUCCESS"),
                                eq("STRING"),
                                anyString(),
                                eq("system"));
        }

        @Test
        void refreshFromOfficialSourceIfDue_shouldMarkFailureWhenRefreshThrows() {
                LocalDateTime currentDateTime = LocalDateTime.of(2026, 7, 27, 7, 0);

                when(runtimeSettingService.getString(
                                "calendar.last.official.refresh.week",
                                ""))
                                .thenReturn("");

                doThrow(new IllegalStateException("NSE source unavailable"))
                                .when(marketCalendarService)
                                .refreshFromOfficialNseSource();

                MarketCalendarService.ScheduledCalendarRefreshResult result = marketCalendarService
                                .refreshFromOfficialSourceIfDue(
                                                currentDateTime);

                assertEquals("FAILED", result.action());
                assertTrue(result.message().contains("NSE source unavailable"));

                verify(runtimeSettingService, times(1)).upsert(
                                eq("calendar.last.official.refresh.status"),
                                eq("FAILED"),
                                eq("STRING"),
                                anyString(),
                                eq("system"));

                verify(runtimeSettingService, times(1)).upsert(
                                eq("calendar.last.official.refresh.message"),
                                eq("NSE source unavailable"),
                                eq("STRING"),
                                anyString(),
                                eq("system"));
        }

        private String loadFixture(String path) throws Exception {
                try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(path)) {
                        return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                }
        }
}