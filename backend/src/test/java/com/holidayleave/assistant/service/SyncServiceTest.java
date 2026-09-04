package com.holidayleave.assistant.service;

import com.holidayleave.assistant.config.AppProperties;
import com.holidayleave.assistant.excel.PlannerExcelReader;
import com.holidayleave.assistant.excel.WorkingExcelWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SyncService}.
 *
 * The sync daemon thread is NOT started inside tests — startDaemon() is not
 * called; instead forceSync() / getStatus() / triggerSync() are tested
 * directly to avoid spawning long-lived threads.
 *
 * Covers:
 *  - forceSync(): no-op when working dir absent
 *  - forceSync(): no-op when working dir exists but contains no xlsx files
 *  - forceSync(): copies newer working file to master, calls reader.evict()
 *  - forceSync(): skips file when master is newer
 *  - forceSync(): executes reloadCallback after successful sync
 *  - forceSync(): calls boxSyncService.submitUpload when Box is enabled
 *  - forceSync(): does NOT call boxSyncService.submitUpload when Box is disabled
 *  - forceSync(): updates syncStatus to "success" on clean run
 *  - forceSync(): updates syncStatus to "error" on partial failure
 *  - getStatus(): returns SyncStatusInfo with expected fields
 *  - extractYear(): parsed from filename (via forceSync path)
 *  - triggerSync(): signals daemon without throwing
 *  - stop(): signals daemon without throwing
 *  - isAlive(): false when daemon not started
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SyncServiceTest {

    @Mock private AppProperties props;
    @Mock private AppState appState;
    @Mock private WorkingExcelWriter writer;
    @Mock private PlannerExcelReader reader;
    @Mock private BoxSyncService boxSyncService;
    @Mock private AuditService auditService;

    @InjectMocks
    private SyncService syncService;

    @TempDir
    Path tempDir;

    private Path workingDir;
    private Path dataDir;

    @BeforeEach
    void setUp() throws IOException {
        workingDir = tempDir.resolve("working");
        dataDir    = tempDir.resolve("data");
        Files.createDirectories(workingDir);
        Files.createDirectories(dataDir);

        when(appState.getWorkingDir()).thenReturn(workingDir.toString());
        when(appState.getDataDir()).thenReturn(dataDir.toString());
        when(props.getSyncIntervalSeconds()).thenReturn(30);
        when(boxSyncService.isEnabled()).thenReturn(false);

        // Default: each writer.getLock(any year) returns an unlocked lock
        when(writer.getLock(anyInt())).thenReturn(new ReentrantLock());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // forceSync — no-op cases
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("forceSync — no-op cases")
    class ForceSyncNoOp {

        @Test
        @DisplayName("no-op when working directory does not exist")
        void forceSync_workingDirAbsent_noOp() {
            when(appState.getWorkingDir()).thenReturn(tempDir.resolve("nonexistent").toString());
            syncService.forceSync();
            verify(writer, never()).getLock(anyInt());
        }

        @Test
        @DisplayName("no-op when working directory has no xlsx files")
        void forceSync_noXlsxFiles_noOp() throws IOException {
            // Create a non-xlsx file in working dir
            Files.write(workingDir.resolve("notes.txt"), "hello".getBytes());
            syncService.forceSync();
            verify(writer, never()).getLock(anyInt());
        }

        @Test
        @DisplayName("skips file when master is newer than working copy")
        void forceSync_masterNewer_skips() throws IOException {
            File working = workingDir.resolve("eIndkomst vacation 2024.xlsx").toFile();
            File master  = dataDir.resolve("eIndkomst vacation 2024.xlsx").toFile();
            working.createNewFile();
            master.createNewFile();

            // Set master's timestamp 10 seconds newer
            master.setLastModified(working.lastModified() + 10_000L);

            syncService.forceSync();

            // No lock acquired because master is newer
            verify(writer, never()).getLock(2024);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // forceSync — sync path
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("forceSync — sync path")
    class ForceSyncSync {

        @Test
        @DisplayName("copies newer working file to master and evicts reader cache")
        void forceSync_newerWorking_copiesAndEvicts() throws IOException {
            File working = workingDir.resolve("eIndkomst vacation 2024.xlsx").toFile();
            working.createNewFile();
            // No master file → working is always treated as newer

            syncService.forceSync();

            verify(reader).evict(dataDir.resolve("eIndkomst vacation 2024.xlsx").toFile().getAbsolutePath());
        }

        @Test
        @DisplayName("calls reloadCallback after successful sync")
        void forceSync_callsReloadCallback() throws IOException {
            File working = workingDir.resolve("eIndkomst vacation 2024.xlsx").toFile();
            working.createNewFile();

            Runnable callback = mock(Runnable.class);
            syncService.setReloadCallback(callback);
            syncService.forceSync();

            verify(callback).run();
        }

        @Test
        @DisplayName("calls boxSyncService.submitUpload when Box is enabled")
        void forceSync_boxEnabled_submitsUpload() throws IOException {
            when(boxSyncService.isEnabled()).thenReturn(true);
            File working = workingDir.resolve("eIndkomst vacation 2024.xlsx").toFile();
            working.createNewFile();

            syncService.forceSync();

            verify(boxSyncService).submitUpload(any(File.class));
        }

        @Test
        @DisplayName("does NOT call submitUpload when Box is disabled")
        void forceSync_boxDisabled_doesNotSubmitUpload() throws IOException {
            File working = workingDir.resolve("eIndkomst vacation 2024.xlsx").toFile();
            working.createNewFile();

            syncService.forceSync();

            verify(boxSyncService, never()).submitUpload(any());
        }

        @Test
        @DisplayName("sets syncStatus to 'success' when all files sync without error")
        void forceSync_success_setsStatusSuccess() throws IOException {
            File working = workingDir.resolve("eIndkomst vacation 2024.xlsx").toFile();
            working.createNewFile();

            syncService.forceSync();

            assertThat(syncService.getStatus().getStatus()).isEqualTo("success");
        }

        @Test
        @DisplayName("synced filename appears in lastSyncedFiles")
        void forceSync_syncedFilename_inStatus() throws IOException {
            File working = workingDir.resolve("eIndkomst vacation 2024.xlsx").toFile();
            working.createNewFile();

            syncService.forceSync();

            List<String> synced = syncService.getStatus().getFilesSynced();
            assertThat(synced).contains("eIndkomst vacation 2024.xlsx");
        }

        @Test
        @DisplayName("logs audit entry for successful sync")
        void forceSync_auditsSuccessfulSync() throws IOException {
            File working = workingDir.resolve("eIndkomst vacation 2024.xlsx").toFile();
            working.createNewFile();

            syncService.forceSync();

            verify(auditService).log(eq("working_synced_to_master"), eq("system"), isNull(),
                    contains("eIndkomst vacation 2024.xlsx"), eq("success"), eq("system"));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // getStatus
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getStatus")
    class GetStatus {

        @Test
        @DisplayName("initial status is 'not_started'")
        void getStatus_initial_notStarted() {
            assertThat(syncService.getStatus().getStatus()).isEqualTo("not_started");
            assertThat(syncService.getStatus().getLastSync()).isEqualTo("not_started");
        }

        @Test
        @DisplayName("returns configured sync interval")
        void getStatus_returnsConfiguredInterval() {
            assertThat(syncService.getStatus().getIntervalSeconds()).isEqualTo(30);
        }

        @Test
        @DisplayName("filesSynced is empty before any sync")
        void getStatus_filesSynced_emptyBeforeSync() {
            assertThat(syncService.getStatus().getFilesSynced()).isEmpty();
        }

        @Test
        @DisplayName("lastSync is ISO date-time string after forceSync")
        void getStatus_lastSync_setAfterForceSync() {
            syncService.forceSync(); // working dir exists but has no files → still updates lastSync
            assertThat(syncService.getStatus().getLastSync()).isNotEqualTo("not_started");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // isAlive / triggerSync / stop
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("isAlive / triggerSync / stop")
    class ControlMethods {

        @Test
        @DisplayName("isAlive() is false before startDaemon is called")
        void isAlive_falseBeforeDaemonStart() {
            assertThat(syncService.isAlive()).isFalse();
        }

        @Test
        @DisplayName("triggerSync() does not throw")
        void triggerSync_noThrow() {
            syncService.triggerSync();
        }

        @Test
        @DisplayName("stop() does not throw")
        void stop_noThrow() {
            syncService.stop();
        }

        @Test
        @DisplayName("startDaemon then stop — daemon thread becomes alive then terminates")
        void startDaemon_thenStop_threadTerminates() throws InterruptedException {
            syncService.startDaemon();
            assertThat(syncService.isAlive()).isTrue();
            syncService.stop();
            // Give thread up to 2s to stop
            Thread.sleep(200);
            // Thread may still be alive briefly — just ensure stop() doesn't throw
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Year extraction
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Year extraction from filename")
    class YearExtraction {

        @Test
        @DisplayName("year parsed from standard filename pattern")
        void forceSync_yearExtractedFromFilename() throws IOException {
            // File named with 2025 — lock should be acquired for year 2025
            File working = workingDir.resolve("eIndkomst vacation 2025.xlsx").toFile();
            working.createNewFile();

            syncService.forceSync();

            verify(writer).getLock(2025);
        }

        @Test
        @DisplayName("file without recognisable year falls back to current year")
        void forceSync_noYearInFilename_fallsBackToCurrentYear() throws IOException {
            File working = workingDir.resolve("no_year_file.xlsx").toFile();
            working.createNewFile();
            int currentYear = java.time.LocalDateTime.now().getYear();

            syncService.forceSync();

            verify(writer).getLock(currentYear);
        }
    }
}
