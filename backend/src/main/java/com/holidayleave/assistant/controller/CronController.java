package com.holidayleave.assistant.controller;

import com.holidayleave.assistant.scheduler.CronExpressionStore;
import com.holidayleave.assistant.scheduler.CronExpressionStore.CronEntry;
import com.holidayleave.assistant.scheduler.CronDateRangeResolver;
import com.holidayleave.assistant.scheduler.CronSchedulerService;
import com.holidayleave.assistant.scheduler.CronSchedulerService.StatusInfo;
import com.holidayleave.assistant.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.*;

/**
 * REST API for the Schedule Cron admin page.
 *
 * <p>All routes are under {@code /api/admin/cron/**} and are automatically
 * protected by the existing {@link com.holidayleave.assistant.service.AuthInterceptor}
 * (admin role required).
 *
 * <pre>
 * GET    /api/admin/cron/expressions          — list all expressions
 * POST   /api/admin/cron/expressions          — add one or more (comma-separated)
 * DELETE /api/admin/cron/expressions          — delete one by id
 * POST   /api/admin/cron/start                — start the scheduler
 * POST   /api/admin/cron/stop                 — stop the scheduler
 * GET    /api/admin/cron/status               — current scheduler status
 * </pre>
 */
@Controller
public class CronController {

    private static final Logger log = LoggerFactory.getLogger(CronController.class);

    @Autowired private CronExpressionStore  store;
    @Autowired private CronSchedulerService schedulerService;
    @Autowired private AuditService         auditService;

    // ── List expressions ──────────────────────────────────────────────────────

    /** GET /api/admin/cron/expressions */
    @GetMapping("/api/admin/cron/expressions")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> listExpressions() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("expressions", store.readAll());
        return ResponseEntity.ok(r);
    }

    // ── Add expressions ───────────────────────────────────────────────────────

    /**
     * POST /api/admin/cron/expressions
     * Body: { "expression": "30 8 * * 5, 30 8 28 * *", "team": "Indian Team" }
     *
     * Tokenises the expression on commas between complete 5-field tokens,
     * validates each, persists all, and live-registers with the scheduler
     * if it is currently running.
     */
    @PostMapping("/api/admin/cron/expressions")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> addExpressions(
            @RequestBody Map<String, String> body,
            HttpSession session) {

        String rawExpr = body.get("expression");
        String team    = body.get("team");

        if (rawExpr == null || rawExpr.trim().isEmpty())
            return ResponseEntity.badRequest().body(err("expression is required."));
        if (team == null || team.trim().isEmpty())
            return ResponseEntity.badRequest().body(err("team is required."));
        team = team.trim();
        if (!"Indian Team".equalsIgnoreCase(team) && !"EIndkomst Team".equalsIgnoreCase(team))
            return ResponseEntity.badRequest()
                    .body(err("team must be 'Indian Team' or 'EIndkomst Team'."));

        // Tokenise on commas that sit between complete 5-field expressions
        List<String> tokens = tokenise(rawExpr);
        if (tokens.isEmpty())
            return ResponseEntity.badRequest().body(err("No valid cron expression found in input."));

        // Validate each token
        List<String> errors  = new ArrayList<>();
        List<String> valid   = new ArrayList<>();
        for (String token : tokens) {
            String validationError = validateFiveField(token);
            if (validationError != null) {
                errors.add("'" + token + "': " + validationError);
            } else {
                valid.add(token);
            }
        }

        if (valid.isEmpty())
            return ResponseEntity.badRequest()
                    .body(err("All expressions are invalid: " + String.join("; ", errors)));

        // Persist and live-register
        String actingUser = actingUser(session);
        List<CronEntry> updatedList = null;
        int added = 0;
        for (String expr : valid) {
            CronEntry entry = new CronEntry(expr, team);
            try {
                updatedList = store.add(entry);
                schedulerService.onEntryAdded(entry);
                auditService.log("cron_expression_added", actingUser, null,
                        "Added cron: " + expr + " team=" + team, "success", "api");
                added++;
            } catch (IOException e) {
                errors.add("Failed to save '" + expr + "': " + e.getMessage());
                log.error("CronController: failed to persist expression '{}': {}", expr, e.getMessage());
            }
        }

        if (added == 0)
            return ResponseEntity.status(500)
                    .body(err("Failed to save any expression: " + String.join("; ", errors)));

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("added",       added);
        r.put("errors",      errors);
        r.put("expressions", updatedList != null ? updatedList : store.readAll());
        r.put("message",     added + " expression(s) added successfully.");
        return ResponseEntity.ok(r);
    }

    // ── Delete expression ─────────────────────────────────────────────────────

    /**
     * DELETE /api/admin/cron/expressions
     * Body: { "id": "a1b2c3d4" }
     */
    @DeleteMapping("/api/admin/cron/expressions")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteExpression(
            @RequestBody Map<String, String> body,
            HttpSession session) {

        String id = body.get("id");
        if (id == null || id.trim().isEmpty())
            return ResponseEntity.badRequest().body(err("id is required."));
        id = id.trim();

        try {
            List<CronEntry> updated = store.delete(id);
            schedulerService.onEntryDeleted(id);

            String actingUser = actingUser(session);
            auditService.log("cron_expression_deleted", actingUser, null,
                    "Deleted cron id=" + id, "success", "api");

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("expressions", updated);
            r.put("message",     "Expression deleted.");
            return ResponseEntity.ok(r);
        } catch (IOException e) {
            log.error("CronController: delete failed for id={}: {}", id, e.getMessage());
            return ResponseEntity.status(500).body(err("Failed to delete expression: " + e.getMessage()));
        }
    }

    // ── Start ─────────────────────────────────────────────────────────────────

    /** POST /api/admin/cron/start */
    @PostMapping("/api/admin/cron/start")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> startScheduler(HttpSession session) {
        try {
            int count = schedulerService.start();
            String actingUser = actingUser(session);
            auditService.log("cron_scheduler_started", actingUser, null,
                    "Scheduler started via API, " + count + " expression(s)", "success", "api");
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("status",    "running");
            r.put("scheduled", count);
            r.put("message",   "Scheduler started with " + count + " expression(s).");
            return ResponseEntity.ok(r);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(err(e.getMessage()));
        }
    }

    // ── Stop ──────────────────────────────────────────────────────────────────

    /** POST /api/admin/cron/stop */
    @PostMapping("/api/admin/cron/stop")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> stopScheduler(HttpSession session) {
        try {
            schedulerService.stop();
            String actingUser = actingUser(session);
            auditService.log("cron_scheduler_stopped", actingUser, null,
                    "Scheduler stopped via API", "success", "api");
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("status",  "stopped");
            r.put("message", "Scheduler stopped successfully.");
            return ResponseEntity.ok(r);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(err(e.getMessage()));
        }
    }

    // ── Status ────────────────────────────────────────────────────────────────

    /** GET /api/admin/cron/status */
    @GetMapping("/api/admin/cron/status")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getStatus() {
        StatusInfo info = schedulerService.getStatus();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status",    info.status);
        r.put("scheduled", info.scheduled);
        r.put("lastFired", info.lastFired);
        return ResponseEntity.ok(r);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Tokenises a raw input string into individual 5-field cron expressions.
     *
     * <p>Strategy: split on commas, then greedily assemble groups of 5
     * whitespace-delimited tokens.  A comma that falls <em>inside</em> a
     * field (e.g. {@code "1,3,5"}) will produce a fragment with fewer than
     * 5 tokens; such fragments are merged with the next comma-fragment until
     * a complete 5-token group is assembled.
     */
    static List<String> tokenise(String raw) {
        // Split on commas to get candidate fragments
        String[] parts = raw.split(",");
        List<String> results = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        for (String part : parts) {
            if (buffer.length() > 0) {
                buffer.append(",");
            }
            buffer.append(part);
            // Check if the accumulated buffer is a complete 5-field expression
            String candidate = buffer.toString().trim();
            String[] fields = candidate.split("\\s+");
            if (fields.length == 5) {
                results.add(candidate);
                buffer = new StringBuilder();
            }
            // If fields.length > 5, this is malformed — skip and reset
            else if (fields.length > 5) {
                buffer = new StringBuilder();
            }
            // else fields.length < 5 → keep accumulating
        }
        return results;
    }

    /**
     * Validates a 5-field Unix cron expression using Spring's own
     * {@link org.springframework.scheduling.support.CronExpression}.
     * Returns {@code null} if valid, or an error message if invalid.
     */
    private static String validateFiveField(String expr) {
        if (expr == null || expr.trim().isEmpty())
            return "Expression is empty.";
        String[] fields = expr.trim().split("\\s+");
        if (fields.length != 5)
            return "Expected 5 fields, got " + fields.length + ".";
        // Prepend seconds "0" and validate using Spring's CronExpression parser
        String sixField = "0 " + expr.trim();
        try {
            org.springframework.scheduling.support.CronExpression.parse(sixField);
            return null; // valid
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    private static String actingUser(HttpSession session) {
        String u = (String) session.getAttribute("username");
        return (u != null) ? u : "admin";
    }

    private static Map<String, Object> err(String message) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("error", message);
        return r;
    }
}
