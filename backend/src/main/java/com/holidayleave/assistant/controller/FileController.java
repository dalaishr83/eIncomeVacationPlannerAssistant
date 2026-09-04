package com.holidayleave.assistant.controller;

import com.holidayleave.assistant.excel.PlannerExcelReader;
import com.holidayleave.assistant.model.FileInfo;
import com.holidayleave.assistant.model.LeaveRecord;
import com.holidayleave.assistant.service.AppState;
import com.holidayleave.assistant.service.AuditService;
import com.holidayleave.assistant.service.SecretService;
import javax.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

@RestController
@RequestMapping("/api")
public class FileController {

    private static final Logger log = LoggerFactory.getLogger(FileController.class);

    @Autowired private AppState appState;
    @Autowired private PlannerExcelReader reader;
    @Autowired private SecretService secretService;
    @Autowired private AuditService auditService;

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                                      HttpSession session) {
        if (file.isEmpty()) return ResponseEntity.badRequest().body(err("No file field in request"));
        String originalName = file.getOriginalFilename();
        if (originalName == null || !originalName.toLowerCase().endsWith(".xlsx")) {
            String ext = originalName != null && originalName.contains(".")
                    ? originalName.substring(originalName.lastIndexOf('.')) : "";
            return ResponseEntity.badRequest().body(err("Only .xlsx files are supported (got " + ext + ")"));
        }
        try {
            Path tmpPath = Paths.get(appState.getUploadsDir(), "_tmp_" + UUID.randomUUID() + ".xlsx");
            Files.createDirectories(tmpPath.getParent());
            try (java.io.InputStream in = file.getInputStream()) {
                Files.copy(in, tmpPath, StandardCopyOption.REPLACE_EXISTING);
            }

            // Single workbook load on the temp file: gives us both records and the year.
            // This replaces the previous detectYear() + load() double-open pattern (F-6).
            List<LeaveRecord> records = reader.load(tmpPath.toString());
            Integer year = records.isEmpty() ? null : records.get(0).year();
            if (year == null) {
                // Filename fallback for edge case where file has no date rows
                year = reader.detectYear(tmpPath.toString());
            }
            if (year == null) {
                Files.deleteIfExists(tmpPath);
                return ResponseEntity.unprocessableEntity().body(err("Could not detect the year from the uploaded file."));
            }
            String canonicalName = "eIndkomst vacation " + year + ".xlsx";
            Path masterPath  = Paths.get(appState.getDataDir(),    canonicalName);
            Path uploadPath  = Paths.get(appState.getUploadsDir(), canonicalName);
            Path workingPath = Paths.get(appState.getWorkingDir(), canonicalName);

            Files.copy(tmpPath, masterPath,  StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmpPath, uploadPath,  StandardCopyOption.REPLACE_EXISTING);
            Files.copy(masterPath, workingPath, StandardCopyOption.REPLACE_EXISTING);

            // Evict the master path from cache — the file was just replaced on disk.
            // The records variable already holds the parsed content from the temp file
            // (identical bytes to masterPath), so no second load is needed.
            reader.evict(masterPath.toAbsolutePath().toString());

            // Use getEmployeeNames(filePath) so employees with zero leave in this file
            // are included (not just those who appear in at least one LeaveRecord).
            List<String> employees = reader.getEmployeeNames(masterPath.toAbsolutePath().toString());

            appState.setLoadedFiles(Collections.singletonList(masterPath.toString()));
            appState.setActiveFiles(Collections.singletonList(masterPath.toString()));
            appState.refreshKnownFiles();

            // Auto-provision login credentials for each employee in the uploaded file.
            String actingUser = (String) session.getAttribute("username");
            if (actingUser == null) actingUser = "admin";
            List<String> provisioned = new ArrayList<>();
            for (String emp : employees) {
                try {
                    String uname = secretService.provisionEmployee(emp);
                    provisioned.add(emp + " → " + uname);
                    /*auditService.log("employee_provisioned", actingUser, emp,
                            "Credential created: " + uname, "success", "api");*/
                } catch (Exception e) {
                    log.warn("Provisioning failed for '{}': {}", emp, e.getMessage());
                }
            }
            //log(String eventType, String user, String employee, String details, String status, String source) 
            auditService.log("File loaded as '" + canonicalName + "'.", "Admin", actingUser, employees.size() + " employee(s) found.", "success", "api");
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("message",              "File loaded as '" + canonicalName + "'. " + employees.size() + " employee(s) found.");
            r.put("employees",            employees);
            r.put("filename",             canonicalName);
            r.put("files",                appState.getKnownFiles());
            r.put("provisioned_employees", provisioned);
            return ResponseEntity.ok(r);
        } catch (IOException e) {
            log.error("Upload failed: {}", e.getMessage(), e);
            return ResponseEntity.unprocessableEntity().body(err("Could not parse Excel file: " + e.getMessage()));
        }
    }

    @GetMapping("/files")
    public ResponseEntity<Map<String, Object>> listFiles() {
        appState.refreshKnownFiles();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("files", appState.getKnownFiles());
        return ResponseEntity.ok(r);
    }

    @PostMapping("/switch-file")
    public ResponseEntity<Map<String, Object>> switchFile(@RequestBody Map<String, String> body) {
        String path = body.get("path");
        if (path == null || path.trim().isEmpty())
            return ResponseEntity.badRequest().body(err("No 'path' field in request"));

        List<FileInfo> known = appState.getKnownFiles();
        boolean isKnown = false;
        for (FileInfo fi : known) {
            if (fi.getPath().equals(path) || fi.getName().equals(new File(path).getName())) { isKnown = true; break; }
        }
        if (!isKnown) return ResponseEntity.status(403).body(err("Requested file is not in the known files list"));

        File f = new File(path);
        if (!f.exists()) return ResponseEntity.status(404).body(err("File not found: " + path));

        try {
            // Fix B: use absolute path so the cache key matches what AppState stores,
            // eliminating a double-parse on every subsequent Chat request.
            String absPath = f.getAbsolutePath();
            reader.load(absPath);
            // Fix A: derive employee list from the structural name column (includes
            // employees with zero leave entries in this file).
            List<String> employees = reader.getEmployeeNames(absPath);
            appState.setLoadedFiles(Collections.singletonList(absPath));
            appState.setActiveFiles(Collections.singletonList(absPath));
            appState.refreshKnownFiles();

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("message",   "Switched to '" + f.getName() + "'. " + employees.size() + " employee(s) loaded.");
            r.put("employees", employees);
            r.put("files",     appState.getKnownFiles());
            return ResponseEntity.ok(r);
        } catch (IOException e) {
            return ResponseEntity.unprocessableEntity().body(err("Could not parse Excel file: " + e.getMessage()));
        }
    }

    @GetMapping("/employees")
    public ResponseEntity<Map<String, Object>> getEmployees() {
        try {
            List<LeaveRecord> records = loadMasterRecords();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("employees", reader.getEmployeeNames(records));
            return ResponseEntity.ok(r);
        } catch (IOException e) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("employees", Collections.emptyList());
            return ResponseEntity.ok(r);
        }
    }

    @GetMapping("/years")
    public ResponseEntity<Map<String, Object>> getYears() {
        try {
            List<LeaveRecord> records = loadMasterRecords();
            Set<Integer> yearsSet = new LinkedHashSet<>();
            for (LeaveRecord r : records) yearsSet.add(r.year());
            List<Integer> years = new ArrayList<>(yearsSet);
            Collections.sort(years, Collections.reverseOrder());
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("years", years);
            return ResponseEntity.ok(r);
        } catch (IOException e) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("years", Collections.emptyList());
            return ResponseEntity.ok(r);
        }
    }

    private List<LeaveRecord> loadMasterRecords() throws IOException {
        List<LeaveRecord> all = new ArrayList<>();
        for (String p : appState.getLoadedFiles()) {
            if (new File(p).exists()) all.addAll(reader.load(p));
        }
        return all;
    }

    private Map<String, Object> err(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg);
        return m;
    }
}
