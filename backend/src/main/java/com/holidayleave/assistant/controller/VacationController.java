package com.holidayleave.assistant.controller;

import com.holidayleave.assistant.excel.PlannerExcelReader;
import com.holidayleave.assistant.excel.WorkingExcelWriter;
import com.holidayleave.assistant.model.LeaveRecord;
import com.holidayleave.assistant.model.VacationType;
import com.holidayleave.assistant.service.*;
import com.holidayleave.assistant.service.RestrictedVacationTypeService;
import javax.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

@RestController
@RequestMapping("/api")
public class VacationController {

    private static final Logger log = LoggerFactory.getLogger(VacationController.class);

    @Autowired private AppState appState;
    @Autowired private PlannerExcelReader reader;
    @Autowired private WorkingExcelWriter writer;
    @Autowired private VacationTypeService typeService;
    @Autowired private RestrictedVacationTypeService restrictedTypeService;
    @Autowired private AuditService auditService;
    @Autowired private SyncService syncService;

    @PostMapping("/vacations")
    public ResponseEntity<Map<String, Object>> addVacation(@RequestBody Map<String, Object> body,
                                                           HttpSession session) {
        String empName   = (String) body.get("employee_name");
        String leaveType = (String) body.get("leave_type");
        String startStr  = (String) body.get("start_date");
        String endStr    = (String) body.get("end_date");
        String reason    = (String) body.get("reason");

        if (empName == null || empName.trim().isEmpty())
            return ResponseEntity.badRequest().body(err("employee_name is required."));

        // ── Employee ownership guard ───────────────────────────────────────────
        String actingRole = (String) session.getAttribute("role");
        String actingEmp  = (String) session.getAttribute("employee_name");
        if ("employee".equals(actingRole)) {
            if (actingEmp == null || !actingEmp.equalsIgnoreCase(empName)) {
                return ResponseEntity.status(403)
                        .body(err("You are not permitted to add vacations for other employees."));
            }
        }

        String actingUser = (String) session.getAttribute("username");
        if (actingUser == null) actingUser = "admin";

        try {
            List<LeaveRecord> allRecords = loadMasterRecords();
            List<String> empNames = reader.getEmployeeNames(allRecords);

            String resolved = null;
            for (String n : empNames) { if (n.equalsIgnoreCase(empName)) { resolved = n; break; } }
            if (resolved == null)
                return ResponseEntity.badRequest().body(err("Employee '" + empName + "' not found. Available: " + String.join(", ", empNames)));

            if (leaveType == null || leaveType.trim().isEmpty())
                return ResponseEntity.badRequest().body(err("leave_type is required."));
            VacationType vtype = typeService.findByLabel(leaveType).orElse(null);
            if (vtype == null) {
                List<String> valid = new ArrayList<>();
                for (VacationType t : typeService.findAll()) valid.add(t.label());
                return ResponseEntity.badRequest().body(err("Invalid leave_type '" + leaveType + "'. Valid types: " + String.join(", ", valid)));
            }
            // Server-side restricted-type enforcement
            if (restrictedTypeService.isRestricted(vtype.code())) {
                return ResponseEntity.status(403).body(err(
                    "The vacation type " + vtype.label() + " is currently disabled by the administrator " +
                    "and cannot be requested at this time."));
            }

            LocalDate start, end;
            try { start = LocalDate.parse(startStr); end = LocalDate.parse(endStr); }
            catch (Exception e) { return ResponseEntity.badRequest().body(err("Dates must be in YYYY-MM-DD format.")); }

            double days = VacationCreationService.countWeekdays(start, end);
            LeaveRecord record = new LeaveRecord(resolved, start, end, days, vtype.label(), reason);

            String workingPath = getWorkingPath(record.year());
            ensureWorkingCopy(workingPath, record.year());
            writer.addVacation(workingPath, record, vtype);
            // Eager cache eviction: invalidate master so the next read re-parses from disk.
            // Evict before triggerSync() so the sync-daemon's confirmed eviction (Step 4)
            // is a belt-and-suspenders fallback, not the primary invalidation.
            reader.evict(getMasterPath(record.year()));

            auditService.log("vacation_added", actingUser, resolved,
                "Added " + days + "d via API", "success", "api");
            syncService.triggerSync();

            Map<String, Object> recMap = new LinkedHashMap<>();
            recMap.put("employee_name", resolved); recMap.put("leave_type", vtype.label());
            recMap.put("leave_code", vtype.code()); recMap.put("start_date", start.toString());
            recMap.put("end_date", end.toString()); recMap.put("days", days);
            recMap.put("reason", reason != null ? reason : "");

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("message", "Vacation added successfully."); resp.put("days", days); resp.put("record", recMap);
            return ResponseEntity.status(201).body(resp);

        } catch (WorkingExcelWriter.ExcelWriteConflictException e) {
            auditService.log("vacation_conflict", actingUser, empName, e.getMessage(), "error", "api");
            return ResponseEntity.status(409).body(err(e.getMessage()));
        } catch (IOException e) {
            log.error("Add vacation error: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(err("Failed to write vacation: " + e.getMessage()));
        }
    }

    @DeleteMapping("/vacations")
    public ResponseEntity<Map<String, Object>> deleteVacation(@RequestBody Map<String, Object> body,
                                                              HttpSession session) {
        String empName  = (String) body.get("employee_name");
        String startStr = (String) body.get("start_date");
        String endStr   = (String) body.get("end_date");
        String reason   = (String) body.get("reason");

        if (empName == null || empName.trim().isEmpty())
            return ResponseEntity.badRequest().body(err("employee_name is required."));

        // ── Employee ownership guard ───────────────────────────────────────────
        String actingRole = (String) session.getAttribute("role");
        String actingEmp  = (String) session.getAttribute("employee_name");
        if ("employee".equals(actingRole)) {
            if (actingEmp == null || !actingEmp.equalsIgnoreCase(empName)) {
                return ResponseEntity.status(403)
                        .body(err("You are not permitted to delete another employee's vacation."));
            }
        }

        try {
            List<LeaveRecord> allRecords = loadMasterRecords();
            List<String> empNames = reader.getEmployeeNames(allRecords);

            String resolved = null;
            for (String n : empNames) { if (n.equalsIgnoreCase(empName)) { resolved = n; break; } }
            if (resolved == null)
                return ResponseEntity.status(404).body(err("Employee '" + empName + "' not found."));

            LocalDate start, end;
            try { start = LocalDate.parse(startStr); end = LocalDate.parse(endStr); }
            catch (Exception e) { return ResponseEntity.badRequest().body(err("Dates must be in YYYY-MM-DD format.")); }

            final String res = resolved;
            final LocalDate s = start; final LocalDate e2 = end;
            boolean found = false;
            for (LeaveRecord r : allRecords) {
                if (r.employeeName().equalsIgnoreCase(res) && !r.startDate().isAfter(e2) && !r.endDate().isBefore(s)) {
                    found = true; break;
                }
            }
            if (!found)
                return ResponseEntity.status(404).body(err("No vacation record found for '" + resolved + "' from " + start + " to " + end));

            String workingPath = getWorkingPath(start.getYear());
            ensureWorkingCopy(workingPath, start.getYear());
            int cleared = writer.deleteVacation(workingPath, resolved, start, end);
            // Eager cache eviction after confirmed delete.
            reader.evict(getMasterPath(start.getYear()));

            String actingUser = (String) session.getAttribute("username");
            if (actingUser == null) actingUser = "admin";
            auditService.log("vacation_deleted", actingUser, resolved, "Deleted vacation via API", "success", "api");
            syncService.triggerSync();

            Map<String, Object> recMap = new LinkedHashMap<>();
            recMap.put("employee_name", resolved); recMap.put("start_date", start.toString());
            recMap.put("end_date", end.toString()); recMap.put("reason", reason != null ? reason : "");

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("message", "Vacation deleted successfully."); resp.put("cells_cleared", cleared); resp.put("record", recMap);
            return ResponseEntity.ok(resp);

        } catch (WorkingExcelWriter.ExcelDeleteNotFoundException e) {
            return ResponseEntity.status(404).body(err(e.getMessage()));
        } catch (IOException e) {
            log.error("Delete vacation error: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(err("Failed to delete vacation: " + e.getMessage()));
        }
    }

    @GetMapping("/vacation-types")
    public ResponseEntity<Map<String, Object>> getTypes() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("vacation_types", typeService.findAll());
        return ResponseEntity.ok(r);
    }

    @PostMapping("/vacation-types")
    public ResponseEntity<Map<String, Object>> addType(@RequestBody Map<String, String> body,
                                                       HttpSession session) {
        String code  = body.get("code");
        String label = body.get("label");
        String color = body.containsKey("color") ? body.get("color") : "FFD3D3D3";
        if (code == null || code.trim().isEmpty() || label == null || label.trim().isEmpty())
            return ResponseEntity.badRequest().body(err("code and label are required."));
        String actingUser = (String) session.getAttribute("username");
        if (actingUser == null) actingUser = "admin";
        try {
            VacationType created = typeService.add(new VacationType(code.toUpperCase(), label, color));
            auditService.log("type_added", actingUser, null, "Added vacation type " + code, "success", "api");
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("message", "Vacation type '" + code + "' added."); r.put("vacation_type", created);
            return ResponseEntity.status(201).body(r);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(err(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(err(e.getMessage()));
        }
    }

    @PutMapping("/vacation-types/{code}")
    public ResponseEntity<Map<String, Object>> updateType(@PathVariable String code,
                                                          @RequestBody Map<String, String> body,
                                                          HttpSession session) {
        String actingUser = (String) session.getAttribute("username");
        if (actingUser == null) actingUser = "admin";
        try {
            VacationType updated = typeService.update(code, body.get("label"), body.get("color"));
            auditService.log("type_updated", actingUser, null, "Updated vacation type " + code, "success", "api");
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("message", "Vacation type '" + code + "' updated."); r.put("vacation_type", updated);
            return ResponseEntity.ok(r);
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(404).body(err(e.getMessage()));
        }
    }

    private List<LeaveRecord> loadMasterRecords() throws IOException {
        List<LeaveRecord> all = new ArrayList<>();
        for (String p : appState.getLoadedFiles()) {
            if (new File(p).exists()) all.addAll(reader.load(p));
        }
        return all;
    }

    private String getWorkingPath(int year) {
        return appState.getWorkingDir() + "/eIndkomst vacation " + year + ".xlsx";
    }

    private String getMasterPath(int year) {
        return appState.getDataDir() + "/eIndkomst vacation " + year + ".xlsx";
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
}
