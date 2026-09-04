package com.holidayleave.assistant.service;

import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates that the required master and working Excel files exist for every
 * calendar year covered by a requested vacation date range.
 *
 * <p>Only single-year and consecutive two-year ranges (Q4 → Q1) are supported.
 * Any range spanning more than two calendar years is treated as invalid.
 *
 * <p>Usage:
 * <pre>
 *   YearFileValidator.Result r = validator.validate(startDate, endDate, dataDir, workingDir);
 *   if (!r.isValid()) { ... r.getMissingFiles() ... }
 * </pre>
 */
@Service
public class YearFileValidator {

    /** File name pattern: eIndkomst vacation {YEAR}.xlsx */
    private static final String FILE_PATTERN = "eIndkomst vacation %d.xlsx";

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Validates file availability for every year touched by [startDate, endDate].
     *
     * @param startDate  vacation start date (inclusive)
     * @param endDate    vacation end date   (inclusive)
     * @param dataDir    absolute path to the master-file directory
     * @param workingDir absolute path to the working-file directory
     * @return a {@link Result} describing missing files, or all-valid if everything is present
     */
    public Result validate(LocalDate startDate, LocalDate endDate,
                           String dataDir, String workingDir) {
        int startYear = startDate.getYear();
        int endYear   = endDate.getYear();

        if (endYear - startYear > 1) {
            return Result.tooManyYears();
        }

        List<YearStatus> statuses = new ArrayList<>();
        for (int year = startYear; year <= endYear; year++) {
            String filename   = String.format(FILE_PATTERN, year);
            File   masterFile = new File(dataDir,    filename);
            File   workingFile = new File(workingDir, filename);

            // The working file is created on-demand from the master by ensureWorkingCopy,
            // so we only require the master to exist (working may be absent legitimately).
            boolean masterExists  = masterFile.exists()  && masterFile.isFile();
            boolean workingExists = workingFile.exists() && workingFile.isFile();
            statuses.add(new YearStatus(year, masterFile.getAbsolutePath(),
                    workingFile.getAbsolutePath(), masterExists, workingExists));
        }
        return new Result(statuses, false);
    }

    // ── Result ────────────────────────────────────────────────────────────────

    public static final class Result {
        private final List<YearStatus> yearStatuses;
        private final boolean tooManyYears;

        /** Package-private constructor used by tests and factory methods within this class. */
        Result(List<YearStatus> yearStatuses, boolean tooManyYears) {
            this.yearStatuses = yearStatuses;
            this.tooManyYears = tooManyYears;
        }

        /** Public factory for creating a valid result from a list of all-present statuses (used in tests). */
        public static Result valid(List<YearStatus> statuses) {
            return new Result(statuses, false);
        }

        private static Result tooManyYears() {
            return new Result(new ArrayList<>(), true);
        }

        /** True when all required master files are present (no missing data). */
        public boolean isValid() {
            if (tooManyYears) return false;
            for (YearStatus s : yearStatuses) {
                if (!s.isMasterExists()) return false;
            }
            return true;
        }

        public boolean isTooManyYears() { return tooManyYears; }

        public List<YearStatus> getYearStatuses() { return yearStatuses; }

        /** Returns only the statuses where the master file is missing. */
        public List<YearStatus> getMissingMasterStatuses() {
            List<YearStatus> missing = new ArrayList<>();
            for (YearStatus s : yearStatuses) {
                if (!s.isMasterExists()) missing.add(s);
            }
            return missing;
        }

        /** Returns distinct years covered by the date range (in ascending order). */
        public List<Integer> getYears() {
            List<Integer> years = new ArrayList<>();
            for (YearStatus s : yearStatuses) years.add(s.getYear());
            return years;
        }
    }

    // ── YearStatus ────────────────────────────────────────────────────────────

    public static final class YearStatus {
        private final int     year;
        private final String  masterPath;
        private final String  workingPath;
        private final boolean masterExists;
        private final boolean workingExists;

        public YearStatus(int year, String masterPath, String workingPath,
                          boolean masterExists, boolean workingExists) {
            this.year          = year;
            this.masterPath    = masterPath;
            this.workingPath   = workingPath;
            this.masterExists  = masterExists;
            this.workingExists = workingExists;
        }

        public int     getYear()          { return year; }
        public String  getMasterPath()    { return masterPath; }
        public String  getWorkingPath()   { return workingPath; }
        public boolean isMasterExists()   { return masterExists; }
        public boolean isWorkingExists()  { return workingExists; }
    }
}
