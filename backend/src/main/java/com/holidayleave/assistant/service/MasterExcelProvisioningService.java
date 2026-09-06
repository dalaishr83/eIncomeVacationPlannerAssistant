package com.holidayleave.assistant.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * Provisions a next-year Master Excel file from the predefined template.
 *
 * <p>Pipeline (all steps are transactional — on any failure the staging file is cleaned up):
 * <ol>
 *   <li>Verify the template exists at {@code /data/template/eIndkomst vacation-template.xlsx}.</li>
 *   <li>Verify the target file does not already exist in {@code /data}.</li>
 *   <li>Copy the template to {@code /data/staging/}.</li>
 *   <li>Open the staging copy with Apache POI and replace every string cell value that
 *       contains the literal text {@code YEAR} with the four-digit next-year integer.</li>
 *   <li>Save the modified workbook back to the staging file.</li>
 *   <li>Rename/move the staging file to {@code /data/eIndkomst vacation <year+1>.xlsx}
 *       using an atomic move where the OS supports it, falling back to copy-then-delete.</li>
 * </ol>
 */
@Service
public class MasterExcelProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(MasterExcelProvisioningService.class);

    private static final String TEMPLATE_SUBDIR   = "template";
    private static final String TEMPLATE_FILENAME = "eIndkomst-vacation-template.xlsx";
    private static final String STAGING_SUBDIR    = "staging";
    private static final String OUTPUT_PATTERN    = "eIndkomst vacation %d.xlsx";

    // ── Result ────────────────────────────────────────────────────────────────

    public static final class ProvisionResult {
        private final boolean success;
        private final String  filename;
        private final String  errorMessage;
        private final int     httpStatus;   // 200, 404, 409, 500

        private ProvisionResult(boolean success, String filename, String errorMessage, int httpStatus) {
            this.success      = success;
            this.filename     = filename;
            this.errorMessage = errorMessage;
            this.httpStatus   = httpStatus;
        }

        public static ProvisionResult ok(String filename) {
            return new ProvisionResult(true, filename, null, 200);
        }
        public static ProvisionResult notFound(String msg) {
            return new ProvisionResult(false, null, msg, 404);
        }
        public static ProvisionResult conflict(String msg) {
            return new ProvisionResult(false, null, msg, 409);
        }
        public static ProvisionResult error(String msg) {
            return new ProvisionResult(false, null, msg, 500);
        }

        public boolean isSuccess()       { return success; }
        public String  getFilename()     { return filename; }
        public String  getErrorMessage() { return errorMessage; }
        public int     getHttpStatus()   { return httpStatus; }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Provisions the next-year master file.
     *
     * @param dataDir  absolute path to the application data directory (e.g. {@code /data})
     * @param nextYear the target year (typically {@code LocalDate.now().getYear() + 1})
     * @return a {@link ProvisionResult} describing success or the specific failure
     */
    public ProvisionResult provision(String dataDir, int nextYear) {

        Path templatePath = Paths.get(dataDir, TEMPLATE_SUBDIR, TEMPLATE_FILENAME);
        String targetFilename = String.format(OUTPUT_PATTERN, nextYear);
        Path targetPath   = Paths.get(dataDir, targetFilename);
        Path stagingDir   = Paths.get(dataDir, STAGING_SUBDIR);
        Path stagingPath  = stagingDir.resolve(targetFilename);

        // 1. Template must exist
        if (!templatePath.toFile().exists()) {
            return ProvisionResult.notFound(
                    "Template not found: " + templatePath.toString());
        }

        // 2. Target must not already exist
        if (targetPath.toFile().exists()) {
            return ProvisionResult.conflict(
                    "File already exists: " + targetFilename + ". Delete it first if you want to re-provision.");
        }

        try {
            // 3. Copy template to staging
            Files.createDirectories(stagingDir);
            Files.copy(templatePath, stagingPath, StandardCopyOption.REPLACE_EXISTING);

            // 4 & 5. Replace YEAR placeholders, recalculate calendar rows, and save
            replaceYearInWorkbook(stagingPath, nextYear);

            // 5b. Final pre-promotion check: scan for any residual colour bleed
            //     (pink/weekend fill that leaked beyond the last employee row) and
            //     strip it.  The boundary is determined dynamically from the staged
            //     file itself — no row number is hardcoded here.
            sanitiseColourBleedBeyondLastEmployee(stagingPath);

            // 6. Move staging file to /data (atomic where supported)
            moveToData(stagingPath, targetPath);

            log.info("Provisioned master file: {}", targetFilename);
            return ProvisionResult.ok(targetFilename);

        } catch (Exception e) {
            log.error("Provisioning failed for year {}: {}", nextYear, e.getMessage(), e);
            deleteSilently(stagingPath);
            return ProvisionResult.error("Provisioning failed: " + e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void replaceYearInWorkbook(Path path, int nextYear) throws IOException {
        String yearString = String.valueOf(nextYear);
        try (FileInputStream fis = new FileInputStream(path.toFile());
             XSSFWorkbook workbook = new XSSFWorkbook(fis)) {

            for (int si = 0; si < workbook.getNumberOfSheets(); si++) {
                Sheet sheet = workbook.getSheetAt(si);

                // Rename sheet tab — template uses "YEAR" as the tab name literal,
                // and previously-provisioned files carry a 4-digit year (e.g. "2026").
                String sheetName = sheet.getSheetName();
                if (sheetName.equals("YEAR") || sheetName.matches("\\d{4}")) {
                    workbook.setSheetName(si, yearString);
                }

                // Determine the last row that contains an employee name (bottom-up scan
                // of column A).  Any row beyond this boundary is an empty formatting
                // artefact — we must not touch it during provisioning so that no extra
                // blank rows are written into the output file.
                int lastEmpRowIdx = findLastEmployeeRowIndex(sheet);
                // Fall back to the sheet's physical last row only when the sheet has
                // no employee data at all (e.g. the Sheet1 legend tab).
                int rowLimit = (lastEmpRowIdx >= 0) ? lastEmpRowIdx : sheet.getLastRowNum();

                // Replace YEAR text in string cells — capped at the last employee row
                for (int r = 0; r <= rowLimit; r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    for (Cell cell : row) {
                        if (cell.getCellType() == CellType.STRING) {
                            String val = cell.getStringCellValue();
                            if (val != null && val.contains("YEAR")) {
                                cell.setCellValue(val.replace("YEAR", yearString));
                            }
                        }
                    }
                }

                // Recalculate weekday letters and weekend fills for the new year
                recalculateCalendarRows(sheet, nextYear);
            }

            // Save back to the same staging file (temp-then-rename for safety)
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp.toFile())) {
                workbook.write(fos);
            }
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Recalculates the weekday-letter row (row index 3) and the day-number fill row
     * (row index 4) for the given {@code year}.
     *
     * <p>Template layout (0-based row indices):
     * <ul>
     *   <li>Row 3 – weekday letter per calendar column (M/T/W/T/F/S/S)</li>
     *   <li>Row 4 – day number per calendar column (1..28/29/30/31 per month)</li>
     * </ul>
     *
     * <p>The template uses the <em>actual</em> number of days per month — months with
     * fewer than 31 days occupy fewer columns.  For example, February 2026 occupies
     * 28 columns (cols 33–60), so March 2026 starts at col 61, not col 64.
     * The old assumption of a fixed 31-column stride per month was therefore wrong
     * and caused spurious extra columns to appear between months.
     *
     * <p>Column boundaries are derived entirely from the existing day-number row:
     * <ul>
     *   <li><b>Calendar start</b>: first NUMERIC cell in the day-number row.</li>
     *   <li><b>Month boundaries</b>: read directly from the existing day-number row
     *       by detecting where the day value resets from a higher number back to 1.
     *       This gives the exact column where each month starts, regardless of how
     *       many days the template year's months had.</li>
     *   <li><b>Calendar end</b>: last NUMERIC cell in the day-number row (within
     *       the rows 0..lastEmployeeRowIdx boundary).</li>
     *   <li><b>Reference styles</b>: first SOLID_FOREGROUND cell → weekend style;
     *       first NO_FILL cell → weekday style, scanned from calendarStart rightward,
     *       separately for the weekday row and the day-number row.</li>
     * </ul>
     *
     * @param sheet the sheet to modify in-place
     * @param year  the target calendar year
     */
    void recalculateCalendarRows(Sheet sheet, int year) {

        // ── Row handles ───────────────────────────────────────────────────────
        Row weekdayRow = sheet.getRow(3);
        Row dayNumRow  = sheet.getRow(4);
        if (weekdayRow == null || dayNumRow == null) {
            log.debug("recalculateCalendarRows: rows 3/4 not found in sheet '{}', skipping",
                    sheet.getSheetName());
            return;
        }

        // ── Remove merged regions in the weekday and day-number rows ──────────
        // The template encodes weekend pairs as merged cells in rows 3–4
        // (e.g. N5:R5 spans a Mon-Fri weekday run; E5:F5 spans a Sat-Sun weekend).
        // When we rewrite individual cells for the new year (e.g. Jan 16 2027 = Sat
        // falls inside the old weekday merge N5:R5), creating a cell inside an
        // existing merged region corrupts the merge state.  Excel then renders the
        // column fill from the merge master for ALL rows below — including bleed rows
        // where we have explicitly stamped NO_FILL — causing residual pink.
        // The fix: remove ALL merged regions that touch rows 3 or 4 before we write
        // any new cells.  The calendar recalculation loop replaces them with
        // correctly-styled individual cells, so no merge is needed after this point.
        removeMergedRegionsInRows(sheet, 3, 4);

        // ── Detect calendar start: first NUMERIC cell in the day-number row ──
        int calendarStart = -1;
        for (Cell cell : dayNumRow) {
            if (cell.getCellType() == CellType.NUMERIC) {
                calendarStart = cell.getColumnIndex();
                break;
            }
        }
        if (calendarStart < 0) {
            log.debug("recalculateCalendarRows: no numeric cell found in day-number row of '{}', skipping",
                    sheet.getSheetName());
            return;
        }

        // ── Detect last NUMERIC cell in day-number row — that is calendarEnd ──
        // We cap the scan at the last cell of row 4 itself (no need to scan all rows).
        int calendarEnd = calendarStart;
        {
            short lastCellNum = dayNumRow.getLastCellNum(); // exclusive upper bound
            for (int c = lastCellNum - 1; c >= calendarStart; c--) {
                Cell cell = dayNumRow.getCell(c);
                if (cell != null && cell.getCellType() == CellType.NUMERIC) {
                    calendarEnd = c;
                    break;
                }
            }
        }

        // ── Read month start columns from the existing day-number row ──────────
        // The day-number row encodes the exact column layout of the template year.
        // We collect the column index where each month (1..12) starts by watching
        // for a day-value of 1.  These month-start positions define how many columns
        // each month occupies in the template — we reuse that exact same layout when
        // writing the new year's data.
        int[] monthStartCol = new int[13]; // monthStartCol[1..12]; [0] unused; [13] = sentinel end
        int monthsFound = 0;
        {
            int prevDay = -1;
            for (int c = calendarStart; c <= calendarEnd; c++) {
                Cell cell = dayNumRow.getCell(c);
                if (cell == null || cell.getCellType() != CellType.NUMERIC) {
                    prevDay = -1;
                    continue;
                }
                int dayVal = (int) cell.getNumericCellValue();
                if (dayVal == 1 && prevDay != 1) {
                    if (monthsFound < 12) {
                        monthStartCol[monthsFound + 1] = c;
                        monthsFound++;
                    }
                }
                prevDay = dayVal;
            }
        }
        // Sentinel: column just past the last calendar column
        monthStartCol[0] = calendarEnd + 1; // reuse slot 0 as a "month 13 start" sentinel

        if (monthsFound != 12) {
            log.debug("recalculateCalendarRows: expected 12 month boundaries but found {} in sheet '{}', skipping",
                    monthsFound, sheet.getSheetName());
            return;
        }

        // ── Detect reference styles by scanning the calendar columns ──────────
        // We need styles for: weekday-letter row, day-number row, and employee rows.
        // Employee rows carry a different style (no bold, different borders) so we
        // read those separately from the first employee row (row index 5).
        CellStyle wdWeekdayStyle  = null; // row 3 (weekday letters), plain
        CellStyle wdWeekendStyle  = null; // row 3 (weekday letters), pink
        CellStyle dnWeekdayStyle  = null; // row 4 (day numbers),    plain
        CellStyle dnWeekendStyle  = null; // row 4 (day numbers),    pink
        CellStyle empWeekdayStyle = null; // employee rows,           plain
        CellStyle empWeekendStyle = null; // employee rows,           pink

        // First employee row starts at index 5; scan it for cell-level styles.
        // Employee cells may be null (column-level only) — if so, fall back to
        // the day-number row styles which are always present.
        int firstEmpRowIdx = 5;
        Row firstEmpRow = sheet.getRow(firstEmpRowIdx);

        for (int c = calendarStart; c <= calendarEnd; c++) {
            Cell wdCell  = weekdayRow.getCell(c);
            Cell dnCell  = dayNumRow.getCell(c);
            Cell empCell = (firstEmpRow == null) ? null : firstEmpRow.getCell(c);

            if (wdCell != null) {
                CellStyle cs = wdCell.getCellStyle();
                if (wdWeekendStyle == null && cs.getFillPattern() == FillPatternType.SOLID_FOREGROUND)
                    wdWeekendStyle = cs;
                if (wdWeekdayStyle == null && cs.getFillPattern() == FillPatternType.NO_FILL)
                    wdWeekdayStyle = cs;
            }
            if (dnCell != null) {
                CellStyle cs = dnCell.getCellStyle();
                if (dnWeekendStyle == null && cs.getFillPattern() == FillPatternType.SOLID_FOREGROUND)
                    dnWeekendStyle = cs;
                if (dnWeekdayStyle == null && cs.getFillPattern() == FillPatternType.NO_FILL)
                    dnWeekdayStyle = cs;
            }
            if (empCell != null) {
                CellStyle cs = empCell.getCellStyle();
                if (empWeekendStyle == null && cs.getFillPattern() == FillPatternType.SOLID_FOREGROUND)
                    empWeekendStyle = cs;
                if (empWeekdayStyle == null && cs.getFillPattern() == FillPatternType.NO_FILL)
                    empWeekdayStyle = cs;
            }

            if (wdWeekdayStyle != null && wdWeekendStyle != null
                    && dnWeekdayStyle != null && dnWeekendStyle != null
                    && empWeekdayStyle != null && empWeekendStyle != null) {
                break;
            }
        }

        // Cross-row fallbacks
        if (wdWeekdayStyle  == null) wdWeekdayStyle  = dnWeekdayStyle;
        if (wdWeekendStyle  == null) wdWeekendStyle   = dnWeekendStyle;
        if (dnWeekdayStyle  == null) dnWeekdayStyle   = wdWeekdayStyle;
        if (dnWeekendStyle  == null) dnWeekendStyle    = wdWeekendStyle;
        if (empWeekdayStyle == null) empWeekdayStyle  = dnWeekdayStyle;
        if (empWeekendStyle == null) empWeekendStyle   = dnWeekendStyle;

        if (wdWeekdayStyle == null || wdWeekendStyle == null) {
            log.debug("recalculateCalendarRows: could not detect reference styles in sheet '{}', skipping",
                    sheet.getSheetName());
            return;
        }

        // ── Determine last employee row ────────────────────────────────────────
        int lastEmpRowIdx = findLastEmployeeRowIndex(sheet);
        // If no employee rows found, skip employee-row re-styling entirely
        // (firstEmpRowIdx..lastEmpRowIdx defines the range to re-style)

        // ── Recalculate month by month, column by column ───────────────────────
        // For each calendar column:
        //   1. Update the weekday-letter cell (row 3) and day-number cell (row 4).
        //   2. Re-apply the correct cell-level background style to every employee
        //      data row (rows firstEmpRowIdx..lastEmpRowIdx).  This is necessary
        //      because the template carries column-level weekend fills that persist
        //      across the entire column — employee cells must have explicit cell-level
        //      styles to override those column fills with the correct (new year) colour.
        for (int month = 1; month <= 12; month++) {
            int colStart       = monthStartCol[month];
            int colEndExclusive = (month < 12) ? monthStartCol[month + 1] : (calendarEnd + 1);
            int slotWidth      = colEndExclusive - colStart;

            int daysInMonth = LocalDate.of(year, month, 1).lengthOfMonth();

            for (int d = 1; d <= slotWidth; d++) {
                int colIdx = colStart + d - 1;

                // ── Header rows 3 and 4 ──────────────────────────────────────
                Cell wdCell = getOrCreateCell(weekdayRow, colIdx);
                Cell dnCell = getOrCreateCell(dayNumRow,  colIdx);

                final boolean isWeekend;
                if (d > daysInMonth) {
                    // Overflow column (month slot wider than actual month length)
                    wdCell.setBlank();
                    wdCell.setCellStyle(wdWeekdayStyle);
                    dnCell.setBlank();
                    dnCell.setCellStyle(dnWeekdayStyle);
                    isWeekend = false;
                } else {
                    DayOfWeek dow = LocalDate.of(year, month, d).getDayOfWeek();
                    isWeekend = (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY);

                    wdCell.setCellValue(weekdayLetter(dow));
                    wdCell.setCellStyle(isWeekend ? wdWeekendStyle : wdWeekdayStyle);

                    dnCell.setCellValue(d);
                    dnCell.setCellStyle(isWeekend ? dnWeekendStyle : dnWeekdayStyle);
                }

                // ── Employee data rows ────────────────────────────────────────
                // Apply cell-level style to every employee row so that the
                // column-level weekend fills from the template are overridden.
                // We only set the style — cell values (leave codes) are untouched.
                if (lastEmpRowIdx >= firstEmpRowIdx) {
                    CellStyle empStyle = isWeekend ? empWeekendStyle : empWeekdayStyle;
                    for (int ri = firstEmpRowIdx; ri <= lastEmpRowIdx; ri++) {
                        Row empRow = sheet.getRow(ri);
                        if (empRow == null) continue;
                        Cell empCell = empRow.getCell(colIdx);
                        if (empCell == null) {
                            // Only create the cell if the column needs a weekend style
                            // (blank weekday cells can stay absent — their default is white)
                            if (isWeekend) {
                                empCell = empRow.createCell(colIdx);
                                empCell.setCellStyle(empStyle);
                            }
                        } else {
                            // Cell exists — update style but preserve value
                            empCell.setCellStyle(empStyle);
                        }
                    }
                }

                // ── Clear column-level weekend bleed in empty rows beyond last employee ──
                // The template carries a column-level pink fill on every weekend column.
                // That fill bleeds into ALL rows that have no cell-level style override —
                // including the empty rows between the last employee and the legend table
                // (rows 39–41 in the real template).
                //
                // Critically, this must also handle NEWLY-WEEKEND columns — columns that were
                // weekday in the template year but become weekend in the provisioned year
                // (e.g. Jan 2 was Friday in 2026 but is Saturday in 2027).  Those columns have
                // NO physical cell in the bleed rows at all, so we must CREATE the cell with an
                // explicit NO_FILL style.  Without this, Excel falls back to the column-level or
                // inherited fill and the pink bleeds through — exactly the 2027 defect.
                if (isWeekend && lastEmpRowIdx >= 0) {
                    for (int ri = lastEmpRowIdx + 1; ri <= sheet.getLastRowNum(); ri++) {
                        Row bleedRow = sheet.getRow(ri);
                        if (bleedRow == null) continue;
                        Cell bleedCell = bleedRow.getCell(colIdx);
                        if (bleedCell == null) {
                            // No physical cell yet — create one so we can stamp NO_FILL.
                            // This is the key fix for newly-weekend columns (e.g. 2027 Jan 2).
                            bleedCell = bleedRow.createCell(colIdx);
                        }
                        bleedCell.setCellStyle(empWeekdayStyle); // NO_FILL overrides any column fill
                    }
                }
            }
        }
    }

    // ── Pre-promotion colour-bleed sanitiser ──────────────────────────────────

    /**
     * Scans every sheet in the staged Excel file and removes any
     * {@link FillPatternType#SOLID_FOREGROUND} fill from cells that fall
     * <em>beyond</em> the last row containing a non-empty employee name in
     * column A.
     *
     * <p>This is the programmatic equivalent of "Option C" described in the
     * defect report: select the cells below the last employee row and apply
     * <em>No Fill</em>.  The boundary row is always resolved dynamically by
     * calling {@link #findLastEmployeeRowIndex(Sheet)}, so the logic continues
     * to work correctly when the template is replaced with one that has a
     * different number of employee rows.
     *
     * <p>Only cells that actually carry a {@code SOLID_FOREGROUND} pattern are
     * touched; a new cell style with {@code NO_FILL} is cloned from the
     * workbook's default cell style and applied.  All other cell attributes
     * (borders, font, number format) are left intact.
     *
     * <p>The method is idempotent: running it on a file that has no bleed
     * produces no changes and logs a single INFO line.
     *
     * @param stagingPath absolute path to the staging Excel file to patch
     * @throws IOException if the file cannot be read or written
     */
    void sanitiseColourBleedBeyondLastEmployee(Path stagingPath) throws IOException {
        try (FileInputStream fis = new FileInputStream(stagingPath.toFile());
             XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(fis)) {

            // Cache a single NO_FILL style per workbook so we don't create
            // thousands of identical style objects (Excel has a cap of 64 000).
            CellStyle noFillStyle = workbook.createCellStyle();
            noFillStyle.setFillPattern(FillPatternType.NO_FILL);

            int totalFixed = 0;

            for (int si = 0; si < workbook.getNumberOfSheets(); si++) {
                Sheet sheet = workbook.getSheetAt(si);

                // Determine the boundary: the last 0-based row index that has
                // a non-empty employee name in column A.
                int lastEmpRowIdx = findLastEmployeeRowIndex(sheet);
                if (lastEmpRowIdx < 0) {
                    // No employee data on this sheet (e.g. a legend sheet) — skip.
                    log.debug("sanitiseColourBleed: sheet '{}' has no employee rows, skipping",
                            sheet.getSheetName());
                    continue;
                }

                int sheetFixed = 0;

                // Iterate only over rows that physically exist in the XML and
                // fall beyond the last employee boundary.
                for (int ri = lastEmpRowIdx + 1; ri <= sheet.getLastRowNum(); ri++) {
                    Row row = sheet.getRow(ri);
                    if (row == null) continue;

                    for (Cell cell : row) {
                        CellStyle cs = cell.getCellStyle();
                        if (cs != null && cs.getFillPattern() == FillPatternType.SOLID_FOREGROUND) {
                            cell.setCellStyle(noFillStyle);
                            sheetFixed++;
                        }
                    }
                }

                if (sheetFixed > 0) {
                    log.info("sanitiseColourBleed: cleared {} solid-fill cell(s) beyond row {} on sheet '{}'",
                            sheetFixed, lastEmpRowIdx + 1 /* 1-based for logging */,
                            sheet.getSheetName());
                    totalFixed += sheetFixed;
                }
            }

            if (totalFixed == 0) {
                log.info("sanitiseColourBleed: no colour bleed detected in '{}'",
                        stagingPath.getFileName());
                return; // nothing to write back — skip the I/O
            }

            // Save the corrected workbook back to the staging path.
            Path tmp = stagingPath.resolveSibling(stagingPath.getFileName() + ".bleed.tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp.toFile())) {
                workbook.write(fos);
            }
            Files.move(tmp, stagingPath, StandardCopyOption.REPLACE_EXISTING);

            log.info("sanitiseColourBleed: removed {} solid-fill cell(s) total; staging file updated",
                    totalFixed);
        }
    }

    // ── Boundary helpers ──────────────────────────────────────────────────────

    /**
     * Scans column A (index 0) backward from {@code sheet.getLastRowNum()} and
     * returns the 0-based index of the last row that contains a non-empty STRING
     * value — i.e. the last row with an employee name.
     *
     * <p>Rows with a null {@link Row}, a null cell, a blank cell, or a non-STRING
     * cell in column A are skipped. Returns {@code -1} if no such row is found
     * (which would indicate the sheet has no employee data at all).
     *
     * @param sheet the sheet to inspect
     * @return 0-based row index of the last employee row, or {@code -1}
     */
    int findLastEmployeeRowIndex(Sheet sheet) {
        for (int r = sheet.getLastRowNum(); r >= 0; r--) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            Cell cell = row.getCell(0);
            if (cell == null) continue;
            if (cell.getCellType() != CellType.STRING) continue;
            String name = cell.getStringCellValue().trim();
            if (!name.isEmpty()) {
                log.debug("findLastEmployeeRowIndex: sheet '{}' last employee row index={} name='{}'",
                        sheet.getSheetName(), r, name);
                return r;
            }
        }
        log.debug("findLastEmployeeRowIndex: no employee row found in sheet '{}'", sheet.getSheetName());
        return -1;
    }

    // ── Small utilities ───────────────────────────────────────────────────────

    /**
     * Removes every merged region in {@code sheet} that touches any of the given
     * 0-based row indices.
     *
     * <p>The template stores weekday and day-number header data as merged cells
     * (e.g. a five-column weekday span or a two-column weekend pair).  Before the
     * calendar recalculation rewrites individual cells into those rows, all such
     * merges must be removed; otherwise creating a cell inside an existing merged
     * region corrupts the merge state and causes Excel to inherit the merge-master's
     * fill colour for all rows below — producing the residual pink bleed.
     *
     * <p>Removal is done in descending index order so that removing one region does
     * not shift the indices of the regions that follow.
     *
     * @param sheet    the sheet to modify in-place
     * @param rowNums  0-based row indices whose merged regions should be removed
     */
    private static void removeMergedRegionsInRows(Sheet sheet, int... rowNums) {
        java.util.Set<Integer> targetRows = new java.util.HashSet<>();
        for (int r : rowNums) targetRows.add(r);

        java.util.List<Integer> toRemove = new java.util.ArrayList<>();
        java.util.List<org.apache.poi.ss.util.CellRangeAddress> regions = sheet.getMergedRegions();
        for (int i = 0; i < regions.size(); i++) {
            org.apache.poi.ss.util.CellRangeAddress region = regions.get(i);
            // Remove the region if ANY of its rows is a target row
            for (int r = region.getFirstRow(); r <= region.getLastRow(); r++) {
                if (targetRows.contains(r)) {
                    toRemove.add(i);
                    break;
                }
            }
        }
        // Remove in descending order to keep indices stable
        toRemove.sort(java.util.Collections.reverseOrder());
        for (int idx : toRemove) {
            sheet.removeMergedRegion(idx);
        }
    }

    /** Returns the single-letter abbreviation used in the template weekday row. */
    private static String weekdayLetter(DayOfWeek dow) {
        switch (dow) {
            case MONDAY:    return "M";
            case TUESDAY:   return "T";
            case WEDNESDAY: return "W";
            case THURSDAY:  return "T";
            case FRIDAY:    return "F";
            case SATURDAY:  return "S";
            default:        return "S"; // SUNDAY
        }
    }

    /** Gets an existing cell or creates a blank one at the given column. */
    private static Cell getOrCreateCell(Row row, int colIdx) {
        Cell cell = row.getCell(colIdx);
        if (cell == null) {
            cell = row.createCell(colIdx);
        }
        return cell;
    }

    private void moveToData(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteSilently(Path path) {
        try { Files.deleteIfExists(path); } catch (IOException ignored) {}
    }
}
