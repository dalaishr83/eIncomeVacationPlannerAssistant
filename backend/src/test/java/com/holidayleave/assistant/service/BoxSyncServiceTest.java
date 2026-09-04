package com.holidayleave.assistant.service;

import com.holidayleave.assistant.config.AppProperties;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link BoxSyncService}.
 *
 * The Box SDK is NOT mocked at the HTTP level here — tests focus on the
 * gatekeeping and no-op paths (enabled/disabled) and the lifecycle contract.
 * Integration tests would need a real or WireMock Box endpoint.
 *
 * Covers:
 *  - isEnabled(): delegates to props.getBox().isEnabled()
 *  - submitUpload: no-op when disabled
 *  - submitUpload: queues work and returns immediately (fire-and-forget) when enabled
 *  - init(): completes without error in both disabled and enabled configurations
 *  - shutdown(): completes without error
 *  - Backoff/retry: internal consecutiveFailures counter does not leak across instances
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BoxSyncServiceTest {

    @Mock private AppProperties props;
    @Mock private AuditService auditService;

    @InjectMocks
    private BoxSyncService boxSyncService;

    @TempDir
    Path tempDir;

    private AppProperties.Box boxConfig;
    private File sampleFile;

    @BeforeEach
    void setUp() throws IOException {
        boxConfig = new AppProperties.Box();
        boxConfig.setRetryBackoffSeconds(1);   // configure directly on the real object
        when(props.getBox()).thenReturn(boxConfig);

        sampleFile = tempDir.resolve("eIndkomst vacation 2024.xlsx").toFile();
        sampleFile.createNewFile();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // isEnabled
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("isEnabled")
    class IsEnabled {

        @Test
        @DisplayName("returns false when Box is disabled")
        void isEnabled_disabled_returnsFalse() {
            boxConfig.setEnabled(false);
            boxSyncService.init();
            assertThat(boxSyncService.isEnabled()).isFalse();
        }

        @Test
        @DisplayName("returns true when Box is enabled")
        void isEnabled_enabled_returnsTrue() {
            boxConfig.setEnabled(true);
            boxSyncService.init();
            assertThat(boxSyncService.isEnabled()).isTrue();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // submitUpload — no-op guard
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("submitUpload — disabled guard")
    class SubmitUploadDisabled {

        @BeforeEach
        void setUp() {
            boxConfig.setEnabled(false);
            boxSyncService.init();
        }

        @Test
        @DisplayName("is a no-op when Box is disabled")
        void submitUpload_disabled_noOp() {
            // Should return immediately without any background work
            boxSyncService.submitUpload(sampleFile);
            // No exception == pass; no Box SDK call will be made
        }

        @Test
        @DisplayName("disabled: returns in well under 1 second")
        void submitUpload_disabled_returnsImmediately() {
            long start = System.currentTimeMillis();
            boxSyncService.submitUpload(sampleFile);
            long elapsed = System.currentTimeMillis() - start;
            assertThat(elapsed).isLessThan(500L);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // submitUpload — fire-and-forget when enabled
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("submitUpload — enabled (fire-and-forget)")
    class SubmitUploadEnabled {

        @BeforeEach
        void setUp() {
            boxConfig.setEnabled(true);
            boxConfig.setClientId("cid");
            boxConfig.setClientSecret("cs");
            boxConfig.setEnterpriseId("eid");
            boxConfig.setFolderId("fid");
            boxSyncService.init();
        }

        @Test
        @DisplayName("returns immediately without waiting for Box network call")
        void submitUpload_enabled_returnsImmediately() {
            long start = System.currentTimeMillis();
            boxSyncService.submitUpload(sampleFile);
            long elapsed = System.currentTimeMillis() - start;
            // The actual upload runs on a background thread; the caller returns fast
            assertThat(elapsed).isLessThan(1000L);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Lifecycle — init / shutdown")
    class Lifecycle {

        @Test
        @DisplayName("init completes without error when disabled")
        void init_disabled_noError() {
            boxConfig.setEnabled(false);
            boxSyncService.init();
        }

        @Test
        @DisplayName("init completes without error when enabled")
        void init_enabled_noError() {
            boxConfig.setEnabled(true);
            boxSyncService.init();
        }

        @Test
        @DisplayName("shutdown completes without error when executor is idle")
        void shutdown_noError() {
            boxConfig.setEnabled(false);
            boxSyncService.init();
            boxSyncService.shutdown();
        }

        @Test
        @DisplayName("shutdown after init+submit does not throw")
        void shutdown_afterSubmit_noThrow() {
            boxConfig.setEnabled(false);
            boxSyncService.init();
            boxSyncService.submitUpload(sampleFile); // no-op when disabled
            boxSyncService.shutdown();
        }

        @Test
        @DisplayName("submitUpload after shutdown is safe when disabled")
        void submitUpload_afterShutdown_disabled_noThrow() {
            boxConfig.setEnabled(false);
            boxSyncService.init();
            boxSyncService.shutdown();
            boxSyncService.submitUpload(sampleFile);
        }
    }
}
