package com.holidayleave.assistant.excel;

import com.holidayleave.assistant.model.LeaveRecord;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resilient horizontal-calendar planner .xlsx parser.
 *
 * Discovery strategy (no hard-coded row indices):
 *  1. Find the "day-number row": first row that contains ≥ 20 numeric values in range 1–31
 *     AND whose sequential pattern matches calendar days.
 *  2. The "month row" is any row at or above the day row that contains ≥ 3 month-name cells.
 *  3. The "employee data rows" start at day_row_index + 2 (one row for weekday letters).
 *  4. Year is extracted from month-row cell text, or from the file name, or defaults to current year.
 *
 * Leave code detection (in priority order):
 *  a. Explicit cell string value
 *  b. Fill colour matched against the legend sheet colour→label map
 */
@Component
public class PlannerExcelReader {

    private static final Logger log = LoggerFactory.getLogger(PlannerExcelReader.class);

    private static final Set<String> SKIP_COLORS = new HashSet<>(Arrays.asList(
            "00000000", "FFFFFFFF", "FFffffff", "000000",
            "FFFFB6C1", "FFD3D3D3", "FFE0E0E0", "FFF2F2F2"
    ));

    private static final Set<String> LEGEND_SHEET_NAMES = new HashSet<>(Arrays.asList(
            "sheet1", "color-code", "colour-code", "legend", "key", "codes"
    ));

    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(20\\d{2})\\b");

    // ── Record cache ──────────────────────────────────────────────────────────

    /**
     * Immutable cache entry pairing the parsed records with the file's
     * lastModified timestamp at parse time.  Both fields are set once on
     * construction and never mutated, so the object is safely shareable
     * across threads without additional synchronisation.
     *
     * <p>{@code allEmployeeNames} is the full structural name list read from
     * column 0 of the data rows, independent of whether any leave codes exist.
     * This ensures employees with zero leave entries are not lost.
     */
    private static final class CacheEntry {
        final List<LeaveRecord> records;         // Collections.unmodifiableList — callers cannot mutate
        final List<String>      allEmployeeNames; // unmodifiable — all names from col 0, regardless of leave
        final long timestamp;                    // File.lastModified() at parse time

        CacheEntry(List<LeaveRecord> records, List<String> allEmployeeNames, long timestamp) {
            this.records          = Collections.unmodifiableList(new ArrayList<>(records));
            this.allEmployeeNames = Collections.unmodifiableList(new ArrayList<>(allEmployeeNames));
            this.timestamp        = timestamp;
        }
    }

    /** Keyed by absolute file path (master copy only — working copy is never cached). */
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the parsed leave records for the given file.
     * Results are cached by (path, lastModified).  Callers receive an
     * unmodifiable view; use {@code new ArrayList<>(records)} if a mutable
     * copy is needed.
     *
     * <p>Cache is invalidated by:
     * <ol>
     *   <li>An explicit {@link #evict(String)} call (eager eviction after a
     *       confirmed write or sync).</li>
     *   <li>The file's {@code lastModified} timestamp changing on disk
     *       (self-invalidating timestamp check — belt-and-suspenders).</li>
     * </ol>
     */
    public List<LeaveRecord> load(String filePath) throws IOException {
        File f = new File(filePath);
        long currentMod = f.lastModified();
        CacheEntry entry = cache.get(filePath);
        if (entry != null && entry.timestamp == currentMod) {
            return entry.records;   // cache hit — unmodifiable list
        }
        ParseResult parsed = loadUncached(filePath);
        CacheEntry newEntry = new CacheEntry(parsed.records, parsed.allEmployeeNames, currentMod);
        cache.put(filePath, newEntry);
        return newEntry.records;
    }

    /**
     * Returns all employee names found in the name column (column 0) of the
     * planner sheet, regardless of whether they have any leave recorded.
     *
     * <p>This is the correct method to use when populating the sidebar employee
     * list or building the employee roster for the Chat window.  It includes
     * employees who have no leave codes in the file (e.g. a future-year planner
     * that has not yet been filled in), which {@link #getEmployeeNames(List)}
     * would miss because it derives names from leave records only.
     *
     * <p>Results are served from the same cache entry as {@link #load(String)},
     * so no second file open is needed.
     *
     * @param filePath absolute path to the master planner file
     * @return ordered, deduplicated list of all employee names in the sheet
     * @throws IOException if the file cannot be opened or parsed
     */
    public List<String> getEmployeeNames(String filePath) throws IOException {
        File f = new File(filePath);
        long currentMod = f.lastModified();
        CacheEntry entry = cache.get(filePath);
        if (entry != null && entry.timestamp == currentMod) {
            return entry.allEmployeeNames;  // cache hit
        }
        // Not yet cached — load() will populate the cache as a side effect.
        load(filePath);
        CacheEntry populated = cache.get(filePath);
        return populated != null ? populated.allEmployeeNames : Collections.emptyList();
    }

    /**
     * Returns all employee names from the planner sheet where the country-code
     * column (column index 1, i.e. Column B) equals {@code "IN"} (case-insensitive).
     *
     * <p>Uses the same layout detection as {@link #load(String)} so header rows
     * (month row, day row, weekday row) are correctly skipped.  Only data rows
     * starting at {@code dataStartRowIdx} are examined.
     *
     * @param filePath absolute path to the master planner file
     * @return ordered list of Indian employee names
     * @throws IOException if the file cannot be opened or parsed
     */
    public List<String> getIndianEmployeeNames(String filePath) throws IOException {
        List<String> result = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(filePath);
             XSSFWorkbook workbook = new XSSFWorkbook(fis)) {

            Sheet sheet = findPlannerSheet(workbook);
            if (sheet == null) return result;

            SheetLayout layout = detectLayout(sheet, filePath);
            if (layout == null) return result;

            for (int r = layout.dataStartRowIdx; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                Cell nameCell    = row.getCell(0);
                Cell countryCell = row.getCell(1);
                if (nameCell == null || countryCell == null) continue;
                String name    = getCellString(nameCell).trim();
                String country = getCellString(countryCell).trim();
                if (name.isEmpty() || country.isEmpty()) continue;
                if (isHeaderLike(name)) continue;
                if ("IN".equalsIgnoreCase(country)) {
                    result.add(name);
                }
            }
        }
        log.debug("getIndianEmployeeNames: {} IN employees found in {}", result.size(), filePath);
        return result;
    }

    /**
     * Evicts the cache entry for the given absolute file path.
     * Must be called with the <em>master</em> file path, not the working copy path.
     *
     * <p>Called from:
     * <ul>
     *   <li>{@code VacationController} and {@code ChatController} immediately after a
     *       confirmed write to the working copy (eager eviction).</li>
     *   <li>{@code SyncService} immediately after a successful atomic rename of the
     *       master file (confirmed eviction).</li>
     *   <li>{@code FileController.upload()} after the master file is replaced.</li>
     * </ul>
     */
    public void evict(String filePath) {
        cache.remove(filePath);
        log.debug("Cache evicted for: {}", filePath);
    }

    private ParseResult loadUncached(String filePath) throws IOException {
        try (FileInputStream fis = new FileInputStream(filePath);
             XSSFWorkbook workbook = new XSSFWorkbook(fis)) {

            Map<String, String> codeToLabel = new LinkedHashMap<>();
            Map<String, String> colorToLabel = new LinkedHashMap<>();
            loadLegend(workbook, codeToLabel, colorToLabel);

            Sheet plannerSheet = findPlannerSheet(workbook);
            if (plannerSheet == null) {
                throw new IOException("Could not find planner sheet in workbook");
            }

            SheetLayout layout = detectLayout(plannerSheet, filePath);
            if (layout == null) {
                // Dump diagnostic info to help debug
                logDiagnostics(plannerSheet, filePath);
                throw new IOException("Could not build date map from planner sheet. " +
                        "Check logs for sheet structure diagnostics.");
            }

            log.info("Planner layout detected: monthRow={} dayRow={} dataStartRow={} year={} cols={}",
                    layout.monthRowIdx, layout.dayRowIdx, layout.dataStartRowIdx,
                    layout.year, layout.colToDate.size());

            return parseEmployeeRows(plannerSheet, layout, codeToLabel, colorToLabel);
        }
    }

    public Integer detectYear(String filePath) throws IOException {
        try (FileInputStream fis = new FileInputStream(filePath);
             XSSFWorkbook workbook = new XSSFWorkbook(fis)) {
            Sheet sheet = findPlannerSheet(workbook);
            if (sheet != null) {
                // Scan first 10 rows for a year pattern
                for (int r = 0; r <= Math.min(9, sheet.getLastRowNum()); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    for (Cell cell : row) {
                        String val = getCellString(cell);
                        Matcher m = YEAR_PATTERN.matcher(val);
                        if (m.find()) return Integer.parseInt(m.group(1));
                    }
                }
            }
        }
        // Fallback: extract from filename
        Matcher m = YEAR_PATTERN.matcher(new File(filePath).getName());
        if (m.find()) return Integer.parseInt(m.group(1));
        return null;
    }

    /**
     * Derives a deduplicated employee name list from a pre-parsed record list.
     *
     * <p><b>Limitation:</b> employees who have no leave entries produce no records
     * and are therefore absent from this result.  Prefer
     * {@link #getEmployeeNames(String)} when working with a file path — it reads
     * the structural name column and includes every employee regardless of leave.
     *
     * <p>Retained for callers that already hold a {@code List<LeaveRecord>} and
     * do not have access to the originating file path (e.g. cross-file aggregations).
     */
    public List<String> getEmployeeNames(List<LeaveRecord> records) {
        List<String> names = new ArrayList<>();
        for (LeaveRecord r : records) {
            if (!names.contains(r.employeeName())) names.add(r.employeeName());
        }
        return names;
    }

    // ── Layout detection ──────────────────────────────────────────────────────

    /**
     * Scans the sheet to locate the day-number row, month row, year, and col→date map.
     * Returns null if the structure cannot be confidently determined.
     */
    private SheetLayout detectLayout(Sheet sheet, String filePath) {
        int lastRow = sheet.getLastRowNum();

        // Step 1: find the "day row" — first row with ≥ 20 cells containing integers 1–31
        int dayRowIdx = -1;
        for (int r = 0; r <= Math.min(lastRow, 15); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            if (countDayNumbers(row) >= 20) {
                dayRowIdx = r;
                break;
            }
        }
        if (dayRowIdx < 0) {
            log.warn("PlannerExcelReader: no day-number row found in first 16 rows");
            return null;
        }

        // Step 2: find the "month row" — any row at or above day row with ≥ 3 month names
        int monthRowIdx = -1;
        for (int r = dayRowIdx; r >= 0; r--) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            if (countMonthNames(row) >= 3) {
                monthRowIdx = r;
                break;
            }
        }
        if (monthRowIdx < 0) {
            log.warn("PlannerExcelReader: no month-name row found at or above day row {}", dayRowIdx);
            return null;
        }

        // Step 3: extract year
        int year = extractYearFromRows(sheet, monthRowIdx, dayRowIdx, filePath);

        // Step 4: build col → date map
        Map<Integer, LocalDate> colToDate = buildColToDateMap(
                sheet.getRow(monthRowIdx), sheet.getRow(dayRowIdx), year);

        if (colToDate.isEmpty()) {
            log.warn("PlannerExcelReader: col→date map is empty (monthRow={} dayRow={} year={})",
                    monthRowIdx, dayRowIdx, year);
            return null;
        }

        // Step 5: data rows start one row after day row (skip optional weekday-letter row)
        // We'll accept either dayRow+1 or dayRow+2 — pick whichever has more employee-like rows
        int dataStart = dayRowIdx + 1;
        // If the very next row looks like weekday letters (M/T/W etc.), skip it
        Row nextRow = sheet.getRow(dayRowIdx + 1);
        if (nextRow != null && looksLikeWeekdayRow(nextRow)) {
            dataStart = dayRowIdx + 2;
        }

        SheetLayout layout = new SheetLayout();
        layout.monthRowIdx    = monthRowIdx;
        layout.dayRowIdx      = dayRowIdx;
        layout.dataStartRowIdx = dataStart;
        layout.year           = year;
        layout.colToDate      = colToDate;
        return layout;
    }

    private int countDayNumbers(Row row) {
        int count = 0;
        for (Cell cell : row) {
            if (cell.getCellType() == CellType.NUMERIC) {
                double v = cell.getNumericCellValue();
                if (v >= 1 && v <= 31 && v == Math.floor(v)) count++;
            }
        }
        return count;
    }

    private int countMonthNames(Row row) {
        int count = 0;
        for (Cell cell : row) {
            String val = getCellString(cell).trim().toLowerCase();
            // Merged cells return repeated text e.g. "JANUARY   JANUARY   JANUARY"
            // Extract only the first whitespace-separated token before matching
            String firstWord = val.isEmpty() ? "" : val.split("\\s+")[0];
            if (parseMonth(firstWord) > 0) count++;
        }
        return count;
    }

    private boolean looksLikeWeekdayRow(Row row) {
        int weekdayCount = 0;
        int cellCount = 0;
        for (Cell cell : row) {
            String v = getCellString(cell).trim().toUpperCase();
            if (!v.isEmpty()) {
                cellCount++;
                if (v.equals("M") || v.equals("T") || v.equals("W") || v.equals("F") || v.equals("S")) {
                    weekdayCount++;
                }
            }
        }
        return cellCount > 10 && (weekdayCount * 100 / Math.max(cellCount, 1)) > 60;
    }

    private int extractYearFromRows(Sheet sheet, int monthRowIdx, int dayRowIdx, String filePath) {
        // Search month row and a few rows above/below for a 4-digit year
        for (int r = 0; r <= Math.min(sheet.getLastRowNum(), dayRowIdx + 1); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (Cell cell : row) {
                String val = getCellString(cell);
                Matcher m = YEAR_PATTERN.matcher(val);
                if (m.find()) return Integer.parseInt(m.group(1));
            }
        }
        // Fallback to filename
        Matcher m = YEAR_PATTERN.matcher(new File(filePath).getName());
        if (m.find()) return Integer.parseInt(m.group(1));
        return LocalDate.now().getYear();
    }

    private Map<Integer, LocalDate> buildColToDateMap(Row monthRow, Row dayRow, int year) {
        Map<Integer, LocalDate> colToDate = new LinkedHashMap<>();
        if (monthRow == null || dayRow == null) return colToDate;

        // Build col → month from the month row
        // A month name "owns" all columns from its position until the next month name
        int currentMonth = 0;
        int maxCol = Math.max(monthRow.getLastCellNum(), dayRow.getLastCellNum());
        for (int c = 0; c < maxCol; c++) {
            Cell mCell = monthRow.getCell(c);
            if (mCell != null) {
                String raw = getCellString(mCell).trim().toLowerCase();
                // Merged cells return repeated text e.g. "january   january   january"
                // Extract only the first whitespace-separated token before matching
                String firstWord = raw.isEmpty() ? "" : raw.split("\\s+")[0];
                int m = parseMonth(firstWord);
                if (m > 0) currentMonth = m;
            }
            if (currentMonth <= 0) continue;

            Cell dCell = dayRow.getCell(c);
            if (dCell == null) continue;
            if (dCell.getCellType() != CellType.NUMERIC) continue;
            int day = (int) dCell.getNumericCellValue();
            if (day < 1 || day > 31) continue;
            try {
                colToDate.put(c, LocalDate.of(year, currentMonth, day));
            } catch (Exception ignored) {
                // e.g. Feb 30 — skip
            }
        }
        return colToDate;
    }

    // ── Legend loading ────────────────────────────────────────────────────────

    private void loadLegend(XSSFWorkbook workbook,
                            Map<String, String> codeToLabel,
                            Map<String, String> colorToLabel) {
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            String name = workbook.getSheetName(i).toLowerCase().trim();
            if (LEGEND_SHEET_NAMES.contains(name)) {
                Sheet sheet = workbook.getSheetAt(i);
                for (Row row : sheet) {
                    Cell codeCell  = row.getCell(0);
                    Cell labelCell = row.getCell(1);
                    if (codeCell == null || labelCell == null) continue;
                    String code  = getCellString(codeCell).trim();
                    String label = getCellString(labelCell).trim();
                    if (code.isEmpty() || label.isEmpty()) continue;
                    codeToLabel.put(code, label);
                    String rgb = getCellRgb(codeCell);
                    if (rgb != null && !isSkipColor(rgb)) colorToLabel.put(rgb, label);
                }
                log.info("Loaded legend from sheet '{}': {} codes, {} colors",
                        workbook.getSheetName(i), codeToLabel.size(), colorToLabel.size());
                return;
            }
        }
        log.debug("No legend sheet found — will rely on cell text values only");
    }

    // ── Sheet finder ──────────────────────────────────────────────────────────

    private Sheet findPlannerSheet(XSSFWorkbook workbook) {
        // Prefer the sheet with the most columns (likely the planner)
        Sheet best = null;
        int bestCols = 0;
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            Sheet sheet = workbook.getSheetAt(i);
            String sheetName = workbook.getSheetName(i).toLowerCase().trim();
            // Skip known legend/key sheets
            if (LEGEND_SHEET_NAMES.contains(sheetName)) continue;
            int maxCol = 0;
            for (Row row : sheet) {
                if (row.getLastCellNum() > maxCol) maxCol = row.getLastCellNum();
            }
            if (maxCol > bestCols) {
                bestCols = maxCol;
                best = sheet;
            }
        }
        // Also accept if we found a sheet with a day-number row even if < 300 cols
        if (best != null) {
            log.debug("Selected planner sheet: '{}' ({} cols)", best.getSheetName(), bestCols);
        }
        return best;
    }

    // ── Employee row parsing ──────────────────────────────────────────────────

    private ParseResult parseEmployeeRows(Sheet sheet, SheetLayout layout,
                                          Map<String, String> codeToLabel,
                                          Map<String, String> colorToLabel) {
        List<LeaveRecord> records      = new ArrayList<>();
        List<String>      allNames     = new ArrayList<>();   // every non-header name in col 0

        for (int r = layout.dataStartRowIdx; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            Cell nameCell = row.getCell(0);
            if (nameCell == null) continue;
            String name = getCellString(nameCell).trim();
            if (name.isEmpty()) continue;
            // Skip rows that look like headers or separators
            if (isHeaderLike(name)) continue;

            // Always record the name — even if the employee has no leave codes this year.
            if (!allNames.contains(name)) allNames.add(name);

            List<LeaveRecord> empRecords = parseOneEmployee(name, row, layout, codeToLabel, colorToLabel);
            records.addAll(empRecords);
        }
        return new ParseResult(records, allNames);
    }

    private List<LeaveRecord> parseOneEmployee(String name, Row row, SheetLayout layout,
                                                Map<String, String> codeToLabel,
                                                Map<String, String> colorToLabel) {
        List<LeaveRecord> records = new ArrayList<>();

        // Build sorted (date, code) list for this employee
        List<DateCode> dateCodes = new ArrayList<>();
        for (Map.Entry<Integer, LocalDate> entry : layout.colToDate.entrySet()) {
            Cell cell = row.getCell(entry.getKey());
            LocalDate date = entry.getValue();
            // Skip weekends
            if (date.getDayOfWeek().getValue() > 5) continue;
            String code = resolveLeaveCode(cell, codeToLabel, colorToLabel);
            dateCodes.add(new DateCode(date, code));
        }
        Collections.sort(dateCodes, new Comparator<DateCode>() {
            @Override public int compare(DateCode a, DateCode b) { return a.date.compareTo(b.date); }
        });

        // Merge contiguous same-code runs into LeaveRecord spans
        String runCode = null;
        String runLabel = null;
        LocalDate runStart = null;
        LocalDate runEnd = null;
        int runDays = 0;

        for (DateCode dc : dateCodes) {
            String label = (dc.code != null) ? codeToLabel.getOrDefault(dc.code, dc.code) : null;

            if (dc.code != null && dc.code.equals(runCode)) {
                // Continue run
                runEnd = dc.date;
                runDays++;
            } else {
                // Emit previous run
                if (runCode != null && runStart != null && runDays > 0) {
                    records.add(new LeaveRecord(name, runStart, runEnd, runDays, runLabel, null));
                }
                // Start new run or reset
                if (dc.code != null) {
                    runCode  = dc.code;
                    runLabel = label;
                    runStart = dc.date;
                    runEnd   = dc.date;
                    runDays  = 1;
                } else {
                    runCode  = null;
                    runLabel = null;
                    runStart = null;
                    runEnd   = null;
                    runDays  = 0;
                }
            }
        }
        // Emit final run
        if (runCode != null && runStart != null && runDays > 0) {
            records.add(new LeaveRecord(name, runStart, runEnd, runDays, runLabel, null));
        }
        return records;
    }

    // ── Leave code resolution ─────────────────────────────────────────────────

    private String resolveLeaveCode(Cell cell, Map<String, String> codeToLabel,
                                     Map<String, String> colorToLabel) {
        if (cell == null) return null;

        // 1. Explicit cell text
        String val = getCellString(cell).trim();
        if (!val.isEmpty()) return val;

        // 2. Fill colour lookup
        String rgb = getCellRgb(cell);
        if (rgb != null && !isSkipColor(rgb)) {
            // Find a code whose legend colour matches
            for (Map.Entry<String, String> colorEntry : colorToLabel.entrySet()) {
                if (colorEntry.getKey().equalsIgnoreCase(rgb)) {
                    String targetLabel = colorEntry.getValue();
                    for (Map.Entry<String, String> codeEntry : codeToLabel.entrySet()) {
                        if (codeEntry.getValue().equals(targetLabel)) return codeEntry.getKey();
                    }
                    // Return the label itself as a synthetic code if no reverse mapping
                    return targetLabel;
                }
            }
            // Unknown colour — return the hex as a code so it's not silently lost
            return "CLR:" + rgb;
        }
        return null;
    }

    // ── Cell utilities ────────────────────────────────────────────────────────

    private String getCellString(Cell cell) {
        if (cell == null) return "";
        switch (cell.getCellType()) {
            case STRING:  return cell.getStringCellValue();
            case NUMERIC: {
                double v = cell.getNumericCellValue();
                // Return integer representation if it is whole
                if (v == Math.floor(v) && !Double.isInfinite(v))
                    return String.valueOf((long) v);
                return String.valueOf(v);
            }
            case BOOLEAN: return String.valueOf(cell.getBooleanCellValue());
            case FORMULA: {
                try { return cell.getStringCellValue(); } catch (Exception ignored) {}
                try { return String.valueOf((long) cell.getNumericCellValue()); } catch (Exception ignored) {}
                return "";
            }
            default: return "";
        }
    }

    private String getCellRgb(Cell cell) {
        if (cell == null) return null;
        CellStyle style = cell.getCellStyle();
        if (style == null) return null;
        Color fillColor = style.getFillForegroundColorColor();
        if (fillColor instanceof XSSFColor) {
            XSSFColor xc = (XSSFColor) fillColor;
            byte[] argb = xc.getARGB();
            if (argb == null || argb.length < 4) return null;
            return String.format("%02X%02X%02X%02X",
                    argb[0] & 0xFF, argb[1] & 0xFF, argb[2] & 0xFF, argb[3] & 0xFF);
        }
        return null;
    }

    private boolean isSkipColor(String rgb) {
        if (rgb == null) return true;
        // Treat near-white and transparent as skip
        String upper = rgb.toUpperCase();
        for (String skip : SKIP_COLORS) {
            if (upper.equals(skip.toUpperCase())) return true;
        }
        // Alpha=0 → fully transparent
        if (upper.startsWith("00")) return true;
        // Very light colours (R,G,B all > 0xE0) → near-white
        try {
            int a = Integer.parseInt(upper.substring(0, 2), 16);
            int r = Integer.parseInt(upper.substring(2, 4), 16);
            int g = Integer.parseInt(upper.substring(4, 6), 16);
            int b = Integer.parseInt(upper.substring(6, 8), 16);
            if (a == 0) return true;
            if (r > 0xE8 && g > 0xE8 && b > 0xE8) return true;
        } catch (Exception ignored) {}
        return false;
    }

    private boolean isHeaderLike(String name) {
        String lower = name.toLowerCase();
        return lower.equals("name") || lower.equals("employee") || lower.equals("employee name")
                || lower.equals("total") || lower.equals("sum") || lower.startsWith("---");
    }

    private int parseMonth(String val) {
        switch (val) {
            case "january":   case "jan": return 1;
            case "february":  case "feb": return 2;
            case "march":     case "mar": return 3;
            case "april":     case "apr": return 4;
            case "may":                   return 5;
            case "june":      case "jun": return 6;
            case "july":      case "jul": return 7;
            case "august":    case "aug": return 8;
            case "september": case "sep": return 9;
            case "october":   case "oct": return 10;
            case "november":  case "nov": return 11;
            case "december":  case "dec": return 12;
            default:                      return 0;
        }
    }

    // ── Diagnostics ───────────────────────────────────────────────────────────

    private void logDiagnostics(Sheet sheet, String filePath) {
        log.error("=== PlannerExcelReader diagnostics for: {} ===", filePath);
        log.error("Sheet name: '{}', lastRowNum: {}", sheet.getSheetName(), sheet.getLastRowNum());
        int maxRows = Math.min(sheet.getLastRowNum(), 10);
        for (int r = 0; r <= maxRows; r++) {
            Row row = sheet.getRow(r);
            if (row == null) { log.error("  Row {}: (null)", r); continue; }
            StringBuilder sb = new StringBuilder("  Row " + r + " [" + row.getLastCellNum() + " cols]: ");
            int shown = 0;
            for (Cell cell : row) {
                String v = getCellString(cell);
                if (!v.isEmpty()) {
                    sb.append("[").append(cell.getColumnIndex()).append("]=").append(v).append(" ");
                    if (++shown >= 15) { sb.append("..."); break; }
                }
            }
            log.error(sb.toString());
        }
        log.error("=== end diagnostics ===");
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    private static final class SheetLayout {
        int monthRowIdx;
        int dayRowIdx;
        int dataStartRowIdx;
        int year;
        Map<Integer, LocalDate> colToDate;
    }

    private static final class DateCode {
        final LocalDate date;
        final String code;   // null = no leave
        DateCode(LocalDate date, String code) { this.date = date; this.code = code; }
    }

    /** Intermediate result from a single workbook parse — records plus the full name roster. */
    private static final class ParseResult {
        final List<LeaveRecord> records;
        final List<String>      allEmployeeNames;
        ParseResult(List<LeaveRecord> records, List<String> allEmployeeNames) {
            this.records          = records;
            this.allEmployeeNames = allEmployeeNames;
        }
    }
}
