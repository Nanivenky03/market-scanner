package com.trading.scanner.service.runtime;

import com.trading.scanner.config.RuntimeAutomationProperties;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.RuntimeSetting;
import com.trading.scanner.repository.RuntimeSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RuntimeSettingServiceTest {

    @Mock
    private RuntimeSettingRepository repository;

    @Mock
    private RuntimeAutomationProperties properties;

    @Mock
    private TimeProvider timeProvider;

    private RuntimeAutomationProperties.Defaults defaultProperties;
    private RuntimeAutomationProperties.Live liveProperties;
    private RuntimeAutomationProperties.Alert alertProperties;

    private RuntimeSettingService service;

    private final LocalDateTime now = LocalDateTime.of(2026, 9, 20, 22, 30);

    @BeforeEach
    void setUp() {
        defaultProperties = new RuntimeAutomationProperties.Defaults();
        defaultProperties.setAngeloneLoginTime("08:50");
        defaultProperties.setWebsocketConnectTime("09:15");
        defaultProperties.setWebsocketDisconnectTime("15:40");
        defaultProperties.setAngeloneDisconnectTime("17:00");
        defaultProperties.setHousekeepingTime("05:30");
        defaultProperties.setWebsocketIdleCloseMinutes(10);
        defaultProperties.setWebsocketCloseRecheckMinutes(10);
        defaultProperties.setRetentionCandlesDays(30);
        defaultProperties.setRetentionLiveSignalsDays(30);

        liveProperties = new RuntimeAutomationProperties.Live();
        liveProperties.setSubscriptionMode(3);

        alertProperties = new RuntimeAutomationProperties.Alert();
        alertProperties.setStaleTicksMinutes(10);
        alertProperties.setParserFailuresThreshold(10);

        lenient().when(properties.getDefaults()).thenReturn(defaultProperties);
        lenient().when(properties.getLive()).thenReturn(liveProperties);
        lenient().when(properties.getAlert()).thenReturn(alertProperties);
        lenient().when(timeProvider.nowDateTime()).thenReturn(now);

        service = new RuntimeSettingService(repository, properties, timeProvider);
    }

    @Test
    void getTime_shouldReturnValueFromDatabaseWhenPresent() {
        when(repository.findByNameAndIsActiveTrue("websocket.connect.time"))
                .thenReturn(Optional.of(RuntimeSetting.builder()
                        .name("websocket.connect.time")
                        .value("09:10")
                        .valueType("TIME")
                        .isActive(true)
                        .build()));

        LocalTime result = service.websocketConnectTime();

        assertEquals(LocalTime.of(9, 10), result);
    }

    @Test
    void getTime_shouldFallbackToPropertiesWhenMissingInDatabase() {
        when(repository.findByNameAndIsActiveTrue("websocket.connect.time"))
                .thenReturn(Optional.empty());

        LocalTime result = service.websocketConnectTime();

        assertEquals(LocalTime.of(9, 15), result);
    }

    @Test
    void getInt_shouldReturnValueFromDatabaseWhenPresent() {
        when(repository.findByNameAndIsActiveTrue("retention.candles.days"))
                .thenReturn(Optional.of(RuntimeSetting.builder()
                        .name("retention.candles.days")
                        .value("60")
                        .valueType("INTEGER")
                        .isActive(true)
                        .build()));

        int result = service.retentionCandlesDays();

        assertEquals(60, result);
    }

    @Test
    void getInt_shouldFallbackToPropertiesWhenMissingInDatabase() {
        when(repository.findByNameAndIsActiveTrue("retention.candles.days"))
                .thenReturn(Optional.empty());

        int result = service.retentionCandlesDays();

        assertEquals(30, result);
    }

    @Test
    void getString_shouldReturnValueFromDatabase() {
        when(repository.findByNameAndIsActiveTrue("custom.key"))
                .thenReturn(Optional.of(RuntimeSetting.builder()
                        .name("custom.key")
                        .value("custom_value")
                        .isActive(true)
                        .build()));

        String result = service.getString("custom.key", "default_value");

        assertEquals("custom_value", result);
    }

    @Test
    void getString_shouldFallbackWhenMissing() {
        when(repository.findByNameAndIsActiveTrue("custom.key"))
                .thenReturn(Optional.empty());

        String result = service.getString("custom.key", "default_value");

        assertEquals("default_value", result);
    }

    @Test
    void typedGetters_shouldReturnExpectedDefaults() {
        when(repository.findByNameAndIsActiveTrue(anyString()))
                .thenReturn(Optional.empty());

        assertEquals(LocalTime.of(8, 50), service.loginTime());
        assertEquals(LocalTime.of(15, 40), service.websocketDisconnectTime());
        assertEquals(LocalTime.of(17, 0), service.angeloneDisconnectTime());
        assertEquals(LocalTime.of(5, 30), service.housekeepingTime());
        assertEquals(3, service.subscriptionMode());
        assertEquals(10, service.websocketIdleCloseMinutes());
        assertEquals(10, service.websocketCloseRecheckMinutes());
        assertEquals(30, service.retentionLiveSignalsDays());
        assertEquals(10, service.staleTicksMinutes());
        assertEquals(10, service.parserFailuresThreshold());
    }

    @Test
    void activeSettings_shouldDelegateToRepository() {
        List<RuntimeSetting> expected = List.of(
                RuntimeSetting.builder().name("setting.a").value("1").build(),
                RuntimeSetting.builder().name("setting.b").value("2").build()
        );

        when(repository.findByIsActiveTrueOrderByNameAsc()).thenReturn(expected);

        List<RuntimeSetting> result = service.activeSettings();

        assertEquals(2, result.size());
        assertEquals("setting.a", result.get(0).getName());
    }

    @Test
    void upsert_shouldUpdateExistingSetting() {
        RuntimeSetting existing = RuntimeSetting.builder()
                .id(1L)
                .name("websocket.connect.time")
                .value("09:15")
                .valueType("TIME")
                .isActive(true)
                .build();

        when(repository.findByName("websocket.connect.time"))
                .thenReturn(Optional.of(existing));
        when(repository.save(any(RuntimeSetting.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        RuntimeSetting updated = service.upsert(
                "websocket.connect.time",
                "09:00",
                "TIME",
                "Updated connect time");

        assertEquals("09:00", updated.getValue());
        assertEquals("Updated connect time", updated.getDescription());
        assertTrue(updated.getIsActive());
        verify(repository).save(existing);
    }

    @Test
    void upsert_shouldCreateNewSettingWhenMissing() {
        when(repository.findByName("new.setting"))
                .thenReturn(Optional.empty());
        when(repository.save(any(RuntimeSetting.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        RuntimeSetting created = service.upsert(
                "new.setting",
                "100",
                "INTEGER",
                "New setting description",
                "admin");

        assertEquals("new.setting", created.getName());
        assertEquals("100", created.getValue());
        assertEquals("INTEGER", created.getValueType());
        assertTrue(created.getIsActive());
        verify(repository).save(any(RuntimeSetting.class));
    }

    @Test
    void ensureRequiredSettingsExist_shouldSeedMissingSettings() {
        when(repository.findByName(anyString())).thenReturn(Optional.empty());
        when(repository.save(any(RuntimeSetting.class))).thenAnswer(inv -> inv.getArgument(0));

        int seeded = service.ensureRequiredSettingsExist();

        assertEquals(17, seeded);
        verify(repository, times(17)).save(any(RuntimeSetting.class));
    }

    @Test
    void upsert_shouldRejectInvalidCronExpression() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> service.upsert("schedule.custom.cron", "not a valid cron", "CRON", "test"));
    }

    @Test
    void dynamicCronGetters_shouldFallbackWhenDbContainsInvalidCron() {
        when(repository.findByNameAndIsActiveTrue("schedule.morning.maintenance.cron"))
                .thenReturn(Optional.of(RuntimeSetting.builder()
                        .name("schedule.morning.maintenance.cron")
                        .value("corrupt-cron")
                        .build()));

        assertEquals("0 0 7 * * MON-FRI", service.morningMaintenanceCron());
        assertEquals("5 * 9-15 * * MON-FRI", service.minuteRolloverCron());
    }
}