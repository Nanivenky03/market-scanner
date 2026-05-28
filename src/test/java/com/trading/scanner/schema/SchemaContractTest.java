package com.trading.scanner.schema;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SchemaContractTest {

    private Path tempDbPath;

    @AfterEach
    void cleanup() throws Exception {
        if (tempDbPath != null) {
            Files.deleteIfExists(tempDbPath);
        }
    }

    @Test
    void flywayBaseline_shouldCreateExpectedTablesAndConstraints() throws Exception {
        tempDbPath = Files.createTempFile("market-scanner-schema-", ".db");

        String jdbcUrl = "jdbc:sqlite:" + tempDbPath.toAbsolutePath();

        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, null, null)
                .locations("classpath:db/migration/sqlite")
                .load();

        flyway.migrate();

        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement stmt = conn.createStatement()) {

            assertTableExists(stmt, "flyway_schema_history");
            assertTableExists(stmt, "stock_universe");
            assertTableExists(stmt, "stock_prices");
            assertTableExists(stmt, "scan_execution_state");
            assertTableExists(stmt, "scan_results");
            assertTableExists(stmt, "signal_outcomes");
            assertTableExists(stmt, "scanner_runs");
            assertTableExists(stmt, "simulation_state");
            assertTableExists(stmt, "emergency_closure");

            assertMigration001Applied(stmt);

            assertIndexExists(stmt, "stock_universe", "sqlite_autoindex_stock_universe_1");
            assertIndexExists(stmt, "stock_prices", "sqlite_autoindex_stock_prices_1");
            assertIndexExists(stmt, "scan_execution_state", "sqlite_autoindex_scan_execution_state_1");
            assertIndexExists(stmt, "scan_results", "sqlite_autoindex_scan_results_1");
            assertIndexExists(stmt, "signal_outcomes", "sqlite_autoindex_signal_outcomes_1");

            assertIndexExists(stmt, "stock_prices", "idx_stock_prices_symbol_date");
            assertIndexExists(stmt, "stock_prices", "idx_stock_prices_date");
            assertIndexExists(stmt, "scan_results", "idx_scan_results_scan_date");
            assertIndexExists(stmt, "signal_outcomes", "idx_signal_outcomes_signal_id");
            assertIndexExists(stmt, "signal_outcomes", "idx_signal_outcomes_horizon_days");

            assertSignalOutcomesForeignKeyExists(stmt);
        }
    }

    private void assertMigration001Applied(Statement stmt) throws Exception {
        try (ResultSet rs = stmt.executeQuery(
                "select version, description, success from flyway_schema_history order by installed_rank"
        )) {
            assertTrue(rs.next(), "flyway_schema_history should contain at least one migration");
            assertEquals("001", rs.getString("version"));
            assertEquals("baseline core schema", rs.getString("description"));
            assertEquals(1, rs.getInt("success"));
        }
    }

    private void assertTableExists(Statement stmt, String tableName) throws Exception {
        try (ResultSet rs = stmt.executeQuery(
                "select name from sqlite_master where type='table' and name='" + tableName + "'"
        )) {
            assertTrue(rs.next(), "Expected table to exist: " + tableName);
        }
    }

    private void assertIndexExists(Statement stmt, String tableName, String expectedIndex) throws Exception {
        Set<String> indexes = new HashSet<>();
        try (ResultSet rs = stmt.executeQuery("PRAGMA index_list('" + tableName + "')")) {
            while (rs.next()) {
                indexes.add(rs.getString("name"));
            }
        }
        assertTrue(indexes.contains(expectedIndex),
                "Expected index '" + expectedIndex + "' on table '" + tableName + "', but found " + indexes);
    }

    private void assertSignalOutcomesForeignKeyExists(Statement stmt) throws Exception {
        boolean found = false;
        try (ResultSet rs = stmt.executeQuery("PRAGMA foreign_key_list('signal_outcomes')")) {
            while (rs.next()) {
                String referencedTable = rs.getString("table");
                String fromColumn = rs.getString("from");
                String toColumn = rs.getString("to");

                if ("scan_results".equalsIgnoreCase(referencedTable)
                        && "signal_id".equalsIgnoreCase(fromColumn)
                        && "id".equalsIgnoreCase(toColumn)) {
                    found = true;
                    break;
                }
            }
        }
        assertTrue(found, "Expected FK signal_outcomes.signal_id -> scan_results.id");
    }
}