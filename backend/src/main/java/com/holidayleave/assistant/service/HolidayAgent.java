package com.holidayleave.assistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.holidayleave.assistant.analysis.LeaveAnalysisService;
import com.holidayleave.assistant.llm.LLMService;
import com.holidayleave.assistant.model.LeaveAnalysisResult;
import com.holidayleave.assistant.model.LeaveRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class HolidayAgent {

    private static final Logger log = LoggerFactory.getLogger(HolidayAgent.class);

    /** Slim prompt used exclusively for date-query calls (who is on leave on date X?).
     *  Contains no leave-type rules or decision rules — only the date-query instruction. */
    private static final String DATE_QUERY_PROMPT =
"You are a leave management assistant. Answer using ONLY the information in <context>.\n\n" +
"The context has query_type=date_query with an employees_on_leave array.\n" +
"List EVERY employee in employees_on_leave with their name and leave type.\n" +
"If the list is empty say no one is on leave. End with 'X employee(s) are on leave on [query_date_display].'\n" +
"Never invent employees. Short and direct. Respond in the user's language.\n\n" +
"<context>\n%s\n</context>\n";

    /** Slim prompt used exclusively for date-range queries (who is on leave FROM date A TO date B?).
     *  Instructs the LLM to list all employees whose leave overlaps the range, with counts per type. */
    private static final String DATE_RANGE_QUERY_PROMPT =
"You are a leave management assistant. Answer using ONLY the information in <context>.\n\n" +
"The context has query_type=date_range_query with an employees_on_leave array.\n" +
"Each entry has employee_name, leave_type (comma-separated types), and leave_type_counts (a map of type → count).\n" +
"List EVERY employee in employees_on_leave. For each employee format the leave types as:\n" +
"  Type1 (count1), Type2 (count2)  — one entry per distinct type with its count in parentheses.\n" +
"If the list is empty say no one is on leave in that period.\n" +
"End with 'X employee(s) are on leave between [range_start_display] and [range_end_display].'\n" +
"Never invent employees. Short and direct. Respond in the user's language.\n\n" +
"<context>\n%s\n</context>\n";

    private static final List<String> ADD_KEYWORDS;
    private static final List<String> DELETE_KEYWORDS;
    private static final List<String> REPORT_KEYWORDS;
    private static final List<String> ALL_EMPLOYEES_KEYWORDS;
    private static final List<String> RECORDS_KEYWORDS;

    static {
        ADD_KEYWORDS = Arrays.asList(
            "add vacation","add leave","add holiday",
            "book vacation","book leave","book holiday",
            "request leave","request vacation","request holiday",
            "new vacation","new leave","create vacation","create leave",
            "record vacation","record leave","log vacation","log leave",
            "schedule vacation","schedule leave"
        );
        DELETE_KEYWORDS = Arrays.asList(
            "delete vacation","delete leave","delete holiday",
            "remove vacation","remove leave","remove holiday",
            "cancel vacation","cancel leave",
            "undo vacation","undo leave",
            "erase vacation","erase leave"
        );
        REPORT_KEYWORDS = Arrays.asList(
            "generate report","create report","make report",
            "yearly report","annual report","leave report",
            "generate leave","create leave","export report",
            "html report","produce report"
        );
        ALL_EMPLOYEES_KEYWORDS = Arrays.asList(
            "all employee","all staff","everyone","all workers"
        );
        RECORDS_KEYWORDS = Arrays.asList(
            "list","show","detail","breakdown","all leave","each",
            "individual","periods","which dates","what dates","when did",
            "display","print","give me all","entries"
        );
    }

    private static final Pattern MONTH_PATTERN = Pattern.compile(
        "\\b(january|february|march|april|may|june|july|august|september|october|november|december|" +
        "jan|feb|mar|apr|jun|jul|aug|sep|oct|nov|dec)\\b", Pattern.CASE_INSENSITIVE
    );
    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(20\\d{2})\\b");

    // ── Date-query detection ──────────────────────────────────────────────────
    // Matches patterns such as: "15 March 2026", "March 15", "03/15/2026",
    // "2026-03-15", "March 2nd", "2nd March 2026", "March 2, 2026"
    private static final Pattern DATE_QUERY_KEYWORDS = Pattern.compile(
        "\\b(who is on leave|who is off|who will be|who has leave|who are on leave" +
        "|who are off|which employees|are any|is anyone|is .+ on leave|on leave on" +
        "|off on|vacation on|away on|absent on)\\b", Pattern.CASE_INSENSITIVE
    );
    // Matches a day number (with optional ordinal) paired with a month name, or numeric date formats
    private static final Pattern SPECIFIC_DATE_PATTERN = Pattern.compile(
        // dd Month yyyy  /  Month dd yyyy  /  Month dd, yyyy
        "(?:(\\d{1,2})(?:st|nd|rd|th)?\\s+" +
            "(january|february|march|april|may|june|july|august|september|october|november|december|" +
             "jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)" +
            "(?:\\s+(20\\d{2}))?)" +
        "|" +
        "(?:(january|february|march|april|may|june|july|august|september|october|november|december|" +
              "jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)" +
           "\\s+(\\d{1,2})(?:st|nd|rd|th)?" +
           "(?:,?\\s+(20\\d{2}))?)" +
        "|" +
        // yyyy-mm-dd
        "(?:(20\\d{2})-(\\d{2})-(\\d{2}))" +
        "|" +
        // dd/mm/yyyy  or  mm/dd/yyyy — treated as dd/mm/yyyy (European, matching regional convention)
        "(?:(\\d{2})/(\\d{2})/(20\\d{2}))",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * Matches a cross-employee date-range query of the form:
     *   "who are on leave from 15 March 2026 to 31 August 2026"
     *   "who is off from 2026-03-15 to 2026-08-31"
     *   "which employees are on leave from 01/03/2026 to 31/08/2026"
     *
     * The pattern captures two date expressions (each matching SPECIFIC_DATE_PATTERN's formats)
     * separated by "to" or "and", anchored inside a "from … to/and …" frame.
     * Groups: 1=fromDate token, 2=toDate token (each full matched text, parsed by extractSpecificDate).
     */
    private static final Pattern DATE_RANGE_PATTERN = Pattern.compile(
        "(?:\\bfrom|\\bbetween)\\s+" +
        // from/between-date: dd Month [yyyy] | Month dd[,] [yyyy] | yyyy-mm-dd | dd/mm/yyyy
        "((?:\\d{1,2}(?:st|nd|rd|th)?\\s+)" +
           "(?:january|february|march|april|may|june|july|august|september|october|november|december|" +
            "jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)" +
           "(?:\\s+20\\d{2})?" +
        "|(?:january|february|march|april|may|june|july|august|september|october|november|december|" +
              "jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)" +
           "\\s+\\d{1,2}(?:st|nd|rd|th)?(?:,?\\s+20\\d{2})?" +
        "|20\\d{2}-\\d{2}-\\d{2}" +
        "|\\d{2}/\\d{2}/20\\d{2})" +
        "\\s+(?:to|and|until|through)\\s+" +
        // to/end-date: same formats
        "((?:\\d{1,2}(?:st|nd|rd|th)?\\s+)" +
           "(?:january|february|march|april|may|june|july|august|september|october|november|december|" +
            "jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)" +
           "(?:\\s+20\\d{2})?" +
        "|(?:january|february|march|april|may|june|july|august|september|october|november|december|" +
              "jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)" +
           "\\s+\\d{1,2}(?:st|nd|rd|th)?(?:,?\\s+20\\d{2})?" +
        "|20\\d{2}-\\d{2}-\\d{2}" +
        "|\\d{2}/\\d{2}/20\\d{2})",
        Pattern.CASE_INSENSITIVE
    );

    @Autowired private LLMService llmService;
    @Autowired private LeaveAnalysisService analysisService;
    @Autowired private AppState appState;

    private final ObjectMapper mapper = new ObjectMapper();

    /** Returns the raw context JSON that would be sent to the LLM for the given question, without calling the LLM. */
    /** Backward-compatible overload — uses record-derived names only (no structural roster). */
    public String buildContext(String question, List<LeaveRecord> allRecords, String sessionId) {
        return buildContext(question, allRecords, Collections.emptyList(), sessionId);
    }

    /**
     * Builds the LLM context for a question using the full structural employee roster.
     *
     * @param allEmployeeNames complete name list from column 0 of the planner sheet;
     *                         if empty, names are derived from {@code allRecords}
     */
    public String buildContext(String question, List<LeaveRecord> allRecords,
                               List<String> allEmployeeNames, String sessionId) {
        String lower = question.toLowerCase();
        int year = extractYear(question, allRecords);

        // Date-range query ("who are on leave from 15 March 2026 to 31 August 2026?") — checked
        // BEFORE the single-date path because it also has no employee name and two dates.
        if (isDateRangeQuery(question, allRecords)) {
            LocalDate[] range = extractDateRange(question, year);
            if (range != null) return buildContextForDateRange(allRecords, range[0], range[1]);
        }

        // Date-specific query ("who is on leave on 02 March 2026?") — only when no employee name
        // is present and the question looks like a cross-employee date query (not a per-employee query).
        if (isDateQuery(question, allRecords)) {
            LocalDate specificDate = extractSpecificDate(question, year);
            if (specificDate != null) return buildContextForDate(allRecords, specificDate);
        }

        boolean allEmployees = isAllEmployeesQuery(lower);
        String employeeName = allEmployees ? null : resolveEmployeeName(question, allRecords, allEmployeeNames);
        if (!allEmployees && employeeName == null) {
            employeeName = resolveEmployeeNameFromHistory(
                    appState.getConversationHistory(sessionId), allRecords, allEmployeeNames);
        }
        if (allEmployees) return buildContextForAll(allRecords, year);
        if (employeeName != null) return buildContextForEmployee(allRecords, employeeName, year, question);
        return buildGenericContext(allRecords, allEmployeeNames, year);
    }

    /** Backward-compatible overload — uses record-derived names only. */
    public String ask(String question, List<LeaveRecord> allRecords, String sessionId) {
        return ask(question, allRecords, Collections.emptyList(), sessionId);
    }

    /**
     * Answers a question using the full structural employee roster so that employees
     * with zero leave entries are reachable by name and appear in the LLM context.
     *
     * @param allEmployeeNames complete name list from column 0 of the planner sheet
     */
    public String ask(String question, List<LeaveRecord> allRecords,
                      List<String> allEmployeeNames, String sessionId) {
        String lower = question.toLowerCase();
        int year = extractYear(question, allRecords);

        // Date-range query ("who are on leave from 15 March 2026 to 31 August 2026?") — checked
        // BEFORE the single-date path so it is not swallowed by isDateQuery().
        if (isDateRangeQuery(question, allRecords)) {
            LocalDate[] range = extractDateRange(question, year);
            if (range != null) {
                String context = buildContextForDateRange(allRecords, range[0], range[1]);
                String reply = llmService.ask(DATE_RANGE_QUERY_PROMPT, context, question, appState.getConversationHistory(sessionId));
                appState.addToHistory(sessionId, question, reply);
                return reply;
            }
        }

        // Date-specific query — only when no employee name is present and question is a
        // cross-employee date lookup, not a per-employee range or single-month query.
        if (isDateQuery(question, allRecords)) {
            LocalDate specificDate = extractSpecificDate(question, year);
            if (specificDate != null) {
                String context = buildContextForDate(allRecords, specificDate);
                String reply = llmService.ask(DATE_QUERY_PROMPT, context, question, appState.getConversationHistory(sessionId));
                appState.addToHistory(sessionId, question, reply);
                return reply;
            }
        }

        boolean allEmployees = isAllEmployeesQuery(lower);
        String employeeName = allEmployees ? null : resolveEmployeeName(question, allRecords, allEmployeeNames);
        // Pronoun reference ("she", "he", "her", "him") — primary pass returns null.
        // Fall back to history before choosing the context builder so the LLM receives
        // real leave data instead of a sparse context.
        if (!allEmployees && employeeName == null) {
            employeeName = resolveEmployeeNameFromHistory(
                    appState.getConversationHistory(sessionId), allRecords, allEmployeeNames);
        }

        String context;
        if (allEmployees) {
            context = buildContextForAll(allRecords, year);
        } else if (employeeName != null) {
            context = buildContextForEmployee(allRecords, employeeName, year, question);
        } else {
            context = buildGenericContext(allRecords, allEmployeeNames, year);
        }

        String reply = llmService.ask(null, context, question, appState.getConversationHistory(sessionId));
        appState.addToHistory(sessionId, question, reply);
        return reply;
    }

    public boolean isAddVacationIntent(String message) {
        String lower = message.toLowerCase();
        for (String kw : ADD_KEYWORDS) { if (lower.contains(kw)) return true; }
        return false;
    }

    public boolean isDeleteVacationIntent(String message) {
        String lower = message.toLowerCase();
        for (String kw : DELETE_KEYWORDS) { if (lower.contains(kw)) return true; }
        return false;
    }

    public boolean isReportIntent(String message) {
        String lower = message.toLowerCase();
        for (String kw : REPORT_KEYWORDS) { if (lower.contains(kw)) return true; }
        return false;
    }

    public boolean isAllEmployeesQuery(String message) {
        String lower = message.toLowerCase();
        for (String kw : ALL_EMPLOYEES_KEYWORDS) { if (lower.contains(kw)) return true; }
        return false;
    }

    /**
     * Returns true when the question is a cross-employee date-specific query
     * ("who is on leave on 02 March?") rather than a per-employee query.
     *
     * A question is treated as a date query ONLY when:
     *   1. No specific employee name can be resolved from it (it's not about one person), AND
     *   2. It does not contain a date range ("from … to …"), which would be a range query.
     *
     * This prevents false-positive routing for queries like:
     *   "How many days did Alice take from 01 March to 05 March?" — has an employee name → not a date query
     *   "How many days from January to March?" — has "from … to" → not a date query
     */
    boolean isDateQuery(String question, List<LeaveRecord> allRecords) {
        // Must contain a recognisable specific date pattern to be routable
        // (checked by the caller, but guard here too for clarity)
        // Reject if a known employee name is found in the question
        if (resolveEmployeeName(question, allRecords) != null) return false;
        // Reject if the question is phrased as a range ("from X to Y") —
        // that would produce two month tokens and should go through the range path
        List<Integer> months = extractMonths(question);
        if (months.size() >= 2) return false;
        return true;
    }

    /**
     * Scans conversation history in reverse order and returns the first employee
     * name that can be resolved from any prior message (user or assistant).
     * Delegates to the existing resolveEmployeeName logic so matching rules stay consistent.
     */
    public String resolveEmployeeNameFromHistory(List<Map<String, String>> history,
                                                  List<LeaveRecord> allRecords) {
        return resolveEmployeeNameFromHistory(history, allRecords, Collections.emptyList());
    }

    /**
     * Scans conversation history using a pre-built full name list.
     * Prefer this overload when the caller already has the structural name roster
     * (includes employees with zero leave entries).
     */
    public String resolveEmployeeNameFromHistory(List<Map<String, String>> history,
                                                  List<LeaveRecord> allRecords,
                                                  List<String> allEmployeeNames) {
        if (history == null || history.isEmpty()) return null;
        for (int i = history.size() - 1; i >= 0; i--) {
            String content = history.get(i).get("content");
            if (content == null || content.isEmpty()) continue;
            String found = resolveEmployeeName(content, allRecords, allEmployeeNames);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * Resolves an employee name from a question, using leave records as the name source.
     * <p><b>Limitation:</b> employees with zero leave entries are invisible.
     * Prefer {@link #resolveEmployeeName(String, List, List)} when a full structural
     * name list is available.
     */
    public String resolveEmployeeName(String question, List<LeaveRecord> allRecords) {
        return resolveEmployeeName(question, allRecords, Collections.emptyList());
    }

    /**
     * Resolves an employee name from a question against the union of:
     * <ol>
     *   <li>The provided {@code allEmployeeNames} list (full structural roster from column 0),</li>
     *   <li>Names derived from {@code allRecords} (fallback for callers that lack the roster).</li>
     * </ol>
     * The {@code allEmployeeNames} list takes priority; if non-empty it is used exclusively
     * so that employees with zero leave entries are always reachable.
     *
     * @param question         the user's question or message text
     * @param allRecords       leave records (used as name source when allEmployeeNames is empty)
     * @param allEmployeeNames full structural employee name list (may be empty)
     * @return matched canonical employee name, or {@code null} if unresolved
     */
    public String resolveEmployeeName(String question,
                                      List<LeaveRecord> allRecords,
                                      List<String> allEmployeeNames) {
        // Build the candidate list: prefer the full structural roster; fall back to records.
        List<String> names;
        if (allEmployeeNames != null && !allEmployeeNames.isEmpty()) {
            names = allEmployeeNames;
        } else {
            names = new ArrayList<>();
            for (LeaveRecord r : allRecords) {
                if (!names.contains(r.employeeName())) names.add(r.employeeName());
            }
        }
        return matchEmployeeName(question, names);
    }

    /**
     * Core 3-pass fuzzy name-matching logic shared by both resolveEmployeeName overloads.
     */
    private String matchEmployeeName(String question, List<String> names) {
        String lower = question.toLowerCase();
        // Pass 1 — substring match
        for (String name : names) {
            if (lower.contains(name.toLowerCase())) return name;
        }
        // Pass 2 — individual token match with minimum-length guard
        String cleaned = question.replaceAll("[^a-zA-Z ]", " ").toLowerCase();
        Set<String> questionWordSet = new HashSet<>(Arrays.asList(cleaned.split("\\s+")));
        for (String name : names) {
            String[] tokens = name.split("[^a-zA-Z]+");
            for (int i = 0; i < tokens.length; i++) {
                String token = tokens[i].toLowerCase();
                int minLen = (i == 0) ? 3 : 4;
                if (token.length() >= minLen && questionWordSet.contains(token)) return name;
            }
        }
        // Pass 3 — best-score multi-token match
        int bestScore = 0; String bestName = null;
        for (String name : names) {
            int score = 0;
            for (String tok : name.split("[^a-zA-Z]+")) {
                if (tok.length() >= 4 && questionWordSet.contains(tok.toLowerCase())) score++;
            }
            if (score > bestScore) { bestScore = score; bestName = name; }
        }
        return bestScore >= 1 ? bestName : null;
    }

    private String buildContextForEmployee(List<LeaveRecord> allRecords, String employeeName,
                                            int year, String question) {
        List<LeaveRecord> empRecords = new ArrayList<>();
        for (LeaveRecord r : allRecords) {
            if (r.employeeName().equalsIgnoreCase(employeeName) && r.year() == year) empRecords.add(r);
        }

        LeaveAnalysisResult analysis = analysisService.analyse(allRecords, employeeName, year);
        double consumed = analysisService.consumedToDate(empRecords);

        List<Integer> requestedMonths = extractMonths(question);
        Integer startMonth = requestedMonths.isEmpty() ? null : requestedMonths.get(0);
        Integer endMonth   = requestedMonths.size() > 1 ? requestedMonths.get(1) : null;

        // Normalise: ensure startMonth <= endMonth so "March to January" is treated as Jan–Mar.
        if (startMonth != null && endMonth != null && startMonth > endMonth) {
            Integer tmp = startMonth; startMonth = endMonth; endMonth = tmp;
        }

        Map<Integer, Double> byMonth = analysis.byMonth();
        Map<String, Double> byType  = analysis.byType();
        List<LeaveRecord> recordsForContext = empRecords;

        if (startMonth != null && endMonth != null) {
            // ── RANGE PATH: aggregate all months from startMonth through endMonth inclusive ──

            // 1. Collect every month number in the range.
            List<Integer> rangeMonths = new ArrayList<>();
            for (int m = startMonth; m <= endMonth; m++) rangeMonths.add(m);

            // 2. Build byMonth map for the range (all-types totals — used for total-leave questions).
            Map<Integer, Double> rangeByMonth = new LinkedHashMap<>();
            double rangeAllTypesTotal = 0.0;
            for (int m : rangeMonths) {
                double val = byMonth.containsKey(m) ? byMonth.get(m) : 0.0;
                rangeByMonth.put(m, val);
                rangeAllTypesTotal += val;
            }
            byMonth = rangeByMonth;

            // 3. Date window covering the full range.
            LocalDate rangeStart = LocalDate.of(year, startMonth, 1);
            LocalDate rangeEnd   = LocalDate.of(year, endMonth, 1)
                                       .withDayOfMonth(LocalDate.of(year, endMonth, 1).lengthOfMonth());

            // 4. Compute proportional byType and per-month-by-type across the entire range window.
            Map<String, Double> rangeByType = new LinkedHashMap<>();
            // byMonthByType: month-number → (leaveType → proportional days)
            Map<Integer, Map<String, Double>> byMonthByType = new LinkedHashMap<>();
            for (int m : rangeMonths) byMonthByType.put(m, new LinkedHashMap<>());
            List<LeaveRecord> rangeRecords = new ArrayList<>();
            for (LeaveRecord r : empRecords) {
                if (r.endDate().isBefore(rangeStart) || r.startDate().isAfter(rangeEnd)) continue;
                rangeRecords.add(r);
                LocalDate oStart = r.startDate().isBefore(rangeStart) ? rangeStart : r.startDate();
                LocalDate oEnd   = r.endDate().isAfter(rangeEnd)       ? rangeEnd   : r.endDate();
                long totalWd   = countWorkingDays(r.startDate(), r.endDate());
                long overlapWd = countWorkingDays(oStart, oEnd);
                double share = totalWd > 0 ? r.days() * ((double) overlapWd / totalWd) : 0.0;
                if (share > 0) {
                    Double existing = rangeByType.get(r.leaveType());
                    rangeByType.put(r.leaveType(), (existing != null ? existing : 0.0) + share);
                }
                // Per-month-by-type: distribute this record's days proportionally to each range month
                for (int m : rangeMonths) {
                    LocalDate mStart = LocalDate.of(year, m, 1);
                    LocalDate mEnd   = mStart.withDayOfMonth(mStart.lengthOfMonth());
                    LocalDate moStart = r.startDate().isBefore(mStart) ? mStart : r.startDate();
                    LocalDate moEnd   = r.endDate().isAfter(mEnd)      ? mEnd   : r.endDate();
                    if (moStart.isAfter(moEnd)) continue;
                    long mOverlapWd = countWorkingDays(moStart, moEnd);
                    double mShare = totalWd > 0 ? r.days() * ((double) mOverlapWd / totalWd) : 0.0;
                    if (mShare > 0) {
                        Map<String, Double> mTypeMap = byMonthByType.get(m);
                        Double mExisting = mTypeMap.get(r.leaveType());
                        mTypeMap.put(r.leaveType(), (mExisting != null ? mExisting : 0.0) + mShare);
                    }
                }
            }
            byType = rangeByType;
            recordsForContext = rangeRecords;

            // 5. Build record maps and context — range-specific fields.
            List<Map<String, Object>> recordMaps = new ArrayList<>();
            if (needsRecords(question)) {
                for (LeaveRecord r : recordsForContext) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("start_date",  r.startDate().toString());
                    m.put("end_date",    r.endDate().toString());
                    m.put("days",        r.days());
                    m.put("leave_type",  r.leaveType());
                    m.put("reason",      r.reason());
                    recordMaps.add(m);
                }
            }

            Map<String, Object> ctx = new LinkedHashMap<>();
            ctx.put("employee_name",                   employeeName);
            ctx.put("analysis_year",                   year);
            ctx.put("today",                           LocalDate.now().toString());
            ctx.put("entitlement_days",                analysis.entitlement());
            ctx.put("consumed_days",                   consumed);
            ctx.put("remaining_days",                  analysis.remaining());
            ctx.put("by_month",                        byMonth);
            ctx.put("by_type",                         byType);
            ctx.put("by_month_by_type",                byMonthByType);
            ctx.put("is_range_query",                  true);
            ctx.put("total_all_leave_types_in_range",  rangeAllTypesTotal);
            ctx.put("range_start_month",               startMonth);
            ctx.put("range_start_month_name",          java.time.Month.of(startMonth)
                    .getDisplayName(TextStyle.FULL, Locale.ENGLISH));
            ctx.put("range_end_month",                 endMonth);
            ctx.put("range_end_month_name",            java.time.Month.of(endMonth)
                    .getDisplayName(TextStyle.FULL, Locale.ENGLISH));
            if (!recordMaps.isEmpty()) {
                ctx.put("leave_records",       recordMaps);
                ctx.put("total_records_shown", recordMaps.size());
            }

            try { return mapper.writeValueAsString(ctx); } catch (Exception e) { return "{}"; }

        } else if (startMonth != null) {
            // ── SINGLE-MONTH PATH: existing logic preserved exactly ──
            final int rm = startMonth;
            Map<Integer, Double> filteredByMonth = new LinkedHashMap<>();
            filteredByMonth.put(rm, byMonth.containsKey(rm) ? byMonth.get(rm) : 0.0);
            byMonth = filteredByMonth;

            LocalDate monthStart = LocalDate.of(year, rm, 1);
            LocalDate monthEnd   = monthStart.withDayOfMonth(monthStart.lengthOfMonth());
            Map<String, Double> filteredByType = new LinkedHashMap<>();
            for (LeaveRecord r : empRecords) {
                LocalDate oStart = r.startDate().isBefore(monthStart) ? monthStart : r.startDate();
                LocalDate oEnd   = r.endDate().isAfter(monthEnd)      ? monthEnd   : r.endDate();
                if (oStart.isAfter(oEnd)) continue;
                long totalWd   = countWorkingDays(r.startDate(), r.endDate());
                long overlapWd = countWorkingDays(oStart, oEnd);
                double share = totalWd > 0 ? r.days() * ((double) overlapWd / totalWd) : 0.0;
                if (share > 0) {
                    Double existing = filteredByType.get(r.leaveType());
                    filteredByType.put(r.leaveType(), (existing != null ? existing : 0.0) + share);
                }
            }
            byType = filteredByType;

            recordsForContext = new ArrayList<>();
            for (LeaveRecord r : empRecords) {
                if (!r.endDate().isBefore(monthStart) && !r.startDate().isAfter(monthEnd)) {
                    recordsForContext.add(r);
                }
            }
        }

        // ── FULL-YEAR PATH and SINGLE-MONTH PATH share the same context serialisation ──
        List<Map<String, Object>> recordMaps = new ArrayList<>();
        if (needsRecords(question)) {
            for (LeaveRecord r : recordsForContext) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("start_date",  r.startDate().toString());
                m.put("end_date",    r.endDate().toString());
                m.put("days",        r.days());
                m.put("leave_type",  r.leaveType());
                m.put("reason",      r.reason());
                recordMaps.add(m);
            }
        }

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("employee_name",       employeeName);
        ctx.put("analysis_year",       year);
        ctx.put("today",               LocalDate.now().toString());
        ctx.put("entitlement_days",    analysis.entitlement());
        ctx.put("consumed_days",       consumed);
        ctx.put("remaining_days",      analysis.remaining());
        ctx.put("by_month",            byMonth);
        ctx.put("by_type",             byType);
        if (startMonth != null) {
            ctx.put("total_all_leave_types_in_month", byMonth.containsKey(startMonth) ? byMonth.get(startMonth) : 0.0);
            ctx.put("context_scope_month",            startMonth);
            ctx.put("context_scope_month_name",       java.time.Month.of(startMonth)
                    .getDisplayName(TextStyle.FULL, Locale.ENGLISH));
        } else {
            // Full-year only: include summary stats and by_year
            ctx.put("utilization_pct",     analysis.utilizationPct());
            ctx.put("avg_days_per_month",  analysis.avgPerMonth());
            ctx.put("longest_streak_days", analysis.longestStreak());
            Map<String, Double> byYear = new LinkedHashMap<>();
            byYear.put(String.valueOf(year), analysis.entitlement());
            ctx.put("by_year",             byYear);
        }
        if (!recordMaps.isEmpty()) {
            ctx.put("leave_records",       recordMaps);
            ctx.put("total_records_shown", recordMaps.size());
        }

        try { return mapper.writeValueAsString(ctx); } catch (Exception e) { return "{}"; }
    }

    /**
     * Returns true when the question explicitly requests a list or breakdown of
     * individual leave spans. For all other questions (totals, counts, type
     * figures) the pre-computed aggregate fields are sufficient and the raw
     * leave_records array is omitted to save input tokens.
     */
    boolean needsRecords(String question) {
        String lower = question.toLowerCase();
        for (String kw : RECORDS_KEYWORDS) {
            if (lower.contains(kw)) return true;
        }
        return false;
    }

    private String buildContextForAll(List<LeaveRecord> allRecords, int year) {
        List<LeaveRecord> yearRecords = new ArrayList<>();
        for (LeaveRecord r : allRecords) { if (r.year() == year) yearRecords.add(r); }

        Map<String, Double> summaryByEmployee = new LinkedHashMap<>();
        for (LeaveRecord r : yearRecords) {
            Double existing = summaryByEmployee.get(r.employeeName());
            summaryByEmployee.put(r.employeeName(), (existing != null ? existing : 0.0) + r.days());
        }

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("analysis_year", year);
        ctx.put("today", LocalDate.now().toString());
        ctx.put("all_employees_summary", summaryByEmployee);
        ctx.put("total_employees", summaryByEmployee.size());
        ctx.put("total_records", yearRecords.size());
        try { return mapper.writeValueAsString(ctx); } catch (Exception e) { return "{}"; }
    }

    private String buildGenericContext(List<LeaveRecord> allRecords,
                                       List<String> allEmployeeNames, int year) {
        // Prefer the full structural roster; fall back to record-derived names.
        List<String> employees;
        if (allEmployeeNames != null && !allEmployeeNames.isEmpty()) {
            employees = allEmployeeNames;
        } else {
            employees = new ArrayList<>();
            for (LeaveRecord r : allRecords) {
                if (!employees.contains(r.employeeName())) employees.add(r.employeeName());
            }
        }
        Set<Integer> yearsSet = new LinkedHashSet<>();
        for (LeaveRecord r : allRecords) yearsSet.add(r.year());
        List<Integer> years = new ArrayList<>(yearsSet);
        Collections.sort(years);

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("analysis_year", year);
        ctx.put("today", LocalDate.now().toString());
        ctx.put("employees", employees);
        ctx.put("total_employees", employees.size());
        ctx.put("available_years", years);
        try { return mapper.writeValueAsString(ctx); } catch (Exception e) { return "{}"; }
    }

    private int extractYear(String question, List<LeaveRecord> allRecords) {
        return extractYearPublic(question, allRecords);
    }

    /** Public accessor for extractYear — used by controllers in other packages. */
    public int extractYearPublic(String question, List<LeaveRecord> allRecords) {
        Matcher m = YEAR_PATTERN.matcher(question);
        if (m.find()) return Integer.parseInt(m.group(1));
        int max = LocalDate.now().getYear();
        for (LeaveRecord r : allRecords) { if (r.year() > max) max = r.year(); }
        return max;
    }

    private Integer extractMonth(String question) {
        Matcher m = MONTH_PATTERN.matcher(question.toLowerCase());
        if (!m.find()) return null;
        return parseMonthName(m.group(1));
    }

    /**
     * Extracts up to two distinct month numbers from the question, in the order they appear.
     * Used to detect range queries such as "from March to April".
     * Preserves the original extractMonth() signature so existing callers and tests are unaffected.
     */
    private List<Integer> extractMonths(String question) {
        Matcher m = MONTH_PATTERN.matcher(question.toLowerCase());
        List<Integer> found = new ArrayList<>();
        while (m.find()) {
            int month = parseMonthName(m.group(1));
            if (month > 0 && !found.contains(month)) found.add(month);
            if (found.size() == 2) break;
        }
        return found;
    }

    /** Counts Mon–Fri days in the inclusive range [start, end]. */
    private long countWorkingDays(LocalDate start, LocalDate end) {
        long count = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            if (d.getDayOfWeek().getValue() <= 5) count++;
        }
        return count;
    }

    private int parseMonthName(String name) {
        switch (name.toLowerCase()) {
            case "jan": case "january":   return 1;
            case "feb": case "february":  return 2;
            case "mar": case "march":     return 3;
            case "apr": case "april":     return 4;
            case "may":                   return 5;
            case "jun": case "june":      return 6;
            case "jul": case "july":      return 7;
            case "aug": case "august":    return 8;
            case "sep": case "september": return 9;
            case "oct": case "october":   return 10;
            case "nov": case "november":  return 11;
            case "dec": case "december":  return 12;
            default: return 0;
        }
    }

    /**
     * Attempts to extract a specific calendar date from the question.
     * Supports: "02 March 2026", "March 2nd 2026", "March 2, 2026",
     *           "2026-03-02", "02/03/2026" (dd/mm/yyyy).
     * Returns null if no specific date is found.
     * The fallback year is supplied by the caller (already extracted from the question or data).
     */
    LocalDate extractSpecificDate(String question, int fallbackYear) {
        Matcher m = SPECIFIC_DATE_PATTERN.matcher(question);
        if (!m.find()) return null;
        try {
            // Group layout (1-based):
            // Alt 1 (dd Month [yyyy]): g1=day, g2=monthName, g3=year
            // Alt 2 (Month dd [yyyy]): g4=monthName, g5=day, g6=year
            // Alt 3 (yyyy-mm-dd):      g7=year, g8=month, g9=day
            // Alt 4 (dd/mm/yyyy):      g10=day, g11=month, g12=year
            if (m.group(1) != null) {
                int day   = Integer.parseInt(m.group(1));
                int month = parseMonthName(m.group(2));
                int year  = m.group(3) != null ? Integer.parseInt(m.group(3)) : fallbackYear;
                return LocalDate.of(year, month, day);
            } else if (m.group(4) != null) {
                int month = parseMonthName(m.group(4));
                int day   = Integer.parseInt(m.group(5));
                int year  = m.group(6) != null ? Integer.parseInt(m.group(6)) : fallbackYear;
                return LocalDate.of(year, month, day);
            } else if (m.group(7) != null) {
                int year  = Integer.parseInt(m.group(7));
                int month = Integer.parseInt(m.group(8));
                int day   = Integer.parseInt(m.group(9));
                return LocalDate.of(year, month, day);
            } else if (m.group(10) != null) {
                int day   = Integer.parseInt(m.group(10));
                int month = Integer.parseInt(m.group(11));
                int year  = Integer.parseInt(m.group(12));
                return LocalDate.of(year, month, day);
            }
        } catch (Exception e) {
            log.debug("extractSpecificDate: could not parse date from '{}': {}", question, e.getMessage());
        }
        return null;
    }

    // ── Date-range helpers ────────────────────────────────────────────────────

    /**
     * Returns true when the question is a cross-employee date-range query
     * ("who are on leave from 15 March 2026 to 31 August 2026") rather than a
     * per-employee query or a single-date query.
     *
     * Conditions for true:
     *   1. No specific employee name can be resolved (it's not about one person).
     *   2. DATE_RANGE_PATTERN matches — i.e. the question contains "from <date> to <date>".
     */
    public boolean isDateRangeQuery(String question, List<LeaveRecord> allRecords) {
        if (resolveEmployeeName(question, allRecords) != null) return false;
        return DATE_RANGE_PATTERN.matcher(question).find();
    }

    /**
     * Extracts the from/to date pair from a question matching DATE_RANGE_PATTERN.
     * Returns a two-element array [fromDate, toDate], or null if parsing fails.
     * Each token is parsed by the existing extractSpecificDate() logic.
     * The fallbackYear is used when a date token omits the year.
     */
    public LocalDate[] extractDateRange(String question, int fallbackYear) {
        Matcher m = DATE_RANGE_PATTERN.matcher(question);
        if (!m.find()) return null;
        try {
            String fromToken = m.group(1).trim();
            String toToken   = m.group(2).trim();
            LocalDate from = extractSpecificDate(fromToken, fallbackYear);
            LocalDate to   = extractSpecificDate(toToken,   fallbackYear);
            if (from == null || to == null) return null;
            // Normalise so from <= to
            if (from.isAfter(to)) { LocalDate tmp = from; from = to; to = tmp; }
            return new LocalDate[]{from, to};
        } catch (Exception e) {
            log.debug("extractDateRange: could not parse range from '{}': {}", question, e.getMessage());
            return null;
        }
    }

    /**
     * Builds a DATE-RANGE-QUERY context: scans all records and collects every employee
     * whose leave span overlaps [rangeFrom, rangeTo] (type ≠ A).
     * Overlap condition: record.startDate ≤ rangeTo AND record.endDate ≥ rangeFrom.
     *
     * Returns a self-contained JSON context with:
     *   query_type=date_range_query, range_start, range_end, employees_on_leave
     *   (array with name/leave_type/start/end/reason), employee counts.
     */
    String buildContextForDateRange(List<LeaveRecord> allRecords, LocalDate rangeFrom, LocalDate rangeTo) {
        // Per-employee: track both the ordered set of distinct leave types AND a count per type.
        // leave_type_counts: employee name → (leave type → number of overlapping records of that type)
        Map<String, Map<String, Integer>> typeCountsByEmployee = new LinkedHashMap<>();
        Set<String> allNames = new LinkedHashSet<>();

        for (LeaveRecord r : allRecords) {
            allNames.add(r.employeeName());
        }

        for (LeaveRecord r : allRecords) {
            String lt = r.leaveType();
            if (lt == null) continue;
            String ltTrimmed = lt.trim();
            if ("A".equalsIgnoreCase(ltTrimmed) || "Available".equalsIgnoreCase(ltTrimmed)) continue;
            // Overlap: record's span intersects [rangeFrom, rangeTo]
            if (!r.startDate().isAfter(rangeTo) && !r.endDate().isBefore(rangeFrom)) {
                String name = r.employeeName();
                typeCountsByEmployee.putIfAbsent(name, new LinkedHashMap<>());
                Map<String, Integer> counts = typeCountsByEmployee.get(name);
                counts.merge(ltTrimmed, 1, Integer::sum);
            }
        }

        // Build the employees_on_leave array from the aggregated counts.
        List<Map<String, Object>> onLeave = new ArrayList<>();
        for (Map.Entry<String, Map<String, Integer>> e : typeCountsByEmployee.entrySet()) {
            String name = e.getKey();
            Map<String, Integer> counts = e.getValue();
            // leave_type: comma-separated distinct type names (for simple display)
            String leaveTypeStr = String.join(", ", counts.keySet());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("employee_name",      name);
            entry.put("leave_type",         leaveTypeStr);
            entry.put("leave_type_counts",  counts);   // e.g. {"Vacation":1,"Public holiday":2}
            onLeave.add(entry);
        }

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("query_type",                  "date_range_query");
        ctx.put("range_start",                 rangeFrom.toString());
        ctx.put("range_end",                   rangeTo.toString());
        ctx.put("range_start_display",         rangeFrom.getDayOfMonth() + " " +
                rangeFrom.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + rangeFrom.getYear());
        ctx.put("range_end_display",           rangeTo.getDayOfMonth() + " " +
                rangeTo.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + rangeTo.getYear());
        ctx.put("today",                       LocalDate.now().toString());
        ctx.put("employees_on_leave",          onLeave);
        ctx.put("employees_on_leave_count",    onLeave.size());
        ctx.put("total_employees_checked",     allNames.size());
        ctx.put("employees_not_on_leave_count", allNames.size() - onLeave.size());
        try { return mapper.writeValueAsString(ctx); } catch (Exception e) { return "{}"; }
    }

    /**
     * Builds the structured table data for a date-range query.
     * Returns one row per (employee, month) combination within [rangeFrom, rangeTo].
     * Each row contains: employee_name, month (display name), total_vacation (proportional
     * working-day count for that month, excluding Available), and leave_types
     * (comma-separated distinct leave types in that month).
     *
     * Days are distributed proportionally across months for records that span a month
     * boundary, using the same working-day ratio used by buildContextForEmployee /
     * LeaveAnalysisService so the table values are consistent with the LLM response.
     */
    public List<Map<String, Object>> buildTableDataForDateRange(List<LeaveRecord> allRecords,
                                                                 LocalDate rangeFrom, LocalDate rangeTo) {
        // Collect distinct months in the range (in order)
        List<java.time.YearMonth> months = new ArrayList<>();
        java.time.YearMonth ym = java.time.YearMonth.from(rangeFrom);
        java.time.YearMonth ymEnd = java.time.YearMonth.from(rangeTo);
        while (!ym.isAfter(ymEnd)) {
            months.add(ym);
            ym = ym.plusMonths(1);
        }

        // Per-employee per-month: days accumulated as double (proportional for cross-month spans)
        // Use a LinkedHashMap to preserve employee encounter order
        Map<String, Map<java.time.YearMonth, double[]>> totalByEmpMonth = new LinkedHashMap<>();
        Map<String, Map<java.time.YearMonth, Set<String>>> typesByEmpMonth = new LinkedHashMap<>();

        for (LeaveRecord r : allRecords) {
            String lt = r.leaveType();
            if (lt == null) continue;
            String ltTrimmed = lt.trim();
            if ("A".equalsIgnoreCase(ltTrimmed) || "Available".equalsIgnoreCase(ltTrimmed)) continue;
            // Overlap: record's span intersects [rangeFrom, rangeTo]
            if (r.startDate().isAfter(rangeTo) || r.endDate().isBefore(rangeFrom)) continue;

            String name = r.employeeName();
            totalByEmpMonth.putIfAbsent(name, new LinkedHashMap<>());
            typesByEmpMonth.putIfAbsent(name, new LinkedHashMap<>());

            long totalWd = countWorkingDays(r.startDate(), r.endDate());

            // Distribute this record's days proportionally across each month it overlaps
            for (java.time.YearMonth m : months) {
                LocalDate mStart = m.atDay(1);
                LocalDate mEnd   = m.atEndOfMonth();
                if (r.startDate().isAfter(mEnd) || r.endDate().isBefore(mStart)) continue;

                // Overlap of record with this month
                LocalDate oStart = r.startDate().isBefore(mStart) ? mStart : r.startDate();
                LocalDate oEnd   = r.endDate().isAfter(mEnd)      ? mEnd   : r.endDate();
                long overlapWd = countWorkingDays(oStart, oEnd);
                double share = totalWd > 0 ? r.days() * ((double) overlapWd / totalWd) : 0.0;
                if (share <= 0) continue;

                Map<java.time.YearMonth, double[]> totals = totalByEmpMonth.get(name);
                totals.putIfAbsent(m, new double[]{0.0});
                totals.get(m)[0] += share;

                Map<java.time.YearMonth, Set<String>> types = typesByEmpMonth.get(name);
                types.putIfAbsent(m, new LinkedHashSet<>());
                types.get(m).add(ltTrimmed);
            }
        }

        // Build result rows: one row per (employee, month) with leave, ordered by employee then month.
        // Round the day total to the nearest integer for display.
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String name : totalByEmpMonth.keySet()) {
            Map<java.time.YearMonth, double[]> totals = totalByEmpMonth.get(name);
            Map<java.time.YearMonth, Set<String>> types = typesByEmpMonth.get(name);
            for (java.time.YearMonth m : months) {
                if (!totals.containsKey(m)) continue;
                long total = Math.round(totals.get(m)[0]);
                if (total <= 0) continue;
                Set<String> leaveTypes = types.getOrDefault(m, Collections.emptySet());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("employee_name",  name);
                row.put("month",          m.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH));
                row.put("total_vacation", total);
                row.put("leave_types",    String.join(", ", leaveTypes));
                rows.add(row);
            }
        }
        return rows;
    }

    /**
     * Builds a DATE-QUERY context: scans all records and collects every employee
     * whose leave span covers the requested date (start_date ≤ date ≤ end_date, type ≠ A).
     * Returns a self-contained JSON context with:
     *   query_date, employees_on_leave (array with name/leave_type/start/end/reason),
     *   employees_not_on_leave_count, total_employees_checked.
     */
    String buildContextForDate(List<LeaveRecord> allRecords, LocalDate date) {
        // Use LinkedHashMap to preserve insertion order and deduplicate by employee name.
        // An employee may have multiple leave spans covering the same date (e.g. a multi-day
        // vacation span followed immediately by a public holiday span). We emit one entry per
        // employee, collecting all matching leave types as a comma-separated list.
        Map<String, Map<String, Object>> onLeaveByEmployee = new LinkedHashMap<>();
        Set<String> allNames = new LinkedHashSet<>();

        for (LeaveRecord r : allRecords) {
            if (r.year() != date.getYear()) continue;
            allNames.add(r.employeeName());
        }
        for (LeaveRecord r : allRecords) {
            if (r.year() != date.getYear()) continue;
            // Exclude Available (A) — also handle full labels "Available" and "  Available"
            String lt = r.leaveType();
            if (lt == null) continue;
            String ltTrimmed = lt.trim();
            if ("A".equalsIgnoreCase(ltTrimmed) || "Available".equalsIgnoreCase(ltTrimmed)) continue;
            if (!r.startDate().isAfter(date) && !r.endDate().isBefore(date)) {
                String name = r.employeeName();
                if (!onLeaveByEmployee.containsKey(name)) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("employee_name", name);
                    entry.put("leave_type",    ltTrimmed);
                    entry.put("start_date",    r.startDate().toString());
                    entry.put("end_date",      r.endDate().toString());
                    entry.put("days",          r.days());
                    entry.put("reason",        r.reason());
                    onLeaveByEmployee.put(name, entry);
                } else {
                    // Employee already has an entry — append additional leave type if different
                    Map<String, Object> existing = onLeaveByEmployee.get(name);
                    String existingType = (String) existing.get("leave_type");
                    if (!existingType.contains(ltTrimmed)) {
                        existing.put("leave_type", existingType + ", " + ltTrimmed);
                    }
                }
            }
        }
        List<Map<String, Object>> onLeave = new ArrayList<>(onLeaveByEmployee.values());
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("query_type",                  "date_query");
        ctx.put("query_date",                  date.toString());
        ctx.put("query_date_display",          date.getDayOfMonth() + " " +
                date.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + date.getYear());
        ctx.put("today",                       LocalDate.now().toString());
        ctx.put("employees_on_leave",          onLeave);
        ctx.put("employees_on_leave_count",    onLeave.size());
        ctx.put("total_employees_checked",     allNames.size());
        ctx.put("employees_not_on_leave_count", allNames.size() - onLeave.size());
        try { return mapper.writeValueAsString(ctx); } catch (Exception e) { return "{}"; }
    }
}
