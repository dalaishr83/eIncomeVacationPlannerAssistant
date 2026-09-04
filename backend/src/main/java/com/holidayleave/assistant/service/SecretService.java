package com.holidayleave.assistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.holidayleave.assistant.config.AppProperties;
import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * File-backed credential store.
 *
 * Schema (username-keyed):
 * {
 *   "admin":       { "username": "admin",       "hash": "...", "role": "admin",    "employee_name": null },
 *   "birgitteDam": { "username": "birgitteDam", "hash": "...", "role": "employee", "employee_name": "Birgitte Dam" }
 * }
 *
 * Legacy role-keyed format (pre-enhancement) is auto-migrated on first boot.
 * Uses BCrypt for all password hashing — same algorithm as the existing auth flow.
 */
@Service
public class SecretService {

    private static final Logger log = LoggerFactory.getLogger(SecretService.class);
    private static final String DEFAULT_EMPLOYEE_PASSWORD = "test1234";

    @Autowired
    private AppProperties props;

    private final ObjectMapper mapper = new ObjectMapper();
    private final ReentrantLock writeLock = new ReentrantLock();
    private Path secretFilePath;

    @PostConstruct
    public void init() throws IOException {
        Path dataDir   = Paths.get(resolveDataDir(props.getDataDir()));
        secretFilePath = dataDir.resolve("secret").resolve("secret.json");
        Files.createDirectories(secretFilePath.getParent());

        if (!Files.exists(secretFilePath)) {
            // First boot — bootstrap admin credential only.
            // Employee accounts are provisioned dynamically when a Master Excel file is uploaded.
            Map<String, Map<String, String>> credentials = new LinkedHashMap<>();

            Map<String, String> adminCred = new LinkedHashMap<>();
            String adminUsername = props.getLoginUsername().isEmpty() ? "admin" : props.getLoginUsername();
            adminCred.put("username", adminUsername);
            String existingHash = props.getLoginPasswordHash();
            adminCred.put("hash", existingHash != null && !existingHash.isEmpty()
                    ? existingHash
                    : BCrypt.hashpw("admin", BCrypt.gensalt()));
            adminCred.put("role", "admin");
            adminCred.put("employee_name", null);
            credentials.put(adminUsername, adminCred);

            save(credentials);
            log.info("SecretService: bootstrapped secret.json");
        } else {
            migrateLegacyFormatIfNeeded();
        }
    }

    /**
     * Detects the legacy role-keyed format (entries lack a "role" sub-field) and
     * migrates in-place. Backs up the original file to secret.json.bak first.
     */
    private void migrateLegacyFormatIfNeeded() throws IOException {
        Map<String, Map<String, String>> creds = readCredentials();
        // Legacy format: at least one entry has no "role" field.
        boolean needsMigration = creds.values().stream()
                .anyMatch(entry -> !entry.containsKey("role"));
        if (!needsMigration) return;

        // Backup original.
        Path backup = secretFilePath.getParent().resolve("secret.json.bak");
        Files.copy(secretFilePath, backup, StandardCopyOption.REPLACE_EXISTING);
        log.info("SecretService: legacy secret.json detected — backed up to secret.json.bak, migrating…");

        Map<String, Map<String, String>> migrated = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> e : creds.entrySet()) {
            String key   = e.getKey();   // legacy key is role name: "admin" or "employee"
            Map<String, String> val = new LinkedHashMap<>(e.getValue());

            // Fill in role and employee_name if absent.
            if (!val.containsKey("role")) {
                val.put("role", key.equals("admin") ? "admin" : "employee");
            }
            if (!val.containsKey("employee_name")) {
                val.put("employee_name", null);
            }

            // Re-key by username (may differ from legacy role key).
            String username = val.getOrDefault("username", key);

            // Evict the legacy placeholder: username="employee" with no real employee_name.
            // Real employees are provisioned from the Master Excel file and always have
            // a non-null employee_name, so this check is safe and non-destructive.
            if ("employee".equals(username) && val.get("employee_name") == null) {
                log.info("SecretService: evicting legacy 'employee' placeholder during migration");
                continue;
            }

            migrated.put(username, val);
        }

        save(migrated);
        log.info("SecretService: migration complete — {} entries", migrated.size());
    }

    // ── Public read API ───────────────────────────────────────────────────────

    /**
     * Returns the credentials map keyed by username.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Map<String, String>> readCredentials() {
        try {
            return mapper.readValue(secretFilePath.toFile(),
                    mapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
        } catch (IOException e) {
            log.error("Failed to read secret.json", e);
            return new LinkedHashMap<>();
        }
    }

    /**
     * Finds a credential entry by username.
     * Returns the entry map containing {username, hash, role, employee_name}, or null if not found.
     */
    public Map<String, String> findByUsername(String username) {
        if (username == null) return null;
        Map<String, Map<String, String>> creds = readCredentials();
        return creds.get(username);
    }

    /**
     * Returns the stored BCrypt hash for the given role key, or null if not found.
     * Retained for backward compatibility with AdminController.
     */
    public String getHash(String role) {
        Map<String, Map<String, String>> creds = readCredentials();
        Map<String, String> entry = creds.get(role);
        if (entry == null) return null;
        return entry.get("hash");
    }

    /**
     * Returns the username for the given role key, or null if not found.
     * Retained for backward compatibility with AdminController.
     */
    public String getUsername(String role) {
        Map<String, Map<String, String>> creds = readCredentials();
        Map<String, String> entry = creds.get(role);
        if (entry == null) return null;
        return entry.get("username");
    }

    // ── Public write API ──────────────────────────────────────────────────────

    /**
     * Updates the password hash for the given username key using BCrypt.
     */
    public void updatePassword(String role, String newPassword) throws IOException {
        writeLock.lock();
        try {
            Map<String, Map<String, String>> creds = readCredentials();
            if (!creds.containsKey(role)) {
                throw new IllegalArgumentException("Unknown credential key: " + role);
            }
            creds.get(role).put("hash", BCrypt.hashpw(newPassword, BCrypt.gensalt()));
            save(creds);
            log.info("SecretService: password updated for key '{}'", role);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Updates only the {@code role} field for the given username key.
     * All other credential attributes (hash, employee_name, username) are preserved.
     *
     * @param username  the key in secret.json (same as the username field)
     * @param newRole   the new role value, e.g. {@code "admin"} or {@code "employee"}
     * @throws IllegalArgumentException if the username key does not exist
     * @throws IOException              if the file cannot be written
     */
    public void updateRole(String username, String newRole) throws IOException {
        writeLock.lock();
        try {
            Map<String, Map<String, String>> creds = readCredentials();
            if (!creds.containsKey(username)) {
                throw new IllegalArgumentException("Unknown credential key: " + username);
            }
            creds.get(username).put("role", newRole);
            save(creds);
            log.info("SecretService: role updated for '{}' → '{}'", username, newRole);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Idempotently provisions a login credential for the given employee full name.
     *
     * Username generation — camelCase progressive expansion:
     *   1. Base candidate = first token, lowercased.
     *   2. On collision: append next token, capitalised (camelCase), repeat.
     *   3. If all tokens are consumed and still colliding (exact-duplicate full name),
     *      append an incrementing integer suffix to the fully-expanded username.
     *
     * Idempotency: if an entry whose employee_name matches (case-insensitive) already
     * exists, no changes are made and the existing username is returned.
     *
     * @param employeeName full name exactly as it appears in the Excel roster
     * @return the generated (or pre-existing) username
     */
    public String provisionEmployee(String employeeName) throws IOException {
        if (employeeName == null || employeeName.trim().isEmpty()) {
            throw new IllegalArgumentException("employeeName must not be blank");
        }
        writeLock.lock();
        try {
            Map<String, Map<String, String>> creds = readCredentials();

            // 1. Idempotency check — match on stored employee_name.
            for (Map<String, String> entry : creds.values()) {
                String stored = entry.get("employee_name");
                if (stored != null && stored.equalsIgnoreCase(employeeName.trim())) {
                    return entry.get("username");
                }
            }

            // 2. Build candidate username via camelCase progressive expansion.
            String[] tokens = employeeName.trim().split("\\s+");
            String candidate = tokens[0].toLowerCase();

            if (creds.containsKey(candidate)) {
                // Expand with successive tokens in camelCase until unique.
                for (int i = 1; i < tokens.length; i++) {
                    candidate = candidate + capitalise(tokens[i]);
                    if (!creds.containsKey(candidate)) break;
                }
                // Last-resort numeric suffix if all tokens exhausted and still colliding.
                if (creds.containsKey(candidate)) {
                    int n = 2;
                    String base = candidate;
                    while (creds.containsKey(candidate)) {
                        candidate = base + n++;
                    }
                }
            }

            // 3. Provision the new entry.
            Map<String, String> newEntry = new LinkedHashMap<>();
            newEntry.put("username",      candidate);
            newEntry.put("hash",          BCrypt.hashpw(DEFAULT_EMPLOYEE_PASSWORD, BCrypt.gensalt()));
            newEntry.put("role",          "employee");
            newEntry.put("employee_name", employeeName.trim());
            creds.put(candidate, newEntry);

            save(creds);
            log.info("SecretService: provisioned employee '{}' → username '{}'", employeeName.trim(), candidate);
            return candidate;
        } finally {
            writeLock.unlock();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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

    /** Capitalises the first character and lowercases the rest. */
    private static String capitalise(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }

    private void save(Map<String, Map<String, String>> credentials) throws IOException {
        Path tmp = secretFilePath.getParent().resolve("." + UUID.randomUUID() + ".tmp");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), credentials);
            Files.move(tmp, secretFilePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }
}
