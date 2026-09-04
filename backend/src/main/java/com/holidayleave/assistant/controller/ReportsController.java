package com.holidayleave.assistant.controller;

import com.holidayleave.assistant.service.AppState;
import com.holidayleave.assistant.service.SyncService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.LinkedHashMap;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * GET /reports/<filename>
 * GET /api/reports/<filename>
 * GET /api/sync-status
 */
@RestController
public class ReportsController {

    @Autowired private AppState appState;
    @Autowired private SyncService syncService;

    private static final HttpHeaders NO_CACHE_HEADERS;
    static {
        NO_CACHE_HEADERS = new HttpHeaders();
        NO_CACHE_HEADERS.setCacheControl("no-cache, no-store, must-revalidate");
        NO_CACHE_HEADERS.set("Pragma", "no-cache");
        NO_CACHE_HEADERS.set("Expires", "0");
    }

    @GetMapping({"/reports/{filename}", "/api/reports/{filename}"})
    public ResponseEntity<FileSystemResource> serveReport(@PathVariable String filename) {
        File file = new File(appState.getReportsDir(), filename);
        if (!file.exists() || !file.isFile()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .headers(NO_CACHE_HEADERS)
                .contentType(MediaType.TEXT_HTML)
                .body(new FileSystemResource(file));
    }

    @GetMapping("/api/sync-status")
    public ResponseEntity<Map<String, Object>> syncStatus() {
        SyncService.SyncStatusInfo info = syncService.getStatus();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("last_sync",        info.getLastSync());
        result.put("status",           info.getStatus());
        result.put("files_synced",     info.getFilesSynced());
        result.put("interval_seconds", info.getIntervalSeconds());
        result.put("thread_alive",     info.isThreadAlive());
        return ResponseEntity.ok(result);
    }
}
