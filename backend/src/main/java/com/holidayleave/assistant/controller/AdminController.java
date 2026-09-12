package com.holidayleave.assistant.controller;

import com.holidayleave.assistant.config.AppProperties;
import com.holidayleave.assistant.excel.IndianHolidayMasterExcelParser;
import com.holidayleave.assistant.excel.PlannerExcelReader;
import com.holidayleave.assistant.excel.WorkingExcelWriter;
import com.holidayleave.assistant.model.FileInfo;
import com.holidayleave.assistant.model.HolidayMasterType;
import com.holidayleave.assistant.model.LeaveRecord;
import com.holidayleave.assistant.model.VacationType;
import com.holidayleave.assistant.service.*;
import com.holidayleave.assistant.service.MasterExcelProvisioningService.ProvisionResult;
import com.holidayleave.assistant.service.TeamForecastService.TeamForecastResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

import org.apache.poi.ss.usermodel.*;

/**
 * Admin-only controller.
 * All routes under /admin/** (pages) and /api/admin/** (REST) require role="admin".
 * The AuthInterceptor enforces this — no additional annotation needed here.
 */
@Controller
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    @Autowired private AppProperties appProperties;
    @Autowired private AppState appState;
    @Autowired private PlannerExcelReader reader;
    @Autowired private WorkingExcelWriter writer;
    @Autowired private VacationTypeService typeService;
    @Autowired private RestrictedVacationTypeService restrictedTypeService;
    @Autowired private SecretService secretService;
    @Autowired private AuditService auditService;
    @Autowired private SyncService syncService;
    @Autowired private MasterExcelProvisioningService provisioningService;
    @Autowired private HolidaySettingsService     holidaySettingsService;
    @Autowired private IndianHolidayMasterExcelParser indianHolidayMasterExcelParser;
    @Autowired private IndianHolidaySyncService   indianHolidaySyncService;
    @Autowired private TeamForecastService         teamForecastService;
    @Autowired private SlackNotificationService    slackNotificationService;

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

    @GetMapping("/admin/schedule-cron")
    public String scheduleCronPage(Model model) {
        model.addAttribute("currentPage", "schedule-cron");
        model.addAttribute("topbarSubtitle", "Admin — Schedule Cron");
        addActiveFilename(model);
        return "admin/schedule-cron";
    }

    /**
     * GET /admin/master-file-view?filename=eIndkomst+vacation+2025.xlsx
     *
     * Opens the named master Excel file read-only and renders its contents as
     * an HTML table in a standalone popup page (excel-viewer.html).
     *
     * Security:
     *  - Route is under /admin/** → AuthInterceptor requires role="admin".
     *  - filename validated: no path separators or ".." allowed.
     *  - filename must be present in the server-side known-files whitelist.
     *
     * Read-only guarantee:
     *  - File opened via FileInputStream only; no write-back path exists.
     *  - WorkbookFactory.create(stream) never writes to disk.
     */
    @GetMapping("/admin/master-file-view")
    public String masterFileViewPage(@RequestParam("filename") String filename, Model model) {

        // ── Validate filename ────────────────────────────────────────────────
        if (filename == null || filename.trim().isEmpty()
                || filename.contains("/") || filename.contains("\\") || filename.contains("..")) {
            model.addAttribute("error", "Invalid filename.");
            return "admin/excel-viewer";
        }

        // ── Whitelist check against known master files ───────────────────────
        boolean isKnown = false;
        for (FileInfo fi : appState.getKnownFiles()) {
            if (fi.getName().equals(filename)) { isKnown = true; break; }
        }
        if (!isKnown) {
            model.addAttribute("error", "File not found or not a known master file.");
            return "admin/excel-viewer";
        }

        // ── Resolve absolute path and open read-only ─────────────────────────
        Path filePath = Paths.get(appState.getDataDir(), filename);
        if (!filePath.toFile().exists()) {
            model.addAttribute("error", "File does not exist on disk.");
            return "admin/excel-viewer";
        }

        // ── Build vacation-type colour map from the application's configured types ──
        // colour field is AARRGGBB; we need the last 6 chars (RRGGBB).
        Map<String, String> vtColour = new LinkedHashMap<>();
        for (com.holidayleave.assistant.model.VacationType vt : typeService.findAll()) {
            String c = vt.color();
            if (c != null && c.length() == 8) {
                // Exclude white (A=available, no visible colour needed) and fully transparent
                String rgb = c.substring(2).toUpperCase();
                if (!"FFFFFF".equals(rgb)) vtColour.put(vt.code().toUpperCase(), rgb);
            }
        }

        // ── Parse workbook and build per-sheet HTML tables ───────────────────
        List<Map<String, Object>> sheets = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(filePath.toFile());
             Workbook workbook = WorkbookFactory.create(fis)) {

            DataFormatter formatter = new DataFormatter();
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();

            for (int si = 0; si < workbook.getNumberOfSheets(); si++) {
                Sheet sheet = workbook.getSheetAt(si);

                // ── Build weekend column set for this sheet ───────────────────
                // Scan the weekday-header row (row index 3 for the main planner sheet).
                // A column is a weekend column when its weekday header cell has a
                // pink (#FFB6C1) fill AND the value is "S".
                // We use the fill-based detection so it works regardless of row position.
                Set<Integer> weekendCols = new HashSet<>();
                for (Row hrow : sheet) {
                    boolean foundWeekendHeader = false;
                    for (Cell hcell : hrow) {
                        String fill = extractFillHex(hcell);
                        String val  = formatter.formatCellValue(hcell).trim();
                        if ("FFB6C1".equals(fill) && "S".equals(val)) {
                            weekendCols.add(hcell.getColumnIndex());
                            foundWeekendHeader = true;
                        }
                    }
                    // Stop after we've processed the first row that has pink "S" cells
                    if (foundWeekendHeader) break;
                }

                // ── Build a lookup: (row,col) → merged region ─────────────────
                // For each CellRangeAddress we store two things:
                //   anchorKey  (r1,c1) → the region itself  (anchor cell: emit colspan/rowspan)
                //   coveredKey (r, c)  → true for every non-anchor cell inside the region
                //                        (those cells must be skipped in the HTML output)
                Map<String, org.apache.poi.ss.util.CellRangeAddress> mergeAnchor  = new HashMap<>();
                Map<String, Boolean>                                  mergeCovered = new HashMap<>();
                for (org.apache.poi.ss.util.CellRangeAddress mr : sheet.getMergedRegions()) {
                    String anchorKey = mr.getFirstRow() + "," + mr.getFirstColumn();
                    mergeAnchor.put(anchorKey, mr);
                    for (int r = mr.getFirstRow(); r <= mr.getLastRow(); r++) {
                        for (int c = mr.getFirstColumn(); c <= mr.getLastColumn(); c++) {
                            // Non-anchor cells
                            if (r != mr.getFirstRow() || c != mr.getFirstColumn()) {
                                mergeCovered.put(r + "," + c, Boolean.TRUE);
                            }
                        }
                    }
                }

                // Determine the max column index across all rows (needed so every
                // row in the HTML has the same number of cells for alignment).
                int maxCol = 0;
                for (Row row : sheet) {
                    if (row.getLastCellNum() > maxCol) maxCol = row.getLastCellNum();
                }

                // ── Find the last data row by scanning column A (index 0) ─────
                // Rows beyond this point are empty trailing rows; we skip them
                // entirely so weekend colour does not bleed into blank space.
                int lastDataRowNum = -1;
                for (Row row : sheet) {
                    Cell colA = row.getCell(0, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    if (colA != null) {
                        String colAVal = "";
                        try { colAVal = formatter.formatCellValue(colA, evaluator).trim(); }
                        catch (Exception ignored) { colAVal = formatter.formatCellValue(colA).trim(); }
                        if (!colAVal.isEmpty()) lastDataRowNum = row.getRowNum();
                    }
                }

                StringBuilder html = new StringBuilder();
                html.append("<table class=\"ev-table\">");

                for (Row row : sheet) {
                    int rowNum = row.getRowNum();
                    // Stop rendering once we have passed the last non-empty column-A row.
                    if (lastDataRowNum >= 0 && rowNum > lastDataRowNum) break;

                    html.append("<tr>");
                    // Use maxCol so we always emit the full column count.
                    int lastCol = Math.max(row.getLastCellNum(), 0);
                    for (int ci = 0; ci < lastCol; ci++) {

                        // Skip cells that are inside a merge region but are not the anchor.
                        if (Boolean.TRUE.equals(mergeCovered.get(rowNum + "," + ci))) {
                            continue;
                        }

                        Cell cell = row.getCell(ci, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                        String value = "";
                        String fillHex = null;
                        if (cell != null) {
                            try {
                                value = formatter.formatCellValue(cell, evaluator);
                            } catch (Exception ignored) {
                                value = formatter.formatCellValue(cell);
                            }
                            fillHex = extractFillHex(cell);
                        }

                        // ── Conditional-format colour fallback ───────────────
                        // Vacation cells have NO_FILL at the cell style level;
                        // Excel colours them via conditional formatting rules.
                        // We replicate those rules here:
                        //   1. If the cell value matches a known vacation type code,
                        //      use its configured colour.
                        //   2. If no cell-level fill and the column is a weekend column,
                        //      apply the weekend pink (#FFB6C1).
                        if (fillHex == null) {
                            String upperVal = value.trim().toUpperCase();
                            if (vtColour.containsKey(upperVal)) {
                                fillHex = vtColour.get(upperVal);
                            } else if (weekendCols.contains(ci)) {
                                fillHex = "FFB6C1";
                            }
                        }

                        // Strip repeated text that Excel stores in each cell of a merged
                        // region (the dump shows "JANUARY   JANUARY   JANUARY").
                        // We want only the clean label at the anchor.
                        value = value.trim();
                        // If the value is a repetition of the same word/phrase, reduce it.
                        value = deduplicateMergedValue(value);

                        // Escape HTML special characters
                        value = value.replace("&", "&amp;")
                                     .replace("<", "&lt;")
                                     .replace(">", "&gt;")
                                     .replace("\"", "&quot;");

                        // Check whether this cell is a merge anchor.
                        org.apache.poi.ss.util.CellRangeAddress mr =
                                mergeAnchor.get(rowNum + "," + ci);

                        // Build attributes
                        StringBuilder attrs = new StringBuilder();
                        if (mr != null) {
                            int cs = mr.getLastColumn() - mr.getFirstColumn() + 1;
                            int rs = mr.getLastRow()    - mr.getFirstRow()    + 1;
                            if (cs > 1) attrs.append(" colspan=\"").append(cs).append("\"");
                            if (rs > 1) attrs.append(" rowspan=\"").append(rs).append("\"");
                            // Merged header cells are always centred
                            if (cs > 1 || rs > 1) attrs.append(" class=\"ev-merged\"");
                        }
                        if (fillHex != null) {
                            attrs.append(" style=\"background:#").append(fillHex).append("\"");
                        }

                        boolean isHeader = (rowNum == sheet.getFirstRowNum());
                        String tag = isHeader ? "th" : "td";
                        html.append("<").append(tag).append(attrs)
                            .append(">").append(value)
                            .append("</").append(tag).append(">");
                    }
                    html.append("</tr>");
                }
                html.append("</table>");

                Map<String, Object> sheetData = new LinkedHashMap<>();
                sheetData.put("name", sheet.getSheetName());
                sheetData.put("html", html.toString());
                sheets.add(sheetData);
            }

        } catch (IOException e) {
            log.error("Excel viewer failed to read '{}': {}", filename, e.getMessage(), e);
            model.addAttribute("error", "Could not read the file: " + e.getMessage());
            return "admin/excel-viewer";
        }

        model.addAttribute("filename", filename);
        model.addAttribute("sheets",   sheets);
        return "admin/excel-viewer";
    }

    /**
     * Excel sometimes stores the label text repeated multiple times inside a merged cell,
     * separated by whitespace (e.g. "JANUARY   JANUARY   JANUARY").
     * This helper detects that pattern and returns just the first occurrence.
     */
    private String deduplicateMergedValue(String value) {
        if (value == null || value.isEmpty()) return value;
        String trimmed = value.trim();
        // Split on two or more whitespace characters; if the result is all the same token, return just one.
        String[] parts = trimmed.split("\\s{2,}");
        if (parts.length > 1) {
            String first = parts[0].trim();
            boolean allSame = true;
            for (String p : parts) {
                if (!p.trim().equals(first)) { allSame = false; break; }
            }
            if (allSame) return first;
        }
        return trimmed;
    }

    /**
     * Extracts the fill (background) colour of a cell as a 6-character upper-case
     * hex string (e.g. "FFB6C1"), or null if the cell has no fill or a default/white fill.
     *
     * Handles both XSSF (.xlsx) and HSSF (.xls) workbooks.
     * Returns null for fully transparent, white (#FFFFFF), or theme-based fills
     * that cannot be resolved to an RGB value, so that only meaningful colours
     * are rendered in the HTML viewer.
     */
    private String extractFillHex(org.apache.poi.ss.usermodel.Cell cell) {
        org.apache.poi.ss.usermodel.CellStyle style = cell.getCellStyle();
        if (style == null) return null;

        // ── XSSF (.xlsx) ─────────────────────────────────────────────────────
        if (style instanceof org.apache.poi.xssf.usermodel.XSSFCellStyle) {
            org.apache.poi.xssf.usermodel.XSSFCellStyle xs =
                    (org.apache.poi.xssf.usermodel.XSSFCellStyle) style;
            org.apache.poi.xssf.usermodel.XSSFColor fg = xs.getFillForegroundXSSFColor();
            if (fg != null) {
                byte[] rgb = fg.getRGB();
                if (rgb != null && rgb.length >= 3) {
                    String hex = String.format("%02X%02X%02X",
                            rgb[0] & 0xFF, rgb[1] & 0xFF, rgb[2] & 0xFF);
                    // Skip white and fully transparent fills
                    if (!"FFFFFF".equals(hex) && !"000000".equals(hex)) return hex;
                }
            }
            return null;
        }

        // ── HSSF (.xls) ──────────────────────────────────────────────────────
        short colorIdx = style.getFillForegroundColor();
        if (colorIdx == org.apache.poi.hssf.util.HSSFColor.HSSFColorPredefined.AUTOMATIC.getIndex()
                || colorIdx == 0) return null;
        org.apache.poi.hssf.util.HSSFColor color =
                org.apache.poi.hssf.util.HSSFColor.getIndexHash().get((int) colorIdx);
        if (color != null) {
            short[] triplet = color.getTriplet();
            if (triplet != null && triplet.length >= 3) {
                String hex = String.format("%02X%02X%02X",
                        triplet[0] & 0xFF, triplet[1] & 0xFF, triplet[2] & 0xFF);
                if (!"FFFFFF".equals(hex)) return hex;
            }
        }
        return null;
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
     * Returns [{username, employee_name}] sourced from the active master Excel sheet.
     * Every employee name found in the planner is included, merged with any existing
     * credential entry so that username (if already provisioned) is preserved.
     * Used by the Settings page Role Management widget and the password-reset dropdown.
     */
    @GetMapping("/api/admin/settings/employee-credentials")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getEmployeeCredentials() {
        // Build a lookup of existing credentials keyed by employee_name (lower-cased).
        Map<String, Map<String, String>> all = secretService.readCredentials();
        Map<String, Map<String, String>> byName = new LinkedHashMap<>();
        for (Map<String, String> entry : all.values()) {
            String empName = entry.get("employee_name");
            if (empName != null) byName.put(empName.toLowerCase(), entry);
        }

        // Load the full employee roster from the active master Excel sheet.
        List<String> excelNames = new ArrayList<>();
        String activePath = appState.resolveCurrentYearFilePath();
        if (activePath == null) {
            List<String> active = appState.getActiveFiles();
            if (!active.isEmpty()) activePath = active.get(0);
        }
        if (activePath != null) {
            try {
                excelNames = reader.getEmployeeNames(activePath);
            } catch (IOException e) {
                log.warn("getEmployeeCredentials: could not read employee names from {}: {}", activePath, e.getMessage());
            }
        }

        List<Map<String, String>> result = new ArrayList<>();
        for (String name : excelNames) {
            Map<String, String> existing = byName.get(name.toLowerCase());
            Map<String, String> item = new LinkedHashMap<>();
            item.put("username",      existing != null ? existing.get("username") : "");
            item.put("employee_name", name);
            result.add(item);
        }

        // Sort by employee_name for a predictable order.
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

            if ("india".equalsIgnoreCase(country)) {
                indianHolidayMasterExcelParser.generateCityMapping(dest.toString(), appState.getDataDir());
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
     * After provisioning, seeds secret.json with any employees discovered across ALL
     * master Excel files in DATA_DIR that are not yet present (incremental, non-destructive).
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

            // Seed secret.json: iterate every master Excel file in DATA_DIR and
            // provisionEmployee() for each name — same mechanism as FileController.upload().
            // provisionEmployee() is idempotent: existing entries are never overwritten.
            List<String> provisionedEmployees = new ArrayList<>();
            for (String xlsxPath : appState.discoverExcelPaths()) {
                try {
                    List<String> names = reader.getEmployeeNames(xlsxPath);
                    for (String name : names) {
                        try {
                            String uname = secretService.provisionEmployee(name);
                            provisionedEmployees.add(name + " → " + uname);
                        } catch (Exception ex) {
                            log.warn("Credential provisioning skipped for '{}': {}", name, ex.getMessage());
                        }
                    }
                } catch (IOException ex) {
                    log.warn("Could not read employee names from '{}': {}", xlsxPath, ex.getMessage());
                }
            }

            String activeName = new File(pathToActivate).getName();
            String actingUser = (String) session.getAttribute("username");
            if (actingUser == null) actingUser = "admin";
            auditService.log("master_file_provisioned", actingUser, null,
                    "Provisioned: " + result.getFilename() + "; activated: " + activeName, "success", "api");
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("message",               "Master file provisioned: " + result.getFilename()
                                           + ". Active file: " + activeName);
            r.put("filename",              result.getFilename());
            r.put("files",                 appState.getKnownFiles());
            r.put("activeFile",            activeName);
            r.put("provisioned_employees", provisionedEmployees);
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
        Map<String, String> cities = holidaySettingsService.readCities(appState.getDataDir());
        Set<String> excludedCities = parseExcludedCities();
        if (!excludedCities.isEmpty()) {
            cities.entrySet().removeIf(entry -> excludedCities.contains(entry.getKey().trim()));
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("cities", cities);
        return ResponseEntity.ok(r);
    }

    private Set<String> parseExcludedCities() {
        String configured = appProperties.getExcludedCityList();
        if (configured == null || configured.trim().isEmpty()) return Collections.emptySet();
        Set<String> excluded = new HashSet<>();
        for (String city : configured.split(",")) {
            String trimmed = city.trim();
            if (!trimmed.isEmpty()) excluded.add(trimmed);
        }
        return excluded;
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

    // ── Team Forecast API ─────────────────────────────────────────────────────

    /**
     * POST /api/admin/team-forecast
     * Body: { "team": "Indian Team" | "EIndkomst Team",
     *         "startDate": "YYYY-MM-DD",
     *         "endDate":   "YYYY-MM-DD" }
     *
     * Returns: { "html": "<table>...</table>", "rowCount": N, "summary": "..." }
     *   or on error: { "error": "descriptive message" }
     */
    @PostMapping("/api/admin/team-forecast")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> generateTeamForecast(
            @RequestBody Map<String, String> body,
            HttpSession session) {

        // ── Validate team ─────────────────────────────────────────────────────
        String team = body.get("team");
        if (team == null || team.trim().isEmpty())
            return ResponseEntity.badRequest().body(err("team is required."));
        team = team.trim();
        if (!"Indian Team".equalsIgnoreCase(team) && !"EIndkomst Team".equalsIgnoreCase(team))
            return ResponseEntity.badRequest()
                    .body(err("team must be 'Indian Team' or 'EIndkomst Team'."));

        // ── Validate dates ────────────────────────────────────────────────────
        String startStr = body.get("startDate");
        String endStr   = body.get("endDate");
        if (startStr == null || startStr.trim().isEmpty())
            return ResponseEntity.badRequest().body(err("startDate is required."));
        if (endStr == null || endStr.trim().isEmpty())
            return ResponseEntity.badRequest().body(err("endDate is required."));

        LocalDate startDate;
        LocalDate endDate;
        try {
            startDate = LocalDate.parse(startStr.trim());
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(err("Invalid startDate format. Use YYYY-MM-DD."));
        }
        try {
            endDate = LocalDate.parse(endStr.trim());
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(err("Invalid endDate format. Use YYYY-MM-DD."));
        }
        if (startDate.isAfter(endDate))
            return ResponseEntity.badRequest()
                    .body(err("startDate must not be after endDate."));

        // ── Generate forecast ─────────────────────────────────────────────────
        String actingUser = (String) session.getAttribute("username");
        if (actingUser == null) actingUser = "admin";
        try {
            TeamForecastResult result = teamForecastService.generateForecast(team, startDate, endDate);

            // Async Slack notification — fires after report is successfully built
            final String finalTeam      = team;
            final LocalDate finalStart  = startDate;
            final LocalDate finalEnd    = endDate;
            final String finalUser      = actingUser;
            final TeamForecastResult fr = result;
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        slackNotificationService.notifyTeamForecast(
                                finalTeam, finalStart, finalEnd, fr.getRowCount(),
                                fr.getSummaryText(), finalUser, fr.getSlackTableText(),
                                fr.getRows(), fr.getMonths());
                    } catch (Exception ex) {
                        log.warn("Team forecast Slack notification failed (non-critical): {}", ex.getMessage());
                    }
                }
            }, "tf-slack-notify").start();

            auditService.log("team_forecast_generated", actingUser, null,
                    "Team=" + team + " start=" + startDate + " end=" + endDate
                    + " rows=" + result.getRowCount(), "success", "api");

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("html",          result.getHtml());
            r.put("rowCount",      result.getRowCount());
            r.put("summary",       result.getSummaryText());
            r.put("excelFilename", result.getExcelFilename());
            r.put("team",          team);
            r.put("startDate",     startDate.toString());
            r.put("endDate",       endDate.toString());
            return ResponseEntity.ok(r);

        } catch (IOException e) {
            log.error("Team forecast generation failed: {}", e.getMessage(), e);
            // Distinguish a missing-file error (404) from a general I/O failure (500)
            String msg = e.getMessage() != null ? e.getMessage() : "Failed to generate forecast.";
            if (msg.contains("not found")) {
                return ResponseEntity.status(404).body(err(msg));
            }
            return ResponseEntity.status(500).body(err("Failed to generate forecast report: " + msg));
        }
    }

    /**
     * GET /api/admin/team-forecast/download?file=team-forecast-12345.xlsx
     * Downloads a generated forecast Excel report from {dataDir}/temp/.
     */
    @GetMapping("/api/admin/team-forecast/download")
    public ResponseEntity<Resource> downloadTeamForecastExcel(
            @RequestParam("file") String filename) {
        if (filename == null || filename.trim().isEmpty() || filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            return ResponseEntity.badRequest().build();
        }
        if (!filename.startsWith("team-forecast-") || !filename.endsWith(".xlsx")) {
            return ResponseEntity.badRequest().build();
        }

        String dataDir = appState != null && appState.getDataDir() != null
                ? appState.getDataDir()
                : (appProperties.getDataDir() != null ? appProperties.getDataDir() : "data");
        Path filePath = Paths.get(dataDir, "temp", filename);

        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            log.warn("downloadTeamForecastExcel: file not found or expired: {}", filePath);
            return ResponseEntity.notFound().build();
        }

        Resource resource = new FileSystemResource(filePath.toFile());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(resource);
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
