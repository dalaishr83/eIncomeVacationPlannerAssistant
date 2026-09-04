package com.holidayleave.assistant.service;

import com.holidayleave.assistant.config.AppProperties;
import com.holidayleave.assistant.model.FileInfo;
import com.holidayleave.assistant.model.PendingVacation;
import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Process-global application state singleton.
 */
@Component
public class AppState {

    private static final Logger log = LoggerFactory.getLogger(AppState.class);

    @Autowired
    private AppProperties props;

    private final List<String> loadedFiles = new ArrayList<>();
    private final List<String> activeFiles = new ArrayList<>();
    private final List<FileInfo> knownFiles = new ArrayList<>();
    private final ConcurrentHashMap<String, PendingVacation> pendingVacations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<Map<String, String>>> conversationHistories = new ConcurrentHashMap<>();
    private final ReentrantLock agentLock = new ReentrantLock();

    private String dataDir;
    private String workingDir;
    private String uploadsDir;
    private String reportsDir;

    @PostConstruct
    public void init() throws Exception {
        // Resolve paths to absolute.  When a relative path is configured (e.g. DATA_DIR=data),
        // resolve it against the directory that contains the .env file (the project root),
        // not against Tomcat's working directory which may be a temp folder.
        dataDir    = resolve(props.getDataDir());
        workingDir = Paths.get(dataDir, "working").toString();
        uploadsDir = Paths.get(dataDir, "uploads").toString();
        reportsDir = resolve(props.getReportOutputDir());

        Files.createDirectories(Paths.get(dataDir));
        Files.createDirectories(Paths.get(workingDir));
        Files.createDirectories(Paths.get(uploadsDir));
        Files.createDirectories(Paths.get(reportsDir));

        log.info("AppState initialized. dataDir={}", dataDir);
    }

    /** Resolves a path to an absolute string.  Relative paths are anchored at app.base.dir
     *  (the directory containing the .env file) rather than the JVM working directory. */
    private static String resolve(String path) {
        Path p = Paths.get(path);
        if (p.isAbsolute()) return p.toString();
        String base = System.getProperty("app.base.dir");
        if (base != null && !base.trim().isEmpty()) {
            return Paths.get(base, path).toAbsolutePath().toString();
        }
        return p.toAbsolutePath().toString();
    }

    public void setLoadedFiles(List<String> paths) {
        agentLock.lock();
        try { loadedFiles.clear(); loadedFiles.addAll(paths); }
        finally { agentLock.unlock(); }
    }

    public List<String> getLoadedFiles() {
        agentLock.lock();
        try { return new ArrayList<>(loadedFiles); }
        finally { agentLock.unlock(); }
    }

    public void setActiveFiles(List<String> paths) {
        agentLock.lock();
        try { activeFiles.clear(); activeFiles.addAll(paths); }
        finally { agentLock.unlock(); }
    }

    public List<String> getActiveFiles() {
        agentLock.lock();
        try { return new ArrayList<>(activeFiles); }
        finally { agentLock.unlock(); }
    }

    public void refreshKnownFiles() {
        agentLock.lock();
        try {
            List<FileInfo> result = new ArrayList<>();
            File dataDirectory = new File(dataDir);
            if (dataDirectory.exists()) {
                File[] files = dataDirectory.listFiles(f -> f.getName().endsWith(".xlsx") && f.isFile());
                if (files != null) {
                    Arrays.sort(files, new Comparator<File>() {
                        @Override
                        public int compare(File a, File b) { return b.getName().compareTo(a.getName()); }
                    });
                    for (File f : files) {
                        boolean isActive = activeFiles.contains(f.getAbsolutePath()) ||
                                           activeFiles.contains(f.getPath()) ||
                                           activeFiles.stream().anyMatch(a -> new File(a).getName().equals(f.getName()));
                        result.add(new FileInfo(f.getName(), f.getPath(), isActive));
                    }
                }
            }
            knownFiles.clear();
            knownFiles.addAll(result);
        } finally { agentLock.unlock(); }
    }

    public List<FileInfo> getKnownFiles() {
        agentLock.lock();
        try { return new ArrayList<>(knownFiles); }
        finally { agentLock.unlock(); }
    }

    public void setPendingVacation(String sessionId, PendingVacation pv) { pendingVacations.put(sessionId, pv); }
    public PendingVacation getPendingVacation(String sessionId)          { return pendingVacations.get(sessionId); }
    public void removePendingVacation(String sessionId)                  { pendingVacations.remove(sessionId); }

    public List<Map<String, String>> getConversationHistory(String sessionId) {
        if (sessionId == null) return Collections.emptyList();
        List<Map<String, String>> h = conversationHistories.get(sessionId);
        return h == null ? Collections.emptyList() : new ArrayList<>(h);
    }

    public void addToHistory(String sessionId, String userMessage, String assistantReply) {
        if (sessionId == null) return;
        conversationHistories.computeIfAbsent(sessionId, k -> new ArrayList<>());
        List<Map<String, String>> hist = conversationHistories.get(sessionId);
        synchronized (hist) {
            Map<String, String> userMsg = new HashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", userMessage);
            hist.add(userMsg);

            Map<String, String> asstMsg = new HashMap<>();
            asstMsg.put("role", "assistant");
            asstMsg.put("content", assistantReply);
            hist.add(asstMsg);

            int maxEntries = props.getLlm().getHistoryWindow();
            while (hist.size() > maxEntries) {
                hist.remove(0);
            }
        }
    }

    public void clearHistory(String sessionId) {
        if (sessionId != null) conversationHistories.remove(sessionId);
    }

    /** Called on logout to free memory for the given session. */
    public void removeSessionHistory(String sessionId) {
        if (sessionId != null) conversationHistories.remove(sessionId);
    }

    public String getDataDir()    { return dataDir; }
    public String getWorkingDir() { return workingDir; }
    public String getUploadsDir() { return uploadsDir; }
    public String getReportsDir() { return reportsDir; }

    public List<String> discoverExcelPaths() {
        List<File> found = new ArrayList<>();
        File dir = new File(dataDir);
        if (dir.exists()) {
            File[] files = dir.listFiles(f -> f.getName().endsWith(".xlsx") && f.isFile());
            if (files != null) { Collections.addAll(found, files); }
        }
        Collections.sort(found, new Comparator<File>() {
            @Override
            public int compare(File a, File b) { return b.getName().compareTo(a.getName()); }
        });
        List<String> paths = new ArrayList<>();
        for (File f : found) paths.add(f.getAbsolutePath());
        return paths;
    }

    public ReentrantLock getAgentLock() { return agentLock; }
}
