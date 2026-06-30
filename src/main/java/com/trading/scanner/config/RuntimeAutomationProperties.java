package com.trading.scanner.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "runtime")
@Data
public class RuntimeAutomationProperties {

    private final Live live = new Live();
    private final Housekeeping housekeeping = new Housekeeping();
    private final Defaults defaults = new Defaults();
    private final Alert alert = new Alert();

    @Data
    public static class Live {
        private boolean autoRun = false;
        private int subscriptionMode = 1;
    }

    @Data
    public static class Housekeeping {
        private boolean autoRun = true;
    }

    @Data
    public static class Defaults {
        private String angeloneLoginTime = "08:50";
        private String websocketConnectTime = "09:00";
        private String websocketDisconnectTime = "15:40";
        private String angeloneDisconnectTime = "16:00";
        private String housekeepingTime = "05:30";
        private String marketOpenTime = "09:15";
        private String marketCloseTime = "15:30";
        private int websocketIdleCloseMinutes = 10;
        private int websocketCloseRecheckMinutes = 10;
        private int retentionCandlesDays = 30;
        private int retentionLiveSignalsDays = 30;
    }

    @Data
    public static class Alert {
        private boolean autoRun = true;
        private long evaluationIntervalMs = 60000L;
        private int staleTicksMinutes = 10;
        private int parserFailuresThreshold = 10;

        private boolean webhookEnabled = false;
        private String webhookUrl = "";
        private long webhookTimeoutMs = 5000L;
        private String webhookMinSeverity = "HIGH";

        private boolean emailEnabled = false;
        private String emailTo = "";
        private String emailFrom = "";
        private String emailMinSeverity = "HIGH";
        private String emailSubjectPrefix = "[Market Scanner]";
    }
}