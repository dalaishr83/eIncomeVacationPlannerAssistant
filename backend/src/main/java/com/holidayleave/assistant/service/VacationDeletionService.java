package com.holidayleave.assistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.holidayleave.assistant.config.AppProperties;
import com.holidayleave.assistant.model.LeaveRecord;
import com.holidayleave.assistant.model.PendingVacation;
import com.holidayleave.assistant.model.PendingVacation.WizardState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

@Service
public class VacationDeletionService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd MMM yyyy");

    /** Prefix used by the frontend to submit the inline delete-vacation form. */
    static final String FORM_PREFIX = "__VACATION_FORM__:";

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private AppProperties props;

    public WizardResult process(PendingVacation pending, String message,
                                List<String> allEmployeeNames, List<LeaveRecord> allRecords) {
        WizardState state = pending.getState();
        if (state == WizardState.DELETE_IDLE)             return handleIdle(pending, message, allEmployeeNames);
        if (state == WizardState.DELETE_NEED_EMP)         return handleNeedEmp(pending, message, allEmployeeNames);
        if (state == WizardState.DELETE_NEED_START)       return handleNeedStart(pending, message);
        if (state == WizardState.DELETE_NEED_DATES_FORM)  return handleNeedDatesForm(pending, message, allRecords);
        if (state == WizardState.DELETE_NEED_END)         return handleNeedEnd(pending, message, allRecords);
        if (state == WizardState.DELETE_CONFIRM)          return handleConfirm(pending, message);
        return new WizardResult("Unexpected state. Type 'cancel' to abort.", "vacation_prompt", false, false);
    }

    private WizardResult handleIdle(PendingVacation p, String msg, List<String> names) {
        String emp = resolveEmployee(msg, names);
        if (emp != null) {
            p.setEmployeeName(emp);
            p.setState(WizardState.DELETE_NEED_DATES_FORM);
            return buildFormResult(emp);
        }
        p.setState(WizardState.DELETE_NEED_EMP);
        return new WizardResult("Which employee's vacation should I delete?\nKnown employees: " + String.join(", ", names),
            "vacation_prompt", false, false);
    }

    private WizardResult handleNeedEmp(PendingVacation p, String msg, List<String> names) {
        if (isCancelled(msg)) return cancel(p);
        String emp = resolveEmployee(msg, names);
        if (emp == null) return new WizardResult("Employee not found. Known employees: " + String.join(", ", names),
            "vacation_prompt", false, false);
        p.setEmployeeName(emp);
        p.setState(WizardState.DELETE_NEED_DATES_FORM);
        return buildFormResult(emp);
    }

    /**
     * Handles the delete date-range form submission (__VACATION_FORM__:DELETE|YYYY-MM-DD|YYYY-MM-DD).
     * Any non-form, non-cancel message re-renders the form so the user always sees
     * the HTML widget rather than a confusing text prompt.
     */
    private WizardResult handleNeedDatesForm(PendingVacation p, String msg, List<LeaveRecord> allRecords) {
        if (isCancelled(msg)) return cancel(p);

        if (isFormSubmission(msg)) {
            return handleFormSubmission(p, msg, allRecords);
        }

        // Re-render the form for any other input (stale session, accidental text, etc.)
        return buildFormResult(p.getEmployeeName());
    }

    private WizardResult handleNeedStart(PendingVacation p, String msg) {
        if (isCancelled(msg)) return cancel(p);
        LocalDate date = parseDate(msg);
        if (date == null) return new WizardResult("Please enter a valid start date in YYYY-MM-DD format.",
            "vacation_prompt", false, false);
        if (isPastDate(date)) {
            return new WizardResult(
                "Adding or deleting vacation for a past date is not allowed.",
                "vacation_prompt", false, false);
        }
        p.setStartDate(date);
        p.setState(WizardState.DELETE_NEED_END);
        return new WizardResult("Start date: **" + date.format(FMT) + "**. What is the end date? (YYYY-MM-DD, or same date for single day)",
            "vacation_prompt", false, false);
    }

    private WizardResult handleNeedEnd(PendingVacation p, String msg, List<LeaveRecord> allRecords) {
        if (isCancelled(msg)) return cancel(p);
        LocalDate date = parseDate(msg);
        if (date == null) return new WizardResult("Please enter a valid end date in YYYY-MM-DD format.",
            "vacation_prompt", false, false);
        if (date.isBefore(p.getStartDate())) return new WizardResult(
            "End date must be on or after start date (" + p.getStartDate().format(FMT) + ").", "vacation_prompt", false, false);
        p.setEndDate(date);
        return buildDeleteConfirm(p, date, allRecords);
    }

    /**
     * Parses a combined form submission: __VACATION_FORM__:DELETE|YYYY-MM-DD|YYYY-MM-DD
     * Applies past-date, ordering, working-day, and coverage validations, then advances to CONFIRM.
     */
    private WizardResult handleFormSubmission(PendingVacation p, String msg, List<LeaveRecord> allRecords) {
        String payload = msg.substring(FORM_PREFIX.length()).trim();
        // Format: DELETE|YYYY-MM-DD|YYYY-MM-DD
        String[] parts = payload.split("\\|", 3);
        if (parts.length != 3) {
            return buildFormResult(p.getEmployeeName());
        }

        // parts[0] is the wizard-type token ("DELETE"); parts[1] and parts[2] are dates
        LocalDate startDate = parseDate(parts[1].trim());
        LocalDate endDate   = parseDate(parts[2].trim());

        if (startDate == null || endDate == null) {
            return new WizardResult("Please select valid start and end dates.", "vacation_prompt", false, false);
        }

        // Past-date validation
        if (isPastDate(startDate)) {
            return new WizardResult(
                "Adding or deleting vacation for a past date is not allowed.",
                "vacation_prompt", false, false);
        }

        // Cross-year window validation: only allow end dates in Q1 of next year during Q4 of current year.
        if (endDate.getYear() > startDate.getYear()) {
            VacationCreationService.WizardResult crossYearCheck =
                    VacationCreationService.validateCrossYearWindow(startDate, endDate,
                            props.isCrossYearBookingOverride());
            if (crossYearCheck != null) {
                // Re-wrap message into this service's WizardResult type.
                return new WizardResult(crossYearCheck.reply(), crossYearCheck.type(), false, false);
            }
        }

        // Date ordering
        if (endDate.isBefore(startDate)) {
            return new WizardResult(
                "Start date cannot be greater than end date.",
                "vacation_prompt", false, false);
        }

        p.setStartDate(startDate);
        p.setEndDate(endDate);
        return buildDeleteConfirm(p, endDate, allRecords);
    }

    /**
     * Shared confirmation-builder used by both the form path and the legacy text path.
     * Validates working-day count and vacation coverage before advancing to DELETE_CONFIRM.
     */
    private WizardResult buildDeleteConfirm(PendingVacation p, LocalDate endDate, List<LeaveRecord> allRecords) {
        Set<LocalDate> coveredDays = new HashSet<>();
        for (LeaveRecord r : allRecords) {
            if (!r.employeeName().equalsIgnoreCase(p.getEmployeeName())) continue;
            for (LocalDate d = r.startDate(); !d.isAfter(r.endDate()); d = d.plusDays(1)) {
                if (d.getDayOfWeek().getValue() <= 5) coveredDays.add(d);
            }
        }

        List<LocalDate> requestedWorkingDays = new ArrayList<>();
        for (LocalDate d = p.getStartDate(); !d.isAfter(endDate); d = d.plusDays(1)) {
            if (d.getDayOfWeek().getValue() <= 5) requestedWorkingDays.add(d);
        }

        for (LocalDate d : requestedWorkingDays) {
            if (!coveredDays.contains(d)) {
                p.setState(WizardState.DELETE_CANCELLED);
                return new WizardResult(
                    "Cannot delete: working day **" + d.format(FMT) + "** is not covered by any vacation for " +
                    p.getEmployeeName() + ". Deletion aborted.", "text", false, true);
            }
        }

        long days = requestedWorkingDays.size();
        if (days == 0) {
            return new WizardResult(
                "The selected date range contains no working days (Mon\u2013Fri). " +
                "Please enter a date range that includes at least one working day.",
                "vacation_prompt", false, false);
        }
        p.setDays(days);
        p.setState(WizardState.DELETE_CONFIRM);

        String warning = "";
        if (p.getStartDate().equals(endDate)) {
            for (LeaveRecord r : allRecords) {
                if (!r.employeeName().equalsIgnoreCase(p.getEmployeeName())) continue;
                if (r.startDate().isBefore(p.getStartDate()) && !r.endDate().isBefore(endDate)) {
                    warning = "\n\nNote: This day is part of a longer block (" + r.startDate().format(FMT) + " to " +
                              r.endDate().format(FMT) + "). Only this single day will be cleared.";
                    break;
                }
            }
        }

        return new WizardResult(
            "Please confirm deletion:\n" +
            "* Employee: **" + p.getEmployeeName() + "**\n" +
            "* Dates: **" + p.getStartDate().format(FMT) + "** to **" + endDate.format(FMT) + "** (" + days + " working day" + (days != 1 ? "s" : "") + ")" +
            warning + "\n\nType **yes** to delete or **no** to cancel.",
            "vacation_prompt", false, false);
    }

    private WizardResult handleConfirm(PendingVacation p, String msg) {
        String lower = msg.toLowerCase().trim();
        if (lower.startsWith("yes") || lower.equals("y") || lower.equals("confirm")) {
            p.setState(WizardState.DELETED);
            return new WizardResult("", "vacation_prompt", true, false);
        }
        return cancel(p);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Builds the vacation_form WizardResult for the delete flow.
     * Only a date-range picker is needed — no radio buttons for vacation type.
     */
    private WizardResult buildFormResult(String employeeName) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("employeeName", employeeName);
        payload.put("wizardType",   "delete");
        payload.put("types",        Collections.emptyList());

        try {
            return new WizardResult(mapper.writeValueAsString(payload), "vacation_form", false, false);
        } catch (Exception e) {
            return new WizardResult("Error building form. Type 'cancel' to abort.", "vacation_prompt", false, false);
        }
    }

    /** Returns true if the message is a structured form submission from the frontend. */
    static boolean isFormSubmission(String msg) {
        return msg != null && msg.startsWith(FORM_PREFIX);
    }

    /**
     * Returns true if startDate falls before the first day of the current calendar month.
     */
    static boolean isPastDate(LocalDate startDate) {
        LocalDate firstOfMonth = LocalDate.now().withDayOfMonth(1);
        return startDate.isBefore(firstOfMonth);
    }

    // Import note: VacationCreationService.validateCrossYearWindow is package-private (same package).

    private WizardResult cancel(PendingVacation p) {
        p.setState(WizardState.DELETE_CANCELLED);
        return new WizardResult("Deletion cancelled.", "text", false, true);
    }

    private boolean isCancelled(String msg) {
        String l = msg.toLowerCase().trim();
        return l.equals("cancel") || l.equals("abort") || l.equals("stop") || l.equals("quit");
    }

    private String resolveEmployee(String message, List<String> names) {
        String lower = message.toLowerCase();
        for (String name : names) {
            if (lower.contains(name.toLowerCase())) return name;
        }
        String[] words = message.replaceAll("[^a-zA-Z ]", " ").toLowerCase().split("\\s+");
        Set<String> wordSet = new HashSet<>(Arrays.asList(words));
        for (String name : names) {
            String[] tokens = name.split("[^a-zA-Z]+");
            for (int i = 0; i < tokens.length; i++) {
                String tok = tokens[i].toLowerCase();
                int min = (i == 0) ? 3 : 4;
                if (tok.length() >= min && wordSet.contains(tok)) return name;
            }
        }
        int best = 0; String bestName = null;
        for (String name : names) {
            int score = 0;
            for (String tok : name.split("[^a-zA-Z]+")) {
                if (tok.length() >= 4 && wordSet.contains(tok.toLowerCase())) score++;
            }
            if (score > best) { best = score; bestName = name; }
        }
        return best >= 1 ? bestName : null;
    }

    private LocalDate parseDate(String s) {
        try { return LocalDate.parse(s.trim()); } catch (DateTimeParseException e) { return null; }
    }

    public static final class WizardResult {
        private final String reply;
        private final String type;
        private final boolean confirmed;
        private final boolean cancelled;

        public WizardResult(String reply, String type, boolean confirmed, boolean cancelled) {
            this.reply     = reply;
            this.type      = type;
            this.confirmed = confirmed;
            this.cancelled = cancelled;
        }

        public String reply()      { return reply; }
        public String type()       { return type; }
        public boolean confirmed() { return confirmed; }
        public boolean cancelled() { return cancelled; }
    }
}
