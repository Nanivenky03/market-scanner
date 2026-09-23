package com.trading.scanner.config;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Custom Flyway migration strategy.
 * Automatically executes flyway.repair() before flyway.migrate() to repair and
 * reconcile any migration script checksum mismatches (e.g., line ending / formatting changes)
 * in the database's flyway_schema_history table before executing migrations.
 */
@Slf4j
@Configuration
public class FlywayConfiguration {

    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy() {
        return flyway -> {
            log.info("Executing Flyway repair to reconcile schema history checksums...");
            flyway.repair();
            log.info("Executing Flyway database migration...");
            flyway.migrate();
            log.info("Flyway database migration completed successfully.");
        };
    }
}

