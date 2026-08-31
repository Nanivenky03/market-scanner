package com.trading.scanner.schema;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "SCHEMA_TEST_DB_URL", matches = ".+")
class SchemaContractTest {

    private static final List<String> EXPECTED_TABLES = List.of(
            "stock_universe",
            "stock_prices",
            "scan_execution_state",
            "scanner_runs",
            "scan_results",
            "signal_outcomes",
            "simulation_state",
            "emergency_closure",
            "instrument_master",
            "simulation_run_group",
            "simulation_variant",
            "simulation_trade",
            "market_candles",
            "live_simulation_signal",
            "daily_stock_context",
            "runtime_setting",
            "runtime_alert_state",
            "exchange_holiday",
            "market_minute_snapshot",
            "volume_daily_baseline",
            "volume_time_window_baseline",
            "daily_data_status",
            "daily_candle_summary",
            "backfill_job",
            "live_feed_state",
            "live_minute_resolution",
            "eod_data_entry");

    private static final Map<String, List<String>> REQUIRED_COLUMNS = Map.of(
            "market_candles",
            List.of(
                    "symbol",
                    "exchange",
                    "timeframe",
                    "candle_time",
                    "open_price",
                    "high_price",
                    "low_price",
                    "close_price",
                    "volume",
                    "quality_status",
                    "processing_status"),

            "live_feed_state",
            List.of(
                    "symbol",
                    "exchange",
                    "trading_date",
                    "cumulative_volume_today",
                    "health_status",
                    "subscription_active"),

            "backfill_job",
            List.of(
                    "symbol",
                    "exchange",
                    "trading_date",
                    "status",
                    "from_time",
                    "to_time",
                    "lease_until"),

            "eod_data_entry",
            List.of(
                    "symbol",
                    "exchange",
                    "trading_date",
                    "status"),

            "volume_daily_baseline",
            List.of(
                    "symbol",
                    "exchange",
                    "trading_date",
                    "avg_daily_volume_20",
                    "sample_days"),

            "volume_time_window_baseline",
            List.of(
                    "symbol",
                    "exchange",
                    "trading_date",
                    "session_minute",
                    "avg_cumulative_volume_20"));

    @Test
    void freshPostgresSchema_shouldMatchV13Contract()
            throws Exception {

        String url = requiredEnvironment("SCHEMA_TEST_DB_URL");

        String username = environmentOrDefault(
                "SCHEMA_TEST_DB_USERNAME",
                "postgres");

        String password = environmentOrDefault(
                "SCHEMA_TEST_DB_PASSWORD",
                "");

        Flyway flyway = Flyway.configure()
                .dataSource(
                        url,
                        username,
                        password)
                .locations(
                        "classpath:db/migration/postgresql")
                .load();

        flyway.migrate();

        try (Connection connection = DriverManager.getConnection(
                url,
                username,
                password)) {

            assertFlywayHistory(connection);

            for (String table : EXPECTED_TABLES) {
                assertTableExists(connection, table);
            }

            for (Map.Entry<String, List<String>> entry : REQUIRED_COLUMNS.entrySet()) {

                for (String column : entry.getValue()) {
                    assertColumnExists(
                            connection,
                            entry.getKey(),
                            column);
                }
            }

            assertQualityStatusConstraint(connection);
        }
    }

    private void assertFlywayHistory(
            Connection connection)
            throws SQLException {

        String countSql = """
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE version IS NOT NULL
                  AND success = TRUE
                """;

        try (PreparedStatement statement = connection.prepareStatement(countSql);
                ResultSet resultSet = statement.executeQuery()) {

            assertTrue(resultSet.next());
            assertEquals(31, resultSet.getInt(1));
        }

        String versionsSql = """
                SELECT version
                FROM flyway_schema_history
                WHERE version IS NOT NULL
                ORDER BY installed_rank
                """;

        try (PreparedStatement statement = connection.prepareStatement(versionsSql);
                ResultSet resultSet = statement.executeQuery()) {

            assertTrue(resultSet.next());
            assertEquals("001", resultSet.getString("version"));

            String lastVersion = null;

            while (resultSet.next()) {
                lastVersion = resultSet.getString("version");
            }

            assertEquals("031", lastVersion);
        }
    }

    private void assertTableExists(
            Connection connection,
            String table)
            throws SQLException {

        String sql = """
                SELECT 1
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name = ?
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, table);

            try (ResultSet resultSet = statement.executeQuery()) {

                assertTrue(
                        resultSet.next(),
                        "Expected table to exist: " + table);
            }
        }
    }

    private void assertColumnExists(
            Connection connection,
            String table,
            String column)
            throws SQLException {

        String sql = """
                SELECT 1
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = ?
                  AND column_name = ?
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, table);
            statement.setString(2, column);

            try (ResultSet resultSet = statement.executeQuery()) {

                assertTrue(
                        resultSet.next(),
                        "Expected column "
                                + table
                                + "."
                                + column);
            }
        }
    }

    private void assertQualityStatusConstraint(
            Connection connection)
            throws SQLException {

        String sql = """
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conrelid = 'market_candles'::regclass
                  AND conname =
                      'ck_market_candles_quality_status'
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {

            assertTrue(
                    resultSet.next(),
                    "quality_status constraint is missing");

            String definition = resultSet.getString(1);

            assertTrue(definition.contains("'LIVE'"));
            assertTrue(definition.contains("'REPAIRED'"));
            assertTrue(definition.contains("'RECONCILED'"));
            assertTrue(definition.contains("'SUSPECT'"));
        }
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);

        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Missing environment variable: " + name);
        }

        return value;
    }

    private String environmentOrDefault(
            String name,
            String defaultValue) {

        String value = System.getenv(name);

        return value == null
                ? defaultValue
                : value;
    }
}
