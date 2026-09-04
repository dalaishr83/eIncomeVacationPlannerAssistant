package com.holidayleave.assistant.controller;

import com.holidayleave.assistant.service.AppState;
import com.holidayleave.assistant.service.SyncService;
import com.holidayleave.assistant.service.SyncService.SyncStatusInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ReportsController}.
 *
 * Covers: serveReport for existing and missing files, syncStatus field mapping.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportsControllerTest {

    @TempDir Path tempDir;

    @Mock  private AppState     appState;
    @Mock  private SyncService  syncService;
    @InjectMocks private ReportsController controller;

    @BeforeEach
    void setUp() {
        when(appState.getReportsDir()).thenReturn(tempDir.toString());
    }

    // =========================================================================
    // serveReport
    // =========================================================================

    @Test
    void serveReport_existingFile_returns200WithHtml() throws Exception {
        Path report = tempDir.resolve("leave-2026.html");
        Files.write(report, ("<html><body>Report</body></html>").getBytes(java.nio.charset.StandardCharsets.UTF_8));

        ResponseEntity<?> resp = controller.serveReport("leave-2026.html");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
    }

    @Test
    void serveReport_missingFile_returns404() {
        ResponseEntity<?> resp = controller.serveReport("nonexistent.html");
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    void serveReport_directoryInsteadOfFile_returns404() throws Exception {
        // Create a directory named like a report file
        File dir = tempDir.resolve("mydir.html").toFile();
        dir.mkdir();

        ResponseEntity<?> resp = controller.serveReport("mydir.html");
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    void serveReport_noCacheHeadersPresent() throws Exception {
        Path report = tempDir.resolve("r.html");
        Files.write(report, ("content").getBytes(java.nio.charset.StandardCharsets.UTF_8));

        ResponseEntity<?> resp = controller.serveReport("r.html");

        String cacheControl = resp.getHeaders().getCacheControl();
        assertNotNull(cacheControl, "Cache-Control header must be present");
        assertTrue(cacheControl.contains("no-cache") || cacheControl.contains("no-store"),
                "Cache-Control must disable caching");
    }

    // =========================================================================
    // syncStatus
    // =========================================================================

    @Test
    void syncStatus_allFieldsMapped() {
        SyncStatusInfo info = new SyncStatusInfo("2026-06-01T10:00:00",
                "success", Arrays.asList("eIndkomst vacation 2026.xlsx"), 300, true);
        when(syncService.getStatus()).thenReturn(info);

        ResponseEntity<Map<String, Object>> resp = controller.syncStatus();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<String, Object> body = resp.getBody();
        assertNotNull(body);
        assertEquals("2026-06-01T10:00:00",                body.get("last_sync"));
        assertEquals("success",                            body.get("status"));
        assertEquals(300,                                  body.get("interval_seconds"));
        assertEquals(true,                                 body.get("thread_alive"));
        assertEquals(Arrays.asList("eIndkomst vacation 2026.xlsx"), body.get("files_synced"));
    }

    @Test
    void syncStatus_notStarted_reflected() {
        SyncStatusInfo info = new SyncStatusInfo("not_started", "not_started",
                Collections.emptyList(), 300, false);
        when(syncService.getStatus()).thenReturn(info);

        ResponseEntity<Map<String, Object>> resp = controller.syncStatus();
        Map<String, Object> body = resp.getBody();
        assertNotNull(body);
        assertEquals("not_started", body.get("status"));
        assertEquals(false,         body.get("thread_alive"));
    }
}
