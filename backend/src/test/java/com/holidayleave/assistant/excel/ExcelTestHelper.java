package com.holidayleave.assistant.excel;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.*;

import java.io.*;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Month;
import java.util.Locale;

/**
 * Builds minimal but structurally-valid planner XLSX files for tests.
 *
 * Layout produced (row indices are 0-based):
 *
 *   Row 0  – year/title row  (cell 0: "Holiday Planner 2024")
 *   Row 1  – month row       (Jan spans cols 1-5, Feb cols 6-10, …)
 *   Row 2  – day-number row  (numeric 1..28/29/30/31 for the month span)
 *   Row 3  – weekday row     (M/T/W/T/F/S/S repeating)
 *   Row 4+ – employee rows   (cell 0: name, remaining cells: leave codes)
 *
 * Helper methods allow callers to:
 *  - request a full-year file (12 months × up to 31 days)
 *  - request a minimal single-month file
 *  - set a leave code in a specific employee row / date column
 */
public final class ExcelTestHelper {

    private ExcelTestHelper() {}

    // ── Full-year planner (Jan-Dec, enough days to satisfy ≥20 day cells) ─────

    /**
     * Writes a full 2024 annual planner XLSX to {@code file}.
     * Two employees are pre-populated: "Alice" (row 4) and "Bob" (row 5).
     * No leave codes are set — cells are blank.
     */
    public static void writeFullYearPlanner(Path file) throws IOException {
        writeFullYearPlanner(file, 2024);
    }

    public static void writeFullYearPlanner(Path file, int year) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sheet = wb.createSheet("Planner");
            buildFullYear(sheet, year,
                    new String[]{"Alice", "Bob"},
                    new int[][]{}); // no leave codes
            saveWorkbook(wb, file);
        }
    }

    /**
     * Writes a planner with leave codes pre-set.
     *
     * @param leaveCodes  array of { employeeRowOffset, colIndex, "CODE" }
     *                    employeeRowOffset is 0 = Alice, 1 = Bob (data rows start at 4)
     */
    public static void writeFullYearPlannerWithLeave(Path file, int year,
                                                      Object[]... leaveCodes) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sheet = wb.createSheet("Planner");
            buildFullYear(sheet, year, new String[]{"Alice", "Bob"}, new int[][]{});
            applyLeaveCodes(sheet, leaveCodes);
            saveWorkbook(wb, file);
        }
    }

    /**
     * Writes a planner with a custom employee list and leave codes.
     */
    public static void writeFullYearPlannerCustom(Path file, int year,
                                                   String[] employees,
                                                   Object[]... leaveCodes) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sheet = wb.createSheet("Planner");
            buildFullYear(sheet, year, employees, new int[][]{});
            applyLeaveCodes(sheet, leaveCodes);
            saveWorkbook(wb, file);
        }
    }

    // ── Corrupt / edge-case files ─────────────────────────────────────────────

    /** Writes an XLSX with only one sheet that has no planner structure at all. */
    public static void writeEmptySheetWorkbook(Path file) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            wb.createSheet("Sheet1");
            saveWorkbook(wb, file);
        }
    }

    /** Writes an XLSX where the day-row has fewer than 20 numeric cells (unrecognised layout). */
    public static void writeInsufficientDayCellsPlanner(Path file) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sheet = wb.createSheet("Planner");
            // Month row – only 2 month names
            Row monthRow = sheet.createRow(0);
            monthRow.createCell(1).setCellValue("January");
            monthRow.createCell(4).setCellValue("February");
            // Day row – only 5 day numbers (< 20 threshold)
            Row dayRow = sheet.createRow(1);
            for (int d = 1; d <= 5; d++) {
                dayRow.createCell(d).setCellValue(d);
            }
            saveWorkbook(wb, file);
        }
    }

    // ── Internal builders ─────────────────────────────────────────────────────

    /**
     * Populates {@code sheet} with 12 months across rows 0-3 (title, month, day, weekday)
     * and employee rows starting at row 4.
     *
     * Column layout: col 0 is the name column; months start at col 1.
     * Each month is allocated (daysInMonth) columns.
     */
    static int buildFullYear(XSSFSheet sheet, int year,
                              String[] employees, int[][] ignored) {
        // Row 0: title
        Row titleRow = sheet.createRow(0);
        titleRow.createCell(0).setCellValue("Holiday Planner " + year);

        // Rows 1 (month), 2 (day), 3 (weekday)
        Row monthRow   = sheet.createRow(1);
        Row dayRow     = sheet.createRow(2);
        Row weekdayRow = sheet.createRow(3);

        int col = 1; // start writing from column 1
        for (int m = 1; m <= 12; m++) {
            String monthName = Month.of(m).getDisplayName(
                    java.time.format.TextStyle.FULL, Locale.ENGLISH).toUpperCase();
            int days = LocalDate.of(year, m, 1).lengthOfMonth();
            // Write month name once (first column of that month span)
            monthRow.createCell(col).setCellValue(monthName);

            for (int d = 1; d <= days; d++, col++) {
                // day number
                Cell dayCell = dayRow.createCell(col);
                dayCell.setCellValue(d);
                // weekday letter
                java.time.DayOfWeek dow = LocalDate.of(year, m, d).getDayOfWeek();
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
                weekdayRow.createCell(col).setCellValue(letter);
            }
        }

        // Row 4: header spacer so WorkingExcelWriter.findEmployeeRow (starts at row 5) can find employees
        Row headerRow = sheet.createRow(4);
        headerRow.createCell(0).setCellValue("Employee Name");

        // Employee rows (starting at row 5)
        for (int e = 0; e < employees.length; e++) {
            Row empRow = sheet.createRow(5 + e);
            empRow.createCell(0).setCellValue(employees[e]);
        }

        return col; // total columns used
    }

    /**
     * Applies leave codes to already-written employee rows.
     *
     * @param leaveCodes  vararg of Object[]{employeeRowOffset, colIndex, "CODE"}
     *                    employeeRowOffset: 0 = first employee (row 5), 1 = second (row 6), etc.
     */
    private static void applyLeaveCodes(XSSFSheet sheet, Object[]... leaveCodes) {
        for (Object[] entry : leaveCodes) {
            int empOffset = (Integer) entry[0];
            int col       = (Integer) entry[1];
            String code   = (String)  entry[2];
            Row row = sheet.getRow(5 + empOffset);
            if (row == null) row = sheet.createRow(5 + empOffset);
            Cell cell = row.getCell(col);
            if (cell == null) cell = row.createCell(col);
            cell.setCellValue(code);
        }
    }

    /**
     * Returns the column index for a given date in a full-year planner built by
     * {@link #buildFullYear}.  Column 1 = Jan 1.
     */
    public static int colForDate(int year, int month, int day) {
        int col = 1;
        for (int m = 1; m < month; m++) {
            col += LocalDate.of(year, m, 1).lengthOfMonth();
        }
        col += (day - 1);
        return col;
    }

    // ── I/O helper ────────────────────────────────────────────────────────────

    private static void saveWorkbook(XSSFWorkbook wb, Path dest) throws IOException {
        java.nio.file.Files.createDirectories(dest.getParent());
        try (FileOutputStream fos = new FileOutputStream(dest.toFile())) {
            wb.write(fos);
        }
    }
}
