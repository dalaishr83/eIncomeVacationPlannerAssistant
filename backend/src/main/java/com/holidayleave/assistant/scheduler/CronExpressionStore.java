package com.holidayleave.assistant.scheduler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.holidayleave.assistant.service.AppState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Reads and writes the cron expression registry at
 * {@code {DATA_DIR}/cron-expression/cron.json}.
 *
 * <p>Each entry is a {@link CronEntry} carrying: id, expression (5-field Unix),
 * team, createdAt, and enabled flag.
 *
 * <p>All writes are atomic (write to a temp file, then move).
 */
@Component
public class CronExpressionStore {

    private static final Logger log = LoggerFactory.getLogger(CronExpressionStore.class);

    static final String CRON_SUBDIR         = "cron-expression";
    static final String CRON_FILENAME       = "cron.json";
    static final String WORKING_CRON_FILENAME = "working-cron.json";

    private static final DateTimeFormatter ISO_LOCAL = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final ObjectMapper jackson = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @Autowired
    private AppState appState;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns all persisted cron entries, newest-first.
     * Returns an empty list if the file does not yet exist.
     */
    public synchronized List<CronEntry> readAll() {
        Path file = cronFile();
        if (!file.toFile().exists()) return new ArrayList<>();
        try {
            Map<String, Object> root = jackson.readValue(file.toFile(),
                    new TypeReference<Map<String, Object>>() {});
            Object raw = root.get("expressions");
            if (!(raw instanceof List)) return new ArrayList<>();
            // Re-serialise each element and map back to CronEntry
            List<?> rawList = (List<?>) raw;
            List<CronEntry> result = new ArrayList<>();
            for (Object item : rawList) {
                String json = jackson.writeValueAsString(item);
                result.add(jackson.readValue(json, CronEntry.class));
            }
            return result;
        } catch (IOException e) {
            log.warn("CronExpressionStore: failed to read {}: {}", file, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Persists the supplied list, completely replacing the existing file.
     * The write is atomic: written to a temp file then moved into place.
     */
    public synchronized void saveAll(List<CronEntry> entries) throws IOException {
        Path dir  = cronDir();
        Files.createDirectories(dir);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("expressions", entries);
        Path tmp = dir.resolve(".cron-tmp-" + System.currentTimeMillis() + ".json");
        jackson.writeValue(tmp.toFile(), root);
        Files.move(tmp, cronFile(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        log.debug("CronExpressionStore: saved {} expression(s)", entries.size());
    }

    /**
     * Adds an entry and persists. Returns the updated list.
     */
    public synchronized List<CronEntry> add(CronEntry entry) throws IOException {
        List<CronEntry> all = readAll();
        all.add(entry);
        saveAll(all);
        return all;
    }

    /**
     * Removes the entry with the given id and persists. Returns the updated list.
     * Is a no-op (and returns the unchanged list) if the id is not found.
     */
    public synchronized List<CronEntry> delete(String id) throws IOException {
        List<CronEntry> all = readAll();
        all.removeIf(e -> id.equals(e.getId()));
        saveAll(all);
        return all;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Path cronDir() {
        return Paths.get(appState.getDataDir(), CRON_SUBDIR);
    }

    private Path cronFile() {
        return cronDir().resolve(CRON_FILENAME);
    }

    public Path workingCronFile() {
        return cronDir().resolve(WORKING_CRON_FILENAME);
    }

    /**
     * Reads all entries from {@code working-cron.json}.
     * If {@code working-cron.json} does not exist, falls back to reading {@code cron.json}.
     */
    public synchronized List<CronEntry> readWorkingAll() {
        Path file = workingCronFile();
        if (!file.toFile().exists()) {
            return readAll();
        }
        try {
            Map<String, Object> root = jackson.readValue(file.toFile(),
                    new TypeReference<Map<String, Object>>() {});
            Object raw = root.get("expressions");
            if (!(raw instanceof List)) return new ArrayList<>();
            List<?> rawList = (List<?>) raw;
            List<CronEntry> result = new ArrayList<>();
            for (Object item : rawList) {
                String json = jackson.writeValueAsString(item);
                result.add(jackson.readValue(json, CronEntry.class));
            }
            return result;
        } catch (IOException e) {
            log.warn("CronExpressionStore: failed to read {}: {}", file, e.getMessage());
            return readAll();
        }
    }

    /**
     * Saves entries to {@code working-cron.json} atomically.
     */
    public synchronized void saveWorkingAll(List<CronEntry> entries) throws IOException {
        Path dir = cronDir();
        Files.createDirectories(dir);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("expressions", entries);
        Path tmp = dir.resolve(".working-cron-tmp-" + System.currentTimeMillis() + ".json");
        jackson.writeValue(tmp.toFile(), root);
        Files.move(tmp, workingCronFile(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        log.debug("CronExpressionStore: saved {} expression(s) to working-cron.json", entries.size());
    }

    // ── Entry model ───────────────────────────────────────────────────────────

    /**
     * Represents one scheduled cron expression entry as stored in {@code cron.json}.
     */
    public static class CronEntry {
        private String  id;
        /** 5-field Unix cron expression (e.g. {@code "30 8 * * 5"}). */
        private String  expression;
        /** "Indian Team" or "EIndkomst Team" */
        private String  team;
        private String  createdAt;
        private boolean enabled = true;
        private boolean endDateAjusted = false;

        public CronEntry() {}

        public CronEntry(String expression, String team) {
            this.id         = shortUuid();
            this.expression = expression.trim();
            this.team       = team;
            this.createdAt  = LocalDateTime.now().format(ISO_LOCAL);
            this.enabled    = true;
            this.endDateAjusted = false;
        }

        private String  originalExpression;

        public CronEntry(CronEntry other) {
            if (other != null) {
                this.id = other.id;
                this.expression = other.expression;
                this.originalExpression = other.originalExpression != null ? other.originalExpression : other.expression;
                this.team = other.team;
                this.createdAt = other.createdAt;
                this.enabled = other.enabled;
                this.endDateAjusted = other.endDateAjusted;
            }
        }

        public String  getId()          { return id; }
        public void    setId(String id) { this.id = id; }
        public String  getExpression()          { return expression; }
        public void    setExpression(String e)  { this.expression = e; }
        public String  getTeam()                { return team; }
        public void    setTeam(String t)        { this.team = t; }
        public String  getCreatedAt()           { return createdAt; }
        public void    setCreatedAt(String c)   { this.createdAt = c; }
        public boolean isEnabled()              { return enabled; }
        public void    setEnabled(boolean en)   { this.enabled = en; }
        public boolean isEndDateAjusted()       { return endDateAjusted; }
        public void    setEndDateAjusted(boolean endDateAjusted) { this.endDateAjusted = endDateAjusted; }
        public String  getOriginalExpression()  { return originalExpression != null ? originalExpression : expression; }
        public void    setOriginalExpression(String orig) { this.originalExpression = orig; }

        private static String shortUuid() {
            return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        }
    }
}
