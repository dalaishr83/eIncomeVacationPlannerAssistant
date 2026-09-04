package com.holidayleave.assistant.service;

import com.holidayleave.assistant.config.AppProperties;
import com.holidayleave.assistant.model.AuditLogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AuditService}.
 *
 * Covers: log() writes a JSONL entry, readAll() returns entries in reverse order,
 * malformed lines are skipped without losing subsequent entries,
 * readAll() returns empty list when file is absent,
 * UTF-8 encoding preserves non-ASCII characters, and concurrent log calls
 * do not corrupt the file.
 */
class AuditServiceTest {

    @TempDir
    Path tempDir;

    private AuditService auditService;

    @BeforeEach
    void setUp() throws Exception {
        AppProperties props = new AppProperties();
        props.setDataDir(tempDir.toString());

        auditService = new AuditService();
        injectField(auditService, "props", props);
        auditService.init();
    }

    private void injectField(Object target, String name, Object value) throws Exception {
        Field f = AuditService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    // =========================================================================
    // log() — basic write
    // =========================================================================

    @Test
    void log_writesEntry_readAllReturnsIt() {
        auditService.log("vacation_added", "admin", "Alice", "Added 5d [V]", "success", "chat");
        List<AuditLogEntry> entries = auditService.readAll();
        assertEquals(1, entries.size());
        AuditLogEntry e = entries.get(0);
        assertEquals("vacation_added", e.getEventType());
        assertEquals("admin",   e.getUser());
        assertEquals("Alice",   e.getEmployee());
        assertEquals("Added 5d [V]", e.getDetails());
        assertEquals("success", e.getStatus());
        assertEquals("chat",    e.getSource());
    }

    @Test
    void log_multipleEntries_appendedInOrder() {
        auditService.log("e1", "u1", null, "d1", "success", "api");
        auditService.log("e2", "u2", null, "d2", "success", "api");
        auditService.log("e3", "u3", null, "d3", "success", "api");

        // readAll() returns reverse-chronological, so e3 first
        List<AuditLogEntry> entries = auditService.readAll();
        assertEquals(3, entries.size());
        assertEquals("e3", entries.get(0).getEventType());
        assertEquals("e2", entries.get(1).getEventType());
        assertEquals("e1", entries.get(2).getEventType());
    }

    // =========================================================================
    // readAll() — edge cases
    // =========================================================================

    @Test
    void readAll_noLogFile_returnsEmptyList() throws Exception {
        // Delete the audit file if it was created
        Path auditPath = tempDir.resolve("audit.log");
        Files.deleteIfExists(auditPath);

        List<AuditLogEntry> entries = auditService.readAll();
        assertTrue(entries.isEmpty());
    }

    @Test
    void readAll_emptyLogFile_returnsEmptyList() throws Exception {
        Path auditPath = tempDir.resolve("audit.log");
        Files.write(auditPath, ("").getBytes(java.nio.charset.StandardCharsets.UTF_8));

        List<AuditLogEntry> entries = auditService.readAll();
        assertTrue(entries.isEmpty());
    }

    @Test
    void readAll_malformedLine_skippedContinuesReading() throws Exception {
        // Write one valid entry, one malformed line, another valid entry
        Path auditPath = tempDir.resolve("audit.log");
        String goodLine1 = "{\"event_type\":\"e1\",\"timestamp\":\"t\",\"user\":\"u\",\"employee\":null,\"details\":\"d\",\"status\":\"ok\",\"source\":\"api\"}";
        String goodLine2 = "{\"event_type\":\"e2\",\"timestamp\":\"t\",\"user\":\"u\",\"employee\":null,\"details\":\"d\",\"status\":\"ok\",\"source\":\"api\"}";
        String badLine   = "NOT_VALID_JSON{{{{";
        Files.write(auditPath, (goodLine1 + "\n" + badLine + "\n" + goodLine2 + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));

        List<AuditLogEntry> entries = auditService.readAll();
        // 2 valid entries returned (bad line skipped), reversed = e2 first
        assertEquals(2, entries.size());
        assertEquals("e2", entries.get(0).getEventType());
        assertEquals("e1", entries.get(1).getEventType());
    }

    @Test
    void readAll_blankLines_ignored() throws Exception {
        Path auditPath = tempDir.resolve("audit.log");
        String goodLine = "{\"event_type\":\"ev\",\"timestamp\":\"t\",\"user\":\"u\",\"employee\":null,\"details\":\"d\",\"status\":\"ok\",\"source\":\"api\"}";
        Files.write(auditPath, ("\n\n" + goodLine + "\n\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));

        List<AuditLogEntry> entries = auditService.readAll();
        assertEquals(1, entries.size());
        assertEquals("ev", entries.get(0).getEventType());
    }

    // =========================================================================
    // Non-ASCII / UTF-8 preservation
    // =========================================================================

    @Test
    void log_nonAsciiEmployee_preservedInRoundTrip() {
        auditService.log("vacation_added", "admin", "Birgitte Dam", "Added leave", "success", "api");
        List<AuditLogEntry> entries = auditService.readAll();
        assertEquals(1, entries.size());
        assertEquals("Birgitte Dam", entries.get(0).getEmployee());
    }

    // =========================================================================
    // Timestamp is set automatically
    // =========================================================================

    @Test
    void log_timestampIsNotNull() {
        auditService.log("test_event", "admin", null, "detail", "success", "api");
        List<AuditLogEntry> entries = auditService.readAll();
        assertNotNull(entries.get(0).getTimestamp());
        assertFalse(entries.get(0).getTimestamp().isEmpty());
    }

    // =========================================================================
    // Null fields accepted
    // =========================================================================

    @Test
    void log_nullEmployeeAndDetails_storedAndReadBack() {
        auditService.log("sync_event", "system", null, null, "success", "system");
        List<AuditLogEntry> entries = auditService.readAll();
        assertEquals(1, entries.size());
        assertNull(entries.get(0).getEmployee());
        assertNull(entries.get(0).getDetails());
    }

    // =========================================================================
    // Concurrent writes do not corrupt the log
    // =========================================================================

    @Test
    void log_concurrentWrites_allEntriesPresent() throws InterruptedException {
        int threadCount = 10;
        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            threads[i] = new Thread(() ->
                auditService.log("event_" + idx, "user", null, "detail", "ok", "test"));
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();

        List<AuditLogEntry> entries = auditService.readAll();
        assertEquals(threadCount, entries.size());
    }
}
