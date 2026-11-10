package com.trading.scanner.service.backup;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseBackupServiceTest {

    @Test
    @DisplayName("GIVEN 15 backup files WHEN purgeOldBackups THEN retains only 10 newest files")
    void testPurgeOldBackupsRetainsTop10(@TempDir Path tempDir) throws IOException, InterruptedException {
        DatabaseBackupService service = new DatabaseBackupService();
        ReflectionTestUtils.setField(service, "retentionFiles", 10);
        ReflectionTestUtils.setField(service, "backupDirectory", tempDir.toString());

        // Create 15 dummy dump files with spaced modification timestamps
        for (int i = 1; i <= 15; i++) {
            Path file = tempDir.resolve("mithron_prod_2026-10-" + String.format("%02d", i) + ".dump");
            Files.writeString(file, "dummy dump content " + i);
            File f = file.toFile();
            f.setLastModified(System.currentTimeMillis() - ((16 - i) * 100_000L));
        }

        File[] initialFiles = tempDir.toFile().listFiles((dir, name) -> name.endsWith(".dump"));
        assertNotNull(initialFiles);
        assertEquals(15, initialFiles.length);

        int purgedCount = service.purgeOldBackups(tempDir);
        assertEquals(5, purgedCount);

        File[] remainingFiles = tempDir.toFile().listFiles((dir, name) -> name.endsWith(".dump"));
        assertNotNull(remainingFiles);
        assertEquals(10, remainingFiles.length);
    }

    @Test
    @DisplayName("GIVEN non-existent backup directory WHEN createBackup called THEN directory is auto-created")
    void testBackupDirectoryAutoCreation(@TempDir Path tempDir) {
        Path nonExistent = tempDir.resolve("auto_created_backups");
        assertFalse(Files.exists(nonExistent));

        DatabaseBackupService service = new DatabaseBackupService();
        ReflectionTestUtils.setField(service, "backupDirectory", nonExistent.toString());
        ReflectionTestUtils.setField(service, "pgDumpPath", "non_existent_pg_dump_cmd");
        ReflectionTestUtils.setField(service, "datasourceUrl", "jdbc:postgresql://localhost:5432/testdb");
        ReflectionTestUtils.setField(service, "datasourceUsername", "postgres");
        ReflectionTestUtils.setField(service, "datasourcePassword", "postgres");
        ReflectionTestUtils.setField(service, "activeProfile", "dev");

        // The process execution will fail because pg_dump executable is fake,
        // but the directory must have been created successfully prior to process invocation!
        DatabaseBackupService.BackupResult result = service.createBackup("TEST_RUN");

        assertTrue(Files.exists(nonExistent), "Backup directory must be automatically created");
        assertFalse(result.success());
    }
}
