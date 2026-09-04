package com.holidayleave.assistant.controller;

import com.holidayleave.assistant.config.AppProperties;
import com.holidayleave.assistant.excel.PlannerExcelReader;
import com.holidayleave.assistant.model.LeaveRecord;
import com.holidayleave.assistant.model.PendingVacation;
import com.holidayleave.assistant.model.PendingVacation.WizardState;
import com.holidayleave.assistant.model.VacationType;
import com.holidayleave.assistant.service.*;
import com.holidayleave.assistant.service.YearFileValidator.Result;
import com.holidayleave.assistant.service.YearFileValidator.YearStatus;
import com.holidayleave.assistant.excel.WorkingExcelWriter;
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
import java.time.format.DateTimeFormatter;

import java.util.*;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd MMM yyyy");

    @Autowired private HolidayAgent agent;
    @Autowired private VacationCreationService creationService;
    @Autowired private VacationDeletionService deletionService;
    @Autowired private ReportGenerator reportGenerator;
    @Autowired private AppState appState;
    @Autowired private PlannerExcelReader reader;
    @Autowired private WorkingExcelWriter writer;
    @Autowired private VacationTypeService typeService;
    @Autowired private AuditService auditService;
    @Autowired private SyncService syncService;
    @Autowired private SlackNotificationService slackNotificationService;
    @Autowired private YearFileValidator yearFileValidator;
    @Autowired private AppProperties props;

    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, String> body, HttpSession session) {
        String message = body.get("message");
        if (message == null || message.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(err("Empty message"));
        }
        String sessionId = (String) session.getAttribute("session_id");
        try {
            List<LeaveRecord> allRecords = loadMasterRecords();
            // Use getEmployeeNames(filePath) so employees with zero leave entries
            // are included in name resolution (wizard, add, delete flows).
            List<String> employeeNames = loadEmployeeNames();

            PendingVacation pending = appState.getPendingVacation(sessionId);
            if (pending != null && "delete".equals(pending.getWizardType())) {
                return handleDeleteWizard(pending, message, sessionId, allRecords, employeeNames, session);
            }
            if (pending != null && "add".equals(pending.getWizardType())) {
                return handleAddWizard(pending, message, sessionId, allRecords, employeeNames, session);
            }
            if (agent.isAddVacationIntent(message)) {
                String sessionRole = (String) session.getAttribute("role");
                String sessionEmp  = (String) session.getAttribute("employee_name");
                // For employee role: if the message names a different employee, reject
                // immediately with a clear authorization message before the wizard starts.
                if ("employee".equals(sessionRole) && sessionEmp != null) {
                    String mentionedEmp = agent.resolveEmployeeName(message, allRecords, employeeNames);
                    if (mentionedEmp != null && !mentionedEmp.equalsIgnoreCase(sessionEmp)) {
                        return ResponseEntity.ok(reply(
                            "Cannot add: **" + mentionedEmp + "** can only modify their own vacation details. Request aborted.",
                            "text"));
                    }
                }
                PendingVacation pv = new PendingVacation("add");
                // Pre-seed the employee name so the wizard skips the "Which employee?" step
                // and goes straight to leave-type selection.
                if ("employee".equals(sessionRole) && sessionEmp != null) {
                    pv.setEmployeeName(sessionEmp);
                    pv.setState(PendingVacation.WizardState.NEED_DATES_FORM);
                }
                VacationCreationService.WizardResult result = creationService.process(pv, message, employeeNames);
                if (result.cancelled()) appState.removePendingVacation(sessionId);
                else if (!result.confirmed()) appState.setPendingVacation(sessionId, pv);
                return ResponseEntity.ok(reply(result.reply(), result.type()));
            }
            if (agent.isDeleteVacationIntent(message)) {
                String sessionRole = (String) session.getAttribute("role");
                String sessionEmp  = (String) session.getAttribute("employee_name");
                // For employee role: if the message names a different employee, reject
                // immediately with a clear authorization message before the wizard starts.
                if ("employee".equals(sessionRole) && sessionEmp != null) {
                    String mentionedEmp = agent.resolveEmployeeName(message, allRecords, employeeNames);
                    if (mentionedEmp != null && !mentionedEmp.equalsIgnoreCase(sessionEmp)) {
                        return ResponseEntity.ok(reply(
                            "Cannot delete: **" + mentionedEmp + "** can only modify their own vacation details. Deletion aborted.",
                            "text"));
                    }
                }
                PendingVacation pv = new PendingVacation("delete");
                // Pre-seed the employee name and skip straight to the date-picker form.
                if ("employee".equals(sessionRole) && sessionEmp != null) {
                    pv.setEmployeeName(sessionEmp);
                    pv.setState(PendingVacation.WizardState.DELETE_NEED_DATES_FORM);
                }
                VacationDeletionService.WizardResult result = deletionService.process(pv, message, employeeNames, allRecords);
                if (result.cancelled()) appState.removePendingVacation(sessionId);
                else if (!result.confirmed()) appState.setPendingVacation(sessionId, pv);
                return ResponseEntity.ok(reply(result.reply(), result.type()));
            }
            if (agent.isReportIntent(message)) {
                // Use the structural name list so employees with zero leave entries are reachable.
                String empName = agent.resolveEmployeeName(message, allRecords, employeeNames);
                // Pronoun / contextual reference ("for her", "for him") — fall back to history
                if (empName == null) {
                    empName = agent.resolveEmployeeNameFromHistory(
                            appState.getConversationHistory(sessionId), allRecords, employeeNames);
                }
                if (empName != null) {
                    // Derive the year from the question or from available records; fall back to current year.
                    int year = agent.extractYearPublic(message, allRecords);
                    String path = reportGenerator.generate(allRecords, empName, year);
                    return ResponseEntity.ok(reply("Report generated.\nreport-file: " + path, "report"));
                }
                // Name still unresolved — ask rather than falling through to the LLM
                return ResponseEntity.ok(reply(
                        "Which employee should I generate the report for? Please provide their name.", "text"));
            }
            String replyText = agent.ask(message, allRecords, employeeNames, sessionId);
            // For cross-employee date-range queries, attach structured table data so the
            // frontend can render an HTML table alongside the LLM response.
            if (agent.isDateRangeQuery(message, allRecords)) {
                int year = agent.extractYearPublic(message, allRecords);
                java.time.LocalDate[] range = agent.extractDateRange(message, year);
                if (range != null) {
                    java.util.List<java.util.Map<String, Object>> tableData =
                            agent.buildTableDataForDateRange(allRecords, range[0], range[1]);
                    Map<String, Object> resp = reply(replyText, "text");
                    resp.put("tableData", tableData);
                    return ResponseEntity.ok(resp);
                }
            }
            return ResponseEntity.ok(reply(replyText, "text"));

        } catch (com.holidayleave.assistant.llm.OpenAIAdapter.LLMServiceException e) {
            return ResponseEntity.status(502).body(err(e.getMessage()));
        } catch (Exception e) {
            log.error("Chat error: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(err("Internal server error"));
        }
    }

    /**
     * Returns lightweight client-side configuration flags.
     * Called once on page load so the frontend can adapt behaviour (e.g. date-picker bounds)
     * without hard-coding server logic in JavaScript.
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> config() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("crossYearBookingOverride", props.isCrossYearBookingOverride());
        return ResponseEntity.ok(r);
    }

    @PostMapping("/clear-history")
    public ResponseEntity<Map<String, Object>> clearHistory(HttpSession session) {
        String sid = (String) session.getAttribute("session_id");
        appState.clearHistory(sid);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("message", "Conversation history cleared.");
        return ResponseEntity.ok(r);
    }

    /** Debug endpoint: returns the raw context JSON for a question without calling the LLM.
     *  Also returns the current session's conversation history so poisoning can be diagnosed.
     *  Restricted to admin role only.
     *  Usage: POST /api/debug/context  body: {"message":"How many leaves does Dayananda have from January to March?"} */
    @PostMapping("/debug/context")
    public ResponseEntity<Map<String, Object>> debugContext(@RequestBody Map<String, String> body,
                                                            HttpSession session) {
        if (!"admin".equals(session.getAttribute("role"))) {
            return ResponseEntity.status(403).body(err("Admin access required"));
        }
        try {
            String sid = (String) session.getAttribute("session_id");
            String message = body.getOrDefault("message", "");
            List<LeaveRecord> allRecords = loadMasterRecords();
            List<String> employeeNames = loadEmployeeNames();
            String contextJson = agent.buildContext(message, allRecords, employeeNames, sid);
            List<Map<String, String>> hist = appState.getConversationHistory(sid);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("question", message);
            r.put("context_json", contextJson);
            r.put("history_size", hist.size());
            r.put("history", hist);
            return ResponseEntity.ok(r);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(err("Debug error: " + e.getMessage()));
        }
    }

    private ResponseEntity<Map<String, Object>> handleAddWizard(
            PendingVacation pending, String message, String sessionId,
            List<LeaveRecord> allRecords, List<String> employeeNames,
            HttpSession session) throws IOException {
        VacationCreationService.WizardResult result = creationService.process(pending, message, employeeNames);
        if (result.confirmed()) {
            // ── Belt-and-suspenders ownership check before write ──────────────
            String sessionRole = (String) session.getAttribute("role");
            String sessionEmp  = (String) session.getAttribute("employee_name");
            if ("employee".equals(sessionRole)) {
                if (sessionEmp == null || !sessionEmp.equalsIgnoreCase(pending.getEmployeeName())) {
                    appState.removePendingVacation(sessionId);
                    return ResponseEntity.status(403).body(err(
                            "You are not permitted to add vacations for other employees."));
                }
            }

            String actingUser = (String) session.getAttribute("username");
            if (actingUser == null) actingUser = "admin";

            LocalDate startDate = pending.getStartDate();
            LocalDate endDate   = pending.getEndDate();

            // ── Year-file validation ──────────────────────────────────────────
            Result fileCheck = yearFileValidator.validate(
                    startDate, endDate, appState.getDataDir(), appState.getWorkingDir());

            if (fileCheck.isTooManyYears()) {
                appState.removePendingVacation(sessionId);
                return ResponseEntity.ok(reply(
                    "Vacation requests spanning more than two calendar years are not supported. " +
                    "Please submit separate requests per year.", "text"));
            }

            if (!fileCheck.isValid()) {
                List<YearStatus> missing = fileCheck.getMissingMasterStatuses();
                appState.removePendingVacation(sessionId);
                StringBuilder msg = new StringBuilder();
                for (YearStatus ms : missing) {
                    if (msg.length() > 0) msg.append("\n");
                    msg.append("**Master vacation planner sheet is missing for ")
                       .append(ms.getYear())
                       .append(".** I have sent a notification to the Admin user(s) to provision the missing data.");
                    slackNotificationService.notifyMissingYearData(
                            ms, "add", pending.getEmployeeName(), startDate, endDate, actingUser);
                }
                return ResponseEntity.ok(reply(msg.toString(), "text"));
            }

            // ── Multi-year write (atomic: roll back year-N if year-N+1 fails) ─
            VacationType vtype = typeService.findByCode(pending.getLeaveCode())
                    .orElseThrow(() -> new IllegalArgumentException("Leave type not found"));

            List<Integer> years = fileCheck.getYears();
            int totalCells = 0;
            // Track which years were successfully written so we can roll back on failure.
            List<Integer> writtenYears = new ArrayList<>();

            try {
                for (int year : years) {
                    LocalDate segStart = startDate.getYear() == year ? startDate : LocalDate.of(year, 1, 1);
                    LocalDate segEnd   = endDate.getYear()   == year ? endDate   : LocalDate.of(year, 12, 31);

                    LeaveRecord segRecord = new LeaveRecord(
                            pending.getEmployeeName(), segStart, segEnd,
                            VacationCreationService.countWeekdays(segStart, segEnd),
                            pending.getLeaveType(), pending.getReason());

                    String workingPath = getWorkingPath(year);
                    ensureWorkingCopy(workingPath, year);
                    int cells = writer.addVacation(workingPath, segRecord, vtype);
                    reader.evict(getMasterPath(year));
                    totalCells += cells;
                    writtenYears.add(year);
                }

            } catch (WorkingExcelWriter.ExcelWriteConflictException e) {
                // Roll back any successfully-written segments to avoid partial state.
                rollbackAddedSegments(pending.getEmployeeName(), startDate, endDate, writtenYears);
                auditService.log("vacation_conflict", actingUser, pending.getEmployeeName(),
                        e.getMessage(), "error", "chat");
                appState.removePendingVacation(sessionId);
                return ResponseEntity.ok(reply(
                    "Could not add vacation: **" + pending.getEmployeeName() + "** already has leave scheduled on **"
                    + e.getConflictDate().format(FMT) + "** (code: " + e.getExistingCode() + "). "
                    + "Please choose a different date range.", "text"));
            } catch (IOException e) {
                // Roll back any successfully-written segments to avoid partial state.
                rollbackAddedSegments(pending.getEmployeeName(), startDate, endDate, writtenYears);
                throw e;
            }

            // ── All segments written — audit, sync, notify ────────────────────
            auditService.log("vacation_added", actingUser, pending.getEmployeeName(),
                "Added " + totalCells + "d [" + pending.getLeaveType() + "] via chat", "success", "chat");
            syncService.triggerSync();
            // Build a full-range LeaveRecord for the Slack notification (for display only).
            LeaveRecord fullRecord = new LeaveRecord(
                    pending.getEmployeeName(), startDate, endDate,
                    totalCells, pending.getLeaveType(), pending.getReason());
            slackNotificationService.notifyPcVacationAdded(fullRecord, pending.getLeaveCode(), actingUser);
            appState.removePendingVacation(sessionId);

            String rangeDesc = years.size() > 1
                    ? startDate.format(FMT) + " to " + endDate.format(FMT)
                      + " (split across " + years.size() + " calendar years)"
                    : startDate.format(FMT) + " to " + endDate.format(FMT);
            return ResponseEntity.ok(reply(
                "Vacation added for **" + pending.getEmployeeName() + "**: " +
                pending.getLeaveType() + " " + rangeDesc + " (" + totalCells + " days).", "text"));
        }
        if (result.cancelled()) appState.removePendingVacation(sessionId);
        return ResponseEntity.ok(reply(result.reply(), result.type()));
    }

    /**
     * Rolls back add operations for any year segments that were already written
     * when a later segment fails.  Best-effort: logs but does not throw on rollback errors.
     */
    private void rollbackAddedSegments(String employeeName,
                                       LocalDate fullStart, LocalDate fullEnd,
                                       List<Integer> writtenYears) {
        for (int year : writtenYears) {
            try {
                LocalDate segStart = fullStart.getYear() == year ? fullStart : LocalDate.of(year, 1, 1);
                LocalDate segEnd   = fullEnd.getYear()   == year ? fullEnd   : LocalDate.of(year, 12, 31);
                String workingPath = getWorkingPath(year);
                if (new File(workingPath).exists()) {
                    writer.deleteVacation(workingPath, employeeName, segStart, segEnd);
                    reader.evict(getMasterPath(year));
                    log.warn("Rolled back add for {} year={} [{} to {}]", employeeName, year, segStart, segEnd);
                }
            } catch (Exception ex) {
                log.error("Rollback failed for employee={} year={}: {}", employeeName, year, ex.getMessage());
            }
        }
    }

    private ResponseEntity<Map<String, Object>> handleDeleteWizard(
            PendingVacation pending, String message, String sessionId,
            List<LeaveRecord> allRecords, List<String> employeeNames,
            HttpSession session) throws IOException {
        // Use all available master records (not just activated files) so the
        // coverage check in the deletion wizard passes for cross-year date ranges
        // where the next year's master file may not yet be activated in the UI.
        List<LeaveRecord> fullRecords = loadAllAvailableMasterRecords();
        VacationDeletionService.WizardResult result = deletionService.process(pending, message, employeeNames, fullRecords);
        if (result.confirmed()) {
            // ── Belt-and-suspenders ownership check before write ──────────────
            String sessionRole = (String) session.getAttribute("role");
            String sessionEmp  = (String) session.getAttribute("employee_name");
            if ("employee".equals(sessionRole)) {
                if (sessionEmp == null || !sessionEmp.equalsIgnoreCase(pending.getEmployeeName())) {
                    appState.removePendingVacation(sessionId);
                    return ResponseEntity.status(403).body(err(
                            "You are not permitted to delete another employee's vacation."));
                }
            }

            String actingUser = (String) session.getAttribute("username");
            if (actingUser == null) actingUser = "admin";

            LocalDate startDate = pending.getStartDate();
            LocalDate endDate   = pending.getEndDate();

            // ── Year-file validation ──────────────────────────────────────────
            Result fileCheck = yearFileValidator.validate(
                    startDate, endDate, appState.getDataDir(), appState.getWorkingDir());

            if (fileCheck.isTooManyYears()) {
                appState.removePendingVacation(sessionId);
                return ResponseEntity.ok(reply(
                    "Vacation requests spanning more than two calendar years are not supported. " +
                    "Please submit separate requests per year.", "text"));
            }

            if (!fileCheck.isValid()) {
                List<YearStatus> missing = fileCheck.getMissingMasterStatuses();
                appState.removePendingVacation(sessionId);
                StringBuilder msg = new StringBuilder();
                for (YearStatus ms : missing) {
                    if (msg.length() > 0) msg.append("\n");
                    msg.append("**Master vacation planner sheet is missing for ")
                       .append(ms.getYear())
                       .append(".** I have sent a notification to the Admin user(s) to provision the missing data.");
                    slackNotificationService.notifyMissingYearData(
                            ms, "delete", pending.getEmployeeName(), startDate, endDate, actingUser);
                }
                return ResponseEntity.ok(reply(msg.toString(), "text"));
            }

            // ── Multi-year delete ─────────────────────────────────────────────
            List<Integer> years = fileCheck.getYears();
            int totalCleared = 0;
            String deletedLeaveType = resolveLeaveTypeForDelete(fullRecords, pending);

            for (int year : years) {
                LocalDate segStart = startDate.getYear() == year ? startDate : LocalDate.of(year, 1, 1);
                LocalDate segEnd   = endDate.getYear()   == year ? endDate   : LocalDate.of(year, 12, 31);
                String workingPath = getWorkingPath(year);
                ensureWorkingCopy(workingPath, year);
                int cleared = writer.deleteVacation(workingPath, pending.getEmployeeName(), segStart, segEnd);
                reader.evict(getMasterPath(year));
                totalCleared += cleared;
            }

            auditService.log("vacation_deleted", actingUser, pending.getEmployeeName(),
                "Deleted vacation via chat", "success", "chat");
            syncService.triggerSync();
            slackNotificationService.notifyVacationDeleted(pending, deletedLeaveType, actingUser);
            appState.removePendingVacation(sessionId);
            return ResponseEntity.ok(reply(
                "Vacation deleted for **" + pending.getEmployeeName() + "** (" + totalCleared + " cells cleared).", "text"));
        }
        if (result.cancelled()) appState.removePendingVacation(sessionId);
        return ResponseEntity.ok(reply(result.reply(), result.type()));
    }

    private List<LeaveRecord> loadMasterRecords() throws IOException {
        List<LeaveRecord> all = new ArrayList<>();
        for (String path : appState.getLoadedFiles()) {
            if (new File(path).exists()) all.addAll(reader.load(path));
        }
        return all;
    }

    /**
     * Returns the full structural employee name list from all loaded files,
     * including employees who have no leave entries (uses the file-path overload
     * of {@link PlannerExcelReader#getEmployeeNames(String)}).
     */
    private List<String> loadEmployeeNames() throws IOException {
        List<String> names = new ArrayList<>();
        for (String path : appState.getLoadedFiles()) {
            if (new File(path).exists()) {
                for (String name : reader.getEmployeeNames(path)) {
                    if (!names.contains(name)) names.add(name);
                }
            }
        }
        return names;
    }

    /**
     * Loads leave records from ALL master Excel files that currently exist in the
     * data directory, regardless of which files are activated in the UI.
     *
     * <p>Used exclusively for the delete-wizard coverage check so that a cross-year
     * delete (e.g. 2026-12-10 → 2027-02-14) can validate that the requested days
     * are actually booked in both the 2026 and 2027 master files, even if the 2027
     * file has not yet been activated via the file management page.
     */
    private List<LeaveRecord> loadAllAvailableMasterRecords() throws IOException {
        List<LeaveRecord> all = new ArrayList<>();
        List<String> discovered = appState.discoverExcelPaths();
        for (String path : discovered) {
            if (new File(path).exists()) {
                try {
                    all.addAll(reader.load(path));
                } catch (Exception e) {
                    log.warn("Could not load master records from {}: {}", path, e.getMessage());
                }
            }
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

    private String resolveLeaveTypeForDelete(List<LeaveRecord> records, PendingVacation pending) {
        for (LeaveRecord r : records) {
            if (!r.employeeName().equalsIgnoreCase(pending.getEmployeeName())) continue;
            // Match any record whose date range overlaps the deleted range
            if (!r.endDate().isBefore(pending.getStartDate())
                    && !r.startDate().isAfter(pending.getEndDate())) {
                return r.leaveType();
            }
        }
        return "Unknown";
    }

    private Map<String, Object> reply(String text, String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reply", text);
        m.put("type", type);
        return m;
    }

    private Map<String, Object> err(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg);
        return m;
    }
}
