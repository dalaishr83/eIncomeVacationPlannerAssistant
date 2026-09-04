package com.holidayleave.assistant.excel;

import com.holidayleave.assistant.model.LeaveRecord;
import com.holidayleave.assistant.model.VacationType;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes/deletes vacation cells in the working copy .xlsx files.
 *
 * Per-year ReentrantLock ensures concurrent safety.
 * Writes are atomic (temp-then-rename).
 */
@Component
public class WorkingExcelWriter {

    private static final Logger log = LoggerFactory.getLogger(WorkingExcelWriter.class);
    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(20\\d{2})\\b");

    private final ConcurrentHashMap<Integer, ReentrantLock> yearLocks = new ConcurrentHashMap<>();
    private final ReentrantLock registryLock = new ReentrantLock();

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Add vacation cells to the working copy.
     * @throws ExcelWriteConflictException if any date already has a leave code
     */
    public int addVacation(String workingFilePath, LeaveRecord record, VacationType type) throws IOException {
        int year = record.year();
        ReentrantLock lock = getLock(year);
        lock.lock();
        // FileInputStream and XSSFWorkbook are both declared in the same try-with-resources
        // so the workbook is guaranteed to be closed even if the constructor throws.
        try (FileInputStream fis = new FileInputStream(workingFilePath);
             XSSFWorkbook workbook = new XSSFWorkbook(fis)) {
            Sheet plannerSheet = findPlannerSheet(workbook);
            if (plannerSheet == null) throw new IOException("Could not find planner sheet");

            Map<LocalDate, Integer> dateToCol = buildDateToColMap(plannerSheet, workingFilePath);
            int employeeRow = findEmployeeRow(plannerSheet, record.employeeName());
            if (employeeRow < 0) throw new IOException("Employee '" + record.employeeName() + "' not found in Excel");

            // Conflict check
            for (LocalDate d = record.startDate(); !d.isAfter(record.endDate()); d = d.plusDays(1)) {
                if (d.getDayOfWeek().getValue() > 5) continue; // skip weekends
                Integer col = dateToCol.get(d);
                if (col == null) continue;
                Row row = plannerSheet.getRow(employeeRow);
                if (row == null) continue;
                Cell cell = row.getCell(col);
                if (cell != null) {
                    String existing = getCellString(cell);
                    if (!existing.isEmpty()) {
                        throw new ExcelWriteConflictException(d, existing);
                    }
                }
            }

            // Write phase — create one style per call, not one per cell (F-4 fix).
            int written = 0;
            XSSFColor color = parseArgb(type.color());
            XSSFCellStyle fillStyle = createFillStyle(workbook, color);
            for (LocalDate d = record.startDate(); !d.isAfter(record.endDate()); d = d.plusDays(1)) {
                if (d.getDayOfWeek().getValue() > 5) continue;
                Integer col = dateToCol.get(d);
                if (col == null) continue;
                Row row = plannerSheet.getRow(employeeRow);
                if (row == null) row = plannerSheet.createRow(employeeRow);
                Cell cell = row.getCell(col);
                if (cell == null) cell = row.createCell(col);
                cell.setCellValue(type.code());
                cell.setCellStyle(fillStyle);
                written++;
            }

            saveAtomically(workbook, workingFilePath);
            return written;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Clear vacation cells in the working copy.
     * @throws ExcelDeleteNotFoundException if no cells were cleared
     */
    public int deleteVacation(String workingFilePath, String employeeName,
                               LocalDate startDate, LocalDate endDate) throws IOException {
        int year = startDate.getYear();
        ReentrantLock lock = getLock(year);
        lock.lock();
        try (FileInputStream fis = new FileInputStream(workingFilePath);
             XSSFWorkbook workbook = new XSSFWorkbook(fis)) {
            Sheet plannerSheet = findPlannerSheet(workbook);
            if (plannerSheet == null) throw new IOException("Could not find planner sheet");

            Map<LocalDate, Integer> dateToCol = buildDateToColMap(plannerSheet, workingFilePath);
            int employeeRow = findEmployeeRow(plannerSheet, employeeName);
            if (employeeRow < 0) throw new IOException("Employee '" + employeeName + "' not found in Excel");

            // Create blank style once per call, not once per cell (F-4 fix).
            XSSFCellStyle blankStyle = createBlankStyle(workbook);
            int cleared = 0;
            for (LocalDate d = startDate; !d.isAfter(endDate); d = d.plusDays(1)) {
                if (d.getDayOfWeek().getValue() > 5) continue;
                Integer col = dateToCol.get(d);
                if (col == null) continue;
                Row row = plannerSheet.getRow(employeeRow);
                if (row == null) continue;
                Cell cell = row.getCell(col);
                if (cell == null) continue;
                String val = getCellString(cell);
                if (!val.isEmpty()) {
                    cell.setCellValue("");
                    cell.setCellType(CellType.BLANK);
                    cell.setCellStyle(blankStyle);
                    cleared++;
                }
            }

            if (cleared == 0) throw new ExcelDeleteNotFoundException("No vacation cells found in range");

            saveAtomically(workbook, workingFilePath);
            return cleared;
        } finally {
            lock.unlock();
        }
    }

    public ReentrantLock getLock(int year) {
        registryLock.lock();
        try {
            return yearLocks.computeIfAbsent(year, k -> new ReentrantLock());
        } finally {
            registryLock.unlock();
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Sheet findPlannerSheet(XSSFWorkbook workbook) {
        // Pick the sheet with the most columns (likely the planner)
        Sheet best = null;
        int bestCols = 0;
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            Sheet sheet = workbook.getSheetAt(i);
            int maxCol = 0;
            for (Row row : sheet) {
                if (row.getLastCellNum() > maxCol) maxCol = row.getLastCellNum();
            }
            if (maxCol > bestCols) { bestCols = maxCol; best = sheet; }
        }
        return best;
    }

    private Map<LocalDate, Integer> buildDateToColMap(Sheet sheet, String filePath) {
        Map<LocalDate, Integer> map = new LinkedHashMap<>();

        // Auto-discover day row (≥20 numeric 1–31 values) within first 16 rows
        int dayRowIdx = -1;
        for (int r = 0; r <= Math.min(sheet.getLastRowNum(), 15); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            int count = 0;
            for (Cell c : row) {
                if (c.getCellType() == CellType.NUMERIC) {
                    double v = c.getNumericCellValue();
                    if (v >= 1 && v <= 31 && v == Math.floor(v)) count++;
                }
            }
            if (count >= 20) { dayRowIdx = r; break; }
        }
        if (dayRowIdx < 0) return map;

        // Auto-discover month row (scan upward from day row for ≥3 month names)
        int monthRowIdx = -1;
        for (int r = dayRowIdx; r >= 0; r--) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            int count = 0;
            for (Cell c : row) {
                String firstWord = getCellFirstWord(c);
                if (parseMonth(firstWord) > 0) count++;
            }
            if (count >= 3) { monthRowIdx = r; break; }
        }
        if (monthRowIdx < 0) return map;

        Row monthRow = sheet.getRow(monthRowIdx);
        Row dayRow   = sheet.getRow(dayRowIdx);
        int year = detectYear(monthRow, filePath);

        int currentMonth = 0;
        int maxCol = Math.max(monthRow.getLastCellNum(), dayRow.getLastCellNum());
        for (int c = 0; c < maxCol; c++) {
            Cell mCell = monthRow.getCell(c);
            if (mCell != null) {
                String firstWord = getCellFirstWord(mCell);
                int m = parseMonth(firstWord);
                if (m > 0) currentMonth = m;
            }
            if (currentMonth > 0) {
                Cell dayCell = dayRow.getCell(c);
                if (dayCell != null && dayCell.getCellType() == CellType.NUMERIC) {
                    int day = (int) dayCell.getNumericCellValue();
                    if (day >= 1 && day <= 31) {
                        try {
                            map.put(LocalDate.of(year, currentMonth, day), c);
                        } catch (Exception ignored) {}
                    }
                }
            }
        }
        return map;
    }

    /** Extract the first whitespace-delimited word from a cell (handles merged-cell repeated text). */
    private String getCellFirstWord(Cell cell) {
        if (cell == null) return "";
        String val = "";
        switch (cell.getCellType()) {
            case STRING:  val = cell.getStringCellValue(); break;
            case NUMERIC: val = String.valueOf((long) cell.getNumericCellValue()); break;
            default: break;
        }
        val = val.trim().toLowerCase();
        if (val.isEmpty()) return "";
        return val.split("\\s+")[0];
    }

    private int findEmployeeRow(Sheet sheet, String name) {
        for (int r = 5; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            Cell cell = row.getCell(0);
            if (cell == null) continue;
            String val = getCellString(cell).trim();
            if (val.equalsIgnoreCase(name)) return r;
        }
        return -1;
    }

    private XSSFCellStyle createFillStyle(XSSFWorkbook wb, XSSFColor color) {
        XSSFCellStyle style = wb.createCellStyle();
        style.setFillForegroundColor(color);
        style.setFillBackgroundColor(color);
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private XSSFCellStyle createBlankStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        style.setFillPattern(FillPatternType.NO_FILL);
        return style;
    }

    private XSSFColor parseArgb(String argb) {
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            bytes[i] = (byte) Integer.parseInt(argb.substring(i * 2, i * 2 + 2), 16);
        }
        return new XSSFColor(bytes, null);
    }

    private void saveAtomically(XSSFWorkbook workbook, String destPath) throws IOException {
        Path dest = Paths.get(destPath);
        Path tmp = dest.getParent().resolve("." + UUID.randomUUID() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp.toFile())) {
            workbook.write(fos);
        }
        // Retry for Windows file locks
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (IOException e) {
                if (attempt == 2) throw e;
                try { Thread.sleep((long) (200 * Math.pow(2, attempt))); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
    }

    private String getCellString(Cell cell) {
        if (cell == null) return "";
        switch (cell.getCellType()) {
            case STRING:  return cell.getStringCellValue();
            case NUMERIC: return String.valueOf((int) cell.getNumericCellValue());
            default:      return "";
        }
    }

    private int detectYear(Row monthRow, String filePath) {
        for (Cell cell : monthRow) {
            String val = getCellFirstWord(cell);
            Matcher m = YEAR_PATTERN.matcher(val);
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        // Also scan the full cell text (years can appear in title cells)
        for (Cell cell : monthRow) {
            String val = cell.getCellType() == CellType.STRING ? cell.getStringCellValue() : "";
            Matcher m = YEAR_PATTERN.matcher(val);
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        Matcher m = YEAR_PATTERN.matcher(new File(filePath).getName());
        if (m.find()) return Integer.parseInt(m.group(1));
        return LocalDate.now().getYear();
    }

    private int parseMonth(String val) {
        switch (val.toLowerCase().trim()) {
            case "january":  case "jan": return 1;
            case "february": case "feb": return 2;
            case "march":    case "mar": return 3;
            case "april":    case "apr": return 4;
            case "may":                  return 5;
            case "june":     case "jun": return 6;
            case "july":     case "jul": return 7;
            case "august":   case "aug": return 8;
            case "september":case "sep": return 9;
            case "october":  case "oct": return 10;
            case "november": case "nov": return 11;
            case "december": case "dec": return 12;
            default:                     return 0;
        }
    }

    // ── Exception types ───────────────────────────────────────────────────────

    public static class ExcelWriteConflictException extends RuntimeException {
        private final LocalDate conflictDate;
        private final String existingCode;
        public ExcelWriteConflictException(LocalDate date, String code) {
            super("Conflict: date " + date + " already has leave code '" + code + "'.");
            this.conflictDate = date;
            this.existingCode = code;
        }
        public LocalDate getConflictDate() { return conflictDate; }
        public String getExistingCode() { return existingCode; }
    }

    public static class ExcelDeleteNotFoundException extends RuntimeException {
        public ExcelDeleteNotFoundException(String msg) { super(msg); }
    }
}
