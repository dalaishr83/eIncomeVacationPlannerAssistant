package com.holidayleave.assistant.service;

import com.box.sdk.BoxAPIConnection;
import com.box.sdk.BoxAPIException;
import com.box.sdk.BoxCCGAPIConnection;
import com.box.sdk.BoxFile;
import com.box.sdk.BoxFolder;
import com.box.sdk.BoxGlobalSettings;
import com.box.sdk.BoxItem;
import com.box.sdk.BoxLock;
import com.holidayleave.assistant.config.AppProperties;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Uploads the local master Excel file to IBM Box after every successful
 * working-copy → master synchronization.
 *
 * <p>The upload is asynchronous and non-blocking: the local sync completes
 * and reports success regardless of Box availability. Any Box failure is
 * caught internally, logged, and audited — it never propagates into
 * {@link SyncService}.
 *
 * <p>The service is disabled by default ({@code BOX_ENABLED=false}).
 * When disabled, every call to {@link #submitUpload(File)} is a no-op.
 *
 * <p>Authentication uses Client Credentials Grant (CCG). Requires
 * {@code BOX_CLIENT_ID}, {@code BOX_CLIENT_SECRET}, and {@code BOX_ENTERPRISE_ID}.
 */
@Service
public class BoxSyncService {

    private static final Logger log = LoggerFactory.getLogger(BoxSyncService.class);

    /** Maximum upload attempts before giving up for this sync cycle. */
    private static final int MAX_ATTEMPTS = 3;

    @Autowired private AppProperties props;
    @Autowired private AuditService auditService;

    /** Single-thread executor keeps uploads sequential and easy to reason about. */
    private ExecutorService executor;

    /** Cached connection; null means not yet initialised or previously invalidated. */
    private volatile BoxAPIConnection connection;

    /** Counts consecutive upload failures; reset to 0 on any success. */
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    /** Epoch millis until which Box uploads are suppressed after repeated failures. */
    private volatile long backoffUntil = 0L;

    @PostConstruct
    public void init() {
        executor = Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "box-sync");
                t.setDaemon(true);
                t.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                    @Override public void uncaughtException(Thread th, Throwable ex) {
                        log.error("BoxSyncService: uncaught exception on box-sync thread — {}", ex.toString(), ex);
                    }
                });
                return t;
            }
        });
        // Apply timeouts globally so the OAuth2 token-fetch HTTP call (which uses
        // BoxGlobalSettings, not the per-connection setters) also times out.
        BoxGlobalSettings.setConnectTimeout(props.getBox().getConnectTimeoutSeconds() * 1000);
        BoxGlobalSettings.setReadTimeout(props.getBox().getReadTimeoutSeconds() * 1000);
        if (props.getBox().isEnabled()) {
            log.info("BoxSyncService enabled — uploads will go to Box folder ID '{}' (connectTimeout={}s readTimeout={}s)",
                    props.getBox().getFolderId(),
                    props.getBox().getConnectTimeoutSeconds(),
                    props.getBox().getReadTimeoutSeconds());
        } else {
            log.info("BoxSyncService disabled (BOX_ENABLED=false)");
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** @return true when Box sync is configured and enabled. */
    public boolean isEnabled() {
        return props.getBox().isEnabled();
    }

    /**
     * Queues an async upload of {@code masterFile} to the configured Box folder.
     * Returns immediately; the upload runs on the background {@code box-sync} thread.
     * Safe to call even when Box is disabled — becomes a no-op.
     */
    public void submitUpload(final File masterFile) {
        if (!isEnabled()) {
            log.debug("BoxSyncService disabled, skipping upload of '{}'", masterFile.getName());
            return;
        }
        log.info("BoxSyncService: queuing upload of '{}' ({} bytes)", masterFile.getName(), masterFile.length());
        executor.submit(new Runnable() {
            @Override public void run() {
                uploadWithRetry(masterFile);
            }
        });
    }

    // ── private implementation ────────────────────────────────────────────────

    private void uploadWithRetry(File masterFile) {
        // Honour the back-off window established after repeated failures.
        if (System.currentTimeMillis() < backoffUntil) {
            log.debug("Box upload suppressed (back-off active) for '{}'", masterFile.getName());
            return;
        }

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                doUpload(masterFile);
                consecutiveFailures.set(0);
                return; // success — done
            } catch (BoxAPIException e) {
                int status = e.getResponseCode();
                String body = e.getMessage() != null ? e.getMessage() : "";
                if (body.contains("access_denied_item_locked")) {
                    // The file is locked by another Box client (e.g. open in Office 365 online).
                    // This is transient — the lock clears when they close the file.
                    logAttemptFailure(masterFile.getName(), attempt,
                            "file locked (access_denied_item_locked) — will retry");
                } else if (status == 401 || status == 403) {
                    // True auth failure — invalidate the connection and do not retry.
                    log.error("Box authentication failure (HTTP {}): {}", status, e.getMessage());
                    auditService.log("box_auth_failed", "system", null,
                            "HTTP " + status + ": " + e.getMessage(), "error", "box-sync");
                    invalidateConnection();
                    recordFailureAndMaybeBackoff(masterFile.getName());
                    return;
                } else if (status == 404) {
                    // Folder not found — either BOX_FOLDER_ID is wrong or the service
                    // account has not been shared on the target folder.  This is a
                    // configuration error; retrying will never help.
                    log.error("Box folder not found (HTTP 404) for folder '{}' — check BOX_FOLDER_ID"
                            + " and that the service account has Editor access to that folder: {}",
                            props.getBox().getFolderId(), e.getMessage());
                    auditService.log("box_upload_failed", "system", null,
                            "HTTP 404 folder not found folderId=" + props.getBox().getFolderId()
                            + " — verify BOX_FOLDER_ID and service account folder permissions",
                            "error", "box-sync");
                    recordFailureAndMaybeBackoff(masterFile.getName());
                    return;
                } else {
                    logAttemptFailure(masterFile.getName(), attempt, e.getMessage());
                }
            } catch (Exception e) {
                logAttemptFailure(masterFile.getName(), attempt, e.getMessage());
            }

            if (attempt < MAX_ATTEMPTS) {
                long delayMs = props.getBox().getRetryBackoffSeconds() * 1000L
                        * (long) Math.pow(2, attempt - 1);
                log.warn("Retrying Box upload for '{}' in {}s (attempt {}/{})",
                        masterFile.getName(), delayMs / 1000, attempt + 1, MAX_ATTEMPTS);
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        // All attempts exhausted.
        String msg = "Box upload failed after " + MAX_ATTEMPTS + " attempts for '"
                + masterFile.getName() + "' — no more retries this cycle";
        log.error(msg);
        auditService.log("box_upload_failed", "system", null, msg, "error", "box-sync");
        recordFailureAndMaybeBackoff(masterFile.getName());
    }

    /**
     * How long to poll for a lock to clear before giving up on this attempt (ms).
     * Kept short so the outer retry loop (with its exponential backoff) still fires
     * and the box-sync thread does not block for more than a few seconds per attempt.
     */
    private static final long LOCK_POLL_TIMEOUT_MS  = 30_000L;
    private static final long LOCK_POLL_INTERVAL_MS =  5_000L;

    private void doUpload(File masterFile) throws IOException {
        AppProperties.Box cfg = props.getBox();
        String filename = masterFile.getName();
        String folderId = cfg.getFolderId();

        auditService.log("box_upload_started", "system", null,
                "file=" + filename + " folderId=" + folderId, "info", "box-sync");
        long start = System.currentTimeMillis();

        BoxAPIConnection api = getConnection();
        BoxFolder folder = new BoxFolder(api, folderId);

        // Detect whether the file already exists to upload a new version vs. create.
        String existingFileId = findExistingFile(folder, filename);

        // If the file already exists in Box, check whether it is currently locked
        // (e.g. by Box Drive re-syncing a previous version or Box for Office previewing
        // it).  Poll briefly so we don't immediately burn an outer retry on a lock that
        // Box Drive typically holds for only a few seconds after each version upload.
        if (existingFileId != null) {
            waitForLockToClear(api, existingFileId, filename);
        }

        String versionId;
        FileInputStream fis = new FileInputStream(masterFile);
        try {
            if (existingFileId != null) {
                BoxFile boxFile = new BoxFile(api, existingFileId);
                BoxFile.Info info = boxFile.uploadNewVersion(fis);
                versionId = info.getVersion().getID();
            } else {
                BoxFile.Info info = folder.uploadFile(fis, filename);
                versionId = info.getID();
            }
        } finally {
            try { fis.close(); } catch (IOException ignored) { /* best-effort */ }
        }

        long duration = System.currentTimeMillis() - start;
        log.info("Box upload complete: '{}' in {}ms, versionId={}", filename, duration, versionId);
        auditService.log("box_upload_complete", "system", null,
                "file=" + filename + " duration=" + duration + "ms versionId=" + versionId,
                "success", "box-sync");
    }

    /**
     * Polls the lock state of {@code fileId} until it is clear or
     * {@link #LOCK_POLL_TIMEOUT_MS} elapses.  If the file is still locked at
     * timeout this method returns normally — the subsequent
     * {@link BoxFile#uploadNewVersion} will then throw {@link BoxAPIException}
     * with {@code access_denied_item_locked} and the outer retry loop handles it.
     *
     * <p>Box Drive typically holds the lock for 5–30 seconds after uploading a
     * new version.  Polling at {@link #LOCK_POLL_INTERVAL_MS} intervals catches
     * most of these transient locks before they burn an outer retry slot.
     */
    private void waitForLockToClear(BoxAPIConnection api, String fileId, String filename) {
        long deadline = System.currentTimeMillis() + LOCK_POLL_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                BoxFile.Info info = new BoxFile(api, fileId).getInfo("lock");
                BoxLock lock = info.getLock();
                if (lock == null) {
                    return; // no lock — proceed immediately
                }
                String lockedBy = lock.getCreatedBy() != null ? lock.getCreatedBy().getLogin() : "unknown";
                log.debug("Box file '{}' is locked by '{}' (expires {}) — waiting {}ms before retry",
                        filename, lockedBy, lock.getExpiresAt(), LOCK_POLL_INTERVAL_MS);
                Thread.sleep(LOCK_POLL_INTERVAL_MS);
            } catch (BoxAPIException e) {
                // If we can't read lock state, don't block the upload — let it try anyway.
                log.debug("Could not read lock state for '{}': {} — proceeding", filename, e.getMessage());
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("Box file '{}' still locked after {}ms — proceeding with upload attempt anyway",
                filename, LOCK_POLL_TIMEOUT_MS);
    }

    /**
     * Returns the Box file ID if {@code filename} already exists in {@code folder},
     * otherwise {@code null} (triggers a first-time upload).
     */
    private String findExistingFile(BoxFolder folder, String filename) {
        for (BoxItem.Info item : folder) {
            if (item instanceof BoxFile.Info && filename.equals(item.getName())) {
                return item.getID();
            }
        }
        return null;
    }

    private synchronized BoxAPIConnection getConnection() {
        if (connection == null) {
            connection = buildConnection();
        }
        return connection;
    }

    private synchronized void invalidateConnection() {
        connection = null;
    }

    /** Builds a CCG {@link BoxAPIConnection}. */
    private BoxAPIConnection buildConnection() {
        AppProperties.Box cfg = props.getBox();
        log.debug("Building Box CCG connection for enterprise '{}'", cfg.getEnterpriseId());
        BoxAPIConnection conn = BoxCCGAPIConnection.applicationServiceAccountConnection(
                cfg.getClientId(), cfg.getClientSecret(), cfg.getEnterpriseId());

        // Box SDK defaults to 0 (no timeout), which causes the box-sync thread to
        // block indefinitely when the Box API is slow or unreachable.
        conn.setConnectTimeout(cfg.getConnectTimeoutSeconds() * 1000);
        conn.setReadTimeout(cfg.getReadTimeoutSeconds() * 1000);
        log.info("BoxSyncService: CCG connection built (connectTimeout={}s readTimeout={}s)",
                cfg.getConnectTimeoutSeconds(), cfg.getReadTimeoutSeconds());
        return conn;
    }

    private void logAttemptFailure(String filename, int attempt, String message) {
        log.warn("Box upload attempt {}/{} failed for '{}': {}",
                attempt, MAX_ATTEMPTS, filename, message);
        auditService.log("box_upload_failed", "system", null,
                "file=" + filename + " attempt=" + attempt + "/" + MAX_ATTEMPTS + " error=" + message,
                "error", "box-sync");
    }

    private void recordFailureAndMaybeBackoff(String filename) {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= 3) {
            long backoffMs = props.getBox().getRetryBackoffSeconds() * 1000L * 4L;
            backoffUntil = System.currentTimeMillis() + backoffMs;
            log.error("Box upload suppressed for {}s after {} consecutive failures for '{}'",
                    backoffMs / 1000, failures, filename);
            consecutiveFailures.set(0);
        }
    }
}
