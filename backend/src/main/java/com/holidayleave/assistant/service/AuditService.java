package com.holidayleave.assistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.holidayleave.assistant.config.AppProperties;
import com.holidayleave.assistant.model.AuditLogEntry;
import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Append-only JSONL audit log writer at data/audit.log.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    @Autowired
    private AppProperties props;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Lock lock = new ReentrantLock();
    private Path auditFilePath;

    @PostConstruct
    public void init() throws IOException {
        String dataDir = resolveDataDir(props.getDataDir());
        auditFilePath  = Paths.get(dataDir, "audit.log");
        Files.createDirectories(auditFilePath.getParent());
    }

    /**
     * Mirrors AppState.resolve() — anchors relative DATA_DIR values to the
     * app.base.dir system property (project root) rather than the JVM working
     * directory, which may be Tomcat's temp folder in some environments.
     * Absolute paths are returned unchanged.
     */
    private static String resolveDataDir(String configured) {
        Path p = Paths.get(configured);
        if (p.isAbsolute()) return p.toString();
        String base = System.getProperty("app.base.dir");
        if (base != null && !base.trim().isEmpty()) {
            return Paths.get(base, configured).toAbsolutePath().toString();
        }
        return p.toAbsolutePath().toString();
    }

    public void log(String eventType, String user, String employee, String details, String status, String source) {
        AuditLogEntry entry = new AuditLogEntry(
                eventType,
                ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                user, employee, details, status, source
        );
        lock.lock();
        // Use explicit UTF-8 encoding — FileWriter uses the JVM default charset
        // (Windows-1252 on European-locale Windows) which corrupts non-ASCII characters
        // such as Danish æ/ø, producing invalid UTF-8 bytes that crash the read path.
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(auditFilePath.toFile(), true), StandardCharsets.UTF_8))) {
            writer.write(mapper.writeValueAsString(entry));
            writer.newLine();
        } catch (IOException e) {
            log.error("Audit log write failed: {}", e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Read all audit log entries in reverse-chronological order (most recent first).
     * Returns an empty list if the log file does not yet exist.
     */
    public List<AuditLogEntry> readAll() {
        if (!Files.exists(auditFilePath)) {
            return Collections.emptyList();
        }
        List<AuditLogEntry> entries = new ArrayList<>();
        lock.lock();
        // Use a lenient UTF-8 decoder (REPLACE on bad bytes) instead of
        // Files.newBufferedReader() which uses a strict decoder (REPORT) and throws
        // MalformedInputException("Input length = 1") on the first invalid byte,
        // aborting the entire read and losing all remaining entries.
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                Files.newInputStream(auditFilePath),
                StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPLACE)
                        .onUnmappableCharacter(CodingErrorAction.REPLACE)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    try {
                        entries.add(mapper.readValue(line, AuditLogEntry.class));
                    } catch (Exception e) {
                        log.warn("Skipping malformed audit log line: {}", e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            log.error("Audit log read failed: {}", e.getMessage());
        } finally {
            lock.unlock();
        }
        Collections.reverse(entries);
        return entries;
    }
}
