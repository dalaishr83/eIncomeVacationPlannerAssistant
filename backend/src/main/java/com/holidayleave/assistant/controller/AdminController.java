package com.holidayleave.assistant.controller;

import com.holidayleave.assistant.excel.PlannerExcelReader;
import com.holidayleave.assistant.excel.WorkingExcelWriter;
import com.holidayleave.assistant.model.FileInfo;
import com.holidayleave.assistant.model.HolidayMasterType;
import com.holidayleave.assistant.model.LeaveRecord;
import com.holidayleave.assistant.model.VacationType;
import com.holidayleave.assistant.service.*;
import com.holidayleave.assistant.service.MasterExcelProvisioningService.ProvisionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/**
 * Admin-only controller.
 * All routes under /admin/** (pages) and /api/admin/** (REST) require role="admin".
 * The AuthInterceptor enforces this — no additional annotation needed here.
 */
@Controller
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    @Autowired private AppState appState;
    @Autowired private PlannerExcelReader reader;
    @Autowired private WorkingExcelWriter writer;
    @Autowired private VacationTypeService typeService;
    @Autowired private RestrictedVacationTypeService restrictedTypeService;
    @Autowired private SecretService secretService;
    @Autowired private AuditService auditService;
    @Autowired private SyncService syncService;
    @Autowired private MasterExcelProvisioningService provisioningService;
    @Autowired private HolidaySettingsService   holidaySettingsService;
    @Autowired private IndianHolidaySyncService  indianHolidaySyncService;

    // ── Page routes ───────────────────────────────────────────────────────────

    @GetMapping("/admin/settings")
    public String settingsPage(Model model) {
        model.addAttribute("vacationTypes", typeService.findAll());
        model.addAttribute("restrictedTypes", restrictedTypeService.getRestrictedTypes());
        model.addAttribute("currentPage", "settings");
        model.addAttribute("topbarSubtitle", "Admin — Settings");
        addActiveFilename(model);
        return "admin/settings";
    }

    @GetMapping("/admin/files")
    public String fileManagementPage(Model model) {
        model.addAttribute("currentPage", "files");
        model.addAttribute("topbarSubtitle", "Admin — File Management");
        addActiveFilename(model);
        return "admin/file-management";
    }

    @GetMapping("/admin/sync-holiday")
    public String syncHolidayPage(Model model) {
        model.addAttribute("currentPage", "sync-holiday");
        model.addAttribute("topbarSubtitle", "Admin — Sync Master Holiday");
        addActiveFilename(model);
        return "admin/sync-holiday";
    }

    @GetMapping("/admin/approvals")
    public String approvalsPage(Model model) {
        List<Map<String, Object>> pcRecords = loadPcRecords();
        model.addAttribute("pcRecords", pcRecords);
        model.addAttribute("currentPage", "approvals");
        model.addAttribute("topbarSubtitle", "Admin — PC Approvals");
        addActiveFilename(model);
        return "admin/approvals";
    }

    @GetMapping("/admin/audit-log")
    public String auditLogPage(Model model) {
        model.addAttribute("currentPage", "audit-log");
        model.addAttribute("topbarSubtitle", "Admin — Audit Log");
        addActiveFilename(model);
        return "admin/audit-log";
    }

    // ── Audit log API ─────────────────────────────────────────────────────────

    /** GET /api/admin/audit-log — returns all audit entries, most recent first */
    @GetMapping("/api/admin/audit-log")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getAuditLog() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("entries", auditService.readAll());
        return ResponseEntity.ok(r);
    }

    // ── Settings API ──────────────────────────────────────────────────────────

    /** GET /api/admin/settings/restricted-types — returns current restricted codes */
    @GetMapping("/api/admin/settings/restricted-types")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getRestrictedTypes() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("restricted_types", restrictedTypeService.getRestrictedTypes());
        r.put("vacation_types", typeService.findAll());
        return ResponseEntity.ok(r);
    }

    /** POST /api/admin/settings/restricted-types — replaces restricted codes list */
    @PostMapping("/api/admin/settings/restricted-types")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> setRestrictedTypes(@RequestBody Map<String, Object> body,
                                                                  HttpSession session) {
        Object raw = body.get("restricted_types");
        if (!(raw instanceof List)) {
            return ResponseEntity.badRequest().body(err("restricted_types must be an array of code strings."));
        }
        List<String> codes = new ArrayList<>();
        for (Object o : (List<?>) raw) codes.add(String.valueOf(o));
        String actingUser = (String) session.getAttribute("username");
        if (actingUser == null) actingUser = "admin";
        try {
            restrictedTypeService.setRestrictedTypes(codes);
            auditService.log("restricted_types_updated", actingUser, null,
                    "Updated restricted types: " + codes, "success", "api");
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("message", "Restricted vacation types updated.");
            r.put("restricted_types", restrictedTypeService.getRestrictedTypes());
            return ResponseEntity.ok(r);
        } catch (IOException e) {
            return ResponseEntity.status(500).body(err("Failed to save restricted types: " + e.getMessage()));
        }
    }

    /**
     * GET /api/admin/settings/employee-credentials
     * Returns [{username, employee_name}] for every employee-role credential entry.
     * Used by the Settings page to populate the employee password-reset dropdown.
     */
    @GetMapping("/api/admin/settings/employee-credentials")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getEmployeeCredentials() {
        Map<String, Map<String, String>> all = secretService.readCredentials();
        List<Map<String, String>> result = new ArrayList<>();
        for (Map<String, String> entry : all.values()) {
            if ("employee".equals(entry.get("role"))) {
                Map<String, String> item = new LinkedHashMap<>();
                item.put("username",      entry.get("username"));
                item.put("employee_name", entry.get("employee_name"));
                result.add(item);
            }
        }
        // Sort by employee_name for a predictable dropdown order.
        result.sort((a, b) -> {
            String na = a.get("employee_name"); String nb = b.get("employee_name");
            if (na == null) na = ""; if (nb == null) nb = "";
            return na.compareToIgnoreCase(nb);
        });
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("employees", result);
        return ResponseEntity.ok(r);
    }

    /**
     * GET /api/admin/settings/admin-credentials
     * Returns [{username, employee_name}] for every admin-role credential entry
     * that represents a real employee (employee_name is non-null).
     * Used by the Role Management widget to pre-populate the right-hand Admins panel.
     */
    @GetMapping("/api/admin/settings/admin-credentials")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getAdminCredentials() {
        Map<String, Map<String, String>> all = secretService.readCredentials();
        List<Map<String, String>> result = new ArrayList<>();
        for (Map<String, String> entry : all.values()) {
            if ("admin".equals(entry.get("role")) && entry.get("employee_name") != null) {
                Map<String, String> item = new LinkedHashMap<>();
                item.put("username",      entry.get("username"));
                item.put("employee_name", entry.get("employee_name"));
                result.add(item);
            }
        }
        result.sort((a, b) -> {
            String na = a.get("employee_name"); String nb = b.get("employee_name");
            if (na == null) na = ""; if (nb == null) nb = "";
            return na.compareToIgnoreCase(nb);
        });
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("admins", result);
        return ResponseEntity.ok(r);
    }

    /**
     * POST /api/admin/settings/promote
     * Body: { "usernames": ["dayananda", "..."] }
     * Sets role = "admin" for each listed username in secret.json.
     * Only the role field is modified; hash and all other attributes are preserved.
     */
    @PostMapping("/api/admin/settings/promote")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> promoteToAdmin(@RequestBody Map<String, Object> body,
                                                              HttpSession session) {
        String actingUser = (String) session.getAttribute("username");
        if (actingUser == null) actingUser = "admin";
        return updateRoles(body, "admin", "promote", actingUser);
    }

    /**
     * POST /api/admin/settings/demote
     * Body: { "usernames": ["dayananda", "..."] }
     * Sets role = "employee" for each listed username in secret.json.
     * Only the role field is modified; hash and all other attributes are preserved.
     */
    @PostMapping("/api/admin/settings/demote")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> demoteToEmployee(@RequestBody Map<String, Object> body,
                                                                HttpSession session) {
        String actingUser = (String) session.getAttribute("username");
        if (actingUser == null) actingUser = "admin";
        return updateRoles(body, "employee", "demote", actingUser);
    }

    /** Shared helper — updates the role field for a batch of usernames. */
    private ResponseEntity<Map<String, Object>> updateRoles(
            Map<String, Object> body, String newRole, String action, String actingUser) {

        Object raw = body.get("usernames");
        if (!(raw instanceof List)) {
            return ResponseEntity.badRequest().body(err("usernames must be an array of username strings."));
        }

        List<String> usernames = new ArrayList<>();
        for (Object o : (List<?>) raw) usernames.add(String.valueOf(o));

        if (usernames.isEmpty()) {
            return ResponseEntity.badRequest().body(err("usernames must not be empty."));
        }

        List<String> errors = new ArrayList<>();
        int updated = 0;

        for (String username : usernames) {
            if (secretService.findByUsername(username) == null) {
                errors.add("Unknown username: '" + username + "'");
                continue;
            }
            try {
                secretService.updateRole(username, newRole);
                auditService.log("role_" + action, actingUser, username,
                        "Role updated to '" + newRole + "' for user: " + username, "success", "api");
                updated++;
            } catch (IOException e) {
                errors.add("Failed for '" + username + "': " + e.getMessage());
            }
        }

        if (updated == 0) {
            return ResponseEntity.badRequest().body(err(
                    errors.isEmpty() ? "No users updated." : String.join("; ", errors)));
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("updated", updated);
        r.put("errors",  errors);
        r.put("message", updated + " user(s) role set to '" + newRole + "'.");
        return ResponseEntity.ok(r);
    }

    /** POST /api/admin/settings/password-reset — update password for a credential key */
    @PostMapping("/api/admin/settings/password-reset")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> resetPassword(@RequestBody Map<String, String> body,
                                                             HttpSession session) {
        String credKey  = body.get("role");          // field name kept as "role" for backward compat
        String password = body.get("new_password");
        if (credKey == null || credKey.trim().isEmpty())
            return ResponseEntity.badRequest().body(err("role is required."));
        if (password == null || password.length() < 6)
            return ResponseEntity.badRequest().body(err("new_password must be at least 6 characters."));
        // Accept any key that actually exists in the credential store.
        if (secretService.findByUsername(credKey) == null)
            return ResponseEntity.badRequest().body(err("Unknown credential key: '" + credKey + "'."));
        String actingUser = (String) session.getAttribute("username");
        if (actingUser == null) actingUser = "admin";
        try {
            secretService.updatePassword(credKey, password);
            auditService.log("password_reset", actingUser, null,
                    "Password reset for credential: " + credKey, "success", "api");
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("message", "Password for '" + credKey + "' updated successfully.");
            return ResponseEntity.ok(r);
        } catch (IOException e) {
            return ResponseEntity.status(500).body(err("Failed to update password: " + e.getMessage()));
        }
    }

    // ── Holiday Master file upload API ────────────────────────────────────────

    /**
     * POST /api/admin/holiday-upload
     * Accepts a single .xlsx file and saves it to {DATA_DIR}/holiday-upload/.
     *
     * When the optional {@code country} parameter is provided, the file is saved
     * under a canonical name derived from the country and the current calendar year:
     *   india   → India-holiday-<year>.xlsx
     *   denmark → Denmark-holiday-<year>.xlsx
     *   romania → Romania-holiday-<year>.xlsx
     *
     * If {@code country} is absent or unrecognised, the original filename is kept
     * (backward-compatible behaviour).
     *
     * This endpoint is independent of the Master File upload workflow; it does
     * not parse the workbook, create working copies, or update AppState.
     */
    @PostMapping("/api/admin/holiday-upload")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> uploadHolidayMaster(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @RequestParam(value = "country", required = false) String country,
            HttpSession session) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(err("No file field in request."));
        }
        String originalName = file.getOriginalFilename();
        if (originalName == null || !originalName.toLowerCase().endsWith(".xlsx")) {
            String ext = originalName != null && originalName.contains(".")
                    ? originalName.substring(originalName.lastIndexOf('.')) : "";
            return ResponseEntity.badRequest().body(err("Only .xlsx files are supported (got " + ext + ")."));
        }
        // Sanitise: reject path traversal in the filename
        String safeName = java.nio.file.Paths.get(originalName).getFileName().toString();
        if (safeName.isEmpty()) {
            return ResponseEntity.badRequest().body(err("Invalid filename."));
        }

        // Derive the canonical destination filename based on country, if supplied.
        String destName;
        if (country != null && !country.trim().isEmpty()) {
            String prefix;
            switch (country.trim().toLowerCase()) {
                case "india":   prefix = "India-holiday-";   break;
                case "denmark": prefix = "Denmark-holiday-"; break;
                case "romania": prefix = "Romania-holiday-"; break;
                default:        prefix = null;               break;
            }
            if (prefix != null) {
                int year = java.time.LocalDate.now().getYear();
                destName = prefix + year + ".xlsx";
            } else {
                destName = safeName;
            }
        } else {
            destName = safeName;
        }

        try {
            java.nio.file.Path uploadDir = java.nio.file.Paths.get(appState.getDataDir(), "holiday-upload");
            java.nio.file.Files.createDirectories(uploadDir);

            java.nio.file.Path dest = uploadDir.resolve(destName);
            try (java.io.InputStream in = file.getInputStream()) {
                java.nio.file.Files.copy(in, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            String actingUser = (String) session.getAttribute("username");
            if (actingUser == null) actingUser = "admin";
            auditService.log("holiday_master_uploaded", actingUser, null,
                    "Holiday master uploaded: " + destName, "success", "api");

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("message", "Holiday master file '" + destName + "' uploaded successfully.");
            r.put("filename", destName);
            return ResponseEntity.ok(r);
        } catch (java.io.IOException e) {
            log.error("Holiday master upload failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(err("Upload failed: " + e.getMessage()));
        }
    }

    // ── Provision next-year master file API ───────────────────────────────────

    /**
     * GET /api/admin/provision-next-year/target-year
     * Returns the year that would be used if the admin triggered provisioning right now.
     * Used by the UI to show the correct year in the confirmation dialog.
     */
    @GetMapping("/api/admin/provision-next-year/target-year")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getProvisionTargetYear() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("targetYear", resolveProvisionTargetYear());
        return ResponseEntity.ok(r);
    }

    /**
     * POST /api/admin/provision-next-year
     * Copies the template, replaces the YEAR placeholder with the calculated target year,
     * and places the result in /data as eIndkomst vacation <year>.xlsx.
     */
    @PostMapping("/api/admin/provision-next-year")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> provisionNextYear(HttpSession session) {
        int targetYear = resolveProvisionTargetYear();
        ProvisionResult result = provisioningService.provision(appState.getDataDir(), targetYear);

        if (result.isSuccess()) {
            // After provisioning, always activate the current-calendar-year file.
            // If the just-provisioned file IS the current year, use it directly.
            // If it is a future-year file, find and activate the current-year file instead.
            String currentYearPath = appState.resolveCurrentYearFilePath();
            String pathToActivate;
            if (currentYearPath != null && new File(currentYearPath).exists()) {
                pathToActivate = currentYearPath;
            } else {
                // Current-year file not found (e.g. provisioning bootstrapped it) — fall back
                // to the just-provisioned file.
                pathToActivate = Paths.get(appState.getDataDir(), result.getFilename())
                                      .toAbsolutePath().toString();
            }
            try {
                reader.load(pathToActivate);
            } catch (IOException e) {
                log.warn("Auto-load after provision failed (files still provisioned): {}", e.getMessage());
            }
            appState.setLoadedFiles(Collections.singletonList(pathToActivate));
            appState.setActiveFiles(Collections.singletonList(pathToActivate));
            appState.refreshKnownFiles();

            String activeName = new File(pathToActivate).getName();
            String actingUser = (String) session.getAttribute("username");
            if (actingUser == null) actingUser = "admin";
            auditService.log("master_file_provisioned", actingUser, null,
                    "Provisioned: " + result.getFilename() + "; activated: " + activeName, "success", "api");
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("message",    "Master file provisioned: " + result.getFilename()
                                + ". Active file: " + activeName);
            r.put("filename",   result.getFilename());
            r.put("files",      appState.getKnownFiles());
            r.put("activeFile", activeName);
            return ResponseEntity.ok(r);
        }


        return ResponseEntity.status(result.getHttpStatus()).body(err(result.getErrorMessage()));
    }

    /**
     * Determines the target year for master-file provisioning.
     * If a master file for the current system year already exists → currentYear + 1.
     * Otherwise → currentYear (provision the missing current-year file first).
     */
    private int resolveProvisionTargetYear() {
        int currentYear = LocalDate.now().getYear();
        Path currentYearPath = Paths.get(appState.getDataDir(),
                "eIndkomst vacation " + currentYear + ".xlsx");
        return currentYearPath.toFile().exists() ? currentYear + 1 : currentYear;
    }

    // ── Holiday Settings API ──────────────────────────────────────────────────

    /** GET /api/admin/holiday/files — list .xlsx files in DATA_DIR/holiday-upload/ */
    @GetMapping("/api/admin/holiday/files")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> listHolidayFiles() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("files", holidaySettingsService.listHolidayFiles(appState.getDataDir()));
        return ResponseEntity.ok(r);
    }

    /**
     * DELETE /api/admin/holiday/files
     * Body: { "filename": "Holiday List - 2026.xlsx" }
     * Permanently deletes the named file from {DATA_DIR}/holiday-upload/.
     */
    @DeleteMapping("/api/admin/holiday/files")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteHolidayFile(
            @RequestBody Map<String, String> body, HttpSession session) {

        String filename = body.get("filename");
        if (filename == null || filename.trim().isEmpty())
            return ResponseEntity.badRequest().body(err("filename is required."));
        if (filename.contains("/") || filename.contains("\\") || filename.contains(".."))
            return ResponseEntity.badRequest().body(err("Invalid filename."));

        java.nio.file.Path target = java.nio.file.Paths.get(
                appState.getDataDir(), "holiday-upload", filename);

        if (!target.toFile().exists())
            return ResponseEntity.status(404).body(err("File not found: " + filename));

        try {
            java.nio.file.Files.delete(target);

            String actingUser = (String) session.getAttribute("username");
            if (actingUser == null) actingUser = "admin";
            auditService.log("holiday_file_deleted", actingUser, null,
                    "Holiday file deleted: " + filename, "success", "api");

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("message", "Holiday file '" + filename + "' deleted.");
            r.put("files", holidaySettingsService.listHolidayFiles(appState.getDataDir()));
            return ResponseEntity.ok(r);
        } catch (java.io.IOException e) {
            log.error("Holiday file delete failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(err("Delete failed: " + e.getMessage()));
        }
    }

    /** GET /api/admin/holiday/indian-employees — read IN employees from active master file */
    @GetMapping("/api/admin/holiday/indian-employees")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getIndianEmployees() {
        List<String> loaded = appState.getLoadedFiles();
        if (loaded.isEmpty()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("employees", Collections.<String>emptyList());
            r.put("warning", "No master file is currently loaded.");
            return ResponseEntity.ok(r);
        }
        String masterPath = loaded.get(0);
        try {
            // Use PlannerExcelReader which correctly skips header rows via layout detection
            List<String> employees = reader.getIndianEmployeeNames(masterPath);
            // Sync: ensure every IN employee has an entry in employee-mapping.json
            Map<String, Object> mapping = holidaySettingsService.readMapping(appState.getDataDir());
            boolean updated = syncMappingEmployees(mapping, employees);
            if (updated) holidaySettingsService.writeMapping(appState.getDataDir(), mapping);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("employees", employees);
            return ResponseEntity.ok(r);
        } catch (IOException e) {
            return ResponseEntity.status(500).body(err("Failed to read master file: " + e.getMessage()));
        }
    }

    /** GET /api/admin/holiday/mapping — return employee-mapping.json contents */
    @GetMapping("/api/admin/holiday/mapping")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getEmployeeMapping() {
        return ResponseEntity.ok(holidaySettingsService.readMapping(appState.getDataDir()));
    }

    /**
     * PUT /api/admin/holiday/mapping — save city selection for one employee.
     * Body: { "employee": "...", "cities": "Bengaluru, Mysore" }
     * {@code cities} is the comma-separated string value from the radio button
     * (sourced directly from indian-city.json).
     */
    @PutMapping("/api/admin/holiday/mapping")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> updateEmployeeMapping(
            @RequestBody Map<String, Object> body, HttpSession session) {
        String employee  = (String) body.get("employee");
        String citiesRaw = body.get("cities") != null ? String.valueOf(body.get("cities")).trim() : "";
        if (employee == null || employee.trim().isEmpty())
            return ResponseEntity.badRequest().body(err("employee is required."));
        if (citiesRaw.isEmpty())
            return ResponseEntity.badRequest().body(err("cities is required."));

        try {
            Map<String, Object> mapping = holidaySettingsService.readMapping(appState.getDataDir());
            @SuppressWarnings("unchecked")
            Map<String, Object> employees = (Map<String, Object>) mapping.computeIfAbsent(
                    "employees", k -> new LinkedHashMap<String, Object>());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("city",    citiesRaw);   // stored as plain comma-separated string
            entry.put("country", "IN");
            employees.put(employee, entry);
            holidaySettingsService.writeMapping(appState.getDataDir(), mapping);

            String actingUser = (String) session.getAttribute("username");
            if (actingUser == null) actingUser = "admin";
            auditService.log("employee_city_mapped", actingUser, employee,
                    "City mapped to " + citiesRaw, "success", "api");

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("message", "Mapping saved for '" + employee + "'.");
            return ResponseEntity.ok(r);
        } catch (IOException e) {
            return ResponseEntity.status(500).body(err("Failed to save mapping: " + e.getMessage()));
        }
    }

    /** GET /api/admin/holiday/cities — return city list from indian-city.json */
    @GetMapping("/api/admin/holiday/cities")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getIndianCities() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("cities", holidaySettingsService.readCities(appState.getDataDir()));
        return ResponseEntity.ok(r);
    }

    /**
     * POST /api/admin/holiday/sync — route the holiday-sync request to the
     * appropriate parser based on the submitted filename.
     *
     * <p>Filename-to-type resolution is delegated entirely to
     * {@link HolidayMasterType#fromFilename(String)} so that no conditional
     * filename logic is scattered across this controller.
     *
     * <ul>
     *   <li>{@code India-holiday-*.xlsx}   → {@link IndianHolidaySyncService}</li>
     *   <li>{@code Denmark-holiday-*.xlsx} → future enhancement (not yet available)</li>
     *   <li>{@code Romania-holiday-*.xlsx} → future enhancement (not yet available)</li>
     *   <li>Unrecognised filename          → 400 Bad Request</li>
     * </ul>
     *
     * Body: { "filename": "India-holiday-2026.xlsx" }
     */
    @PostMapping("/api/admin/holiday/sync")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> syncIndianHolidays(
            @RequestBody Map<String, String> body, HttpSession session) {
        String filename = body.get("filename");
        if (filename == null || filename.trim().isEmpty())
            return ResponseEntity.badRequest().body(err("filename is required."));
        if (filename.contains("/") || filename.contains("\\") || filename.contains(".."))
            return ResponseEntity.badRequest().body(err("Invalid filename."));

        // Resolve the holiday type from the filename — single source of truth.
        HolidayMasterType type = HolidayMasterType.fromFilename(filename).orElse(null);
        if (type == null) {
            return ResponseEntity.badRequest().body(err(
                    "Unsupported or unrecognised holiday master file: '" + filename + "'. " +
                    "Expected a file named India-holiday-<year>.xlsx, " +
                    "Denmark-holiday-<year>.xlsx, or Romania-holiday-<year>.xlsx."));
        }

        // Route to the appropriate parser flow.
        switch (type) {
            case DENMARK_HOLIDAY: {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("message", "Denmark holiday synchronization is a future enhancement and is not currently available.");
                return ResponseEntity.ok(r);
            }
            case ROMANIA_HOLIDAY: {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("message", "Romania holiday synchronization is a future enhancement and is not currently available.");
                return ResponseEntity.ok(r);
            }
            case INDIAN_HOLIDAY:
            default: {
                String actingUser = (String) session.getAttribute("username");
                if (actingUser == null) actingUser = "admin";
                try {
                    IndianHolidaySyncService.SyncResult result =
                            indianHolidaySyncService.sync(filename, actingUser);
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("message",          result.getMessage());
                    r.put("written",          result.getWritten());
                    r.put("skipped",          result.getSkipped());
                    r.put("skipped_weekend",  result.getSkippedWeekend());
                    r.put("skipped_conflict", result.getSkippedConflict());
                    r.put("employees",        result.getEmployees());
                    return ResponseEntity.ok(r);
                } catch (IllegalArgumentException e) {
                    return ResponseEntity.badRequest().body(err(e.getMessage()));
                } catch (IOException e) {
                    log.error("Holiday sync failed: {}", e.getMessage(), e);
                    return ResponseEntity.status(500).body(err("Sync failed: " + e.getMessage()));
                }
            }
        }
    }

    /**
     * Ensures every employee in {@code employees} has an entry in the mapping.
     * New entries are initialised with an empty city list.
     * Returns {@code true} if any entry was added.
     */
    @SuppressWarnings("unchecked")
    private boolean syncMappingEmployees(Map<String, Object> mapping, List<String> employees) {
        Map<String, Object> empMap = (Map<String, Object>) mapping.computeIfAbsent(
                "employees", k -> new LinkedHashMap<String, Object>());
        boolean changed = false;
        for (String emp : employees) {
            if (!empMap.containsKey(emp)) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("city",    new ArrayList<String>());
                entry.put("country", "IN");
                empMap.put(emp, entry);
                changed = true;
            }
        }
        return changed;
    }

    // ── File management API ───────────────────────────────────────────────────

    /** DELETE /api/admin/files — delete a master file by name */
    @DeleteMapping("/api/admin/files")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteFile(@RequestBody Map<String, String> body,
                                                          HttpSession session) {
        String filename = body.get("filename");
        if (filename == null || filename.trim().isEmpty())
            return ResponseEntity.badRequest().body(err("filename is required."));

        // Security: only allow simple filenames (no path traversal)
        if (filename.contains("/") || filename.contains("\\") || filename.contains(".."))
            return ResponseEntity.badRequest().body(err("Invalid filename."));

        Path masterPath  = Paths.get(appState.getDataDir(), filename);
        Path workingPath = Paths.get(appState.getWorkingDir(), filename);
        Path uploadPath  = Paths.get(appState.getUploadsDir(), filename);

        if (!masterPath.toFile().exists())
            return ResponseEntity.status(404).body(err("File not found: " + filename));

        try {
            Files.deleteIfExists(masterPath);
            Files.deleteIfExists(workingPath);
            Files.deleteIfExists(uploadPath);

            // Evict from cache and reset state
            reader.evict(masterPath.toAbsolutePath().toString());
            appState.setLoadedFiles(Collections.<String>emptyList());
            appState.setActiveFiles(Collections.<String>emptyList());
            appState.refreshKnownFiles();

            String actingUser = (String) session.getAttribute("username");
            if (actingUser == null) actingUser = "admin";
            auditService.log("file_deleted", actingUser, null, "Deleted file: " + filename, "success", "api");

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("message", "File '" + filename + "' deleted.");
            r.put("files",   appState.getKnownFiles());
            return ResponseEntity.ok(r);
        } catch (IOException e) {
            log.error("File delete failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(err("Failed to delete file: " + e.getMessage()));
        }
    }

    // ── PC Approval API ───────────────────────────────────────────────────────

    /** GET /api/admin/approvals/pc-records — list all PC vacation entries */
    @GetMapping("/api/admin/approvals/pc-records")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getPcRecords() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("pc_records", loadPcRecords());
        return ResponseEntity.ok(r);
    }

    /**
     * POST /api/admin/approvals/approve-pc — convert selected PC entries to V in working file.
     * Body: { "approvals": [ { "employee_name":"...", "start_date":"YYYY-MM-DD", "end_date":"YYYY-MM-DD" }, ... ] }
     */
    @PostMapping("/api/admin/approvals/approve-pc")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> approvePc(@RequestBody Map<String, Object> body,
                                                         HttpSession session) {
        String actingUser = (String) session.getAttribute("username");
        if (actingUser == null) actingUser = "admin";
        Object raw = body.get("approvals");
        if (!(raw instanceof List))
            return ResponseEntity.badRequest().body(err("approvals must be an array."));

        VacationType vType = typeService.findByCode("V").orElse(null);
        if (vType == null)
            return ResponseEntity.status(500).body(err("Vacation type 'V' not found in configuration."));

        List<?> approvalList = (List<?>) raw;
        int approved = 0;
        List<String> errors = new ArrayList<>();

        for (Object item : approvalList) {
            if (!(item instanceof Map)) continue;
            Map<?, ?> entry = (Map<?, ?>) item;
            String empName  = (String) entry.get("employee_name");
            String startStr = (String) entry.get("start_date");
            String endStr   = (String) entry.get("end_date");

            if (empName == null || startStr == null || endStr == null) {
                errors.add("Skipped entry with missing fields.");
                continue;
            }
            try {
                LocalDate start = LocalDate.parse(startStr);
                LocalDate end   = LocalDate.parse(endStr);
                int year = start.getYear();

                String workingPath = appState.getWorkingDir() + "/eIndkomst vacation " + year + ".xlsx";
                ensureWorkingCopy(workingPath, year);

                // Delete existing PC cells then write V cells
                writer.deleteVacation(workingPath, empName, start, end);
                LeaveRecord newRecord = new LeaveRecord(empName, start, end,
                        VacationCreationService.countWeekdays(start, end), vType.label(), "Approved PC");
                writer.addVacation(workingPath, newRecord, vType);
                reader.evict(Paths.get(appState.getDataDir(), "eIndkomst vacation " + year + ".xlsx")
                        .toAbsolutePath().toString());
                approved++;
                auditService.log("pc_approved", actingUser, empName,
                        "PC→V approved " + startStr + " to " + endStr, "success", "api");
            } catch (Exception e) {
                errors.add("Failed for " + empName + " (" + startStr + "): " + e.getMessage());
                log.warn("PC approval error: {}", e.getMessage());
            }
        }

        if (approved > 0) syncService.triggerSync();

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("approved", approved);
        r.put("errors",   errors);
        r.put("message",  approved + " PC vacation(s) approved as Vacation (V).");
        return ResponseEntity.ok(r);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<Map<String, Object>> loadPcRecords() {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            List<LeaveRecord> all = new ArrayList<>();
            for (String p : appState.getLoadedFiles()) {
                if (new File(p).exists()) all.addAll(reader.load(p));
            }
            for (LeaveRecord r : all) {
                if ("PC".equalsIgnoreCase(r.leaveType()) ||
                    "Personal Choice Holiday".equalsIgnoreCase(r.leaveType())) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("employee_name",  r.employeeName());
                    m.put("start_date",     r.startDate().toString());
                    m.put("end_date",       r.endDate().toString());
                    m.put("days",           r.days());
                    m.put("year",           r.year());
                    m.put("holiday_type",   r.leaveType());
                    result.add(m);
                }
            }
        } catch (IOException e) {
            log.error("Failed to load PC records: {}", e.getMessage());
        }
        return result;
    }

    private void ensureWorkingCopy(String workingPath, int year) throws IOException {
        if (!new File(workingPath).exists()) {
            String masterPath = appState.getDataDir() + "/eIndkomst vacation " + year + ".xlsx";
            if (new File(masterPath).exists()) {
                Files.createDirectories(Paths.get(workingPath).getParent());
                Files.copy(Paths.get(masterPath), Paths.get(workingPath), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private Map<String, Object> err(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg);
        return m;
    }

    /**
     * Adds {@code activeFilename} to the model — the bare filename of the currently
     * active/loaded master Excel file, or {@code null} if no file is loaded.
     * Used by layout.html to show the active file in the sidebar brand area.
     */
    private void addActiveFilename(Model model) {
        List<String> active = appState.getActiveFiles();
        String name = null;
        if (!active.isEmpty()) {
            name = new File(active.get(0)).getName();
        }
        model.addAttribute("activeFilename", name);
    }
}
