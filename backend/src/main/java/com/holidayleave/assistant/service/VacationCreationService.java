package com.holidayleave.assistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.holidayleave.assistant.config.AppProperties;
import com.holidayleave.assistant.model.PendingVacation;
import com.holidayleave.assistant.model.PendingVacation.WizardState;
import com.holidayleave.assistant.model.VacationType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

@Service
public class VacationCreationService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd MMM yyyy");

    /** Prefix used by the frontend to submit the inline vacation form as a chat message. */
    static final String FORM_PREFIX = "__VACATION_FORM__:";

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private VacationTypeService vacationTypeService;

    @Autowired
    private RestrictedVacationTypeService restrictedVacationTypeService;

    @Autowired
    private AppProperties props;

    public WizardResult process(PendingVacation pending, String message, List<String> allEmployeeNames) {
        WizardState state = pending.getState();
        if (state == WizardState.IDLE)              return handleIdle(pending, message, allEmployeeNames);
        if (state == WizardState.NEED_EMP)          return handleNeedEmp(pending, message, allEmployeeNames);
        if (state == WizardState.NEED_TYPE)         return handleNeedType(pending, message);
        if (state == WizardState.NEED_DATES_FORM)   return handleNeedType(pending, message);  // form re-submit
        if (state == WizardState.NEED_START)        return handleNeedStart(pending, message);
        if (state == WizardState.NEED_END)          return handleNeedEnd(pending, message);
        if (state == WizardState.CONFIRM)           return handleConfirm(pending, message);
        return new WizardResult("Unexpected wizard state. Type 'cancel' to abort.", "vacation_prompt", false, false);
    }

    private WizardResult handleIdle(PendingVacation p, String msg, List<String> names) {
        String emp = resolveEmployee(msg, names);
        if (emp != null) {
            p.setEmployeeName(emp);
            p.setState(WizardState.NEED_DATES_FORM);
            return buildFormResult(emp);
        }
        p.setState(WizardState.NEED_EMP);
        return new WizardResult(
            "Which employee should I add the vacation for?\nKnown employees: " + String.join(", ", names),
            "vacation_prompt", false, false);
    }

    private WizardResult handleNeedEmp(PendingVacation p, String msg, List<String> names) {
        if (isCancelled(msg)) return cancel(p);
        String emp = resolveEmployee(msg, names);
        if (emp == null) {
            return new WizardResult(
                "I couldn't find that employee. Known employees: " + String.join(", ", names),
                "vacation_prompt", false, false);
        }
        p.setEmployeeName(emp);
        p.setState(WizardState.NEED_DATES_FORM);
        return buildFormResult(emp);
    }

    /**
     * Handles both legacy free-text type entry (NEED_TYPE) and the new combined
     * form submission (NEED_DATES_FORM).  When a __VACATION_FORM__: prefix is
     * detected the method parses all three fields (code, start, end) in one pass
     * and advances directly to CONFIRM.  Legacy text input still works so that
     * existing tests and fallback behaviour are preserved.
     */
    private WizardResult handleNeedType(PendingVacation p, String msg) {
        if (isCancelled(msg)) return cancel(p);

        // ── New path: combined form submission ────────────────────────────────
        if (isFormSubmission(msg)) {
            return handleFormSubmission(p, msg);
        }

        // ── Legacy path: free-text type selection (fallback / NEED_TYPE state) ─
        String lower = msg.toLowerCase().trim();
        VacationType found = null;

        // Pass 1 — exact code match
        for (VacationType t : vacationTypeService.findAll()) {
            if (t.code().equalsIgnoreCase(lower)) { found = t; break; }
        }
        // Pass 2 — exact label match
        if (found == null) {
            for (VacationType t : vacationTypeService.findAll()) {
                if (t.label().equalsIgnoreCase(lower)) { found = t; break; }
            }
        }
        // Pass 3 — label substring (3+ chars)
        if (found == null && lower.length() >= 3) {
            for (VacationType t : vacationTypeService.findAll()) {
                if (t.label().toLowerCase().contains(lower)) { found = t; break; }
            }
        }

        if (found == null) {
            // Re-render the form instead of a plain-text error
            return buildFormResult(p.getEmployeeName());
        }
        if (restrictedVacationTypeService.isRestricted(found.code())) {
            return buildFormResult(p.getEmployeeName());
        }
        p.setLeaveType(found.label());
        p.setLeaveCode(found.code());
        p.setState(WizardState.NEED_START);
        return new WizardResult("Leave type: **" + found.label() + "**.\nWhat is the start date? (YYYY-MM-DD)",
            "vacation_prompt", false, false);
    }

    /**
     * Parses a combined form submission: __VACATION_FORM__:CODE|YYYY-MM-DD|YYYY-MM-DD
     * Validates the type, past-date rule, date ordering, and working-day count,
     * then advances to CONFIRM in a single turn.
     */
    private WizardResult handleFormSubmission(PendingVacation p, String msg) {
        String payload = msg.substring(FORM_PREFIX.length()).trim();
        String[] parts = payload.split("\\|", 3);
        if (parts.length != 3) {
            return buildFormResult(p.getEmployeeName());
        }

        String code = parts[0].trim();
        LocalDate startDate = parseDate(parts[1].trim());
        LocalDate endDate   = parseDate(parts[2].trim());

        if (startDate == null || endDate == null) {
            return new WizardResult("Please select valid start and end dates.", "vacation_prompt", false, false);
        }

        // Validate leave type
        VacationType found = null;
        for (VacationType t : vacationTypeService.findAll()) {
            if (t.code().equalsIgnoreCase(code)) { found = t; break; }
        }
        if (found == null) {
            return buildFormResult(p.getEmployeeName());
        }
        if (restrictedVacationTypeService.isRestricted(found.code())) {
            return new WizardResult(
                "The vacation type **" + found.label() + "** is currently disabled by the administrator " +
                "and cannot be requested at this time.",
                "vacation_prompt", false, false);
        }

        // Past-date validation
        if (isPastDate(startDate)) {
            return new WizardResult(
                "Adding or deleting vacation for a past date is not allowed.",
                "vacation_prompt", false, false);
        }

        // Cross-year window validation: only allow end dates in Q1 of next year during Q4 of current year.
        if (endDate.getYear() > startDate.getYear()) {
            WizardResult crossYearCheck = validateCrossYearWindow(startDate, endDate,
                    props.isCrossYearBookingOverride());
            if (crossYearCheck != null) return crossYearCheck;
        }

        // Date ordering
        if (endDate.isBefore(startDate)) {
            return new WizardResult(
                "Start date cannot be greater than end date.",
                "vacation_prompt", false, false);
        }

        // Working-day check
        long days = countWeekdays(startDate, endDate);
        if (days == 0) {
            return new WizardResult(
                "The selected date range contains no working days (Mon\u2013Fri). " +
                "Please select a date range that includes at least one working day.",
                "vacation_prompt", false, false);
        }

        p.setLeaveType(found.label());
        p.setLeaveCode(found.code());
        p.setStartDate(startDate);
        p.setEndDate(endDate);
        p.setDays(days);
        p.setState(WizardState.CONFIRM);

        return new WizardResult(
            "Please confirm:\n" +
            "* Employee: **" + p.getEmployeeName() + "**\n" +
            "* Type: **" + found.label() + "**\n" +
            "* Dates: **" + startDate.format(FMT) + "** to **" + endDate.format(FMT) +
            "** (" + days + " working day" + (days != 1 ? "s" : "") + ")\n\n" +
            "Type **yes** to save or **no** to cancel.",
            "vacation_prompt", false, false);
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
        p.setState(WizardState.NEED_END);
        return new WizardResult("Start date: **" + date.format(FMT) + "**.\nWhat is the end date? (YYYY-MM-DD, must be >= start date)",
            "vacation_prompt", false, false);
    }

    private WizardResult handleNeedEnd(PendingVacation p, String msg) {
        if (isCancelled(msg)) return cancel(p);
        LocalDate date = parseDate(msg);
        if (date == null) return new WizardResult("Please enter a valid end date in YYYY-MM-DD format.",
            "vacation_prompt", false, false);
        if (date.isBefore(p.getStartDate())) return new WizardResult(
            "End date must be on or after start date (" + p.getStartDate().format(FMT) + ").", "vacation_prompt", false, false);
        p.setEndDate(date);
        long days = countWeekdays(p.getStartDate(), date);
        if (days == 0) {
            return new WizardResult(
                "The selected date range contains no working days (Mon\u2013Fri). " +
                "Please enter a date range that includes at least one working day.",
                "vacation_prompt", false, false);
        }
        p.setDays(days);
        p.setState(WizardState.CONFIRM);
        return new WizardResult(
            "Please confirm:\n" +
            "* Employee: **" + p.getEmployeeName() + "**\n" +
            "* Type: **" + p.getLeaveType() + "**\n" +
            "* Dates: **" + p.getStartDate().format(FMT) + "** to **" + date.format(FMT) + "** (" + days + " working day" + (days != 1 ? "s" : "") + ")\n\n" +
            "Type **yes** to save or **no** to cancel.",
            "vacation_prompt", false, false);
    }

    private WizardResult handleConfirm(PendingVacation p, String msg) {
        String lower = msg.toLowerCase().trim();
        if (lower.startsWith("yes") || lower.equals("y") || lower.equals("confirm")) {
            p.setState(WizardState.SAVED);
            return new WizardResult("", "vacation_prompt", true, false);
        }
        return cancel(p);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Builds the vacation_form WizardResult that asks the frontend to render
     * an inline HTML form (radio buttons for type + date-range picker).
     * The reply is a JSON object consumed exclusively by renderVacationForm() in JS.
     */
    private WizardResult buildFormResult(String employeeName) {
        List<VacationType> allTypes = vacationTypeService.findAll();
        List<String> restricted    = restrictedVacationTypeService.getRestrictedTypes();

        List<Map<String, String>> types = new ArrayList<>();
        for (VacationType t : allTypes) {
            if (restricted.contains(t.code().toUpperCase())) continue;
            Map<String, String> m = new LinkedHashMap<>();
            m.put("code",  t.code());
            m.put("label", t.label());
            types.add(m);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("employeeName", employeeName);
        payload.put("wizardType",   "add");
        payload.put("types",        types);

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
     * Vacation entries in the current month (even if some days have already passed) are allowed.
     */
    static boolean isPastDate(LocalDate startDate) {
        LocalDate firstOfMonth = LocalDate.now().withDayOfMonth(1);
        return startDate.isBefore(firstOfMonth);
    }

    /**
     * Validates the cross-calendar-year booking window.
     *
     * <p>Cross-year bookings (end date in a different year from start date) are only
     * permitted when:
     * <ol>
     *   <li>Today is in Q4 of the current year (October–December), AND</li>
     *   <li>The end date falls within Q1 (January–March) of the immediately following year.</li>
     * </ol>
     *
     * @return a {@link WizardResult} with an error message if the window is violated,
     *         or {@code null} if the cross-year range is acceptable.
     */
    /**
     * @param crossYearBookingOverride when {@code true} the Q4 calendar-window check is
     *        skipped, allowing advance booking outside October–December (testing only).
     */
    static WizardResult validateCrossYearWindow(LocalDate startDate, LocalDate endDate,
                                                boolean crossYearBookingOverride) {
        int currentYear = LocalDate.now().getYear();
        int currentMonth = LocalDate.now().getMonthValue();
        int nextYear = currentYear + 1;

        // Only same-year-start → next-year-end is in scope; anything beyond is always rejected.
        if (endDate.getYear() > nextYear || startDate.getYear() < currentYear) {
            return new WizardResult(
                "Vacation dates spanning more than two consecutive calendar years are not supported. " +
                "Please submit separate requests per year.",
                "vacation_prompt", false, false);
        }

        // End date must be in Q1 (January–March) of next year.
        if (endDate.getYear() == nextYear && endDate.getMonthValue() > 3) {
            return new WizardResult(
                "Advance vacation booking across calendar years is limited to Q1 (January–March) of "
                + nextYear + ". Please choose an end date no later than 31 March " + nextYear + ".",
                "vacation_prompt", false, false);
        }

        // Advance booking into next year is only permitted during Q4 (October–December).
        // This check is skipped when the testing override flag is enabled.
        if (!crossYearBookingOverride && currentMonth < 10) {
            return new WizardResult(
                "Advance vacation booking for " + nextYear
                + " is only permitted during Q4 (October–December) of " + currentYear
                + ". Please try again when Q4 begins.",
                "vacation_prompt", false, false);
        }

        // All conditions met — cross-year range is valid.
        return null;
    }

    private WizardResult cancel(PendingVacation p) {
        p.setState(WizardState.CANCELLED);
        return new WizardResult("Vacation entry cancelled.", "text", false, true);
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

    public static long countWeekdays(LocalDate start, LocalDate end) {
        long count = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            if (d.getDayOfWeek().getValue() <= 5) count++;
        }
        return count;
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
