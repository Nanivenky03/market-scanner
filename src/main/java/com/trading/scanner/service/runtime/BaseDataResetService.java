package com.trading.scanner.service.runtime;

import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.service.provider.angelone.LiveMarketSnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class BaseDataResetService {

    public static final String BOOTSTRAP_STATUS_KEY = "runtime.bootstrap.status";

    public static final String BOOTSTRAP_REQUIRED = "REQUIRED";

    private static final String UPDATED_BY_SYSTEM = "system";

    private static final List<String> RESET_TABLES = List.of(
            "live_minute_resolution",
            "market_minute_snapshot",
            "eod_data_entry",
            "daily_candle_summary",
            "daily_stock_context",
            "daily_data_status",
            "live_feed_state",
            "backfill_job",
            "market_candles",
            "stock_prices",
            "volume_daily_baseline",
            "volume_time_window_baseline",
            "runtime_alert_state",
            "live_simulation_signal");

    private final JdbcTemplate jdbcTemplate;
    private final RuntimeAutomationService runtimeAutomationService;
    private final RuntimeSettingService runtimeSettingService;
    private final LiveMarketSnapshotService liveMarketSnapshotService;
    private final TimeProvider timeProvider;

    @Transactional
    public ResetResult resetBaseData() {
        List<String> deletedTables = new ArrayList<>();
        List<String> skippedTables = new ArrayList<>();

        try {
            runtimeAutomationService.flushAndDisconnect();
        } catch (Exception ex) {
            log.warn(
                    "Could not flush/disconnect before base reset: {}",
                    ex.getMessage(),
                    ex);
        }

        try {
            runtimeAutomationService.clearBrokerSession();
        } catch (Exception ex) {
            log.warn(
                    "Could not clear broker session before base reset: {}",
                    ex.getMessage(),
                    ex);
        }

        liveMarketSnapshotService.clear();

        for (String table : RESET_TABLES) {
            if (!tableExists(table)) {
                skippedTables.add(table);
                continue;
            }

            jdbcTemplate.update("DELETE FROM \"" + table + "\"");
            deletedTables.add(table);
        }

        LocalDateTime now = timeProvider.nowDateTime();

        runtimeSettingService.upsert(
                BaseDataResetService.BOOTSTRAP_STATUS_KEY,
                BaseDataResetService.BOOTSTRAP_REQUIRED,
                "STRING",
                "Historical bootstrap is required after base-data reset",
                UPDATED_BY_SYSTEM);

        runtimeSettingService.upsert(
                "runtime.bootstrap.last.reset.at",
                now.toString(),
                "DATETIME",
                "Last base-data reset timestamp",
                UPDATED_BY_SYSTEM);

        return new ResetResult(
                now,
                deletedTables,
                skippedTables,
                BOOTSTRAP_REQUIRED,
                "Base market data reset completed; historical bootstrap required");
    }

    private boolean tableExists(String expectedName) {
        Boolean exists = jdbcTemplate.execute(
                (ConnectionCallback<Boolean>) connection -> {
                    try (ResultSet resultSet = connection.getMetaData().getTables(
                            null,
                            null,
                            null,
                            new String[] { "TABLE" })) {

                        while (resultSet.next()) {
                            String actualName = resultSet.getString("TABLE_NAME");

                            if (expectedName.equalsIgnoreCase(actualName)) {
                                return true;
                            }
                        }

                        return false;
                    }
                });

        return Boolean.TRUE.equals(exists);
    }

    public record ResetResult(
            LocalDateTime resetAt,
            List<String> deletedTables,
            List<String> skippedTables,
            String bootstrapStatus,
            String message) {
    }
}
