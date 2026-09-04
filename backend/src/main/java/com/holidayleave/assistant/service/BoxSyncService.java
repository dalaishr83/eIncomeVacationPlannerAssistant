package com.holidayleave.assistant.service;

import com.box.sdk.BoxAPIConnection;
import com.box.sdk.BoxAPIException;
import com.box.sdk.BoxCCGAPIConnection;
import com.box.sdk.BoxDeveloperEditionAPIConnection;
import com.box.sdk.BoxFile;
import com.box.sdk.BoxFolder;
import com.box.sdk.BoxItem;
import com.box.sdk.IAccessTokenCache;
import com.box.sdk.InMemoryLRUAccessTokenCache;
import com.box.sdk.JWTEncryptionPreferences;
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
 * <p>Authentication:
 * <ul>
 *   <li><b>JWT</b> — used when {@code BOX_JWT_PRIVATE_KEY} is non-empty.
 *       Requires {@code BOX_CLIENT_ID}, {@code BOX_CLIENT_SECRET},
 *       {@code BOX_ENTERPRISE_ID}, {@code BOX_JWT_PUBLIC_KEY_ID}, and
 *       {@code BOX_JWT_PRIVATE_KEY_PASSPHRASE}.</li>
 *   <li><b>CCG</b> (Client Credentials Grant) — used when
 *       {@code BOX_JWT_PRIVATE_KEY} is empty. Requires only
 *       {@code BOX_CLIENT_ID}, {@code BOX_CLIENT_SECRET}, and
 *       {@code BOX_ENTERPRISE_ID}.</li>
 * </ul>
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
                return t;
            }
        });
        if (props.getBox().isEnabled()) {
            log.info("BoxSyncService enabled — uploads will go to Box folder ID '{}'",
                    props.getBox().getFolderId());
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
        log.debug("Queuing Box upload for '{}' ({} bytes)", masterFile.getName(), masterFile.length());
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
                if (status == 401 || status == 403) {
                    // Auth failure — invalidate the connection and do not retry.
                    log.error("Box authentication failure (HTTP {}): {}", status, e.getMessage());
                    auditService.log("box_auth_failed", "system", null,
                            "HTTP " + status + ": " + e.getMessage(), "error", "box-sync");
                    invalidateConnection();
                    recordFailureAndMaybeBackoff(masterFile.getName());
                    return;
                }
                logAttemptFailure(masterFile.getName(), attempt, e.getMessage());
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

    /**
     * Builds a {@link BoxAPIConnection}.
     *
     * <ul>
     *   <li>If {@code BOX_JWT_PRIVATE_KEY} is set, uses JWT via
     *       {@link BoxDeveloperEditionAPIConnection}.</li>
     *   <li>Otherwise uses CCG via {@link BoxCCGAPIConnection}.</li>
     * </ul>
     */
    private BoxAPIConnection buildConnection() {
        AppProperties.Box cfg = props.getBox();
        boolean useJwt = cfg.getJwtPrivateKey() != null && !cfg.getJwtPrivateKey().isEmpty();

        if (useJwt) {
            log.debug("Building Box JWT connection for enterprise '{}'", cfg.getEnterpriseId());
            JWTEncryptionPreferences encPrefs = new JWTEncryptionPreferences();
            encPrefs.setPublicKeyID(cfg.getJwtPublicKeyId());
            encPrefs.setPrivateKey(cfg.getJwtPrivateKey());
            encPrefs.setPrivateKeyPassword(cfg.getJwtPrivateKeyPassphrase());

            IAccessTokenCache tokenCache = new InMemoryLRUAccessTokenCache(10);
            return BoxDeveloperEditionAPIConnection.getAppEnterpriseConnection(
                    cfg.getEnterpriseId(), cfg.getClientId(), cfg.getClientSecret(),
                    encPrefs, tokenCache);
        } else {
            log.debug("Building Box CCG connection for enterprise '{}'", cfg.getEnterpriseId());
            return BoxCCGAPIConnection.applicationServiceAccountConnection(
                    cfg.getEnterpriseId(), cfg.getClientId(), cfg.getClientSecret());
        }
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
