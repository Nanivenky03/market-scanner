package com.trading.scanner.service.runtime;

import com.trading.scanner.config.RuntimeAutomationProperties;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.RuntimeSetting;
import com.trading.scanner.repository.RuntimeSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RuntimeSettingService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final RuntimeSettingRepository runtimeSettingRepository;
    private final RuntimeAutomationProperties runtimeAutomationProperties;
    private final TimeProvider timeProvider;

        public static final String PROCESS_DATE_KEY = "runtime.process.date";

    @Transactional(readOnly = true)
    public LocalDate processDate() {
        return runtimeSettingRepository.findByNameAndIsActiveTrue(PROCESS_DATE_KEY)
                .map(RuntimeSetting::getValue)
                .filter(val -> val != null && !val.isBlank())
                .map(LocalDate::parse)
                .orElse(null);
    }

    @Transactional
    public RuntimeSetting setProcessDate(LocalDate date) {
        String value = date != null ? date.toString() : timeProvider.today().toString();
        return upsert(
                PROCESS_DATE_KEY,
                value,
                "DATE",
                "Current trading business process date for the application",
                "system");
    }

        @Transactional(readOnly = true)
    public String morningMaintenanceCron() {
        String cron = getString("schedule.morning.maintenance.cron", "0 0 7 * * MON-FRI");
        return org.springframework.scheduling.support.CronExpression.isValidExpression(cron) ? cron : "0 0 7 * * MON-FRI";
    }

    @Transactional(readOnly = true)
    public String preMarketDataPipelineCron() {
        String cron = getString("schedule.premarket.data.cron", "0 0 8 * * MON-FRI");
        return org.springframework.scheduling.support.CronExpression.isValidExpression(cron) ? cron : "0 0 8 * * MON-FRI";
    }

    @Transactional(readOnly = true)
    public String liveRuntimeStartCron() {
        String cron = getString("schedule.live.start.cron", "0 55 8 * * MON-FRI");
        return org.springframework.scheduling.support.CronExpression.isValidExpression(cron) ? cron : "0 55 8 * * MON-FRI";
    }

    @Transactional(readOnly = true)
    public String scheduleZone() {
        String zone = getString("schedule.zone", "Asia/Kolkata");
        try {
            java.time.ZoneId.of(zone);
            return zone;
        } catch (Exception ex) {
            return "Asia/Kolkata";
        }
    }

    @Transactional(readOnly = true)
    public String minuteRolloverCron() {
        String cron = getString("schedule.minute.rollover.cron", "5 * 9-15 * * MON-FRI");
        return org.springframework.scheduling.support.CronExpression.isValidExpression(cron) ? cron : "5 * 9-15 * * MON-FRI";
    }

    @Transactional(readOnly = true)
    public LocalTime getTime(String name, LocalTime fallback) {
        return runtimeSettingRepository.findByNameAndIsActiveTrue(name)
                .map(RuntimeSetting::getValue)
                .map(value -> LocalTime.parse(value, TIME_FORMAT))
                .orElse(fallback);
    }

    @Transactional(readOnly = true)
    public int getInt(String name, int fallback) {
        return runtimeSettingRepository.findByNameAndIsActiveTrue(name)
                .map(RuntimeSetting::getValue)
                .map(Integer::parseInt)
                .orElse(fallback);
    }

    @Transactional(readOnly = true)
    public String getString(String name, String fallback) {
        return runtimeSettingRepository.findByNameAndIsActiveTrue(name)
                .map(RuntimeSetting::getValue)
                .orElse(fallback);
    }

    @Transactional(readOnly = true)
    public LocalTime loginTime() {
        return getTime(
                "angelone.login.time",
                LocalTime.parse(runtimeAutomationProperties.getDefaults().getAngeloneLoginTime(), TIME_FORMAT));
    }

    @Transactional(readOnly = true)
    public LocalTime websocketConnectTime() {
        return getTime(
                "websocket.connect.time",
                LocalTime.parse(runtimeAutomationProperties.getDefaults().getWebsocketConnectTime(), TIME_FORMAT));
    }

    @Transactional(readOnly = true)
    public LocalTime websocketDisconnectTime() {
        return getTime(
                "websocket.disconnect.time",
                LocalTime.parse(runtimeAutomationProperties.getDefaults().getWebsocketDisconnectTime(), TIME_FORMAT));
    }

    @Transactional(readOnly = true)
    public LocalTime angeloneDisconnectTime() {
        return getTime(
                "angelone.disconnect.time",
                LocalTime.parse(runtimeAutomationProperties.getDefaults().getAngeloneDisconnectTime(), TIME_FORMAT));
    }

    @Transactional(readOnly = true)
    public LocalTime housekeepingTime() {
        return getTime(
                "housekeeping.time",
                LocalTime.parse(runtimeAutomationProperties.getDefaults().getHousekeepingTime(), TIME_FORMAT));
    }

    @Transactional(readOnly = true)
    public int subscriptionMode() {
        return getInt(
                "websocket.subscribe.mode",
                runtimeAutomationProperties.getLive().getSubscriptionMode());
    }

    @Transactional(readOnly = true)
    public int websocketIdleCloseMinutes() {
        return getInt(
                "websocket.idle.close.minutes",
                runtimeAutomationProperties.getDefaults().getWebsocketIdleCloseMinutes());
    }

    @Transactional(readOnly = true)
    public int websocketCloseRecheckMinutes() {
        return getInt(
                "websocket.close.recheck.minutes",
                runtimeAutomationProperties.getDefaults().getWebsocketCloseRecheckMinutes());
    }

    @Transactional(readOnly = true)
    public int retentionCandlesDays() {
        return getInt(
                "retention.candles.days",
                runtimeAutomationProperties.getDefaults().getRetentionCandlesDays());
    }

    @Transactional(readOnly = true)
    public int retentionLiveSignalsDays() {
        return getInt(
                "retention.live.signals.days",
                runtimeAutomationProperties.getDefaults().getRetentionLiveSignalsDays());
    }

    @Transactional(readOnly = true)
    public int staleTicksMinutes() {
        return getInt(
                "alert.stale.ticks.minutes",
                runtimeAutomationProperties.getAlert().getStaleTicksMinutes());
    }

    @Transactional(readOnly = true)
    public int parserFailuresThreshold() {
        return getInt(
                "alert.parser.failures.threshold",
                runtimeAutomationProperties.getAlert().getParserFailuresThreshold());
    }

    @Transactional(readOnly = true)
    public List<RuntimeSetting> activeSettings() {
        return runtimeSettingRepository.findByIsActiveTrueOrderByNameAsc();
    }

        @Transactional
    public RuntimeSetting upsert(String name, String value, String valueType, String description) {
        return upsert(name, value, valueType, description, "system");
    }

    @Transactional
    public RuntimeSetting upsert(String name, String value, String valueType, String description, String updatedBy) {
        if (name != null && (name.endsWith(".cron") || "CRON".equalsIgnoreCase(valueType))) {
            if (!org.springframework.scheduling.support.CronExpression.isValidExpression(value)) {
                throw new IllegalArgumentException("Invalid cron expression for setting '" + name + "': " + value);
            }
        }

        RuntimeSetting setting = runtimeSettingRepository.findByName(name)
                .orElseGet(() -> RuntimeSetting.builder()
                        .name(name)
                        .isActive(true)
                        .build());

        setting.setValue(value);
        setting.setValueType(valueType);
        setting.setDescription(description);
        setting.setIsActive(true);
        setting.setUpdatedAt(timeProvider.nowDateTime().toString());

        return runtimeSettingRepository.save(setting);
    }

    @Transactional
    public int ensureRequiredSettingsExist() {
        int createdCount = 0;

        createdCount += seedIfMissing("angelone.login.time",
                runtimeAutomationProperties.getDefaults().getAngeloneLoginTime(),
                "TIME", "Daily broker authentication trigger time");

        createdCount += seedIfMissing("websocket.connect.time",
                runtimeAutomationProperties.getDefaults().getWebsocketConnectTime(),
                "TIME", "Daily market data websocket connection time");

        createdCount += seedIfMissing("websocket.disconnect.time",
                runtimeAutomationProperties.getDefaults().getWebsocketDisconnectTime(),
                "TIME", "Daily market data websocket disconnect time");

        createdCount += seedIfMissing("angelone.disconnect.time",
                runtimeAutomationProperties.getDefaults().getAngeloneDisconnectTime(),
                "TIME", "Daily broker session termination time");

        createdCount += seedIfMissing("housekeeping.time",
                runtimeAutomationProperties.getDefaults().getHousekeepingTime(),
                "TIME", "Daily system maintenance and data cleanup time");

        createdCount += seedIfMissing("websocket.subscribe.mode",
                String.valueOf(runtimeAutomationProperties.getLive().getSubscriptionMode()),
                "INTEGER", "Angel One websocket subscription mode (1=LTP, 2=Quote, 3=SnapQuote)");

        createdCount += seedIfMissing("websocket.idle.close.minutes",
                String.valueOf(runtimeAutomationProperties.getDefaults().getWebsocketIdleCloseMinutes()),
                "INTEGER", "Inactivity timeout in minutes before idling websocket closes");

        createdCount += seedIfMissing("websocket.close.recheck.minutes",
                String.valueOf(runtimeAutomationProperties.getDefaults().getWebsocketCloseRecheckMinutes()),
                "INTEGER", "Interval in minutes to recheck websocket closure");

        createdCount += seedIfMissing("retention.candles.days",
                String.valueOf(runtimeAutomationProperties.getDefaults().getRetentionCandlesDays()),
                "INTEGER", "Historical retention period for candle records in days");

        createdCount += seedIfMissing("retention.live.signals.days",
                String.valueOf(runtimeAutomationProperties.getDefaults().getRetentionLiveSignalsDays()),
                "INTEGER", "Historical retention period for simulation signals in days");

        createdCount += seedIfMissing("alert.stale.ticks.minutes",
                String.valueOf(runtimeAutomationProperties.getAlert().getStaleTicksMinutes()),
                "INTEGER", "Alert threshold for tick arrival staleness in minutes");

        createdCount += seedIfMissing("alert.parser.failures.threshold",
                String.valueOf(runtimeAutomationProperties.getAlert().getParserFailuresThreshold()),
                "INTEGER", "Alert threshold for consecutive websocket parse errors");

        createdCount += seedIfMissing("schedule.morning.maintenance.cron",
                "0 0 7 * * MON-FRI",
                "CRON", "Daily morning maintenance trigger cron expression (07:00 AM)");

        createdCount += seedIfMissing("schedule.premarket.data.cron",
                "0 0 8 * * MON-FRI",
                "CRON", "Daily pre-market data preparation pipeline trigger cron expression (08:00 AM)");

        createdCount += seedIfMissing("schedule.live.start.cron",
                "0 55 8 * * MON-FRI",
                "CRON", "Daily live market data start trigger cron expression (08:55 AM)");

        createdCount += seedIfMissing("schedule.minute.rollover.cron",
                "5 * 9-15 * * MON-FRI",
                "CRON", "Clock-based minute candle rollover trigger cron expression (5s grace period)");

        createdCount += seedIfMissing("schedule.zone",
                "Asia/Kolkata",
                "STRING", "Timezone identifier for scheduler triggers");

        return createdCount;
    }

    private int seedIfMissing(String name, String defaultValue, String valueType, String description) {
        if (runtimeSettingRepository.findByName(name).isEmpty()) {
            upsert(name, defaultValue, valueType, description, "system");
            return 1;
        }
        return 0;
    }
}