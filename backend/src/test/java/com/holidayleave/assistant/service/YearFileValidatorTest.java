package com.holidayleave.assistant.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link YearFileValidator}.
 *
 * Uses a temporary directory so no real Excel files are needed.
 * Files are created as empty placeholders — the validator only checks for existence.
 */
class YearFileValidatorTest {

    @TempDir Path dataDir;
    @TempDir Path workingDir;

    private YearFileValidator validator;

    @BeforeEach
    void setUp() {
        validator = new YearFileValidator();
    }

    // ── Single-year, files present ────────────────────────────────────────────

    @Test
    void singleYear_allFilesPresent_isValid() throws IOException {
        createFile(dataDir, "eIndkomst vacation 2026.xlsx");
        createFile(workingDir, "eIndkomst vacation 2026.xlsx");

        YearFileValidator.Result result = validator.validate(
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 5, 31),
                dataDir.toString(), workingDir.toString());

        assertTrue(result.isValid());
        assertFalse(result.isTooManyYears());
        assertEquals(Arrays.asList(2026), result.getYears());
        assertTrue(result.getMissingMasterStatuses().isEmpty());
    }

    @Test
    void singleYear_masterMissing_notValid() {
        // No files created — master is absent.
        YearFileValidator.Result result = validator.validate(
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 5, 31),
                dataDir.toString(), workingDir.toString());

        assertFalse(result.isValid());
        List<YearFileValidator.YearStatus> missing = result.getMissingMasterStatuses();
        assertEquals(1, missing.size());
        assertEquals(2026, missing.get(0).getYear());
        assertFalse(missing.get(0).isMasterExists());
    }

    @Test
    void singleYear_workingMissing_stillValid() throws IOException {
        // Only master present; working is legitimately absent (created on demand).
        createFile(dataDir, "eIndkomst vacation 2026.xlsx");

        YearFileValidator.Result result = validator.validate(
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 5, 31),
                dataDir.toString(), workingDir.toString());

        assertTrue(result.isValid(),
                "Should be valid when master exists, even if working copy is absent");
        assertEquals(1, result.getYears().size());
        assertEquals(2026, result.getYears().get(0));
    }

    // ── Two-year range (cross-year) ───────────────────────────────────────────

    @Test
    void twoYears_bothMastersPresent_isValid() throws IOException {
        createFile(dataDir, "eIndkomst vacation 2026.xlsx");
        createFile(dataDir, "eIndkomst vacation 2027.xlsx");

        YearFileValidator.Result result = validator.validate(
                LocalDate.of(2026, 12, 10), LocalDate.of(2027, 2, 14),
                dataDir.toString(), workingDir.toString());

        assertTrue(result.isValid());
        assertFalse(result.isTooManyYears());
        assertEquals(Arrays.asList(2026, 2027), result.getYears());
        assertTrue(result.getMissingMasterStatuses().isEmpty());
    }

    @Test
    void twoYears_nextYearMasterMissing_notValid() throws IOException {
        createFile(dataDir, "eIndkomst vacation 2026.xlsx");
        // 2027 master deliberately absent.

        YearFileValidator.Result result = validator.validate(
                LocalDate.of(2026, 12, 10), LocalDate.of(2027, 2, 14),
                dataDir.toString(), workingDir.toString());

        assertFalse(result.isValid());
        List<YearFileValidator.YearStatus> missing = result.getMissingMasterStatuses();
        assertEquals(1, missing.size());
        assertEquals(2027, missing.get(0).getYear());
    }

    @Test
    void twoYears_bothMastersMissing_bothReported() {
        YearFileValidator.Result result = validator.validate(
                LocalDate.of(2026, 12, 10), LocalDate.of(2027, 2, 14),
                dataDir.toString(), workingDir.toString());

        assertFalse(result.isValid());
        List<YearFileValidator.YearStatus> missing = result.getMissingMasterStatuses();
        assertEquals(2, missing.size());
    }

    @Test
    void twoYears_currentYearMasterMissing_notValid() throws IOException {
        // Only 2027 present; 2026 master is absent.
        createFile(dataDir, "eIndkomst vacation 2027.xlsx");

        YearFileValidator.Result result = validator.validate(
                LocalDate.of(2026, 12, 10), LocalDate.of(2027, 2, 14),
                dataDir.toString(), workingDir.toString());

        assertFalse(result.isValid());
        List<YearFileValidator.YearStatus> missing = result.getMissingMasterStatuses();
        assertEquals(1, missing.size());
        assertEquals(2026, missing.get(0).getYear());
    }

    // ── Scenario B: entire vacation in next year ──────────────────────────────

    @Test
    void nextYearOnly_masterPresent_isValid() throws IOException {
        createFile(dataDir, "eIndkomst vacation 2027.xlsx");

        YearFileValidator.Result result = validator.validate(
                LocalDate.of(2027, 1, 10), LocalDate.of(2027, 1, 31),
                dataDir.toString(), workingDir.toString());

        assertTrue(result.isValid());
        assertEquals(Arrays.asList(2027), result.getYears());
    }

    // ── Too-many-years guard ──────────────────────────────────────────────────

    @Test
    void threeYearRange_isTooManyYears() {
        YearFileValidator.Result result = validator.validate(
                LocalDate.of(2025, 6, 1), LocalDate.of(2027, 3, 31),
                dataDir.toString(), workingDir.toString());

        assertTrue(result.isTooManyYears());
        assertFalse(result.isValid());
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private void createFile(Path dir, String name) throws IOException {
        new File(dir.toFile(), name).createNewFile();
    }
}
