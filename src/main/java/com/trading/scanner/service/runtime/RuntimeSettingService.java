package com.trading.scanner.service.runtime;

import com.trading.scanner.config.RuntimeAutomationProperties;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.RuntimeSetting;
import com.trading.scanner.repository.RuntimeSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RuntimeSettingService {

    private static final String GLOBAL_SCOPE = "GLOBAL";
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final RuntimeSettingRepository runtimeSettingRepository;
    private final RuntimeAutomationProperties runtimeAutomationProperties;
    private final TimeProvider timeProvider;

    @Transactional(readOnly = true)
    public LocalTime getTime(String key, LocalTime fallback) {
        return runtimeSettingRepository.findBySettingKeyAndScopeAndIsActiveTrue(key, GLOBAL_SCOPE)
                .map(RuntimeSetting::getSettingValue)
                .map(value -> LocalTime.parse(value, TIME_FORMAT))
                .orElse(fallback);
    }

    @Transactional(readOnly = true)
    public int getInt(String key, int fallback) {
        return runtimeSettingRepository.findBySettingKeyAndScopeAndIsActiveTrue(key, GLOBAL_SCOPE)
                .map(RuntimeSetting::getSettingValue)
                .map(Integer::parseInt)
                .orElse(fallback);
    }

    @Transactional(readOnly = true)
    public String getString(String key, String fallback) {
        return runtimeSettingRepository.findBySettingKeyAndScopeAndIsActiveTrue(key, GLOBAL_SCOPE)
                .map(RuntimeSetting::getSettingValue)
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
        return runtimeSettingRepository.findByIsActiveTrueOrderByScopeAscSettingKeyAsc();
    }

    @Transactional
    public RuntimeSetting upsert(String key, String value, String valueType, String description, String updatedBy) {
        RuntimeSetting setting = runtimeSettingRepository.findBySettingKeyAndScopeAndIsActiveTrue(key, GLOBAL_SCOPE)
                .orElseGet(() -> RuntimeSetting.builder()
                        .settingKey(key)
                        .scope(GLOBAL_SCOPE)
                        .isActive(true)
                        .version(0)
                        .build());

        setting.setSettingValue(value);
        setting.setValueType(valueType);
        setting.setDescription(description);
        setting.setUpdatedAt(timeProvider.nowDateTime());
        setting.setUpdatedBy(updatedBy);
        setting.setVersion(setting.getVersion() == null ? 0 : setting.getVersion() + 1);

        return runtimeSettingRepository.save(setting);
    }
}