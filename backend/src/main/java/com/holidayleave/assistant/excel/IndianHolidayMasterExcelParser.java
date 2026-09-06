package com.holidayleave.assistant.excel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Parser for Indian Holiday Master Excel files stored under
 * {@code DATA_DIR/holiday-upload/}.
 *
 * <p>Layout assumption (auto-detected, not hard-coded):
 * <ul>
 *   <li>One column contains date values (POI date cells or recognisable date strings).</li>
 *   <li>One or more columns are headed by a city name that matches one of the
 *       configured city tokens from {@code indian-city.json}.</li>
 *   <li>A cell in a city column is considered a holiday if it is non-empty.</li>
 * </ul>
 *
 * <p>City matching: each value from {@code indian-city.json} (e.g.
 * {@code "Bengaluru, Mysore"}) is split on {@code ","} to obtain individual
 * city tokens. Each token is normalised (trim + lowercase, specials stripped)
 * and compared against the sheet header cells.  All matching columns are
 * merged under the same city-group key (the full original value string).
 *
 * <p>Returns {@code Map<cityGroupKey, List<LocalDate>>} where the key is the
 * <em>value</em> string from {@code indian-city.json} exactly as stored
 * (e.g. {@code "Bengaluru, Mysore"}).
 */
@Component
public class IndianHolidayMasterExcelParser {

    private static final Logger log = LoggerFactory.getLogger(IndianHolidayMasterExcelParser.class);

    /**
     * Parse the given Holiday Master Excel file and return a map of
     * cityGroupValue → list of public-holiday dates for that group.
     *
     * @param filePath   absolute path to the holiday master .xlsx file
     * @param cityValues collection of city-group values from {@code indian-city.json}
     *                   (e.g. {@code ["Bengaluru, Mysore", "Hyderabad"]})
     * @return map from city-group value to the list of holiday dates; never null
     * @throws IOException if the file cannot be read
     */
    public Map<String, List<LocalDate>> parse(String filePath,
                                               Collection<String> cityValues) throws IOException {
        Map<String, List<LocalDate>> result = new LinkedHashMap<>();
        for (String v : cityValues) result.put(v, new ArrayList<>());

        log.info("=== IndianHolidayMasterExcelParser: START parsing '{}' ===", filePath);
        log.info("IndianHolidayMasterExcelParser: configured city-group values: {}", cityValues);

        try (FileInputStream fis = new FileInputStream(filePath);
             XSSFWorkbook workbook = new XSSFWorkbook(fis)) {

            Sheet sheet = findBestSheet(workbook);
            if (sheet == null) {
                log.warn("IndianHolidayMasterExcelParser: no usable sheet found in {}", filePath);
                return result;
            }
            log.info("IndianHolidayMasterExcelParser: using sheet '{}' (rows={})", sheet.getSheetName(), sheet.getLastRowNum());

            // Dump first 3 rows for diagnostics
            for (int dr = 0; dr <= Math.min(2, sheet.getLastRowNum()); dr++) {
                Row dRow = sheet.getRow(dr);
                if (dRow == null) { log.info("  diag row[{}]: (null)", dr); continue; }
                StringBuilder sb = new StringBuilder("  diag row[").append(dr).append("]: ");
                for (int dc = 0; dc < Math.min(dRow.getLastCellNum(), 15); dc++) {
                    sb.append("[").append(dc).append("]=").append(getCellString(dRow.getCell(dc))).append("  ");
                }
                log.info(sb.toString());
            }

            // Step 1 — find the header row and the date column
            HeaderInfo info = detectHeaderAndDateCol(sheet, cityValues);
            if (info == null) {
                log.warn("IndianHolidayMasterExcelParser: could not detect header/date column in {}", filePath);
                return result;
            }
            log.info("IndianHolidayMasterExcelParser: selected headerRow={} dateCol={} cityColCount={}",
                    info.headerRowIdx, info.dateColIdx, info.cityCols.size());
            log.info("IndianHolidayMasterExcelParser: city columns found in header: {}", info.cityCols);

            if (info.cityCols.isEmpty()) {
                log.warn("IndianHolidayMasterExcelParser: no non-empty columns in header row besides the date column");
                return result;
            }

            // Step 2 — match city columns to city-group values
            Map<String, List<Integer>> groupToCols = buildGroupToColsMap(cityValues, info.cityCols);
            log.info("IndianHolidayMasterExcelParser: city-group → column mapping: {}", groupToCols);

            int unmappedGroups = 0;
            for (Map.Entry<String, List<Integer>> e : groupToCols.entrySet()) {
                if (e.getValue().isEmpty()) {
                    log.warn("IndianHolidayMasterExcelParser: city-group '{}' matched NO columns — check header cell vs indian-city.json value", e.getKey());
                    unmappedGroups++;
                }
            }
            if (unmappedGroups == groupToCols.size()) {
                log.warn("IndianHolidayMasterExcelParser: ALL city-groups unmatched — no holidays will be extracted");
            }

            // Step 3 — scan data rows
            int rowsScanned = 0;
            int datesFound  = 0;
            for (int r = info.headerRowIdx + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                rowsScanned++;

                LocalDate date = readDate(row.getCell(info.dateColIdx));
                if (date == null) {
                    String rawCell = getCellString(row.getCell(info.dateColIdx));
                    if (!rawCell.trim().isEmpty()) {
                        log.debug("IndianHolidayMasterExcelParser: row[{}] col[{}] = '{}' — not a recognisable date, skipping",
                                r, info.dateColIdx, rawCell);
                    }
                    continue;
                }
                datesFound++;

                for (Map.Entry<String, List<Integer>> entry : groupToCols.entrySet()) {
                    String groupKey = entry.getKey();
                    for (int col : entry.getValue()) {
                        Cell cell = row.getCell(col);
                        if (cell != null && !getCellString(cell).trim().isEmpty()) {
                            List<LocalDate> dates = result.get(groupKey);
                            if (dates != null && !dates.contains(date)) {
                                dates.add(date);
                                log.debug("IndianHolidayMasterExcelParser: holiday {} → city-group '{}'", date, groupKey);
                            }
                            break; // one match per group per row is enough
                        }
                    }
                }
            }
            log.info("IndianHolidayMasterExcelParser: scanned {} data rows, parsed {} date rows", rowsScanned, datesFound);
        }

        // Log summary
        log.info("=== IndianHolidayMasterExcelParser: RESULT SUMMARY ===");
        for (Map.Entry<String, List<LocalDate>> e : result.entrySet()) {
            log.info("  city-group '{}' → {} holiday dates: {}", e.getKey(), e.getValue().size(), e.getValue());
        }
        log.info("=== IndianHolidayMasterExcelParser: END ===");
        return result;
    }

    /**
     * Rebuild the city mapping from the first two rows of the workbook.
     * Row 1 supplies the keys and row 2 supplies the city values, beginning at column E.
     *
     * @param filePath absolute path to the Indian holiday master .xlsx file
     * @param dataDir  absolute data directory containing the holiday-mapping directory
     * @throws IOException if the workbook or mapping file cannot be read or written
     */
    public void generateCityMapping(String filePath, String dataDir) throws IOException {
        Map<String, String> cities = new LinkedHashMap<>();

        try (FileInputStream fis = new FileInputStream(filePath);
             XSSFWorkbook workbook = new XSSFWorkbook(fis)) {
            Sheet sheet = findBestSheet(workbook);
            if (sheet != null) {
                Row keyRow = sheet.getRow(0);
                Row valueRow = sheet.getRow(1);
                int lastColumn = Math.max(keyRow == null ? 0 : keyRow.getLastCellNum(),
                        valueRow == null ? 0 : valueRow.getLastCellNum());

                for (int col = 4; col < lastColumn; col++) {
                    String key = getCellString(keyRow == null ? null : keyRow.getCell(col)).trim();
                    if (key.isEmpty()) continue;
                    String value = normalizeCity(getCellString(valueRow == null ? null : valueRow.getCell(col)));
                    cities.put(key, value);
                }
            }
        }

        Path mappingDir = Paths.get(dataDir, "holiday-mapping");
        Files.createDirectories(mappingDir);
        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("cities", cities);
        new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValue(mappingDir.resolve("indian-city.json").toFile(), mapping);
        log.info("Indian city mapping rebuilt with {} entries", cities.size());
    }

    // ── Header + date-column detection ───────────────────────────────────────

    /**
     * Detects the header row and date column.
     *
     * <p>Strategy: scan the first 20 rows. For each row, find any column that is a
     * likely date column (≥3 date values in subsequent rows). Among all candidate
     * (headerRow, dateCol) pairs, prefer the one whose <em>other</em> columns contain
     * the most city-token matches against the configured city values.  This prevents
     * selecting a row whose only match is a year number (e.g. "2026") in the date
     * column instead of the real city-name header row.
     *
     * @param sheet      the sheet to scan
     * @param cityValues the configured city-group values (used for scoring)
     */
    private HeaderInfo detectHeaderAndDateCol(Sheet sheet, Collection<String> cityValues) {
        int lastRow = sheet.getLastRowNum();

        // Build a flat set of normalised city tokens for scoring
        Set<String> cityTokens = new HashSet<>();
        for (String v : cityValues) {
            for (String tok : v.split(",")) {
                String n = normalize(tok);
                if (!n.isEmpty()) cityTokens.add(n);
            }
        }

        HeaderInfo best       = null;
        int        bestScore  = -1;

        for (int r = 0; r <= Math.min(lastRow, 20); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (int c = 0; c < row.getLastCellNum(); c++) {
                if (!isLikelyDateColumn(sheet, r, c)) continue;

                // Build candidate city columns for this (row, dateCol) pair
                Map<Integer, String> cityCols = new LinkedHashMap<>();
                for (int cc = 0; cc < row.getLastCellNum(); cc++) {
                    if (cc == c) continue;
                    Cell hCell = row.getCell(cc);
                    String txt = getCellString(hCell).trim();
                    if (!txt.isEmpty()) cityCols.put(cc, txt);
                }

                // Score = number of city-col header values that contain at least one city token
                int score = 0;
                for (String hdr : cityCols.values()) {
                    for (String tok : hdr.split(",")) {
                        if (cityTokens.contains(normalize(tok))) { score++; break; }
                    }
                }

                log.debug("IndianHolidayMasterExcelParser: candidate headerRow={} dateCol={} cityColCount={} score={}",
                        r, c, cityCols.size(), score);

                if (score > bestScore) {
                    bestScore = score;
                    best = new HeaderInfo();
                    best.headerRowIdx = r;
                    best.dateColIdx   = c;
                    best.cityCols     = cityCols;
                }
            }
        }

        if (best != null) {
            log.info("IndianHolidayMasterExcelParser: selected headerRow={} dateCol={} cityColCount={} score={}",
                    best.headerRowIdx, best.dateColIdx, best.cityCols.size(), bestScore);
        }
        return best;
    }

    /**
     * Returns true if at least 3 of the next 30 data rows have a POI date cell
     * or a recognisable date string in column {@code col}.
     */
    private boolean isLikelyDateColumn(Sheet sheet, int headerRow, int col) {
        int count = 0;
        for (int r = headerRow + 1; r <= Math.min(sheet.getLastRowNum(), headerRow + 40); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            if (readDate(row.getCell(col)) != null) {
                count++;
                if (count >= 3) return true;
            }
        }
        return false;
    }

    // ── City-column → group mapping ───────────────────────────────────────────

    private Map<String, List<Integer>> buildGroupToColsMap(Collection<String> cityValues,
                                                            Map<Integer, String> cityCols) {
        Map<String, List<Integer>> groupToCols = new LinkedHashMap<>();
        for (String groupValue : cityValues) {
            List<Integer> cols = new ArrayList<>();
            String normGroupValue = normalizeCity(groupValue);

            for (Map.Entry<Integer, String> colEntry : cityCols.entrySet()) {
                String normColHeader = normalizeCity(colEntry.getValue());
                if (normColHeader.equals(normGroupValue)) {
                    // Exact full-value match (e.g. "bengaluru,mysore" == "bengaluru,mysore")
                    cols.add(colEntry.getKey());
                } else {
                    // Also match if the column header is any single token within the group value
                    // (e.g. group "bengaluru,mysore" → also match a column headed just "bengaluru"
                    //  or just "mysore" so both dates are merged into this group)
                    for (String token : normGroupValue.split(",")) {
                        if (!token.isEmpty() && token.equals(normColHeader)) {
                            cols.add(colEntry.getKey());
                            break;
                        }
                    }
                }
            }
            groupToCols.put(groupValue, cols);
            log.debug("IndianHolidayMasterExcelParser: city-group '{}' (norm='{}') → columns {}",
                    groupValue, normGroupValue, cols);
        }
        return groupToCols;
    }

    /**
     * Canonical city-name normalization shared by both the JSON-value side and the
     * Excel-header side of every comparison.
     *
     * <p>Rules (matching the specified requirement):
     * <ol>
     *   <li>Trim leading and trailing whitespace.</li>
     *   <li>Convert to lowercase.</li>
     *   <li>Remove all spaces (including those around commas).</li>
     *   <li>Preserve the comma as separator between multiple city names.</li>
     * </ol>
     *
     * <p>Examples:
     * <pre>
     *   "Bengaluru, Mysore"   → "bengaluru,mysore"
     *   " Bengaluru , Mysore" → "bengaluru,mysore"
     *   "Hyderabad "          → "hyderabad"
     *   "Noida, Lucknow"      → "noida,lucknow"
     * </pre>
     */
    public static String normalizeCity(String s) {
        if (s == null) return "";
        // lowercase → remove all spaces (handles leading, trailing, and around commas)
        return s.trim().toLowerCase().replaceAll("\\s+", "");
    }

    /**
     * Internal token normalizer — strips everything non-alphanumeric.
     * Used only for header-row scoring (not for city-group matching).
     */
    private String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    // ── Date reading ──────────────────────────────────────────────────────────

    private LocalDate readDate(Cell cell) {
        if (cell == null) return null;
        // POI numeric date cell
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            try {
                Date d = cell.getDateCellValue();
                return d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            } catch (Exception ignored) {}
        }
        // String cell — try common patterns
        String s = getCellString(cell).trim();
        if (s.isEmpty()) return null;
        for (String fmt : new String[]{
                "yyyy-MM-dd", "dd-MM-yyyy", "dd/MM/yyyy", "MM/dd/yyyy",
                "d-MMM-yyyy", "d MMM yyyy", "dd MMM yyyy", "MMMM d, yyyy"}) {
            try {
                return LocalDate.parse(s,
                        java.time.format.DateTimeFormatter.ofPattern(fmt,
                                java.util.Locale.ENGLISH));
            } catch (Exception ignored) {}
        }
        return null;
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private Sheet findBestSheet(XSSFWorkbook workbook) {
        // Prefer sheet with most rows
        Sheet best = null;
        int bestRows = 0;
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            Sheet s = workbook.getSheetAt(i);
            if (s.getLastRowNum() > bestRows) {
                bestRows = s.getLastRowNum();
                best = s;
            }
        }
        return best;
    }

    private String getCellString(Cell cell) {
        if (cell == null) return "";
        switch (cell.getCellType()) {
            case STRING:  return cell.getStringCellValue();
            case NUMERIC: {
                double v = cell.getNumericCellValue();
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

    // ── Inner types ───────────────────────────────────────────────────────────

    private static final class HeaderInfo {
        int headerRowIdx;
        int dateColIdx;
        Map<Integer, String> cityCols; // col index → header text
    }
}
