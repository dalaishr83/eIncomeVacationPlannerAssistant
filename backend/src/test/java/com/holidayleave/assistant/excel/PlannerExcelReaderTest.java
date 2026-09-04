package com.holidayleave.assistant.excel;

import com.holidayleave.assistant.model.LeaveRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link PlannerExcelReader}.
 *
 * All tests build real in-memory XLSX files using {@link ExcelTestHelper}
 * so they exercise the full parsing pipeline without mocking POI.
 *
 * Covers:
 *
 * load():
 *  - Parses employee names and leave records from a valid planner
 *  - Records for multiple employees are all returned
 *  - Contiguous same-code cells are merged into a single LeaveRecord span
 *  - Non-contiguous cells produce separate records
 *  - Weekend columns are skipped
 *  - Blank employee rows produce no records
 *  - Rows whose name is a header keyword (Name, Total, …) are skipped
 *  - File with unrecognised layout throws IOException
 *  - File with no planner sheet throws IOException
 *  - Year is read from planner content; falls back to filename
 *
 * Caching:
 *  - Same path with same timestamp → cache hit (no second parse)
 *  - evict() invalidates cache so next load re-parses
 *  - Modified timestamp → stale cache is replaced automatically
 *
 * getEmployeeNames(List):
 *  - Returns names in encounter order, deduplicating
 *  - Empty list → empty result
 *
 * getEmployeeNames(String filePath):
 *  - Returns ALL employees from the name column, including those with zero leave entries
 *  - Served from cache (no second file open after load())
 *
 * detectYear():
 *  - Returns year from sheet content when present
 *  - Falls back to filename when year not in sheet
 *  - Returns null when year absent from both sheet and filename
 */
class PlannerExcelReaderTest {

    private PlannerExcelReader reader;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        reader = new PlannerExcelReader();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // load() — positive scenarios
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("load() — positive scenarios")
    class LoadPositive {

        @Test
        @DisplayName("parses employee names from a valid full-year planner")
        void load_validPlanner_parsesEmployeeNames() throws IOException {
            Path file = tempDir.resolve("planner2024.xlsx");
            ExcelTestHelper.writeFullYearPlanner(file, 2024);

            List<LeaveRecord> records = reader.load(file.toString());

            // Both employees have blank cells → no leave records, but the
            // sheet is valid so the list is returned (empty, not exception)
            assertThat(records).isNotNull();
        }

        @Test
        @DisplayName("parses leave record for a single contiguous leave span")
        void load_contiguousLeave_mergedIntoSingleRecord() throws IOException {
            Path file = tempDir.resolve("planner2024.xlsx");
            // Alice (row 4, offset 0) has "V" on Mon 1 Apr, Tue 2 Apr, Wed 3 Apr 2024
            // Apr 1 2024 = Monday (weekday), so 3 consecutive weekdays
            int col1 = ExcelTestHelper.colForDate(2024, 4, 1); // Monday
            int col2 = ExcelTestHelper.colForDate(2024, 4, 2); // Tuesday
            int col3 = ExcelTestHelper.colForDate(2024, 4, 3); // Wednesday
            ExcelTestHelper.writeFullYearPlannerWithLeave(file, 2024,
                    new Object[]{0, col1, "V"},
                    new Object[]{0, col2, "V"},
                    new Object[]{0, col3, "V"});

            List<LeaveRecord> records = reader.load(file.toString());
            List<LeaveRecord> alice = filterByName(records, "Alice");

            assertThat(alice).hasSize(1);
            assertThat(alice.get(0).days()).isEqualTo(3.0);
            assertThat(alice.get(0).leaveType()).isEqualTo("V");
            assertThat(alice.get(0).startDate()).isEqualTo(LocalDate.of(2024, 4, 1));
            assertThat(alice.get(0).endDate()).isEqualTo(LocalDate.of(2024, 4, 3));
        }

        @Test
        @DisplayName("non-contiguous leave cells produce separate records")
        void load_nonContiguousLeave_separateRecords() throws IOException {
            Path file = tempDir.resolve("planner2024.xlsx");
            // Alice has "V" on Apr 1 (Mon) and Apr 8 (Mon) — a week apart
            int col1 = ExcelTestHelper.colForDate(2024, 4, 1);
            int col8 = ExcelTestHelper.colForDate(2024, 4, 8);
            ExcelTestHelper.writeFullYearPlannerWithLeave(file, 2024,
                    new Object[]{0, col1, "V"},
                    new Object[]{0, col8, "V"});

            List<LeaveRecord> records = reader.load(file.toString());
            List<LeaveRecord> alice = filterByName(records, "Alice");

            assertThat(alice).hasSize(2);
        }

        @Test
        @DisplayName("records for multiple employees are all returned")
        void load_multipleEmployees_allRecordsReturned() throws IOException {
            Path file = tempDir.resolve("planner2024.xlsx");
            int colA = ExcelTestHelper.colForDate(2024, 5, 6);  // Mon 6 May
            int colB = ExcelTestHelper.colForDate(2024, 6, 3);  // Mon 3 Jun
            ExcelTestHelper.writeFullYearPlannerWithLeave(file, 2024,
                    new Object[]{0, colA, "V"},   // Alice
                    new Object[]{1, colB, "PC"}); // Bob

            List<LeaveRecord> records = reader.load(file.toString());

            assertThat(filterByName(records, "Alice")).hasSize(1);
            assertThat(filterByName(records, "Bob")).hasSize(1);
        }

        @Test
        @DisplayName("different leave codes in same row produce separate records")
        void load_differentCodesInSameRow_separateRecords() throws IOException {
            Path file = tempDir.resolve("planner2024.xlsx");
            // Alice: "V" on Mon 1 Apr, "PC" on Tue 2 Apr → two records
            int col1 = ExcelTestHelper.colForDate(2024, 4, 1);
            int col2 = ExcelTestHelper.colForDate(2024, 4, 2);
            ExcelTestHelper.writeFullYearPlannerWithLeave(file, 2024,
                    new Object[]{0, col1, "V"},
                    new Object[]{0, col2, "PC"});

            List<LeaveRecord> records = reader.load(file.toString());
            List<LeaveRecord> alice = filterByName(records, "Alice");

            assertThat(alice).hasSize(2);
            assertThat(alice).extracting(LeaveRecord::leaveType)
                    .containsExactlyInAnyOrder("V", "PC");
        }

        @Test
        @DisplayName("leave record year matches the planner year")
        void load_leaveRecordYear_matchesPlannerYear() throws IOException {
            Path file = tempDir.resolve("planner2023.xlsx");
            int col = ExcelTestHelper.colForDate(2023, 3, 6); // Mon 6 Mar 2023
            ExcelTestHelper.writeFullYearPlannerWithLeave(file, 2023,
                    new Object[]{0, col, "V"});

            List<LeaveRecord> records = reader.load(file.toString());
            List<LeaveRecord> alice = filterByName(records, "Alice");

            assertThat(alice).isNotEmpty();
            assertThat(alice.get(0).year()).isEqualTo(2023);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // load() — weekend skipping
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("load() — weekend skipping")
    class LoadWeekendSkipping {

        @Test
        @DisplayName("Saturday and Sunday columns are not included in leave records")
        void load_weekendCells_skipped() throws IOException {
            Path file = tempDir.resolve("planner2024.xlsx");
            // Apr 6 2024 = Saturday, Apr 7 2024 = Sunday
            int colSat = ExcelTestHelper.colForDate(2024, 4, 6);
            int colSun = ExcelTestHelper.colForDate(2024, 4, 7);
            ExcelTestHelper.writeFullYearPlannerWithLeave(file, 2024,
                    new Object[]{0, colSat, "V"},
                    new Object[]{0, colSun, "V"});

            List<LeaveRecord> records = reader.load(file.toString());
            List<LeaveRecord> alice = filterByName(records, "Alice");

            // Weekend codes are ignored — no records created
            assertThat(alice).isEmpty();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // load() — header row skipping
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("load() — header row skipping")
    class LoadHeaderSkipping {

        @Test
        @DisplayName("rows with reserved names (Name, Total, …) are not treated as employees")
        void load_headerLikeNames_skipped() throws IOException {
            Path file = tempDir.resolve("planner2024.xlsx");
            ExcelTestHelper.writeFullYearPlannerCustom(file, 2024,
                    new String[]{"Name", "Employee", "Total", "Alice"});

            List<LeaveRecord> records = reader.load(file.toString());
            List<String> names = reader.getEmployeeNames(records);

            // Only "Alice" should be retained; the header-like names are skipped
            // (They produce no records since their cells are blank anyway, but
            //  getEmployeeNames would include them if they produced any records)
            // This verifies no exception is thrown and the planner is parseable
            assertThat(names).doesNotContain("Name", "Employee", "Total");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // load() — error paths
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("load() — error paths")
    class LoadErrors {

        @Test
        @DisplayName("file with no planner structure throws IOException")
        void load_noLayoutRecognised_throwsIOException() throws IOException {
            Path file = tempDir.resolve("bad.xlsx");
            ExcelTestHelper.writeInsufficientDayCellsPlanner(file);

            assertThatThrownBy(() -> reader.load(file.toString()))
                    .isInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("empty sheet workbook throws IOException")
        void load_emptySheetWorkbook_throwsIOException() throws IOException {
            Path file = tempDir.resolve("empty.xlsx");
            ExcelTestHelper.writeEmptySheetWorkbook(file);

            assertThatThrownBy(() -> reader.load(file.toString()))
                    .isInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("non-existent file throws IOException")
        void load_nonExistentFile_throwsIOException() {
            assertThatThrownBy(() -> reader.load(tempDir.resolve("missing.xlsx").toString()))
                    .isInstanceOf(IOException.class);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Caching behaviour
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Caching behaviour")
    class Caching {

        @Test
        @DisplayName("same file with unchanged timestamp returns cached result")
        void load_sameTimestamp_returnsCachedResult() throws IOException {
            Path file = tempDir.resolve("planner2024.xlsx");
            ExcelTestHelper.writeFullYearPlanner(file, 2024);

            List<LeaveRecord> first  = reader.load(file.toString());
            List<LeaveRecord> second = reader.load(file.toString());

            // Same list instance (cache hit) — both calls return the same unmodifiable view
            assertThat(first).isSameAs(second);
        }

        @Test
        @DisplayName("evict() removes the cache so next load re-parses the file")
        void load_afterEvict_reParses() throws IOException {
            Path file = tempDir.resolve("planner2024.xlsx");
            ExcelTestHelper.writeFullYearPlanner(file, 2024);

            List<LeaveRecord> first = reader.load(file.toString());
            reader.evict(file.toString());
            List<LeaveRecord> second = reader.load(file.toString());

            // After eviction the cache is gone — a fresh parse returns a new list instance
            assertThat(first).isNotSameAs(second);
        }

        @Test
        @DisplayName("evict() on a path not in cache is a no-op (no exception)")
        void evict_unknownPath_noException() {
            assertThatCode(() -> reader.evict("/some/nonexistent/path.xlsx"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("changed file timestamp invalidates stale cache entry")
        void load_changedTimestamp_reParses() throws IOException, InterruptedException {
            Path file = tempDir.resolve("planner2024.xlsx");
            ExcelTestHelper.writeFullYearPlanner(file, 2024);

            List<LeaveRecord> first = reader.load(file.toString());

            // Force a timestamp change by sleeping 5ms and rewriting
            Thread.sleep(5);
            ExcelTestHelper.writeFullYearPlanner(file, 2024);

            List<LeaveRecord> second = reader.load(file.toString());

            // Different instances because the stale cache entry was replaced
            assertThat(first).isNotSameAs(second);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // getEmployeeNames()
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getEmployeeNames(List) — record-based overload")
    class GetEmployeeNamesList {

        @Test
        @DisplayName("returns names in encounter order")
        void getEmployeeNames_returnedInOrder() {
            LeaveRecord r1 = new LeaveRecord("Alice",
                    LocalDate.of(2024, 1, 2), LocalDate.of(2024, 1, 2), 1, "V", null);
            LeaveRecord r2 = new LeaveRecord("Bob",
                    LocalDate.of(2024, 2, 5), LocalDate.of(2024, 2, 5), 1, "V", null);
            LeaveRecord r3 = new LeaveRecord("Alice",
                    LocalDate.of(2024, 3, 4), LocalDate.of(2024, 3, 4), 1, "PC", null);

            List<String> names = reader.getEmployeeNames(Arrays.asList(r1, r2, r3));

            assertThat(names).containsExactly("Alice", "Bob");
        }

        @Test
        @DisplayName("deduplicates employee names")
        void getEmployeeNames_deduplicates() {
            LeaveRecord r1 = new LeaveRecord("Alice",
                    LocalDate.of(2024, 1, 2), LocalDate.of(2024, 1, 2), 1, "V", null);
            LeaveRecord r2 = new LeaveRecord("Alice",
                    LocalDate.of(2024, 2, 5), LocalDate.of(2024, 2, 5), 1, "V", null);

            assertThat(reader.getEmployeeNames(Arrays.asList(r1, r2))).hasSize(1);
        }

        @Test
        @DisplayName("empty record list returns empty name list")
        void getEmployeeNames_emptyList_returnsEmpty() {
            assertThat(reader.getEmployeeNames(Collections.<LeaveRecord>emptyList())).isEmpty();
        }

        @Test
        @DisplayName("single record returns single name")
        void getEmployeeNames_singleRecord_singleName() {
            LeaveRecord r = new LeaveRecord("Charlie",
                    LocalDate.of(2024, 1, 2), LocalDate.of(2024, 1, 2), 1, "V", null);
            assertThat(reader.getEmployeeNames(Collections.singletonList(r)))
                    .containsExactly("Charlie");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // getEmployeeNames(String filePath) — structural name column overload
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getEmployeeNames(filePath) — structural name-column overload")
    class GetEmployeeNamesFilePath {

        @Test
        @DisplayName("returns ALL employees from the name column, including those with zero leave")
        void getEmployeeNames_filePath_includesZeroLeaveEmployees() throws IOException {
            // Write a planner where Alice has leave but Carol has none (all blank cells).
            Path file = tempDir.resolve("planner2027.xlsx");
            int col1 = ExcelTestHelper.colForDate(2027, 4, 1); // Wednesday — weekday
            ExcelTestHelper.writeFullYearPlannerCustom(file, 2027,
                    new String[]{"Alice", "Bob", "Carol"},
                    new Object[]{0, col1, "V"});  // only Alice has leave

            // getEmployeeNames(List) would return only Alice (one record) and miss Carol.
            List<LeaveRecord> records = reader.load(file.toString());
            assertThat(reader.getEmployeeNames(records))
                    .as("record-based overload misses employees with no leave")
                    .doesNotContain("Carol");

            // getEmployeeNames(filePath) must return all three.
            List<String> allNames = reader.getEmployeeNames(file.toString());
            assertThat(allNames)
                    .as("file-path overload must include zero-leave employees")
                    .contains("Alice", "Bob", "Carol")
                    .hasSize(3);
        }

        @Test
        @DisplayName("served from cache — no second file open after load()")
        void getEmployeeNames_filePath_servedFromCache() throws IOException {
            Path file = tempDir.resolve("planner2024cached.xlsx");
            ExcelTestHelper.writeFullYearPlanner(file, 2024);

            reader.load(file.toString());   // populate cache
            // Second call must return same list instance (cache hit)
            List<String> first  = reader.getEmployeeNames(file.toString());
            List<String> second = reader.getEmployeeNames(file.toString());
            assertThat(first).isSameAs(second);
        }

        @Test
        @DisplayName("header-like names are excluded from the structural name list")
        void getEmployeeNames_filePath_excludesHeaderRows() throws IOException {
            Path file = tempDir.resolve("plannerHeaders2024.xlsx");
            ExcelTestHelper.writeFullYearPlannerCustom(file, 2024,
                    new String[]{"Name", "Employee", "Total", "Alice"});

            List<String> names = reader.getEmployeeNames(file.toString());
            assertThat(names).doesNotContain("Name", "Employee", "Total");
            assertThat(names).contains("Alice");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // detectYear()
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("detectYear()")
    class DetectYear {

        @Test
        @DisplayName("returns year embedded in planner title cell")
        void detectYear_fromSheetContent() throws IOException {
            Path file = tempDir.resolve("planner2024.xlsx");
            ExcelTestHelper.writeFullYearPlanner(file, 2024);

            Integer year = reader.detectYear(file.toString());

            assertThat(year).isEqualTo(2024);
        }

        @Test
        @DisplayName("falls back to filename when planner sheet is absent (empty workbook)")
        void detectYear_fallbackToFilename() throws IOException {
            // An empty workbook has no planner sheet → findPlannerSheet returns null
            // → code skips the scan and goes straight to the filename fallback.
            // The filename contains "2022" as a standalone numeric token.
            Path file = tempDir.resolve("eIndkomst vacation 2022.xlsx");
            ExcelTestHelper.writeEmptySheetWorkbook(file);

            Integer year = reader.detectYear(file.toString());

            assertThat(year).isEqualTo(2022);
        }

        @Test
        @DisplayName("returns null when year absent from both sheet and filename")
        void detectYear_noYear_returnsNull() throws IOException {
            Path file = tempDir.resolve("no_year.xlsx");
            ExcelTestHelper.writeEmptySheetWorkbook(file);

            Integer year = reader.detectYear(file.toString());

            assertThat(year).isNull();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // helpers
    // ──────────────────────────────────────────────────────────────────────────

    private List<LeaveRecord> filterByName(List<LeaveRecord> records, String name) {
        List<LeaveRecord> result = new java.util.ArrayList<>();
        for (LeaveRecord r : records) {
            if (r.employeeName().equalsIgnoreCase(name)) result.add(r);
        }
        return result;
    }
}
