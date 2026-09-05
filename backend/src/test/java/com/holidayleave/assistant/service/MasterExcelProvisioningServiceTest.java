package com.holidayleave.assistant.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.apache.poi.ss.util.CellRangeAddress;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MasterExcelProvisioningService#recalculateCalendarRows}.
 *
 * <p>Each test builds a minimal in-memory XLSX that mirrors the template layout:
 * <ul>
 *   <li>Row 0 – title row (col 0 = "YEAR title")</li>
 *   <li>Row 1 – spare</li>
 *   <li>Row 2 – month-name row (not touched by recalculate)</li>
 *   <li>Row 3 – weekday-letter row (M/T/W/T/F/S/S) — RECALCULATED</li>
 *   <li>Row 4 – day-number row (1..31 per month)    — RECALCULATED</li>
 *   <li>Row 5+ – employee data rows                  — NOT TOUCHED</li>
 * </ul>
 *
 * Calendar columns start at col index 2; each month occupies exactly 31 columns.
 */
class MasterExcelProvisioningServiceTest {

    // ── Constants matching the template layout ─────────────────────────────────
    private static final int WEEKDAY_ROW  = 3;
    private static final int DAY_NUM_ROW  = 4;
    private static final int CAL_START    = 2;   // col index of Jan day 1

    private MasterExcelProvisioningService service;

    @BeforeEach
    void setUp() {
        service = new MasterExcelProvisioningService();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1 – 2027 weekday shift
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Jan 1 2027 is a Friday — col 2 should be 'F', col 3 Saturday 'S', col 4 Sunday 'S'")
    void jan1_2027_isFriday() throws Exception {
        // Jan 1 2027 = Friday (verified: DayOfWeek.FRIDAY)
        assertThat(LocalDate.of(2027, 1, 1).getDayOfWeek().name()).isEqualTo("FRIDAY");

        XSSFWorkbook wb = buildMinimalTemplate(2026); // template starts with 2026 data
        Sheet sheet = wb.getSheetAt(0);
        service.recalculateCalendarRows(sheet, 2027);

        // Col 2 = Jan day 1 = Friday
        assertThat(cellString(sheet, WEEKDAY_ROW, CAL_START)).isEqualTo("F");
        // Col 3 = Jan day 2 = Saturday
        assertThat(cellString(sheet, WEEKDAY_ROW, CAL_START + 1)).isEqualTo("S");
        // Col 4 = Jan day 3 = Sunday
        assertThat(cellString(sheet, WEEKDAY_ROW, CAL_START + 2)).isEqualTo("S");
        // Col 5 = Jan day 4 = Monday
        assertThat(cellString(sheet, WEEKDAY_ROW, CAL_START + 3)).isEqualTo("M");
        wb.close();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2 – 2028 leap year
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Feb 2028 is a leap year — day 29 cell must contain 29, day-slot 30 & 31 must be blank")
    void feb_2028_leapYear() throws Exception {
        assertThat(LocalDate.of(2028, 2, 1).isLeapYear()).isTrue();

        XSSFWorkbook wb = buildMinimalTemplate(2026);
        Sheet sheet = wb.getSheetAt(0);
        service.recalculateCalendarRows(sheet, 2028);

        // Feb occupies cols 33..63 (offset 31..61, month index 1)
        int febStart = CAL_START + 31; // col 33
        // Day 29 = col 33 + 28 = col 61
        int day29Col = febStart + 28;
        int day30Col = febStart + 29;
        int day31Col = febStart + 30;

        assertThat(cellNumeric(sheet, DAY_NUM_ROW, day29Col)).isEqualTo(29.0);
        assertThat(cellIsBlank(sheet, DAY_NUM_ROW, day30Col)).isTrue();
        assertThat(cellIsBlank(sheet, DAY_NUM_ROW, day31Col)).isTrue();
        wb.close();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3 – 2025 non-leap Feb
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Feb 2025 non-leap year — day 29 slot must be blank, day 28 slot must be 28")
    void feb_2025_nonLeap() throws Exception {
        assertThat(LocalDate.of(2025, 2, 1).isLeapYear()).isFalse();

        XSSFWorkbook wb = buildMinimalTemplate(2026);
        Sheet sheet = wb.getSheetAt(0);
        service.recalculateCalendarRows(sheet, 2025);

        int febStart = CAL_START + 31;
        int day28Col = febStart + 27;
        int day29Col = febStart + 28;

        assertThat(cellNumeric(sheet, DAY_NUM_ROW, day28Col)).isEqualTo(28.0);
        assertThat(cellIsBlank(sheet, DAY_NUM_ROW, day29Col)).isTrue();
        wb.close();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 4 – Stale weekend fill cleared for newly-weekday column
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Column that was a weekend in old year but is a weekday in new year gets weekday style")
    void stale_weekend_fill_cleared() throws Exception {
        // Jan 3 2026 = Saturday (weekend, col 4 in template).
        // Jan 3 2025 = Friday (weekday) — after recalculate for 2025, col 4 must become weekday style.
        assertThat(LocalDate.of(2025, 1, 3).getDayOfWeek().name()).isEqualTo("FRIDAY");
        assertThat(LocalDate.of(2026, 1, 3).getDayOfWeek().name()).isEqualTo("SATURDAY");

        XSSFWorkbook wb = buildMinimalTemplate(2026); // col 4 starts as weekend style (pink fill)
        Sheet sheet = wb.getSheetAt(0);

        // Confirm col 4 has a solid fill in the template (weekend = pink)
        XSSFCellStyle beforeStyle = (XSSFCellStyle) sheet.getRow(WEEKDAY_ROW).getCell(4).getCellStyle();
        assertThat(beforeStyle.getFillPattern()).isEqualTo(FillPatternType.SOLID_FOREGROUND);

        service.recalculateCalendarRows(sheet, 2025);

        // Col 4 = Jan 3 2025 = Friday → fill pattern must be NO_FILL (weekday style)
        XSSFCellStyle col4StyleAfter = (XSSFCellStyle) sheet.getRow(WEEKDAY_ROW).getCell(4).getCellStyle();
        assertThat(col4StyleAfter.getFillPattern()).isEqualTo(FillPatternType.NO_FILL);
        // Letter must be "F" for Friday
        assertThat(cellString(sheet, WEEKDAY_ROW, 4)).isEqualTo("F");
        wb.close();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 5 – YEAR title replacement (via provision pipeline)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Year title text YEAR is replaced with nextYear in STRING cells")
    void year_title_replaced(@TempDir Path tmp) throws Exception {
        // Write a minimal template file to disk with "YEAR" in the title cell
        XSSFWorkbook wb = buildMinimalTemplate(2026);
        // Overwrite title cell with the "YEAR" placeholder (as the real template has)
        wb.getSheetAt(0).getRow(0).getCell(0).setCellValue("Holiday Planner YEAR");
        Path templateDir = tmp.resolve("template");
        Files.createDirectories(templateDir);
        Path templateFile = templateDir.resolve("eIndkomst vacation-template.xlsx");
        try (FileOutputStream fos = new FileOutputStream(templateFile.toFile())) {
            wb.write(fos);
        }
        wb.close();

        MasterExcelProvisioningService svc = new MasterExcelProvisioningService();
        MasterExcelProvisioningService.ProvisionResult result =
                svc.provision(tmp.toString(), 2027);

        assertThat(result.isSuccess()).isTrue();
        Path outputFile = tmp.resolve("eIndkomst vacation 2027.xlsx");
        assertThat(outputFile.toFile()).exists();

        try (FileInputStream fis = new FileInputStream(outputFile.toFile());
             XSSFWorkbook out = new XSSFWorkbook(fis)) {
            String title = out.getSheetAt(0).getRow(0).getCell(0).getStringCellValue();
            assertThat(title).contains("2027");
            assertThat(title).doesNotContain("YEAR");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 6 – Employee rows are NOT modified
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Employee data rows (row 5+) are untouched after recalculation")
    void employee_rows_untouched() throws Exception {
        XSSFWorkbook wb = buildMinimalTemplate(2026);
        Sheet sheet = wb.getSheetAt(0);

        // Put sentinel data in employee row 5
        Row empRow = sheet.getRow(5);
        if (empRow == null) empRow = sheet.createRow(5);
        Cell nameCell = empRow.getCell(0);
        if (nameCell == null) nameCell = empRow.createCell(0);
        nameCell.setCellValue("Test Employee");
        Cell leaveCell = empRow.createCell(CAL_START);
        leaveCell.setCellValue("P");

        service.recalculateCalendarRows(sheet, 2027);

        // Name cell must be untouched
        assertThat(sheet.getRow(5).getCell(0).getStringCellValue()).isEqualTo("Test Employee");
        // Leave code must be untouched
        assertThat(sheet.getRow(5).getCell(CAL_START).getStringCellValue()).isEqualTo("P");
        wb.close();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 7 – Month name row (row 2) is NOT modified
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Month-name row (row 2) is untouched after recalculation")
    void month_name_row_untouched() throws Exception {
        XSSFWorkbook wb = buildMinimalTemplate(2026);
        Sheet sheet = wb.getSheetAt(0);

        String beforeJan = sheet.getRow(2).getCell(CAL_START).getStringCellValue();

        service.recalculateCalendarRows(sheet, 2027);

        assertThat(sheet.getRow(2).getCell(CAL_START).getStringCellValue()).isEqualTo(beforeJan);
        wb.close();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 8 – findLastEmployeeRowIndex returns last non-empty col-A row
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("findLastEmployeeRowIndex returns the last row with a non-empty employee name in col A")
    void findLastEmployeeRowIndex_returns_last_name_row() throws Exception {
        XSSFWorkbook wb = new XSSFWorkbook();
        XSSFSheet sheet = wb.createSheet("YEAR");

        // Row 0 – title (non-employee string in col A)
        sheet.createRow(0).createCell(0).setCellValue("eIndkomst vacation calendar");
        // Rows 1–2 – header/spare (blank col A)
        sheet.createRow(1);
        sheet.createRow(2).createCell(0).setCellValue("YEAR");
        // Rows 3–4 – weekday/day-number header (blank col A)
        sheet.createRow(3);
        sheet.createRow(4);
        // Row 5 – first employee
        sheet.createRow(5).createCell(0).setCellValue("Alice");
        // Row 6 – second employee
        sheet.createRow(6).createCell(0).setCellValue("Bob");
        // Row 7 – last employee
        sheet.createRow(7).createCell(0).setCellValue("Vishal Agrawal");
        // Rows 8–12 – empty rows with no col A content (formatting artefacts)
        for (int r = 8; r <= 12; r++) {
            sheet.createRow(r); // row exists but col A is null
        }

        int result = service.findLastEmployeeRowIndex(sheet);

        assertThat(result).isEqualTo(7);
        assertThat(sheet.getRow(result).getCell(0).getStringCellValue())
                .isEqualTo("Vishal Agrawal");
        wb.close();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 9 – findLastEmployeeRowIndex ignores trailing blank/null col-A rows
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("findLastEmployeeRowIndex skips trailing null rows and blank-cell rows")
    void findLastEmployeeRowIndex_skips_trailing_empty_rows() throws Exception {
        XSSFWorkbook wb = new XSSFWorkbook();
        XSSFSheet sheet = wb.createSheet("YEAR");

        sheet.createRow(5).createCell(0).setCellValue("Only Employee");
        // Row 6 exists but col A cell is explicitly blank
        Row r6 = sheet.createRow(6);
        r6.createCell(0).setBlank();
        // Row 7 exists but col A is null (no cell created)
        sheet.createRow(7);
        // Row 8 does not even have a Row object — getRow(8) returns null

        int result = service.findLastEmployeeRowIndex(sheet);

        assertThat(result).isEqualTo(5);
        wb.close();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 10 – sheet tab named "YEAR" is renamed to nextYear during provision
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Sheet tab named YEAR is renamed to the provisioned year")
    void sheet_tab_year_placeholder_is_renamed(@TempDir Path tmp) throws Exception {
        // Build a template whose sheet tab is the literal string "YEAR"
        XSSFWorkbook wb = buildMinimalTemplate(2026);
        // The helper already creates the sheet with a numeric name; rename it to "YEAR"
        wb.setSheetName(0, "YEAR");
        wb.getSheetAt(0).getRow(0).getCell(0).setCellValue("Holiday Planner YEAR");

        Path templateDir = tmp.resolve("template");
        Files.createDirectories(templateDir);
        Path templateFile = templateDir.resolve("eIndkomst vacation-template.xlsx");
        try (FileOutputStream fos = new FileOutputStream(templateFile.toFile())) {
            wb.write(fos);
        }
        wb.close();

        MasterExcelProvisioningService.ProvisionResult result =
                service.provision(tmp.toString(), 2027);

        assertThat(result.isSuccess()).isTrue();

        Path outputFile = tmp.resolve("eIndkomst vacation 2027.xlsx");
        try (FileInputStream fis = new FileInputStream(outputFile.toFile());
             XSSFWorkbook out = new XSSFWorkbook(fis)) {
            // Sheet tab must be "2027", not "YEAR"
            assertThat(out.getSheetAt(0).getSheetName()).isEqualTo("2027");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 11 – Rows beyond the last employee row are NOT touched during provision
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Rows beyond the last non-empty employee row are not modified during provisioning")
    void rows_beyond_last_employee_are_not_modified(@TempDir Path tmp) throws Exception {
        // Build a template where the last real employee is at row 7,
        // and rows 8..10 are empty formatting artefacts that carry a "YEAR" string
        // (simulating template cruft that must never be touched).
        XSSFWorkbook wb = buildMinimalTemplate(2026);
        Sheet sheet = wb.getSheetAt(0);

        // Row 7 – last real employee (col A non-empty)
        Row lastEmpRow = sheet.createRow(7);
        lastEmpRow.createCell(0).setCellValue("Vishal Agrawal");

        // Rows 8–10 – empty artefact rows with a "YEAR" marker in col B
        // (these must NOT be rewritten to "2027")
        for (int r = 8; r <= 10; r++) {
            Row artefact = sheet.createRow(r);
            artefact.createCell(1).setCellValue("YEAR");  // sentinel — must survive unchanged
        }

        // Write template to disk
        Path templateDir = tmp.resolve("template");
        Files.createDirectories(templateDir);
        Path templateFile = templateDir.resolve("eIndkomst vacation-template.xlsx");
        try (FileOutputStream fos = new FileOutputStream(templateFile.toFile())) {
            wb.write(fos);
        }
        wb.close();

        MasterExcelProvisioningService svc = new MasterExcelProvisioningService();
        MasterExcelProvisioningService.ProvisionResult result = svc.provision(tmp.toString(), 2027);

        assertThat(result.isSuccess()).isTrue();

        Path outputFile = tmp.resolve("eIndkomst vacation 2027.xlsx");
        try (FileInputStream fis = new FileInputStream(outputFile.toFile());
             XSSFWorkbook out = new XSSFWorkbook(fis)) {

            Sheet outSheet = out.getSheetAt(0);

            // Last real employee row must still carry the correct name
            assertThat(outSheet.getRow(7).getCell(0).getStringCellValue())
                    .isEqualTo("Vishal Agrawal");

            // Artefact rows 8–10: col B cell must still contain the literal "YEAR",
            // proving the provisioning loop did not touch them.
            for (int r = 8; r <= 10; r++) {
                Row artefact = outSheet.getRow(r);
                assertThat(artefact).as("artefact row %d must exist", r).isNotNull();
                Cell cell = artefact.getCell(1);
                assertThat(cell).as("artefact row %d col B must exist", r).isNotNull();
                assertThat(cell.getStringCellValue())
                        .as("artefact row %d col B must be untouched 'YEAR'", r)
                        .isEqualTo("YEAR");
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 12 – Pink weekend bleed is cleared in empty rows below last employee
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Weekend column cells in empty rows below last employee get NO_FILL after recalculation")
    void weekend_bleed_cleared_in_empty_rows_below_last_employee() throws Exception {
        // Jan 3 2026 = Saturday → col index CAL_START + 2 = 4 carries a pink weekend fill.
        // We add an explicit cell at that column in row 6 (one row past the last employee at row 5)
        // to simulate the template artefact that causes the pink bleed in the real file.
        assertThat(LocalDate.of(2026, 1, 3).getDayOfWeek().name()).isEqualTo("SATURDAY");

        XSSFWorkbook wb = buildMinimalTemplate(2026);
        XSSFSheet sheet = wb.getSheetAt(0);

        // ── Define a pink weekend style to pre-stamp onto the bleed row ──────
        XSSFCellStyle pinkStyle = wb.createCellStyle();
        pinkStyle.setFillForegroundColor(
                new XSSFColor(new byte[]{(byte)0xFF, (byte)0xB6, (byte)0xC1}, null));
        pinkStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        // Row 5 = last employee (already set by buildMinimalTemplate as "Sample Employee")
        // Row 6 = empty bleed row — create a cell at the Saturday column and give it pink fill
        int saturdayCol = CAL_START + 2; // Jan 3 2026 = Saturday
        Row bleedRow = sheet.createRow(6);
        Cell bleedCell = bleedRow.createCell(saturdayCol);
        bleedCell.setCellStyle(pinkStyle);

        // Confirm the bleed cell starts pink
        assertThat(((XSSFCellStyle) bleedCell.getCellStyle()).getFillPattern())
                .isEqualTo(FillPatternType.SOLID_FOREGROUND);

        // Recalculate for any year where Jan 3 is still a weekend (e.g. 2026 itself)
        service.recalculateCalendarRows(sheet, 2026);

        // After recalculation the bleed cell must have NO_FILL — pink is cleared
        XSSFCellStyle styleAfter = (XSSFCellStyle) sheet.getRow(6).getCell(saturdayCol).getCellStyle();
        assertThat(styleAfter.getFillPattern())
                .as("bleed cell in empty row below last employee must be NO_FILL after recalculation")
                .isEqualTo(FillPatternType.NO_FILL);

        wb.close();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 13 – Newly-weekend column bleed cleared even when no physical cell exists
    //           (the 2027 scenario: Jan 2 was Friday in 2026, becomes Saturday in 2027)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Newly-weekend column (no prior cell in bleed rows) gets NO_FILL cell created in empty rows")
    void newly_weekend_column_bleed_cleared_when_no_prior_cell() throws Exception {
        // Jan 2 2026 = Friday (weekday) → col CAL_START + 1 = 3 has NO weekend fill in template.
        // Jan 2 2027 = Saturday (weekend) → after recalculate for 2027 that same col becomes
        // a weekend col.  Row 6 is the bleed zone (one past the last employee at row 5).
        // Critically: row 6 col 3 has NO physical cell in the template — our fix must CREATE
        // one with NO_FILL so Excel does not fall back to column-level or inherited pink fill.
        assertThat(LocalDate.of(2026, 1, 2).getDayOfWeek().name()).isEqualTo("FRIDAY");
        assertThat(LocalDate.of(2027, 1, 2).getDayOfWeek().name()).isEqualTo("SATURDAY");

        XSSFWorkbook wb = buildMinimalTemplate(2026); // Jan 2 col = weekday in template
        Sheet sheet = wb.getSheetAt(0);

        int jan2Col = CAL_START + 1; // col index for Jan 2 (0-based col 3)

        // Add an empty row 6 (the bleed zone) — it exists as a Row object but has no
        // cell at jan2Col, simulating the real template's empty rows beyond last employee.
        Row emptyBleedRow = sheet.createRow(6);
        assertThat(emptyBleedRow.getCell(jan2Col)).isNull(); // confirm no cell yet

        // Recalculate for 2027 — Jan 2 becomes Saturday
        service.recalculateCalendarRows(sheet, 2027);

        // Row 6 bleed zone: the fix must have created a cell at jan2Col with NO_FILL
        Row bleedRow = sheet.getRow(6);
        assertThat(bleedRow)
                .as("bleed row 6 must still exist")
                .isNotNull();
        Cell bleedCell = bleedRow.getCell(jan2Col);
        assertThat(bleedCell)
                .as("cell at newly-weekend col in bleed row must be created")
                .isNotNull();
        assertThat(((XSSFCellStyle) bleedCell.getCellStyle()).getFillPattern())
                .as("newly-created bleed cell must have NO_FILL — not pink")
                .isEqualTo(FillPatternType.NO_FILL);

        wb.close();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 14 – Merged regions in weekday/day-number rows are removed before
    //           recalculation, preventing residual pink bleed via merge-master fill
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Merged regions in rows 3-4 are removed before recalculation to prevent merge-master fill bleed")
    void merged_regions_in_header_rows_removed_before_recalculation() throws Exception {
        // In the real template rows 3-4 (0-based) carry merged cells for weekday spans
        // and weekend pairs (e.g. N5:R5 = Mon-Fri span for Jan12-16 2026).
        // When col Q (Jan 16 2027 = Saturday) is rewritten as an individual pink cell
        // inside the existing N5:R5 merge, the merge state is corrupted.  Excel then
        // renders col Q's fill from the merge master (N5) for all rows below, making
        // the bleed-clear NO_FILL cells ineffective.
        // This test verifies that all merged regions touching rows 3-4 are GONE
        // after recalculateCalendarRows runs.

        XSSFWorkbook wb = buildMinimalTemplate(2026);
        XSSFSheet sheet = wb.getSheetAt(0);

        // Add a merged region spanning cols 3-7 in the weekday row (row 3, 0-based)
        // to simulate the template's weekday span (e.g. N4:R4 in the real file).
        sheet.addMergedRegion(new CellRangeAddress(3, 3, 3, 7));
        // Add a merged region spanning cols 4-5 in the day-number row (row 4, 0-based)
        // to simulate the template's weekend pair (e.g. E5:F5 = Jan3-4 pink).
        sheet.addMergedRegion(new CellRangeAddress(4, 4, 4, 5));

        // Confirm the merges exist before recalculation
        long mergesBefore = sheet.getMergedRegions().stream()
                .filter(r -> r.getFirstRow() == 3 || r.getFirstRow() == 4)
                .count();
        assertThat(mergesBefore).isGreaterThan(0);

        // Run recalculation — this should strip the merged regions from rows 3-4
        service.recalculateCalendarRows(sheet, 2027);

        // After recalculation: no merged regions must remain in rows 3 or 4
        long mergesAfter = sheet.getMergedRegions().stream()
                .filter(r -> r.getFirstRow() <= 4 && r.getLastRow() >= 3)
                .count();
        assertThat(mergesAfter)
                .as("all merged regions in weekday/day-number rows must be removed before rewriting")
                .isEqualTo(0);

        wb.close();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 15 – Pre-promotion bleed sanitiser (end-to-end via provision())
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("provision() clears pink SOLID_FOREGROUND cells beyond the last employee row before promoting to /data/")
    void provision_clears_colour_bleed_beyond_last_employee(@TempDir Path tmp) throws Exception {
        // ── Build a template that carries pink bleed cells beyond row 7 ──────
        //
        // Structure:
        //   Row 5  – first employee  ("Alice")
        //   Row 6  – second employee ("Bob")
        //   Row 7  – last employee   ("Vishal Agrawal")
        //   Rows 8–10 – empty rows that carry a SOLID_FOREGROUND (pink) fill
        //               on a calendar column — simulating the real defect.

        XSSFWorkbook wb = buildMinimalTemplate(2026);
        XSSFSheet sheet = wb.getSheetAt(0);

        // Add employees at rows 5–7 (row 5 already present from buildMinimalTemplate)
        Row r5 = sheet.getRow(5);
        r5.getCell(0).setCellValue("Alice");

        Row r6 = sheet.createRow(6);
        r6.createCell(0).setCellValue("Bob");

        Row r7 = sheet.createRow(7);
        r7.createCell(0).setCellValue("Vishal Agrawal");

        // Define a pink style to simulate the bleed
        XSSFCellStyle pinkStyle = wb.createCellStyle();
        pinkStyle.setFillForegroundColor(
                new XSSFColor(new byte[]{(byte) 0xFF, (byte) 0xB6, (byte) 0xC1}, null));
        pinkStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        // Stamp pink bleed onto rows 8, 9, 10 — column M equivalent (index 14)
        int bleedCol = 14;
        for (int r = 8; r <= 10; r++) {
            Row bleedRow = sheet.createRow(r);
            Cell bleedCell = bleedRow.createCell(bleedCol);
            bleedCell.setCellStyle(pinkStyle);
        }

        // Confirm the bleed exists before provisioning
        for (int r = 8; r <= 10; r++) {
            XSSFCellStyle cs = (XSSFCellStyle) sheet.getRow(r).getCell(bleedCol).getCellStyle();
            assertThat(cs.getFillPattern())
                    .as("pre-condition: row %d col %d must be pink before provision", r, bleedCol)
                    .isEqualTo(FillPatternType.SOLID_FOREGROUND);
        }

        // Write template to disk
        Path templateDir = tmp.resolve("template");
        Files.createDirectories(templateDir);
        Path templateFile = templateDir.resolve("eIndkomst vacation-template.xlsx");
        try (FileOutputStream fos = new FileOutputStream(templateFile.toFile())) {
            wb.write(fos);
        }
        wb.close();

        // ── Run the full provision pipeline ───────────────────────────────────
        MasterExcelProvisioningService svc = new MasterExcelProvisioningService();
        MasterExcelProvisioningService.ProvisionResult result = svc.provision(tmp.toString(), 2027);

        assertThat(result.isSuccess())
                .as("provision must succeed")
                .isTrue();

        // ── Verify the promoted file has no pink bleed beyond the last employee ─
        Path promoted = tmp.resolve("eIndkomst vacation 2027.xlsx");
        assertThat(promoted.toFile()).exists();

        try (FileInputStream fis = new FileInputStream(promoted.toFile());
             XSSFWorkbook out = new XSSFWorkbook(fis)) {

            XSSFSheet outSheet = out.getSheetAt(0);

            // Last employee row must still be intact
            assertThat(outSheet.getRow(7).getCell(0).getStringCellValue())
                    .isEqualTo("Vishal Agrawal");

            // Bleed rows 8–10 at bleedCol must now be NO_FILL (or cell may be absent)
            for (int r = 8; r <= 10; r++) {
                Row row = outSheet.getRow(r);
                if (row == null) continue; // row was removed entirely — also acceptable
                Cell cell = row.getCell(bleedCol);
                if (cell == null) continue; // cell absent — acceptable (no fill = no bleed)
                FillPatternType fill = ((XSSFCellStyle) cell.getCellStyle()).getFillPattern();
                assertThat(fill)
                        .as("row %d col %d must not have SOLID_FOREGROUND after provision", r, bleedCol)
                        .isNotEqualTo(FillPatternType.SOLID_FOREGROUND);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper – build a minimal in-memory template XLSX
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds a minimal workbook that mirrors the real template structure:
     * <ul>
     *   <li>Row 0 – title</li>
     *   <li>Row 1 – spare</li>
     *   <li>Row 2 – month names (JANUARY…DECEMBER at every 31st col)</li>
     *   <li>Row 3 – weekday letters for {@code templateYear}</li>
     *   <li>Row 4 – day numbers for {@code templateYear}</li>
     *   <li>Row 5 – one sample employee row</li>
     * </ul>
     *
     * Reference styles are set so that:
     * <ul>
     *   <li>Col 2 (Jan 1 of templateYear) = weekday style (no fill)</li>
     *   <li>Col 4 (Jan 3 of templateYear 2026 = Saturday) = weekend style (pink fill)</li>
     * </ul>
     */
    private XSSFWorkbook buildMinimalTemplate(int templateYear) {
        XSSFWorkbook wb = new XSSFWorkbook();
        XSSFSheet sheet = wb.createSheet(String.valueOf(templateYear));

        // ── Define two styles: weekday (no fill) and weekend (pink) ──────────
        XSSFCellStyle weekdayStyle = wb.createCellStyle();
        weekdayStyle.setFillPattern(FillPatternType.NO_FILL);

        XSSFCellStyle weekendStyle = wb.createCellStyle();
        weekendStyle.setFillForegroundColor(new XSSFColor(new byte[]{(byte)0xFF, (byte)0xB6, (byte)0xC1}, null));
        weekendStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        // ── Row 0: title ──────────────────────────────────────────────────────
        Row titleRow = sheet.createRow(0);
        titleRow.createCell(0).setCellValue("Holiday Planner " + templateYear);

        // ── Row 1: spare ──────────────────────────────────────────────────────
        sheet.createRow(1);

        // ── Rows 2, 3, 4 ─────────────────────────────────────────────────────
        Row monthRow   = sheet.createRow(2);
        Row weekdayRow = sheet.createRow(3);
        Row dayNumRow  = sheet.createRow(4);

        // Name/CTY header cells
        monthRow.createCell(0).setCellValue("Name");
        monthRow.createCell(1).setCellValue("CTY");
        weekdayRow.createCell(0).setCellValue("Name");
        weekdayRow.createCell(1).setCellValue("CTY");
        dayNumRow.createCell(0).setCellValue("Name");
        dayNumRow.createCell(1).setCellValue("CTY");

        String[] monthNames = {
            "JANUARY","FEBRUARY","MARCH","APRIL","MAY","JUNE",
            "JULY","AUGUST","SEPTEMBER","OCTOBER","NOVEMBER","DECEMBER"
        };

        for (int m = 0; m < 12; m++) {
            int monthStartCol = CAL_START + m * 31;
            // Month name
            Cell mnCell = monthRow.createCell(monthStartCol);
            mnCell.setCellValue(monthNames[m]);

            int daysInMonth = LocalDate.of(templateYear, m + 1, 1).lengthOfMonth();
            for (int d = 1; d <= 31; d++) {
                int colIdx = monthStartCol + (d - 1);
                Cell wdCell = weekdayRow.createCell(colIdx);
                Cell dnCell = dayNumRow.createCell(colIdx);

                if (d > daysInMonth) {
                    wdCell.setCellStyle(weekdayStyle);
                    wdCell.setBlank();
                    dnCell.setCellStyle(weekdayStyle);
                    dnCell.setBlank();
                } else {
                    java.time.DayOfWeek dow = LocalDate.of(templateYear, m + 1, d).getDayOfWeek();
                    boolean isWeekend = (dow == java.time.DayOfWeek.SATURDAY
                                      || dow == java.time.DayOfWeek.SUNDAY);
                    CellStyle cs = isWeekend ? weekendStyle : weekdayStyle;

                    String letter;
                    switch (dow) {
                        case MONDAY:    letter = "M"; break;
                        case TUESDAY:   letter = "T"; break;
                        case WEDNESDAY: letter = "W"; break;
                        case THURSDAY:  letter = "T"; break;
                        case FRIDAY:    letter = "F"; break;
                        case SATURDAY:  letter = "S"; break;
                        default:        letter = "S"; break;
                    }
                    wdCell.setCellValue(letter);
                    wdCell.setCellStyle(cs);
                    dnCell.setCellValue(d);
                    dnCell.setCellStyle(cs);
                }
            }
        }

        // ── Row 5: sample employee ────────────────────────────────────────────
        Row empRow = sheet.createRow(5);
        empRow.createCell(0).setCellValue("Sample Employee");
        empRow.createCell(1).setCellValue("IN");

        return wb;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Assertion helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static String cellString(Sheet sheet, int rowIdx, int colIdx) {
        Row row = sheet.getRow(rowIdx);
        if (row == null) return null;
        Cell cell = row.getCell(colIdx);
        if (cell == null) return null;
        if (cell.getCellType() == CellType.STRING) return cell.getStringCellValue();
        return null;
    }

    private static double cellNumeric(Sheet sheet, int rowIdx, int colIdx) {
        Row row = sheet.getRow(rowIdx);
        if (row == null) return Double.NaN;
        Cell cell = row.getCell(colIdx);
        if (cell == null) return Double.NaN;
        if (cell.getCellType() == CellType.NUMERIC) return cell.getNumericCellValue();
        return Double.NaN;
    }

    private static boolean cellIsBlank(Sheet sheet, int rowIdx, int colIdx) {
        Row row = sheet.getRow(rowIdx);
        if (row == null) return true;
        Cell cell = row.getCell(colIdx);
        if (cell == null) return true;
        return cell.getCellType() == CellType.BLANK;
    }
}
