package com.holidayleave.assistant.service;

import com.holidayleave.assistant.config.AppProperties;
import com.holidayleave.assistant.excel.PlannerExcelReader;
import com.holidayleave.assistant.excel.WorkingExcelWriter;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);
    private static final Pattern YEAR_PAT = Pattern.compile("\\b(20\\d{2})\\b");

    @Autowired private AppProperties props;
    @Autowired private AppState appState;
    @Autowired private WorkingExcelWriter writer;
    @Autowired private PlannerExcelReader reader;
    @Autowired private BoxSyncService boxSyncService;
    @Autowired private AuditService auditService;

    private Thread syncThread;
    private volatile boolean running = false;
    private final Object triggerLock = new Object();
    private volatile boolean triggered = false;

    private final AtomicReference<String> lastSyncTime = new AtomicReference<>("not_started");
    private final AtomicReference<String> syncStatus   = new AtomicReference<>("not_started");
    private final List<String> lastSyncedFiles = new ArrayList<>();
    private final ReentrantLock statusLock = new ReentrantLock();

    private Runnable reloadCallback;

    @PostConstruct
    public void startDaemon() {
        running = true;
        syncThread = new Thread(new Runnable() {
            @Override public void run() { syncLoop(); }
        }, "sync-daemon");
        syncThread.setDaemon(true);
        syncThread.start();
        log.info("SyncService daemon started");
    }

    @PreDestroy
    public void stop() {
        running = false;
        synchronized (triggerLock) { triggerLock.notifyAll(); }
    }

    public void triggerSync() {
        synchronized (triggerLock) { triggered = true; triggerLock.notifyAll(); }
    }

    public void forceSync() { syncAll(); }

    public void setReloadCallback(Runnable callback) { this.reloadCallback = callback; }

    public boolean isAlive() { return syncThread != null && syncThread.isAlive(); }

    public SyncStatusInfo getStatus() {
        statusLock.lock();
        try {
            return new SyncStatusInfo(lastSyncTime.get(), syncStatus.get(),
                    new ArrayList<>(lastSyncedFiles), props.getSyncIntervalSeconds(), isAlive());
        } finally { statusLock.unlock(); }
    }

    private void syncLoop() {
        int failCount = 0;
        while (running) {
            synchronized (triggerLock) {
                try {
                    if (!triggered) triggerLock.wait(5000);
                    triggered = false;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (!running) break;
            try {
                syncAll();
                failCount = 0;
            } catch (Exception e) {
                log.error("Sync error: {}", e.getMessage());
                failCount++;
                if (failCount >= 3) {
                    try { Thread.sleep(props.getSyncIntervalSeconds() * 2000L); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    failCount = 0;
                }
            }
        }
    }

    private void syncAll() {
        File workingDir = new File(appState.getWorkingDir());
        if (!workingDir.exists()) return;
        File[] workingFiles = workingDir.listFiles(f -> f.getName().endsWith(".xlsx"));
        if (workingFiles == null) return;

        List<String> synced = new ArrayList<>();
        boolean anyError = false;

        for (File workingFile : workingFiles) {
            try {
                File masterFile = new File(appState.getDataDir(), workingFile.getName());
                if (!masterFile.exists() || workingFile.lastModified() > masterFile.lastModified()) {
                    int year = extractYear(workingFile.getName());
                    ReentrantLock lock = writer.getLock(year);
                    lock.lock();
                    try {
                        Path tmp = masterFile.toPath().getParent().resolve("." + UUID.randomUUID() + ".tmp");
                        Files.copy(workingFile.toPath(), tmp, StandardCopyOption.REPLACE_EXISTING);
                        for (int attempt = 0; attempt < 3; attempt++) {
                            try {
                                Files.move(tmp, masterFile.toPath(),
                                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                                break;
                            } catch (IOException e) {
                                if (attempt == 2) throw e;
                                Thread.sleep((long) (200 * Math.pow(2, attempt)));
                            }
                        }
                        synced.add(workingFile.getName());
                        log.info("Synced {} -> {}", workingFile.getName(), masterFile.getPath());
                        auditService.log("working_synced_to_master", "system", null,
                                "Synced working copy to master: " + workingFile.getName(),
                                "success", "system");
                        // Confirmed eviction: master file was atomically replaced.
                        // Belt-and-suspenders alongside the eager eviction fired by the
                        // controller immediately after the working copy was written.
                        reader.evict(masterFile.getAbsolutePath());
                        if (reloadCallback != null) reloadCallback.run();
                        if (boxSyncService.isEnabled()) {
                            boxSyncService.submitUpload(masterFile);
                        }
                    } finally { lock.unlock(); }
                }
            } catch (Exception e) {
                log.error("Sync failed for {}: {}", workingFile.getName(), e.getMessage());
                anyError = true;
            }
        }

        statusLock.lock();
        try {
            lastSyncTime.set(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            syncStatus.set(anyError ? "error" : "success");
            lastSyncedFiles.clear();
            lastSyncedFiles.addAll(synced);
        } finally { statusLock.unlock(); }
    }

    private int extractYear(String filename) {
        Matcher m = YEAR_PAT.matcher(filename);
        if (m.find()) return Integer.parseInt(m.group(1));
        return LocalDateTime.now().getYear();
    }

    // Plain class replacing record
    public static final class SyncStatusInfo {
        private final String lastSync;
        private final String status;
        private final List<String> filesSynced;
        private final int intervalSeconds;
        private final boolean threadAlive;

        public SyncStatusInfo(String lastSync, String status, List<String> filesSynced,
                               int intervalSeconds, boolean threadAlive) {
            this.lastSync        = lastSync;
            this.status          = status;
            this.filesSynced     = filesSynced;
            this.intervalSeconds = intervalSeconds;
            this.threadAlive     = threadAlive;
        }

        public String getLastSync()         { return lastSync; }
        public String getStatus()           { return status; }
        public List<String> getFilesSynced(){ return filesSynced; }
        public int getIntervalSeconds()     { return intervalSeconds; }
        public boolean isThreadAlive()      { return threadAlive; }
    }
}
