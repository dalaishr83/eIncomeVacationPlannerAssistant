package com.holidayleave.assistant.service;

import com.holidayleave.assistant.excel.PlannerExcelReader;
import com.holidayleave.assistant.model.ForecastRow;
import com.holidayleave.assistant.model.LeaveRecord;
import com.holidayleave.assistant.model.VacationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Generates the Team Vacation Forecast report.
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>Resolve which master Excel year-file(s) are needed for the requested date range.</li>
 *   <li>Load records from those files via {@link PlannerExcelReader}.</li>
 *   <li>Filter by team (Indian Team employees from employee-mapping.json, or all employees for
 *       EIndkomst Team).</li>
 *   <li>Calculate per-employee-per-month forecast metrics (vacation days in range, full-year
 *       entitlement, consumed-to-today, remaining, utilization %).</li>
 *   <li>Produce a self-contained HTML fragment that the controller embeds in the JSON response.</li>
 * </ol>
 */
@Service
public class TeamForecastService {

    private static final Logger log = LoggerFactory.getLogger(TeamForecastService.class);

    /** Name pattern for the master vacation planner files. */
    private static final String MASTER_FILE_PATTERN = "eIndkomst vacation %d.xlsx";

    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("dd MMM yyyy");
    private static final DateTimeFormatter MONTH_YEAR   = DateTimeFormatter.ofPattern("MMMM yyyy");

    /** Vacation type code that is structural/placeholder (not a real leave event). */
    private static final String CODE_AVAILABLE = "A";

    @Autowired private AppState               appState;
    @Autowired private PlannerExcelReader      reader;
    @Autowired private HolidaySettingsService  holidaySettingsService;
    @Autowired private VacationTypeService     vacationTypeService;

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Result DTO returned by {@link #generateForecast}.
     */
    public static final class TeamForecastResult {
        private final String html;
        private final int    rowCount;
        private final String summaryText;
        /** Plain-text mrkdwn table for inline inclusion in the Slack notification. */
        private final String slackTableText;

        public TeamForecastResult(String html, int rowCount, String summaryText, String slackTableText) {
            this.html           = html;
            this.rowCount       = rowCount;
            this.summaryText    = summaryText;
            this.slackTableText = slackTableText;
        }

        public String getHtml()            { return html; }
        public int    getRowCount()         { return rowCount; }
        public String getSummaryText()     { return summaryText; }
        public String getSlackTableText()  { return slackTableText; }
    }

    /**
     * Generates the Team Vacation Forecast for the given team and date range.
     *
     * @param team      "Indian Team" or "EIndkomst Team"
     * @param startDate inclusive start of the reporting period
     * @param endDate   inclusive end of the reporting period
     * @return {@link TeamForecastResult} containing the HTML fragment and summary
     * @throws IOException if a required master Excel file cannot be read
     */
    public TeamForecastResult generateForecast(String team,
                                               LocalDate startDate,
                                               LocalDate endDate) throws IOException {

        // 1. Determine which year files are required
        List<String> masterPaths = resolveMasterPaths(startDate, endDate);

        // 2. Load all records from the required files
        List<LeaveRecord> allRecords = new ArrayList<>();
        for (String path : masterPaths) {
            log.debug("TeamForecastService: loading {}", path);
            allRecords.addAll(reader.load(path));
        }

        // 3. Filter to the requested team
        List<LeaveRecord> teamRecords = filterByTeam(allRecords, team);

        // 4. Build forecast rows
        List<ForecastRow> rows = buildForecastRows(teamRecords, allRecords, startDate, endDate);

        // 5. Build HTML report fragment
        String html = buildHtmlFragment(rows, team, startDate, endDate);

        // 6. Build plain-text Slack table for inline notification
        String slackTableText = buildSlackTableText(rows, team, startDate, endDate);

        // 7. Build plain-text summary for Slack
        String summary = buildSummaryText(team, startDate, endDate, rows.size());

        return new TeamForecastResult(html, rows.size(), summary, slackTableText);
    }

    // ── Master file resolution ─────────────────────────────────────────────────

    /**
     * Returns the absolute paths of the master Excel files that cover the supplied date range.
     * One file per calendar year spanned by [startDate, endDate].
     *
     * @throws IOException if any required file is missing
     */
    private List<String> resolveMasterPaths(LocalDate startDate, LocalDate endDate) throws IOException {
        List<String> paths = new ArrayList<>();
        for (int year = startDate.getYear(); year <= endDate.getYear(); year++) {
            String filename = String.format(MASTER_FILE_PATTERN, year);
            String path = Paths.get(appState.getDataDir(), filename).toAbsolutePath().toString();
            if (!new File(path).exists()) {
                throw new IOException("Master Excel file for year " + year + " not found: " + filename
                        + ". Please provision it first via Provision Master Excel.");
            }
            paths.add(path);
        }
        return paths;
    }

    // ── Team filtering ─────────────────────────────────────────────────────────

    /**
     * Filters records to those belonging to the selected team.
     * <ul>
     *   <li><b>Indian Team</b> — only employees listed in employee-mapping.json (country=IN).</li>
     *   <li><b>EIndkomst Team</b> — all employees, no filtering applied.</li>
     * </ul>
     */
    private List<LeaveRecord> filterByTeam(List<LeaveRecord> allRecords, String team) throws IOException {
        if ("EIndkomst Team".equalsIgnoreCase(team)) {
            // No filtering — the whole team (all employees in the master files)
            return new ArrayList<>(allRecords);
        }

        // Indian Team — use employee-mapping.json as the authoritative roster
        Set<String> indianEmployees = readIndianTeamEmployees();
        if (indianEmployees.isEmpty()) {
            log.warn("TeamForecastService: employee-mapping.json is empty or missing; no Indian Team records will be returned.");
        }

        List<LeaveRecord> result = new ArrayList<>();
        for (LeaveRecord r : allRecords) {
            if (indianEmployees.contains(r.employeeName())) {
                result.add(r);
            }
        }
        return result;
    }

    /**
     * Reads the Indian team employee names from employee-mapping.json via
     * {@link HolidaySettingsService}. The keys of the {@code "employees"} map are the names.
     */
    @SuppressWarnings("unchecked")
    private Set<String> readIndianTeamEmployees() {
        Map<String, Object> mapping = holidaySettingsService.readMapping(appState.getDataDir());
        Object raw = mapping.get("employees");
        if (!(raw instanceof Map)) return Collections.emptySet();
        Map<String, Object> empMap = (Map<String, Object>) raw;
        return new LinkedHashSet<>(empMap.keySet());
    }

    // ── Forecast row calculation ───────────────────────────────────────────────

    /**
     * Builds the list of {@link ForecastRow} objects.
     *
     * <p>Grouping: one row per (employee, year-month) combination that falls within [startDate, endDate].
     *
     * @param teamRecords  already-filtered records for the selected team (used for "vacations in range")
     * @param allRecords   full unfiltered record set (used for entitlement and consumed calculations)
     */
    private List<ForecastRow> buildForecastRows(List<LeaveRecord> teamRecords,
                                                List<LeaveRecord> allRecords,
                                                LocalDate startDate,
                                                LocalDate endDate) {
        // Collect all valid (non-Available) vacation type codes
        Set<String> validCodes = getValidVacationCodes();

        // Group by (employee, yearMonth) counting only the working days that fall within
        // [startDate, endDate].  A single LeaveRecord may span many months (the Excel reader
        // merges contiguous same-code cells into one run), so we:
        //   1. Clip the record to the intersection with the requested window.
        //   2. Derive the month key from clippedStart (not from r.startDate()).
        //   3. Split clips that cross a calendar-month boundary so each month gets
        //      its own accurate working-day count.
        Map<String, Map<String, Double>> vacsByEmployeeMonth = new LinkedHashMap<>();
        for (LeaveRecord r : teamRecords) {
            String code = extractCode(r.leaveType());
            if (!validCodes.contains(code)) continue;
            // Skip records that don't overlap the requested window at all
            if (r.endDate().isBefore(startDate) || r.startDate().isAfter(endDate)) continue;

            // Clip to the window
            LocalDate clippedStart = r.startDate().isBefore(startDate) ? startDate : r.startDate();
            LocalDate clippedEnd   = r.endDate().isAfter(endDate)      ? endDate   : r.endDate();

            // Walk day-by-day through the clipped span, accumulating working days per month
            LocalDate cur = clippedStart;
            while (!cur.isAfter(clippedEnd)) {
                // Skip weekends (the Excel reader also skips weekends when counting days)
                int dow = cur.getDayOfWeek().getValue(); // 1=Mon … 7=Sun
                if (dow <= 5) {
                    String empKey   = r.employeeName();
                    String monthKey = cur.format(MONTH_YEAR);
                    vacsByEmployeeMonth
                            .computeIfAbsent(empKey, k -> new LinkedHashMap<>())
                            .merge(monthKey, 1.0, Double::sum);
                }
                cur = cur.plusDays(1);
            }
        }

        // Pre-compute per-employee entitlement and consumed values (for all relevant years)
        // so we don't recalculate for every row
        LocalDate today = LocalDate.now();
        Map<String, Double> entitlementByEmp = new LinkedHashMap<>();
        Map<String, Double> consumedByEmp    = new LinkedHashMap<>();

        Set<String> employees = new LinkedHashSet<>(vacsByEmployeeMonth.keySet());

        for (String emp : employees) {
            // Entitlement = sum of ALL records for this employee across all years in the range
            double entitled = 0.0;
            double consumed = 0.0;

            for (int year = startDate.getYear(); year <= endDate.getYear(); year++) {
                final int y = year;
                for (LeaveRecord r : allRecords) {
                    if (!r.employeeName().equalsIgnoreCase(emp)) continue;
                    if (r.year() != y) continue;
                    String code = extractCode(r.leaveType());
                    if (!validCodes.contains(code)) continue;
                    entitled += r.days();
                    // Consumed = records whose startDate is on or before today
                    if (!r.startDate().isAfter(today)) {
                        consumed += r.days();
                    }
                }
            }

            entitlementByEmp.put(emp, entitled);
            consumedByEmp.put(emp, consumed);
        }

        // Build the final rows in order: employees appear in the order they first showed up,
        // months in calendar order within each employee
        List<ForecastRow> rows = new ArrayList<>();
        for (Map.Entry<String, Map<String, Double>> empEntry : vacsByEmployeeMonth.entrySet()) {
            String emp      = empEntry.getKey();
            double entitled = entitlementByEmp.getOrDefault(emp, 0.0);
            double consumed = consumedByEmp.getOrDefault(emp, 0.0);
            double remaining = Math.max(0.0, entitled - consumed);
            double utilPct   = entitled > 0 ? (consumed / entitled) * 100.0 : 0.0;

            // Sort months chronologically
            List<Map.Entry<String, Double>> monthEntries = new ArrayList<>(empEntry.getValue().entrySet());
            monthEntries.sort(Comparator.comparing(e -> parseMonthYear(e.getKey())));

            for (Map.Entry<String, Double> monthEntry : monthEntries) {
                rows.add(new ForecastRow(
                        emp,
                        monthEntry.getKey(),
                        monthEntry.getValue(),
                        entitled,
                        consumed,
                        remaining,
                        utilPct
                ));
            }
        }
        return rows;
    }

    /**
     * Returns the set of valid (meaningful) vacation type codes from vacation_types.json,
     * excluding the structural "Available" (A) code.
     */
    private Set<String> getValidVacationCodes() {
        Set<String> codes = new LinkedHashSet<>();
        for (VacationType vt : vacationTypeService.findAll()) {
            if (!CODE_AVAILABLE.equalsIgnoreCase(vt.code())) {
                codes.add(vt.code().toUpperCase());
            }
        }
        return codes;
    }

    /**
     * Extracts the short leave-type code from a label/code string.
     * If the stored value is already a code (short, no spaces), return it upper-cased.
     * Otherwise try to look it up by label.
     */
    private String extractCode(String leaveType) {
        if (leaveType == null) return "";
        String trimmed = leaveType.trim();
        // If it contains no spaces and is short (≤4 chars) treat it as a code directly
        if (!trimmed.contains(" ") && trimmed.length() <= 4) return trimmed.toUpperCase();
        // Try to find matching code by label
        for (VacationType vt : vacationTypeService.findAll()) {
            if (vt.label().equalsIgnoreCase(trimmed)) return vt.code().toUpperCase();
        }
        return trimmed.toUpperCase();
    }

    /** Parses a "MMMM yyyy" string into a LocalDate (always first of month) for sorting. */
    private static LocalDate parseMonthYear(String monthYear) {
        try {
            return LocalDate.parse("01 " + monthYear, DateTimeFormatter.ofPattern("dd MMMM yyyy"));
        } catch (Exception e) {
            return LocalDate.MIN;
        }
    }

    // ── HTML fragment builder ──────────────────────────────────────────────────

    private String buildHtmlFragment(List<ForecastRow> rows, String team,
                                     LocalDate startDate, LocalDate endDate) {
        LocalDate now = LocalDate.now();
        DateTimeFormatter genFmt = DateTimeFormatter.ofPattern("dd MMMM yyyy, HH:mm");
        String generatedAt = java.time.LocalDateTime.now().format(genFmt);

        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"tf-report-header\">")
          .append("<p class=\"tf-report-title\">Team Vacation Forecast Report</p>")
          .append("<p class=\"tf-report-meta\">")
          .append("<strong>Team:</strong> ").append(escHtml(team))
          .append(" &nbsp;|&nbsp; <strong>Period:</strong> ")
          .append(escHtml(startDate.format(DISPLAY_DATE)))
          .append(" &ndash; ")
          .append(escHtml(endDate.format(DISPLAY_DATE)))
          .append(" &nbsp;|&nbsp; <strong>Generated:</strong> ")
          .append(escHtml(generatedAt))
          .append(" &nbsp;|&nbsp; <strong>Consumed figures as of:</strong> ")
          .append(escHtml(now.format(DISPLAY_DATE)))
          .append("</p></div>");

        if (rows.isEmpty()) {
            sb.append("<p class=\"tf-no-records\">No vacation records found for the selected criteria.</p>");
            return sb.toString();
        }

        sb.append("<table class=\"tf-report-table\"><thead><tr>")
          .append("<th>Employee Name</th>")
          .append("<th>Month</th>")
          .append("<th class=\"tf-num\">Total Vacation(s)</th>")
          .append("<th class=\"tf-num\">Total Entitled Holidays</th>")
          .append("<th class=\"tf-num\">Total Consumed</th>")
          .append("<th class=\"tf-num\">Total Remaining</th>")
          .append("<th class=\"tf-num\">Utilization (%)</th>")
          .append("</tr></thead><tbody>");

        for (ForecastRow row : rows) {
            double util = row.getUtilizationPct();
            String utilClass = util >= 80 ? "tf-util-high"
                             : util >= 50 ? "tf-util-mid"
                             : "tf-util-low";

            sb.append("<tr>")
              .append("<td>").append(escHtml(row.getEmployeeName())).append("</td>")
              .append("<td>").append(escHtml(row.getMonth())).append("</td>")
              .append("<td class=\"tf-num\">").append(fmt(row.getTotalVacations())).append("</td>")
              .append("<td class=\"tf-num\">").append(fmt(row.getTotalEntitled())).append("</td>")
              .append("<td class=\"tf-num\">").append(fmt(row.getTotalConsumed())).append("</td>")
              .append("<td class=\"tf-num\">").append(fmt(row.getTotalRemaining())).append("</td>")
              .append("<td class=\"tf-num\"><span class=\"").append(utilClass).append("\">")
              .append(String.format("%.1f%%", util))
              .append("</span></td>")
              .append("</tr>");
        }

        sb.append("</tbody></table>");
        return sb.toString();
    }

    // ── Slack inline table ─────────────────────────────────────────────────────

    /**
     * Builds a plain-text mrkdwn table suitable for inline inclusion in a Slack message.
     *
     * <p>The table is wrapped in a triple-backtick code fence so Slack renders it in
     * monospace.  Output is capped at 2900 characters (safely under Slack's 3000-char
     * block text limit); if the full table exceeds that, it is truncated with a note.
     */
    private String buildSlackTableText(List<ForecastRow> rows, String team,
                                       LocalDate startDate, LocalDate endDate) {
        DateTimeFormatter genFmt = DateTimeFormatter.ofPattern("dd MMM yyyy");
        String header = "Team: " + team
                + "  |  Period: " + startDate.format(DISPLAY_DATE)
                + " – " + endDate.format(DISPLAY_DATE)
                + "  |  As of: " + LocalDate.now().format(genFmt);

        if (rows.isEmpty()) {
            return "```\n" + header + "\n\nNo vacation records found for the selected criteria.\n```";
        }

        // Column widths (fixed so monospace aligns correctly)
        final int W_EMP  = 26;
        final int W_MON  = 16;
        final int W_VAC  =  8;
        final int W_ENT  =  9;
        final int W_CON  =  9;
        final int W_REM  =  9;
        final int W_UTL  =  7;

        String sep = repeat("-", W_EMP) + "+" + repeat("-", W_MON) + "+"
                + repeat("-", W_VAC) + "+" + repeat("-", W_ENT) + "+"
                + repeat("-", W_CON) + "+" + repeat("-", W_REM) + "+"
                + repeat("-", W_UTL);

        String colHeader = pad("Employee Name", W_EMP) + "|"
                + pad("Month", W_MON) + "|"
                + padL("Vacation", W_VAC) + "|"
                + padL("Entitled", W_ENT) + "|"
                + padL("Consumed", W_CON) + "|"
                + padL("Remaining", W_REM) + "|"
                + padL("Util%", W_UTL);

        StringBuilder table = new StringBuilder();
        table.append("```\n").append(header).append("\n\n")
             .append(colHeader).append("\n")
             .append(sep).append("\n");

        final int MAX_CHARS = 2900;
        int totalRows = rows.size();

        for (int i = 0; i < totalRows; i++) {
            ForecastRow row = rows.get(i);
            String line = pad(truncate(row.getEmployeeName(), W_EMP - 1), W_EMP) + "|"
                    + pad(truncate(row.getMonth(), W_MON - 1), W_MON) + "|"
                    + padL(fmt(row.getTotalVacations()), W_VAC) + "|"
                    + padL(fmt(row.getTotalEntitled()), W_ENT) + "|"
                    + padL(fmt(row.getTotalConsumed()), W_CON) + "|"
                    + padL(fmt(row.getTotalRemaining()), W_REM) + "|"
                    + padL(String.format("%.1f%%", row.getUtilizationPct()), W_UTL);

            // Check if appending this line would exceed limit (leave room for truncation note)
            if (table.length() + line.length() + 50 > MAX_CHARS) {
                table.append("... (").append(totalRows - i).append(" more rows — ")
                     .append(totalRows).append(" total)");
                break;
            }
            table.append(line).append("\n");
        }
        table.append("```");
        return table.toString();
    }

    /** Right-pads {@code s} to {@code width} characters. */
    private static String pad(String s, int width) {
        if (s == null) s = "";
        if (s.length() >= width) return s.substring(0, width);
        return s + repeat(" ", width - s.length());
    }

    /** Left-pads (right-aligns) {@code s} to {@code width} characters. */
    private static String padL(String s, int width) {
        if (s == null) s = "";
        if (s.length() >= width) return s.substring(0, width);
        return repeat(" ", width - s.length()) + s;
    }

    private static String repeat(String ch, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(ch);
        return sb.toString();
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 1) + "…";
    }

    // ── Slack summary text ─────────────────────────────────────────────────────

    private String buildSummaryText(String team, LocalDate startDate, LocalDate endDate, int rowCount) {
        return "Team: " + team
                + " | Period: " + startDate.format(DISPLAY_DATE) + " – " + endDate.format(DISPLAY_DATE)
                + " | Records: " + rowCount
                + " | Status: Report generated successfully"
                + " | Consumed figures as of: " + LocalDate.now().format(DISPLAY_DATE);
    }

    // ── Utility helpers ────────────────────────────────────────────────────────

    private static String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /** Formats a double as an integer if it is whole, or with one decimal place otherwise. */
    private static String fmt(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) return String.valueOf((long) v);
        return String.format("%.1f", v);
    }
}
