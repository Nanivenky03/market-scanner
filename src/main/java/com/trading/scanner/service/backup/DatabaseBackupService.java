package com.trading.scanner.service.backup;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Production automated database backup engine using PostgreSQL's native pg_dump.
 * Exports compressed custom format dumps (-Fc), enforces a rolling 10-file retention
 * policy, and supports zero-touch directory auto-creation across Windows and Linux.
 */
@Service
@Slf4j
public class DatabaseBackupService {

    private static final Pattern JDBC_URL_PATTERN = Pattern.compile(
            "jdbc:postgresql://([^:/]+)(?::(\\d+))?/([^?]+)"
    );

    private static final List<String> KNOWN_WINDOWS_PG_PATHS = List.of(
            "C:\\Program Files\\PostgreSQL\\17\\bin\\pg_dump.exe",
            "C:\\Program Files\\PostgreSQL\\16\\bin\\pg_dump.exe",
            "C:\\Program Files\\PostgreSQL\\15\\bin\\pg_dump.exe",
            "C:\\Program Files\\PostgreSQL\\14\\bin\\pg_dump.exe"
    );

    @Value("${backup.directory:backups}")
    private String backupDirectory;

    @Value("${backup.pgdump.path:pg_dump}")
    private String pgDumpPath;

    @Value("${backup.retention.files:10}")
    private int retentionFiles;

    @Value("${spring.datasource.url:jdbc:postgresql://localhost:5432/mithron_dev}")
    private String datasourceUrl;

    @Value("${spring.datasource.username:postgres}")
    private String datasourceUsername;

    @Value("${spring.datasource.password:postgres}")
    private String datasourcePassword;

    @Value("${spring.profiles.active:dev}")
    private String activeProfile;

    public record BackupResult(
            boolean success,
            String filePath,
            long sizeBytes,
            int totalBackupsRetained,
            int deletedOldBackups,
            String message
    ) {}

    /**
     * Executes a PostgreSQL database dump using pg_dump with custom compression (-Fc).
     */
    public synchronized BackupResult createBackup(String triggerReason) {
        log.info("[BACKUP] Starting automated database backup. triggerReason='{}', directory='{}'",
                triggerReason, backupDirectory);

        try {
            Path targetDir = Paths.get(backupDirectory).toAbsolutePath().normalize();
            if (!Files.exists(targetDir)) {
                Files.createDirectories(targetDir);
                log.info("[BACKUP] Created database backup directory: {}", targetDir);
            }

            String executable = resolvePgDumpExecutable();
            DatabaseConnectionDetails db = parseConnectionDetails(datasourceUrl);

            String dateStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
            String fileName = String.format("mithron_%s_%s.dump", activeProfile, dateStr);
            Path targetFile = targetDir.resolve(fileName);

            List<String> command = new ArrayList<>(List.of(
                    executable,
                    "-h", db.host(),
                    "-p", String.valueOf(db.port()),
                    "-U", datasourceUsername,
                    "-d", db.databaseName(),
                    "-Fc",
                    "-f", targetFile.toString()
            ));

            log.info("[BACKUP] Executing command: {} -h {} -p {} -U {} -d {} -Fc -f {}",
                    executable, db.host(), db.port(), datasourceUsername, db.databaseName(), targetFile);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.environment().put("PGPASSWORD", datasourcePassword != null ? datasourcePassword : "");
            pb.redirectErrorStream(true);

            Process process = pb.start();
            StringBuilder processOutput = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    processOutput.append(line).append(System.lineSeparator());
                }
            }

            boolean finished = process.waitFor(10, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                log.error("[BACKUP] pg_dump timed out after 10 minutes");
                return new BackupResult(false, null, 0, 0, 0, "pg_dump timed out after 10 minutes");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.error("[BACKUP] pg_dump failed with exitCode={}. Output: {}", exitCode, processOutput);
                return new BackupResult(false, null, 0, 0, 0,
                        "pg_dump exited with error code " + exitCode + ": " + processOutput);
            }

            long sizeBytes = Files.exists(targetFile) ? Files.size(targetFile) : 0L;
            log.info("[BACKUP] Database backup completed successfully: file={}, size={} MB",
                    targetFile.getFileName(), String.format("%.2f", sizeBytes / (1024.0 * 1024.0)));

            int deletedCount = purgeOldBackups(targetDir);

            int remainingCount = countExistingBackups(targetDir);

            return new BackupResult(
                    true,
                    targetFile.toString(),
                    sizeBytes,
                    remainingCount,
                    deletedCount,
                    "Backup created successfully (" + String.format("%.2f", sizeBytes / (1024.0 * 1024.0)) + " MB)"
            );

        } catch (Exception ex) {
            log.error("[BACKUP] Exception occurred while executing database backup: {}", ex.getMessage(), ex);
            return new BackupResult(false, null, 0, 0, 0, "Backup error: " + ex.getMessage());
        }
    }

    /**
     * Purges backup files beyond the retention limit, keeping the newest N files.
     */
    public int purgeOldBackups(Path targetDir) {
        try {
            File[] files = targetDir.toFile().listFiles((dir, name) -> name.endsWith(".dump"));
            if (files == null || files.length <= retentionFiles) {
                return 0;
            }

            List<File> fileList = new ArrayList<>(Arrays.asList(files));
            fileList.sort(Comparator.comparingLong(File::lastModified).reversed());

            int deleted = 0;
            for (int i = retentionFiles; i < fileList.size(); i++) {
                File toDelete = fileList.get(i);
                if (toDelete.delete()) {
                    log.info("[BACKUP] Purged expired backup file: {}", toDelete.getName());
                    deleted++;
                } else {
                    log.warn("[BACKUP] Failed to delete expired backup file: {}", toDelete.getName());
                }
            }
            return deleted;
        } catch (Exception ex) {
            log.warn("[BACKUP] Error during backup retention purge: {}", ex.getMessage());
            return 0;
        }
    }

    private int countExistingBackups(Path targetDir) {
        File[] files = targetDir.toFile().listFiles((dir, name) -> name.endsWith(".dump"));
        return files != null ? files.length : 0;
    }

    private String resolvePgDumpExecutable() {
        if (pgDumpPath != null && !pgDumpPath.isBlank() && !"pg_dump".equalsIgnoreCase(pgDumpPath.trim())) {
            File custom = new File(pgDumpPath);
            if (custom.exists() && custom.canExecute()) {
                return custom.getAbsolutePath();
            }
        }

        // On Windows, try standard installation directories if simple "pg_dump" is configured
        boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        if (isWindows) {
            for (String knownPath : KNOWN_WINDOWS_PG_PATHS) {
                File file = new File(knownPath);
                if (file.exists()) {
                    log.info("[BACKUP] Detected Windows PostgreSQL pg_dump binary at: {}", knownPath);
                    return file.getAbsolutePath();
                }
            }
        }

        return pgDumpPath != null && !pgDumpPath.isBlank() ? pgDumpPath.trim() : "pg_dump";
    }

    private record DatabaseConnectionDetails(String host, int port, String databaseName) {}

    private DatabaseConnectionDetails parseConnectionDetails(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return new DatabaseConnectionDetails("localhost", 5432, "mithron_dev");
        }
        Matcher matcher = JDBC_URL_PATTERN.matcher(jdbcUrl);
        if (matcher.find()) {
            String host = matcher.group(1);
            String portStr = matcher.group(2);
            String db = matcher.group(3);
            int port = (portStr != null && !portStr.isBlank()) ? Integer.parseInt(portStr) : 5432;
            return new DatabaseConnectionDetails(host, port, db);
        }
        return new DatabaseConnectionDetails("localhost", 5432, "mithron_dev");
    }
}
