package com.holidayleave.assistant.service;

import com.holidayleave.assistant.excel.PlannerExcelReader;
import com.holidayleave.assistant.model.ForecastRow;
import com.holidayleave.assistant.model.LeaveRecord;
import com.holidayleave.assistant.model.VacationType;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.YearMonth;
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
 *   <li>Pivot per-employee vacation counts into one row per employee, with one column per month
 *       in the requested range. Months with no vacation show 0.</li>
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
        /** Pivoted forecast rows — one per employee. Used by the Excel writer. */
        private final List<ForecastRow> rows;
        /** Ordered month labels ("MMMM yyyy") for the requested date range. */
        private final List<String> months;
        /** Generated Excel report filename located under {dataDir}/temp/. */
        private final String excelFilename;

        public TeamForecastResult(String html, int rowCount, String summaryText, String slackTableText,
                                  List<ForecastRow> rows, List<String> months) {
            this(html, rowCount, summaryText, slackTableText, rows, months, null);
        }

        public TeamForecastResult(String html, int rowCount, String summaryText, String slackTableText,
                                  List<ForecastRow> rows, List<String> months, String excelFilename) {
            this.html           = html;
            this.rowCount       = rowCount;
            this.summaryText    = summaryText;
            this.slackTableText = slackTableText;
            this.rows           = rows;
            this.months         = months;
            this.excelFilename  = excelFilename;
        }

        public String getHtml()                   { return html; }
        public int    getRowCount()               { return rowCount; }
        public String getSummaryText()            { return summaryText; }
        public String getSlackTableText()         { return slackTableText; }
        public List<ForecastRow> getRows()        { return rows; }
        public List<String>      getMonths()      { return months; }
        public String getExcelFilename()          { return excelFilename; }
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

        // 4. Build the ordered list of months covered by the date range
        List<String> months = buildMonthList(startDate, endDate);

        // 5. Build pivoted forecast rows (one per employee)
        List<ForecastRow> rows = buildForecastRows(teamRecords, months, startDate, endDate);

        // 6. Build HTML report fragment
        String html = buildHtmlFragment(rows, months, team, startDate, endDate);

        // 7. Build plain-text Slack table for inline notification
        String slackTableText = buildSlackTableText(rows, months, team, startDate, endDate);

        // 8. Build plain-text summary for Slack
        String summary = buildSummaryText(team, startDate, endDate, rows.size());

        // 9. Generate and save Excel file to {dataDir}/temp/
        String excelFilename = null;
        try {
            String dataDir = appState != null && appState.getDataDir() != null
                    ? appState.getDataDir()
                    : "data";
            Path excelPath = writeForecastExcel(rows, months, team, startDate, endDate, dataDir);
            excelFilename = excelPath.getFileName().toString();
        } catch (Exception e) {
            log.warn("TeamForecastService: Failed to generate Excel report file upfront: {}", e.getMessage());
        }

        return new TeamForecastResult(html, rows.size(), summary, slackTableText, rows, months, excelFilename);
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

    // ── Month list ─────────────────────────────────────────────────────────────

    /**
     * Returns an ordered list of "MMMM yyyy" strings for every calendar month
     * that overlaps the [startDate, endDate] range.
     */
    private List<String> buildMonthList(LocalDate startDate, LocalDate endDate) {
        List<String> months = new ArrayList<>();
        YearMonth cur = YearMonth.from(startDate);
        YearMonth last = YearMonth.from(endDate);
        while (!cur.isAfter(last)) {
            months.add(cur.atDay(1).format(MONTH_YEAR));
            cur = cur.plusMonths(1);
        }
        return months;
    }

    // ── Forecast row calculation ───────────────────────────────────────────────

    /**
     * Builds the list of pivoted {@link ForecastRow} objects — one row per employee.
     * Every employee has an entry for every month in {@code months}; missing months are 0.
     *
     * @param teamRecords already-filtered records for the selected team
     * @param months      ordered list of "MMMM yyyy" labels for the date range
     */
    private List<ForecastRow> buildForecastRows(List<LeaveRecord> teamRecords,
                                                List<String> months,
                                                LocalDate startDate,
                                                LocalDate endDate) {
        // Collect all valid (non-Available) vacation type codes
        Set<String> validCodes = getValidVacationCodes();

        // Accumulate working days per (employee, month).
        // Using LinkedHashMap to preserve first-seen employee order.
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

        // Build pivoted rows — every employee gets an entry for every month in the range (0 if absent)
        List<ForecastRow> rows = new ArrayList<>();
        for (Map.Entry<String, Map<String, Double>> empEntry : vacsByEmployeeMonth.entrySet()) {
            String emp = empEntry.getKey();
            Map<String, Double> rawByMonth = empEntry.getValue();

            // Build an ordered map with all months present (0 for missing months)
            Map<String, Double> pivotMonths = new LinkedHashMap<>();
            double total = 0.0;
            for (String month : months) {
                double v = rawByMonth.getOrDefault(month, 0.0);
                pivotMonths.put(month, v);
                total += v;
            }

            rows.add(new ForecastRow(emp, pivotMonths, total));
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

    // ── Excel report writer ────────────────────────────────────────────────────

    /**
     * Generates a Team Vacation Forecast Excel workbook from pre-computed forecast data
     * and saves it to {@code {dataDir}/temp/}.
     *
     * <p>The layout matches the reference report:
     * <ul>
     *   <li>Row 1 — Title "Team Vacation Forecast Report"</li>
     *   <li>Row 2 — Meta line (team, period, as-of date)</li>
     *   <li>Row 3 — empty</li>
     *   <li>Row 4 — Column headers: Employee Name | &lt;month columns&gt; | Vacation</li>
     *   <li>Rows 5..N — Data rows (employee name, monthly day counts, total)</li>
     *   <li>Row N+1 — Total row</li>
     * </ul>
     *
     * @param rows      pivoted forecast rows already produced by {@link #generateForecast}
     * @param months    ordered month labels ("MMMM yyyy") for the date range
     * @param team      team name (for the meta row)
     * @param startDate period start (for the meta row)
     * @param endDate   period end (for the meta row)
     * @param dataDir   base data directory — the file is written to {@code dataDir/temp/}
     * @return absolute {@link Path} to the generated .xlsx file
     * @throws IOException if the temp directory cannot be created or the workbook cannot be written
     */
    /**
     * Housekeeping utility: deletes forecast Excel files in {@code {dataDir}/temp/}
     * that are older than the specified age threshold.
     *
     * @param dataDir       base data directory containing the temp/ folder
     * @param maxAgeMillis  maximum allowed age in milliseconds (e.g. 3,600,000 for 1 hour)
     * @return count of deleted files
     */
    public int cleanupForecastReport(String dataDir, long maxAgeMillis) {
        if (dataDir == null || dataDir.trim().isEmpty()) {
            dataDir = appState != null && appState.getDataDir() != null
                    ? appState.getDataDir()
                    : "data";
        }
        Path tempDir = Paths.get(dataDir, "temp");
        if (!Files.exists(tempDir) || !Files.isDirectory(tempDir)) {
            log.debug("cleanupForecastReport: temp directory '{}' does not exist or is not a directory", tempDir);
            return 0;
        }

        long now = System.currentTimeMillis();
        int deletedCount = 0;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(tempDir, "team-forecast-*.xlsx")) {
            for (Path file : stream) {
                try {
                    BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                    long fileTime = attrs.lastModifiedTime().toMillis();
                    long age = now - fileTime;

                    if (age > maxAgeMillis) {
                        Files.delete(file);
                        deletedCount++;
                        log.info("cleanupForecastReport: Deleted old forecast report '{}' (age: {}s)", file.getFileName(), age / 1000);
                    } else {
                        log.debug("cleanupForecastReport: Retaining recent forecast report '{}' (age: {}s)", file.getFileName(), age / 1000);
                    }
                } catch (Exception ex) {
                    log.warn("cleanupForecastReport: Failed to inspect/delete '{}': {}", file.getFileName(), ex.getMessage());
                }
            }
        } catch (Exception ex) {
            log.error("cleanupForecastReport: Failed to read temp directory '{}': {}", tempDir, ex.getMessage(), ex);
        }

        log.info("cleanupForecastReport completed: deleted {} file(s) from '{}'", deletedCount, tempDir);
        return deletedCount;
    }

    public Path writeForecastExcel(List<ForecastRow> rows,
                                   List<String> months,
                                   String team,
                                   LocalDate startDate,
                                   LocalDate endDate,
                                   String dataDir) throws IOException {

        // ── Ensure temp directory exists ──────────────────────────────────────
        Path tempDir = Paths.get(dataDir, "temp");
        Files.createDirectories(tempDir);

        String filename = "team-forecast-" + System.currentTimeMillis() + ".xlsx";
        Path outPath = tempDir.resolve(filename);

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sheet = wb.createSheet("Sheet1");

            // ── Styles ────────────────────────────────────────────────────────
            XSSFCellStyle titleStyle = wb.createCellStyle();
            XSSFFont titleFont = wb.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            titleStyle.setFont(titleFont);

            XSSFCellStyle metaStyle = wb.createCellStyle();
            XSSFFont metaFont = wb.createFont();
            metaFont.setFontHeightInPoints((short) 11);
            metaStyle.setFont(metaFont);

            XSSFCellStyle headerStyle = wb.createCellStyle();
            XSSFFont headerFont = wb.createFont();
            headerFont.setBold(true);
            headerFont.setColor(new XSSFColor(Color.WHITE, null));
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(new XSSFColor(new Color(31, 73, 125), null));
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            setBorders(headerStyle);

            XSSFCellStyle dataStyle = wb.createCellStyle();
            setBorders(dataStyle);

            XSSFCellStyle dataNumStyle = wb.createCellStyle();
            dataNumStyle.setAlignment(HorizontalAlignment.CENTER);
            setBorders(dataNumStyle);

            XSSFCellStyle totalLabelStyle = wb.createCellStyle();
            XSSFFont totalFont = wb.createFont();
            totalFont.setBold(true);
            totalLabelStyle.setFont(totalFont);
            totalLabelStyle.setFillForegroundColor(new XSSFColor(new Color(217, 225, 242), null));
            totalLabelStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            setBorders(totalLabelStyle);

            XSSFCellStyle totalNumStyle = wb.createCellStyle();
            totalNumStyle.setFont(totalFont);
            totalNumStyle.setAlignment(HorizontalAlignment.CENTER);
            totalNumStyle.setFillForegroundColor(new XSSFColor(new Color(217, 225, 242), null));
            totalNumStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            setBorders(totalNumStyle);

            // ── Row 1: Title ──────────────────────────────────────────────────
            XSSFRow titleRow = sheet.createRow(0);
            XSSFCell titleCell = titleRow.createCell(0);
            titleCell.setCellValue("Team Vacation Forecast Report");
            titleCell.setCellStyle(titleStyle);

            // ── Row 2: Meta ───────────────────────────────────────────────────
            XSSFRow metaRow = sheet.createRow(1);
            String metaText = "Team: " + team
                    + "  |  Period: " + startDate.format(DISPLAY_DATE)
                    + " \u2013 " + endDate.format(DISPLAY_DATE)
                    + "  |  As of: " + LocalDate.now().format(DISPLAY_DATE);
            XSSFCell metaCell = metaRow.createCell(0);
            metaCell.setCellValue(metaText);
            metaCell.setCellStyle(metaStyle);
            // Merge across all data columns (A–F or wider depending on month count)
            int lastCol = months.size() + 1; // Employee + months + Vacation
            sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, lastCol));

            // Row 3 is intentionally left empty (matches reference layout).

            // ── Row 4: Column headers ─────────────────────────────────────────
            XSSFRow headerRow = sheet.createRow(3);
            XSSFCell empHeader = headerRow.createCell(0);
            empHeader.setCellValue("Employee Name");
            empHeader.setCellStyle(headerStyle);

            for (int m = 0; m < months.size(); m++) {
                XSSFCell mCell = headerRow.createCell(m + 1);
                mCell.setCellValue(months.get(m));
                mCell.setCellStyle(headerStyle);
            }
            XSSFCell vacHeader = headerRow.createCell(months.size() + 1);
            vacHeader.setCellValue("Vacation");
            vacHeader.setCellStyle(headerStyle);

            // ── Data rows ─────────────────────────────────────────────────────
            double grandTotal = 0.0;
            int dataRowIndex = 4; // row 5 (0-based index 4)
            for (ForecastRow row : rows) {
                XSSFRow dataRow = sheet.createRow(dataRowIndex++);
                XSSFCell empCell = dataRow.createCell(0);
                empCell.setCellValue(row.getEmployeeName());
                empCell.setCellStyle(dataStyle);

                for (int m = 0; m < months.size(); m++) {
                    double v = row.getMonthVacations().getOrDefault(months.get(m), 0.0);
                    XSSFCell numCell = dataRow.createCell(m + 1);
                    numCell.setCellValue(v);
                    numCell.setCellStyle(dataNumStyle);
                }
                XSSFCell totalCell = dataRow.createCell(months.size() + 1);
                totalCell.setCellValue(row.getTotalVacations());
                totalCell.setCellStyle(dataNumStyle);
                grandTotal += row.getTotalVacations();
            }

            // ── Total row ─────────────────────────────────────────────────────
            XSSFRow totalRow = sheet.createRow(dataRowIndex);
            XSSFCell totalLabelCell = totalRow.createCell(0);
            totalLabelCell.setCellValue("Total");
            totalLabelCell.setCellStyle(totalLabelStyle);
            for (int m = 0; m < months.size(); m++) {
                XSSFCell blankCell = totalRow.createCell(m + 1);
                blankCell.setCellStyle(totalNumStyle);
            }
            XSSFCell grandTotalCell = totalRow.createCell(months.size() + 1);
            grandTotalCell.setCellValue(grandTotal);
            grandTotalCell.setCellStyle(totalNumStyle);

            // ── Column widths ─────────────────────────────────────────────────
            sheet.setColumnWidth(0, 35 * 256); // Employee Name
            for (int m = 0; m < months.size(); m++) {
                sheet.setColumnWidth(m + 1, 16 * 256);
            }
            sheet.setColumnWidth(months.size() + 1, 12 * 256); // Vacation total

            // ── Write to disk ─────────────────────────────────────────────────
            try (FileOutputStream fos = new FileOutputStream(outPath.toFile())) {
                wb.write(fos);
            }
        }

        log.info("Team forecast Excel report written: {}", outPath);
        return outPath;
    }

    /** Applies a thin border on all four sides of a cell style. */
    private static void setBorders(XSSFCellStyle style) {
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
    }

    // ── HTML fragment builder ──────────────────────────────────────────────────

    private String buildHtmlFragment(List<ForecastRow> rows, List<String> months, String team,
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
          .append(" &nbsp;|&nbsp; <strong>As of:</strong> ")
          .append(escHtml(now.format(DISPLAY_DATE)))
          .append("</p></div>");

        if (rows.isEmpty()) {
            sb.append("<p class=\"tf-no-records\">No vacation records found for the selected criteria.</p>");
            return sb.toString();
        }

        // ── Table header ──
        sb.append("<table class=\"tf-report-table\"><thead><tr>")
          .append("<th>Employee Name</th>");
        for (String month : months) {
            sb.append("<th class=\"tf-num\">").append(escHtml(month)).append("</th>");
        }
        sb.append("<th class=\"tf-num\">Vacation</th>")
          .append("</tr></thead><tbody>");

        // ── Data rows ──
        double grandTotal = 0.0;
        for (ForecastRow row : rows) {
            sb.append("<tr>")
              .append("<td>").append(escHtml(row.getEmployeeName())).append("</td>");
            for (String month : months) {
                double v = row.getMonthVacations().getOrDefault(month, 0.0);
                sb.append("<td class=\"tf-num\">").append(fmt(v)).append("</td>");
            }
            sb.append("<td class=\"tf-num\">").append(fmt(row.getTotalVacations())).append("</td>")
              .append("</tr>");
            grandTotal += row.getTotalVacations();
        }

        // ── Total row ──
        int colSpan = months.size();
        sb.append("<tr class=\"tf-total-row\">")
          .append("<td colspan=\"").append(1 + colSpan).append("\" class=\"tf-total-label\">Total</td>")
          .append("<td class=\"tf-num tf-total-value\">").append(fmt(grandTotal)).append("</td>")
          .append("</tr>");

        sb.append("</tbody></table>");
        return sb.toString();
    }

    // ── Slack inline table ─────────────────────────────────────────────────────

    /**
     * Builds a plain-text table suitable for uploading to Slack as a file snippet.
     *
     * <p>The table is wrapped in a triple-backtick code fence so it renders in monospace
     * when previewed inline.  There is no character limit here — the full table is always
     * returned and uploaded via the Slack Files API v2, which has no block-text restrictions.
     */
    private String buildSlackTableText(List<ForecastRow> rows, List<String> months, String team,
                                       LocalDate startDate, LocalDate endDate) {
        DateTimeFormatter genFmt = DateTimeFormatter.ofPattern("dd MMM yyyy");
        String header = "Team: " + team
                + "  |  Period: " + startDate.format(DISPLAY_DATE)
                + " – " + endDate.format(DISPLAY_DATE)
                + "  |  As of: " + LocalDate.now().format(genFmt);

        if (rows.isEmpty()) {
            return "```\n" + header + "\n\nNo vacation records found for the selected criteria.\n```";
        }

        // Column widths
        final int W_EMP = 26;
        final int W_MON = 14; // per month column
        final int W_VAC =  8; // Vacation total column

        // Build separator and column header
        StringBuilder sepSb = new StringBuilder(repeat("-", W_EMP)).append("+");
        StringBuilder hdrSb = new StringBuilder(pad("Employee Name", W_EMP)).append("|");
        for (String month : months) {
            // Use abbreviated month label (e.g. "Sep 2026") to save width
            String abbr = abbreviateMonth(month);
            sepSb.append(repeat("-", W_MON)).append("+");
            hdrSb.append(padL(abbr, W_MON)).append("|");
        }
        sepSb.append(repeat("-", W_VAC));
        hdrSb.append(padL("Vacation", W_VAC));

        StringBuilder table = new StringBuilder();
        table.append("```\n").append(header).append("\n\n")
             .append(hdrSb).append("\n")
             .append(sepSb).append("\n");

        double grandTotal = 0.0;
        for (ForecastRow row : rows) {
            StringBuilder line = new StringBuilder(pad(truncate(row.getEmployeeName(), W_EMP - 1), W_EMP)).append("|");
            for (String month : months) {
                double v = row.getMonthVacations().getOrDefault(month, 0.0);
                line.append(padL(fmt(v), W_MON)).append("|");
            }
            line.append(padL(fmt(row.getTotalVacations()), W_VAC));
            grandTotal += row.getTotalVacations();
            table.append(line).append("\n");
        }

        // Append total row
        table.append(sepSb).append("\n");
        String totalLine = pad("", W_EMP) + "|"
                + repeat(" ", W_MON * months.size() + months.size() - 1)
                + " Total: " + fmt(grandTotal);
        table.append(totalLine).append("\n");
        table.append("```");
        return table.toString();
    }

    /**
     * Converts a "MMMM yyyy" label to an abbreviated "MMM yyyy" (e.g. "September 2026" → "Sep 2026").
     */
    private static String abbreviateMonth(String monthYear) {
        try {
            LocalDate d = LocalDate.parse("01 " + monthYear, DateTimeFormatter.ofPattern("dd MMMM yyyy"));
            return d.format(DateTimeFormatter.ofPattern("MMM yyyy"));
        } catch (Exception e) {
            return monthYear;
        }
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
                + " | Employees: " + rowCount
                + " | Status: Report generated successfully"
                + " | As of: " + LocalDate.now().format(DISPLAY_DATE);
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
