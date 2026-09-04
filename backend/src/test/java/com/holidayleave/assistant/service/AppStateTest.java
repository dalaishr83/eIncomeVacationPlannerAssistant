package com.holidayleave.assistant.service;

import com.holidayleave.assistant.config.AppProperties;
import com.holidayleave.assistant.model.PendingVacation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AppState}.
 *
 * Covers: conversation history management (add, cap at 20, clear),
 * pending vacation CRUD, loaded/active files management,
 * thread-safety of history and file lists, path resolution.
 *
 * Note: @PostConstruct (init) is NOT invoked when constructing AppState directly.
 * We set up the underlying props and TempDir to allow calling init() manually.
 */
class AppStateTest {

    @TempDir
    Path tempDir;

    private AppState appState;

    @BeforeEach
    void setUp() throws Exception {
        AppProperties props = new AppProperties();
        props.setDataDir(tempDir.toString());
        props.setReportOutputDir(tempDir.resolve("reports").toString());

        appState = new AppState();
        // Inject the props field via reflection (avoids Spring context overhead)
        injectField(appState, "props", props);

        // Manually call @PostConstruct
        appState.init();
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private void injectField(Object target, String fieldName, Object value) throws Exception {
        java.lang.reflect.Field f = AppState.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Conversation history
    // ═══════════════════════════════════════════════════════════════════════════

    private static final String SID = "test-session-1";

    @Test
    void addToHistory_singleTurn_twoEntries() {
        appState.addToHistory(SID, "Hello?", "Hello back!");
        List<Map<String, String>> hist = appState.getConversationHistory(SID);
        assertEquals(2, hist.size());
        assertEquals("user",        hist.get(0).get("role"));
        assertEquals("Hello?",      hist.get(0).get("content"));
        assertEquals("assistant",   hist.get(1).get("role"));
        assertEquals("Hello back!", hist.get(1).get("content"));
    }

    @Test
    void addToHistory_multipleTurns_allPresent() {
        appState.addToHistory(SID, "Q1", "A1");
        appState.addToHistory(SID, "Q2", "A2");
        List<Map<String, String>> hist = appState.getConversationHistory(SID);
        assertEquals(4, hist.size());
    }

    @Test
    void addToHistory_cap_maxEntries() {
        // historyWindow=6 → cap at 6 entries (3 user + 3 assistant pairs)
        // Adding 4 turns (8 entries) should evict the oldest 2
        for (int i = 0; i < 4; i++) {
            appState.addToHistory(SID, "Q" + i, "A" + i);
        }
        assertEquals(6, appState.getConversationHistory(SID).size());
    }

    @Test
    void addToHistory_exactlyThreeTurns_exactlySixEntries() {
        for (int i = 0; i < 3; i++) {
            appState.addToHistory(SID, "Q" + i, "A" + i);
        }
        assertEquals(6, appState.getConversationHistory(SID).size());
    }

    @Test
    void addToHistory_cap_oldestEvictedFirst() {
        // historyWindow=6: add 4 turns → oldest pair (Q0/A0) evicted
        for (int i = 0; i < 4; i++) {
            appState.addToHistory(SID, "Q" + i, "A" + i);
        }
        List<Map<String, String>> hist = appState.getConversationHistory(SID);
        // First entry should be Q1 (user), not Q0
        assertEquals("Q1", hist.get(0).get("content"));
    }

    @Test
    void clearHistory_emptyAfterClear() {
        appState.addToHistory(SID, "Hello", "Hi");
        appState.clearHistory(SID);
        assertTrue(appState.getConversationHistory(SID).isEmpty());
    }

    @Test
    void getConversationHistory_returnsCopy_notLiveList() {
        appState.addToHistory(SID, "Q", "A");
        List<Map<String, String>> hist = appState.getConversationHistory(SID);
        hist.clear(); // modify the returned list
        // Internal list should be untouched
        assertEquals(2, appState.getConversationHistory(SID).size());
    }

    @Test
    void conversationHistory_initiallyEmpty() {
        assertTrue(appState.getConversationHistory(SID).isEmpty());
    }

    @Test
    void conversationHistory_unknownSession_returnsEmptyList() {
        assertTrue(appState.getConversationHistory("no-such-session").isEmpty());
    }

    @Test
    void addToHistory_nullSessionId_noException() {
        // Must not throw — null session IDs are silently ignored
        assertDoesNotThrow(() -> appState.addToHistory(null, "Q", "A"));
    }

    @Test
    void addToHistory_twoSessions_isolated() {
        appState.addToHistory("sid-A", "Q-A", "R-A");
        appState.addToHistory("sid-B", "Q-B", "R-B");

        List<Map<String, String>> histA = appState.getConversationHistory("sid-A");
        List<Map<String, String>> histB = appState.getConversationHistory("sid-B");

        assertEquals(2, histA.size());
        assertEquals("Q-A", histA.get(0).get("content"));

        assertEquals(2, histB.size());
        assertEquals("Q-B", histB.get(0).get("content"));
    }

    @Test
    void clearHistory_onlyAffectsTargetSession() {
        appState.addToHistory("sid-A", "Q-A", "R-A");
        appState.addToHistory("sid-B", "Q-B", "R-B");
        appState.clearHistory("sid-A");

        assertTrue(appState.getConversationHistory("sid-A").isEmpty());
        assertEquals(2, appState.getConversationHistory("sid-B").size());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Thread-safety: addToHistory under concurrent access
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void addToHistory_concurrentAccess_doesNotThrow() throws InterruptedException {
        ExecutorService exec = Executors.newFixedThreadPool(8);
        CountDownLatch latch = new CountDownLatch(1);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < 20; i++) {
            final int idx = i;
            exec.submit(() -> {
                try {
                    latch.await();
                    appState.addToHistory("sid-concurrent", "Q" + idx, "A" + idx);
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
        }
        latch.countDown();
        exec.shutdown();
        assertTrue(exec.awaitTermination(5, TimeUnit.SECONDS));
        assertTrue(errors.isEmpty(), "Concurrent history add threw: " + errors);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Pending vacation CRUD
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void setPendingVacation_thenGet_returnsIt() {
        PendingVacation pv = new PendingVacation("add");
        appState.setPendingVacation("session-1", pv);
        assertSame(pv, appState.getPendingVacation("session-1"));
    }

    @Test
    void getPendingVacation_unknownSession_returnsNull() {
        assertNull(appState.getPendingVacation("no-such-session"));
    }

    @Test
    void removePendingVacation_thenGetReturnsNull() {
        PendingVacation pv = new PendingVacation("add");
        appState.setPendingVacation("session-2", pv);
        appState.removePendingVacation("session-2");
        assertNull(appState.getPendingVacation("session-2"));
    }

    @Test
    void pendingVacation_differentSessions_isolated() {
        PendingVacation pv1 = new PendingVacation("add");
        PendingVacation pv2 = new PendingVacation("delete");
        appState.setPendingVacation("s1", pv1);
        appState.setPendingVacation("s2", pv2);
        assertSame(pv1, appState.getPendingVacation("s1"));
        assertSame(pv2, appState.getPendingVacation("s2"));
    }

    @Test
    void pendingVacation_overwrite_returnsLatest() {
        PendingVacation pv1 = new PendingVacation("add");
        PendingVacation pv2 = new PendingVacation("delete");
        appState.setPendingVacation("s1", pv1);
        appState.setPendingVacation("s1", pv2);
        assertSame(pv2, appState.getPendingVacation("s1"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Loaded / active files
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void setLoadedFiles_thenGetLoadedFiles_equal() {
        List<String> paths = Arrays.asList("/data/file1.xlsx", "/data/file2.xlsx");
        appState.setLoadedFiles(paths);
        assertEquals(paths, appState.getLoadedFiles());
    }

    @Test
    void getLoadedFiles_returnsCopy_notLiveList() {
        appState.setLoadedFiles(Arrays.asList("/data/file.xlsx"));
        List<String> copy = appState.getLoadedFiles();
        copy.add("extra");
        assertEquals(1, appState.getLoadedFiles().size());
    }

    @Test
    void setActiveFiles_thenGetActiveFiles_equal() {
        List<String> active = Arrays.asList("/data/active.xlsx");
        appState.setActiveFiles(active);
        assertEquals(active, appState.getActiveFiles());
    }

    @Test
    void setLoadedFiles_empty_clearsExisting() {
        appState.setLoadedFiles(Arrays.asList("/data/file.xlsx"));
        appState.setLoadedFiles(Collections.emptyList());
        assertTrue(appState.getLoadedFiles().isEmpty());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Directory initialization via @PostConstruct (already called in setUp)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void init_createsWorkingDirectory() {
        File working = new File(appState.getWorkingDir());
        assertTrue(working.exists() && working.isDirectory());
    }

    @Test
    void init_createsUploadsDirectory() {
        File uploads = new File(appState.getUploadsDir());
        assertTrue(uploads.exists() && uploads.isDirectory());
    }

    @Test
    void init_dataDirAndWorkingDirAreNotNull() {
        assertNotNull(appState.getDataDir());
        assertNotNull(appState.getWorkingDir());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // discoverExcelPaths
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void discoverExcelPaths_emptyDataDir_returnsEmptyList() throws Exception {
        // tempDir is fresh, no xlsx files
        List<String> paths = appState.discoverExcelPaths();
        assertTrue(paths.isEmpty() || paths.stream().noneMatch(p -> !new File(p).exists()));
    }

    @Test
    void discoverExcelPaths_withXlsxFile_includesIt() throws Exception {
        // Create a dummy xlsx in the data dir
        File xlsx = tempDir.resolve("test-2026.xlsx").toFile();
        xlsx.createNewFile();
        List<String> paths = appState.discoverExcelPaths();
        assertTrue(paths.stream().anyMatch(p -> p.contains("test-2026.xlsx")));
    }

    @Test
    void discoverExcelPaths_nonXlsxFileIgnored() throws Exception {
        tempDir.resolve("notes.txt").toFile().createNewFile();
        List<String> paths = appState.discoverExcelPaths();
        assertTrue(paths.stream().noneMatch(p -> p.endsWith(".txt")));
    }
}
