package com.holidayleave.assistant.service;

import com.holidayleave.assistant.excel.IndianHolidayMasterExcelParser;
import com.holidayleave.assistant.excel.PlannerExcelReader;
import com.holidayleave.assistant.excel.WorkingExcelWriter;
import com.holidayleave.assistant.model.LeaveRecord;
import com.holidayleave.assistant.model.VacationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/**
 * Orchestrates the Indian public-holiday synchronisation workflow:
 *
 * <ol>
 *   <li>Read city values from {@code DATA_DIR/holiday-mapping/indian-city.json}.</li>
 *   <li>Parse the selected Holiday Master Excel (under {@code holiday-upload/}) via
 *       {@link IndianHolidayMasterExcelParser} to obtain
 *       {@code Map<cityGroupValue, List<LocalDate>>}.</li>
 *   <li>Read {@code employee-mapping.json} and group employees by the stored city string.</li>
 *   <li>For every (employee, holiday-date) pair call
 *       {@link WorkingExcelWriter#addVacation} with vacation type {@code P}
 *       (Public Holiday).  Conflict exceptions are silently counted as skips —
 *       re-running the sync is safe.</li>
 *   <li>Evict the reader cache and trigger the working→master sync daemon.</li>
 * </ol>
 *
 * <p>No existing service or writer is modified.
 */
@Service
public class IndianHolidaySyncService {

    private static final Logger log = LoggerFactory.getLogger(IndianHolidaySyncService.class);

    private static final String HOLIDAY_UPLOAD_SUBDIR = "holiday-upload";
    /** Vacation type code for Public Holiday — must exist in vacation_types.json. */
    private static final String PUBLIC_HOLIDAY_CODE   = "P";

    @Autowired private AppState               appState;
    @Autowired private HolidaySettingsService  holidaySettingsService;
    @Autowired private IndianHolidayMasterExcelParser parser;
    @Autowired private WorkingExcelWriter       writer;
    @Autowired private PlannerExcelReader       reader;
    @Autowired private VacationTypeService      vacationTypeService;
    @Autowired private SyncService              syncService;
    @Autowired private AuditService             auditService;

    /**
     * Execute the full sync for the given Holiday Master filename.
     *
     * @param filename  name of the file inside {@code DATA_DIR/holiday-upload/}
     * @param actingUser username for audit logging
     * @return result summary
     * @throws IOException if the holiday master file cannot be read
     * @throws IllegalArgumentException if the file is not found or {@code P} type is missing
     */
    public SyncResult sync(String filename, String actingUser) throws IOException {

        // ── Guard: resolve & validate file path ───────────────────────────────
        String dataDir     = appState.getDataDir();
        Path   uploadPath  = Paths.get(dataDir, HOLIDAY_UPLOAD_SUBDIR, filename);
        if (!uploadPath.toFile().exists()) {
            throw new IllegalArgumentException(
                    "Holiday file not found: " + filename + " (expected in holiday-upload/)");
        }

        // ── Guard: P type must exist ──────────────────────────────────────────
        VacationType publicHolidayType = vacationTypeService.findByCode(PUBLIC_HOLIDAY_CODE)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Vacation type '" + PUBLIC_HOLIDAY_CODE + "' (Public Holiday) is not configured."));

        // ── Step 1: read city values (the JSON map values, not keys) ──────────
        Map<String, String> cityMap = holidaySettingsService.readCities(dataDir);
        log.info("=== IndianHolidaySyncService: START sync for '{}' ===", filename);
        log.info("IndianHolidaySyncService: [Step 1] city map from indian-city.json: {}", cityMap);
        if (cityMap.isEmpty()) {
            log.warn("IndianHolidaySyncService: no cities configured in indian-city.json — nothing to sync");
            return new SyncResult(0, 0, 0, 0, 0,
                    "No cities are configured in indian-city.json. Please add city mappings first.");
        }
        Collection<String> cityValues = cityMap.values(); // e.g. ["Bengaluru, Mysore", "Hyderabad"]
        log.info("IndianHolidaySyncService: [Step 1] city-group values used for parsing: {}", cityValues);

        // ── Step 2: parse Holiday Master Excel ────────────────────────────────
        log.info("IndianHolidaySyncService: [Step 2] parsing '{}' with {} city groups", filename, cityValues.size());
        Map<String, List<LocalDate>> holidayMap = parser.parse(uploadPath.toString(), cityValues);
        log.info("IndianHolidaySyncService: [Step 2] holiday parse result:");
        for (Map.Entry<String, List<LocalDate>> hme : holidayMap.entrySet()) {
            log.info("  city-group '{}' → {} dates: {}", hme.getKey(), hme.getValue().size(), hme.getValue());
        }

        // ── Step 3: read employee mapping grouped by city value ───────────────
        Map<String, Object> mappingRoot = holidaySettingsService.readMapping(dataDir);
        Map<String, List<String>> cityToEmployees = buildCityToEmployeesMap(mappingRoot);
        log.info("IndianHolidaySyncService: [Step 3] employees grouped by city: {}", cityToEmployees);

        // cross-check: warn for any city group that has holidays but no employees
        for (Map.Entry<String, List<LocalDate>> hme : holidayMap.entrySet()) {
            if (!hme.getValue().isEmpty()) {
                String normKey = IndianHolidayMasterExcelParser.normalizeCity(hme.getKey());
                List<String> emps = cityToEmployees.get(normKey);
                if (emps == null || emps.isEmpty()) {
                    log.warn("IndianHolidaySyncService: city-group '{}' (norm='{}') has {} holiday(s) but NO employees mapped — " +
                            "check employee-mapping.json city field matches this value exactly",
                            hme.getKey(), normKey, hme.getValue().size());
                }
            }
        }

        // ── Step 4: write P entries into working copies ───────────────────────
        int written         = 0;
        int skippedWeekend  = 0;   // holiday date falls on Saturday or Sunday
        int skippedConflict = 0;   // cell already occupied (idempotent re-sync)
        int skippedError    = 0;   // IO / missing working-copy errors
        Set<String>   employeesProcessed = new LinkedHashSet<>();
        Set<LocalDate> weekendDatesLogged = new LinkedHashSet<>(); // log each date once

        for (Map.Entry<String, List<LocalDate>> entry : holidayMap.entrySet()) {
            String           cityGroupValue     = entry.getKey();
            // Normalize the key so it matches the normalized keys in cityToEmployees
            String           normCityGroupValue = IndianHolidayMasterExcelParser.normalizeCity(cityGroupValue);
            List<LocalDate>  holidayDates       = entry.getValue();

            if (holidayDates.isEmpty()) {
                log.info("IndianHolidaySyncService: [Step 4] city group '{}' has 0 holidays — skipping", cityGroupValue);
                continue;
            }

            List<String> employees = cityToEmployees.get(normCityGroupValue);
            if (employees == null || employees.isEmpty()) {
                log.warn("IndianHolidaySyncService: [Step 4] city group '{}' (norm='{}') has {} holiday(s) but 0 mapped employees",
                        cityGroupValue, normCityGroupValue, holidayDates.size());
                continue;
            }
            log.info("IndianHolidaySyncService: [Step 4] city group '{}' (norm='{}') → {} holiday(s), {} employee(s): {}",
                    cityGroupValue, normCityGroupValue, holidayDates.size(), employees.size(), employees);

            for (String empName : employees) {
                for (LocalDate holidayDate : holidayDates) {
                    // ── Weekend validation ────────────────────────────────────
                    if (holidayDate.getDayOfWeek().getValue() > 5) {
                        skippedWeekend++;
                        if (weekendDatesLogged.add(holidayDate)) {
                            log.info("IndianHolidaySyncService: [Weekend skip] {} ({}) falls on {} — skipped for all employees",
                                    holidayDate, cityGroupValue, holidayDate.getDayOfWeek());
                        }
                        continue;
                    }

                    int year = holidayDate.getYear();
                    String workingPath = getWorkingPath(year);
                    try {
                        ensureWorkingCopy(workingPath, year);
                    } catch (IOException e) {
                        log.warn("IndianHolidaySyncService: cannot ensure working copy for year {}: {}",
                                year, e.getMessage());
                        skippedError++;
                        continue;
                    }

                    LeaveRecord record = new LeaveRecord(
                            empName, holidayDate, holidayDate, 1.0,
                            publicHolidayType.label(), null);
                    try {
                        writer.addVacation(workingPath, record, publicHolidayType);
                        written++;
                        employeesProcessed.add(empName);
                        // Eager cache eviction after each successful write
                        reader.evict(getMasterPath(year));
                    } catch (WorkingExcelWriter.ExcelWriteConflictException e) {
                        // Cell already occupied — safe to skip, re-sync is idempotent
                        log.debug("IndianHolidaySyncService: [conflict skip] {} {} — already has leave code",
                                empName, holidayDate);
                        skippedConflict++;
                    } catch (IOException e) {
                        log.warn("IndianHolidaySyncService: [write error] {} on {}: {}",
                                empName, holidayDate, e.getMessage());
                        skippedError++;
                    }
                }
            }
        }

        // ── Step 5: trigger working→master sync ───────────────────────────────
        if (written > 0) {
            syncService.triggerSync();
        }

        int totalSkipped = skippedWeekend + skippedConflict + skippedError;
        auditService.log("indian_holiday_synced", actingUser, null,
                "Synced " + filename + ": written=" + written
                        + " skippedWeekend=" + skippedWeekend
                        + " skippedConflict=" + skippedConflict
                        + " skippedError=" + skippedError
                        + " employees=" + employeesProcessed.size(),
                "success", "api");

        log.info("=== IndianHolidaySyncService: COMPLETE — written={} weekend={} conflict={} error={} employees={} ===",
                written, skippedWeekend, skippedConflict, skippedError, employeesProcessed.size());

        // Build user-facing message
        StringBuilder msg = new StringBuilder();
        if (written > 0) {
            msg.append("Sync complete. ").append(written)
               .append(" public holiday entr").append(written == 1 ? "y" : "ies")
               .append(" written for ").append(employeesProcessed.size()).append(" employee(s).");
        } else {
            msg.append("Sync complete. No new entries written");
            if (skippedConflict > 0) {
                msg.append(" (").append(skippedConflict).append(" already existed).");
            } else {
                msg.append(" — check city and employee mappings.");
            }
        }
        if (skippedWeekend > 0) {
            // divide by number of employees to get unique dates (each date skipped once per employee)
            int uniqueWeekendDates = weekendDatesLogged.size();
            msg.append(" ").append(uniqueWeekendDates)
               .append(" weekend public holiday date").append(uniqueWeekendDates == 1 ? "" : "s")
               .append(" skipped (Saturday/Sunday — not applicable as working days).");
        }
        if (skippedConflict > 0 && written > 0) {
            msg.append(" ").append(skippedConflict)
               .append(" already-existing entr").append(skippedConflict == 1 ? "y" : "ies").append(" skipped.");
        }

        return new SyncResult(written, totalSkipped, skippedWeekend, skippedConflict, employeesProcessed.size(), msg.toString());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds a map: normalizedCityValue → employee names.
     *
     * <p>The stored "city" field in employee-mapping.json is normalized with
     * {@link IndianHolidayMasterExcelParser#normalizeCity} before being used as the map key
     * so that lookups against the normalized holiday-map keys always succeed regardless
     * of case, leading/trailing spaces, or spaces around commas.
     */
    @SuppressWarnings("unchecked")
    private Map<String, List<String>> buildCityToEmployeesMap(Map<String, Object> mappingRoot) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        Object empObj = mappingRoot.get("employees");
        if (!(empObj instanceof Map)) return result;
        Map<String, Object> employees = (Map<String, Object>) empObj;
        for (Map.Entry<String, Object> e : employees.entrySet()) {
            String empName = e.getKey();
            if (!(e.getValue() instanceof Map)) continue;
            Map<String, Object> entry = (Map<String, Object>) e.getValue();
            Object cityObj = entry.get("city");
            if (cityObj == null) continue;
            // Normalize the stored city value — same method as used on the Excel side
            String cityValue = IndianHolidayMasterExcelParser.normalizeCity(String.valueOf(cityObj));
            if (cityValue.isEmpty()) continue;
            result.computeIfAbsent(cityValue, k -> new ArrayList<>()).add(empName);
        }
        return result;
    }

    private String getWorkingPath(int year) {
        return appState.getWorkingDir() + "/eIndkomst vacation " + year + ".xlsx";
    }

    private String getMasterPath(int year) {
        return appState.getDataDir() + "/eIndkomst vacation " + year + ".xlsx";
    }

    private void ensureWorkingCopy(String workingPath, int year) throws IOException {
        if (!new File(workingPath).exists()) {
            String masterPath = getMasterPath(year);
            if (new File(masterPath).exists()) {
                Files.createDirectories(Paths.get(workingPath).getParent());
                Files.copy(Paths.get(masterPath), Paths.get(workingPath),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    // ── Result type ───────────────────────────────────────────────────────────

    public static final class SyncResult {
        private final int    written;
        private final int    skipped;          // total skipped (weekend + conflict + error)
        private final int    skippedWeekend;   // holiday fell on Sat/Sun
        private final int    skippedConflict;  // cell already occupied
        private final int    employees;
        private final String message;

        public SyncResult(int written, int skipped, int skippedWeekend, int skippedConflict,
                          int employees, String message) {
            this.written          = written;
            this.skipped          = skipped;
            this.skippedWeekend   = skippedWeekend;
            this.skippedConflict  = skippedConflict;
            this.employees        = employees;
            this.message          = message;
        }

        public int    getWritten()         { return written; }
        public int    getSkipped()         { return skipped; }
        public int    getSkippedWeekend()  { return skippedWeekend; }
        public int    getSkippedConflict() { return skippedConflict; }
        public int    getEmployees()       { return employees; }
        public String getMessage()         { return message; }
    }
}
