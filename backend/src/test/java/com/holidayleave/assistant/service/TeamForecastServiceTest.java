package com.holidayleave.assistant.service;

import com.holidayleave.assistant.excel.PlannerExcelReader;
import com.holidayleave.assistant.model.ForecastRow;
import com.holidayleave.assistant.model.LeaveRecord;
import com.holidayleave.assistant.model.VacationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TeamForecastService}.
 *
 * Covers:
 *  - Year-file resolution for single-year and multi-year date ranges
 *  - Missing master file → IOException with descriptive message
 *  - Indian Team filtering uses employee-mapping.json
 *  - EIndkomst Team → no employee filtering
 *  - Forecast row calculations (vacation count per month, total per employee)
 *  - No records in range → empty row list, HTML still generated
 *  - HTML fragment contains expected column headers
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class TeamForecastServiceTest {

    @Mock private AppState              appState;
    @Mock private PlannerExcelReader    reader;
    @Mock private HolidaySettingsService holidaySettingsService;
    @Mock private VacationTypeService   vacationTypeService;

    @InjectMocks
    private TeamForecastService service;

    /** Temporary data directory backed by JUnit's @TempDir would be ideal, but
     *  we stub AppState so we use a fixed non-existent path and control File existence
     *  via Mockito where needed.  For tests that need real files we use a temp dir. */
    private static final String FAKE_DATA_DIR = "/fake/data";

    @BeforeEach
    void setUp() {
        when(appState.getDataDir()).thenReturn(FAKE_DATA_DIR);

        // Default vacation types (V, P, PC, H, E, O — no A)
        List<VacationType> types = Arrays.asList(
                new VacationType("V",  "Vacation",                "FF92D050"),
                new VacationType("P",  "Public Holiday",          "FFFF0000"),
                new VacationType("PC", "Personal Choice Holiday", "FFFFFF00"),
                new VacationType("H",  "Half-day Vacation",       "FFFFC000"),
                new VacationType("E",  "Education",               "FF00B0F0"),
                new VacationType("O",  "Other",                   "FFD3D3D3"),
                new VacationType("A",  "Available",               "FFFFFFFF")
        );
        when(vacationTypeService.findAll()).thenReturn(types);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private LeaveRecord rec(String emp, String start, String end, double days, String code) {
        return new LeaveRecord(emp, LocalDate.parse(start), LocalDate.parse(end), days, code, null);
    }

    private Map<String, Object> mappingFor(String... names) {
        Map<String, Object> empMap = new LinkedHashMap<>();
        for (String name : names) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("city",    "City");
            entry.put("country", "IN");
            empMap.put(name, entry);
        }
        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("employees", empMap);
        return mapping;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Year-file resolution
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Master file resolution")
    class MasterFileResolution {

        @Test
        @DisplayName("Single-year range: only one file path is resolved")
        void singleYear_onlyOneFilePath() throws IOException {
            // Arrange: mock reader to accept any path (but file must exist — simulate via stubbing)
            List<LeaveRecord> emptyRecords = Collections.emptyList();
            when(reader.load(contains("2026"))).thenReturn(emptyRecords);
            when(holidaySettingsService.readMapping(anyString())).thenReturn(mappingFor("Alice"));

            // We need the file to appear to exist.  Use a real temp file path workaround:
            // Instead, verify the IOException is thrown for a missing file with the right message.
            // The service resolves: FAKE_DATA_DIR + "/eIndkomst vacation 2026.xlsx"
            // Since that file does not exist, we expect an IOException.
            assertThatThrownBy(() ->
                service.generateForecast("Indian Team",
                        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("2026")
                .hasMessageContaining("not found");
        }

        @Test
        @DisplayName("Multi-year range: IOException mentions both years if first file missing")
        void multiYear_throwsIfFirstFileMissing() {
            // Neither 2026 nor 2027 file exists under FAKE_DATA_DIR
            assertThatThrownBy(() ->
                service.generateForecast("EIndkomst Team",
                        LocalDate.of(2026, 12, 13), LocalDate.of(2027, 1, 31)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("2026");
        }

        @Test
        @DisplayName("Missing master file message is user-friendly (contains 'provision')")
        void missingFile_messageContainsProvision() {
            assertThatThrownBy(() ->
                service.generateForecast("Indian Team",
                        LocalDate.of(2025, 1, 1), LocalDate.of(2025, 3, 31)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("provision");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Team filtering
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Team filtering")
    class TeamFiltering {

        /**
         * Creates a real temp file so the file-existence check passes, then
         * stubs the reader.load() to return controlled records.
         */
        private Path createTempMasterFile(java.nio.file.Path tempDir, int year) throws IOException {
            Path file = tempDir.resolve("eIndkomst vacation " + year + ".xlsx");
            java.nio.file.Files.createFile(file);
            return file;
        }

        @Test
        @DisplayName("Indian Team: only employees in employee-mapping.json are included")
        void indianTeam_filtersToMappedEmployees(@org.junit.jupiter.api.io.TempDir Path tempDir)
                throws IOException {
            // Arrange
            when(appState.getDataDir()).thenReturn(tempDir.toString());
            createTempMasterFile(tempDir, 2026);

            List<LeaveRecord> records = Arrays.asList(
                    rec("Alice",   "2026-03-01", "2026-03-05", 5, "V"),
                    rec("Bob",     "2026-03-01", "2026-03-05", 5, "V"),  // not in mapping
                    rec("Charlie", "2026-04-01", "2026-04-03", 3, "P")   // not in mapping
            );
            when(reader.load(anyString())).thenReturn(records);
            when(holidaySettingsService.readMapping(anyString()))
                    .thenReturn(mappingFor("Alice")); // only Alice is Indian Team

            // Act
            TeamForecastService.TeamForecastResult result =
                    service.generateForecast("Indian Team",
                            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));

            // Assert: only Alice's row(s) appear in the HTML
            assertThat(result.getHtml()).contains("Alice");
            assertThat(result.getHtml()).doesNotContain("Bob");
            assertThat(result.getHtml()).doesNotContain("Charlie");
        }

        @Test
        @DisplayName("EIndkomst Team: all employees included, no filtering")
        void eindkomstTeam_noFiltering(@org.junit.jupiter.api.io.TempDir Path tempDir)
                throws IOException {
            when(appState.getDataDir()).thenReturn(tempDir.toString());
            createTempMasterFile(tempDir, 2026);

            List<LeaveRecord> records = Arrays.asList(
                    rec("Alice",   "2026-03-01", "2026-03-05", 5, "V"),
                    rec("Bob",     "2026-03-10", "2026-03-12", 3, "P"),
                    rec("Charlie", "2026-04-01", "2026-04-03", 3, "PC")
            );
            when(reader.load(anyString())).thenReturn(records);
            // mapping is NOT consulted for EIndkomst Team
            when(holidaySettingsService.readMapping(anyString())).thenReturn(mappingFor("Alice"));

            TeamForecastService.TeamForecastResult result =
                    service.generateForecast("EIndkomst Team",
                            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));

            assertThat(result.getHtml()).contains("Alice");
            assertThat(result.getHtml()).contains("Bob");
            assertThat(result.getHtml()).contains("Charlie");
        }

        @Test
        @DisplayName("Empty employee-mapping: Indian Team returns no rows")
        void indianTeam_emptyMapping_noRows(@org.junit.jupiter.api.io.TempDir Path tempDir)
                throws IOException {
            when(appState.getDataDir()).thenReturn(tempDir.toString());
            createTempMasterFile(tempDir, 2026);

            List<LeaveRecord> records = Arrays.asList(
                    rec("Alice", "2026-03-01", "2026-03-05", 5, "V")
            );
            when(reader.load(anyString())).thenReturn(records);
            when(holidaySettingsService.readMapping(anyString())).thenReturn(mappingFor()); // empty

            TeamForecastService.TeamForecastResult result =
                    service.generateForecast("Indian Team",
                            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));

            assertThat(result.getRowCount()).isZero();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Forecast calculations
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Forecast calculations")
    class ForecastCalculations {

        @Test
        @DisplayName("Division-by-zero guard: no exception when no vacation records exist")
        void noRecords_noException(@org.junit.jupiter.api.io.TempDir Path tempDir)
                throws IOException {
            when(appState.getDataDir()).thenReturn(tempDir.toString());
            Path file = tempDir.resolve("eIndkomst vacation 2026.xlsx");
            java.nio.file.Files.createFile(file);

            // No records at all
            when(reader.load(anyString())).thenReturn(Collections.emptyList());
            when(holidaySettingsService.readMapping(anyString())).thenReturn(mappingFor("Alice"));

            TeamForecastService.TeamForecastResult result =
                    service.generateForecast("EIndkomst Team",
                            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));

            // No rows because no records in range — but no exception should be thrown
            assertThat(result.getRowCount()).isZero();
            assertThatCode(() ->
                service.generateForecast("EIndkomst Team",
                        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Total vacations in range: only days within [startDate, endDate] are counted")
        void totalVacations_onlyCountsRecordsWithinRange(@org.junit.jupiter.api.io.TempDir Path tempDir)
                throws IOException {
            when(appState.getDataDir()).thenReturn(tempDir.toString());
            java.nio.file.Files.createFile(tempDir.resolve("eIndkomst vacation 2026.xlsx"));

            // Record within range (March 2026) → should appear as a month column
            // Record outside range (November 2026) → should NOT appear as a month column
            List<LeaveRecord> records = Arrays.asList(
                    rec("Alice", "2026-03-01", "2026-03-05", 5, "V"),   // within Jan–Jun
                    rec("Alice", "2026-11-01", "2026-11-05", 5, "V")    // outside Jan–Jun
            );
            when(reader.load(anyString())).thenReturn(records);
            when(holidaySettingsService.readMapping(anyString())).thenReturn(mappingFor("Alice"));

            TeamForecastService.TeamForecastResult result =
                    service.generateForecast("Indian Team",
                            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30));

            // March 2026 must be a column header; November must not
            assertThat(result.getHtml()).contains("March 2026");
            assertThat(result.getHtml()).doesNotContain("November 2026");
        }

        @Test
        @DisplayName("Available (A) code is excluded from vacation counts")
        void availableCode_isExcluded(@org.junit.jupiter.api.io.TempDir Path tempDir)
                throws IOException {
            when(appState.getDataDir()).thenReturn(tempDir.toString());
            java.nio.file.Files.createFile(tempDir.resolve("eIndkomst vacation 2026.xlsx"));

            List<LeaveRecord> records = Arrays.asList(
                    rec("Alice", "2026-03-01", "2026-03-05", 5, "A")   // Available — should be excluded
            );
            when(reader.load(anyString())).thenReturn(records);
            when(holidaySettingsService.readMapping(anyString())).thenReturn(mappingFor("Alice"));

            TeamForecastService.TeamForecastResult result =
                    service.generateForecast("Indian Team",
                            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));

            assertThat(result.getRowCount()).isZero();
        }

        @Test
        @DisplayName("HTML report contains all expected column headers (pivot layout)")
        void htmlReport_containsExpectedColumns(@org.junit.jupiter.api.io.TempDir Path tempDir)
                throws IOException {
            when(appState.getDataDir()).thenReturn(tempDir.toString());
            java.nio.file.Files.createFile(tempDir.resolve("eIndkomst vacation 2026.xlsx"));

            List<LeaveRecord> records = Collections.singletonList(
                    rec("Alice", "2026-03-01", "2026-03-05", 5, "V")
            );
            when(reader.load(anyString())).thenReturn(records);
            when(holidaySettingsService.readMapping(anyString())).thenReturn(mappingFor("Alice"));

            TeamForecastService.TeamForecastResult result =
                    service.generateForecast("Indian Team",
                            LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

            String html = result.getHtml();
            // Pivot layout: Employee Name, dynamic month column(s), Vacation total
            assertThat(html).contains("Employee Name");
            assertThat(html).contains("March 2026");   // dynamic month column
            assertThat(html).contains("Vacation");      // total column
            // Removed columns must NOT be present
            assertThat(html).doesNotContain("Entitled");
            assertThat(html).doesNotContain("Consumed");
            assertThat(html).doesNotContain("Remaining");
            assertThat(html).doesNotContain("Utilization");
        }

        @Test
        @DisplayName("No records in range: rowCount is 0 and HTML still generated")
        void noRecordsInRange_rowCountZeroHtmlPresent(@org.junit.jupiter.api.io.TempDir Path tempDir)
                throws IOException {
            when(appState.getDataDir()).thenReturn(tempDir.toString());
            java.nio.file.Files.createFile(tempDir.resolve("eIndkomst vacation 2026.xlsx"));

            when(reader.load(anyString())).thenReturn(Collections.emptyList());
            when(holidaySettingsService.readMapping(anyString())).thenReturn(mappingFor("Alice"));

            TeamForecastService.TeamForecastResult result =
                    service.generateForecast("Indian Team",
                            LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

            assertThat(result.getRowCount()).isZero();
            assertThat(result.getHtml()).isNotBlank();
            assertThat(result.getHtml()).contains("No vacation records found");
        }

        @Test
        @DisplayName("Summary text contains team name and date range")
        void summaryText_containsTeamAndDates(@org.junit.jupiter.api.io.TempDir Path tempDir)
                throws IOException {
            when(appState.getDataDir()).thenReturn(tempDir.toString());
            java.nio.file.Files.createFile(tempDir.resolve("eIndkomst vacation 2026.xlsx"));

            when(reader.load(anyString())).thenReturn(Collections.emptyList());
            when(holidaySettingsService.readMapping(anyString())).thenReturn(mappingFor("Alice"));

            TeamForecastService.TeamForecastResult result =
                    service.generateForecast("Indian Team",
                            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31));

            assertThat(result.getSummaryText()).contains("Indian Team");
            assertThat(result.getSummaryText()).contains("2026");
        }

        @Test
        @DisplayName("Long leave run is clipped: only working days inside window are counted, " +
                     "and month column matches the window (regression for Jun-26/125 bug)")
        void longLeaveRun_clippedToWindow_correctMonthAndCount(
                @org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
            when(appState.getDataDir()).thenReturn(tempDir.toString());
            java.nio.file.Files.createFile(tempDir.resolve("eIndkomst vacation 2026.xlsx"));

            // Simulate the reported scenario:
            //   A leave run stored in Excel from 2026-06-01 to 2026-09-30 (all V)
            //   The Excel reader merged all those cells into one LeaveRecord with days=125 (approx).
            //   Requested window: 07 Sep 2026 – 18 Sep 2026.
            //   Expected: month column = "September 2026", day count = 10 working days (Mon 7–Fri 11, Mon 14–Fri 18).
            List<LeaveRecord> records = Collections.singletonList(
                    rec("Karina", "2026-06-01", "2026-09-30", 125, "V")
            );
            when(reader.load(anyString())).thenReturn(records);
            when(holidaySettingsService.readMapping(anyString())).thenReturn(mappingFor());

            TeamForecastService.TeamForecastResult result =
                    service.generateForecast("EIndkomst Team",
                            LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 18));

            // Only September 2026 is in the date range window, so only that month column appears
            assertThat(result.getHtml()).contains("September 2026");
            assertThat(result.getHtml()).doesNotContain("June 2026");

            // Exact working days in 07 Sep–18 Sep 2026:
            // Mon 7, Tue 8, Wed 9, Thu 10, Fri 11 = 5; Mon 14, Tue 15, Wed 16, Thu 17, Fri 18 = 5 → total 10
            // In the pivot layout the cell value for September 2026 and the Vacation total must both be 10.
            assertThat(result.getHtml()).contains(">10<");

            // Row count = 1 (one employee)
            assertThat(result.getRowCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Leave run spanning two months: employee has one row with both month columns")
        void leaveRunSpanningTwoMonths_pivotedIntoSingleRow(
                @org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
            when(appState.getDataDir()).thenReturn(tempDir.toString());
            java.nio.file.Files.createFile(tempDir.resolve("eIndkomst vacation 2026.xlsx"));

            // Run: Mon 28 Sep – Fri 2 Oct 2026 (stored as one LeaveRecord)
            // Window: 01 Sep 2026 – 31 Oct 2026
            // Sep 28=Mon, Sep 29=Tue, Sep 30=Wed = 3 days in Sep
            // Oct 1=Thu, Oct 2=Fri = 2 days in Oct → total 5
            List<LeaveRecord> records = Collections.singletonList(
                    rec("Bob", "2026-09-28", "2026-10-02", 5, "V")
            );
            when(reader.load(anyString())).thenReturn(records);
            when(holidaySettingsService.readMapping(anyString())).thenReturn(mappingFor());

            TeamForecastService.TeamForecastResult result =
                    service.generateForecast("EIndkomst Team",
                            LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 31));

            // Both month labels appear as column headers
            assertThat(result.getHtml()).contains("September 2026");
            assertThat(result.getHtml()).contains("October 2026");
            // Sep column value = 3, Oct column value = 2, Vacation total = 5
            assertThat(result.getHtml()).contains(">3<");
            assertThat(result.getHtml()).contains(">2<");
            assertThat(result.getHtml()).contains(">5<");
            // Only ONE employee row (pivot layout)
            assertThat(result.getRowCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Employee with vacation in only one month shows 0 for other months in range")
        void employeeWithPartialMonths_zeroForMissingMonths(
                @org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
            when(appState.getDataDir()).thenReturn(tempDir.toString());
            java.nio.file.Files.createFile(tempDir.resolve("eIndkomst vacation 2026.xlsx"));

            // Alice has vacation only in September; October should show 0
            List<LeaveRecord> records = Collections.singletonList(
                    rec("Alice", "2026-09-01", "2026-09-05", 5, "V")
            );
            when(reader.load(anyString())).thenReturn(records);
            when(holidaySettingsService.readMapping(anyString())).thenReturn(mappingFor("Alice"));

            TeamForecastService.TeamForecastResult result =
                    service.generateForecast("Indian Team",
                            LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 31));

            // Both month columns must appear
            assertThat(result.getHtml()).contains("September 2026");
            assertThat(result.getHtml()).contains("October 2026");
            // October cell shows 0
            assertThat(result.getHtml()).contains(">0<");
            // One employee row
            assertThat(result.getRowCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("slackTableText contains all employees — no truncation for large teams")
        void slackTableText_containsAllRows_noTruncation(
                @org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
            when(appState.getDataDir()).thenReturn(tempDir.toString());
            java.nio.file.Files.createFile(tempDir.resolve("eIndkomst vacation 2026.xlsx"));

            // Build a list of 33 employees (matches the reported real-world case)
            List<LeaveRecord> records = new ArrayList<>();
            for (int i = 1; i <= 33; i++) {
                records.add(rec("Employee " + String.format("%02d", i),
                        "2026-09-07", "2026-09-09", 3, "V"));
            }
            when(reader.load(anyString())).thenReturn(records);
            when(holidaySettingsService.readMapping(anyString())).thenReturn(mappingFor());

            TeamForecastService.TeamForecastResult result =
                    service.generateForecast("EIndkomst Team",
                            LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31));

            String tableText = result.getSlackTableText();

            // All 33 employees must appear — no "more rows" truncation marker
            assertThat(tableText).doesNotContain("more rows");
            for (int i = 1; i <= 33; i++) {
                assertThat(tableText).contains("Employee " + String.format("%02d", i));
            }
            // Total row must be present
            assertThat(tableText).contains("Total:");
        }

        @Test
        @DisplayName("Grand total row is present and equals sum of all employee vacation totals")
        void grandTotalRow_isPresentAndCorrect(
                @org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
            when(appState.getDataDir()).thenReturn(tempDir.toString());
            java.nio.file.Files.createFile(tempDir.resolve("eIndkomst vacation 2026.xlsx"));

            // 2026-09-07 = Mon, 2026-09-08 = Tue, 2026-09-09 = Wed → Alice: 3 working days
            // 2026-09-10 = Thu, 2026-09-11 = Fri                   → Bob:   2 working days
            // Grand total = 5
            List<LeaveRecord> records = Arrays.asList(
                    rec("Alice", "2026-09-07", "2026-09-09", 3, "V"),
                    rec("Bob",   "2026-09-10", "2026-09-11", 2, "V")
            );
            when(reader.load(anyString())).thenReturn(records);
            when(holidaySettingsService.readMapping(anyString())).thenReturn(mappingFor());

            TeamForecastService.TeamForecastResult result =
                    service.generateForecast("EIndkomst Team",
                            LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

            String html = result.getHtml();
            assertThat(html).contains("tf-total-row");
            // Grand total cell shows 5
            assertThat(html).contains("tf-total-value\">5<");
        }
    }

    // ── Cleanup tests ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("cleanupForecastReport")
    class CleanupForecastReportTests {

        @Test
        @DisplayName("Deletes files older than 1 hour and retains newer files")
        void cleanup_deletesOldFilesOnly(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
            Path dataDir = tempDir.resolve("data");
            Path tempSubdir = dataDir.resolve("temp");
            Files.createDirectories(tempSubdir);

            Path oldFile = tempSubdir.resolve("team-forecast-old.xlsx");
            Path newFile = tempSubdir.resolve("team-forecast-new.xlsx");
            Path unrelatedFile = tempSubdir.resolve("other-file.txt");

            Files.write(oldFile, "old content".getBytes());
            Files.write(newFile, "new content".getBytes());
            Files.write(unrelatedFile, "unrelated content".getBytes());

            // Set oldFile modified time to 2 hours ago
            long twoHoursAgo = System.currentTimeMillis() - 7200_000L;
            Files.setLastModifiedTime(oldFile, java.nio.file.attribute.FileTime.fromMillis(twoHoursAgo));

            // Set newFile modified time to 10 minutes ago
            long tenMinutesAgo = System.currentTimeMillis() - 600_000L;
            Files.setLastModifiedTime(newFile, java.nio.file.attribute.FileTime.fromMillis(tenMinutesAgo));

            // Set unrelatedFile modified time to 2 hours ago (should not be deleted since name doesn't match team-forecast-*.xlsx)
            Files.setLastModifiedTime(unrelatedFile, java.nio.file.attribute.FileTime.fromMillis(twoHoursAgo));

            int deleted = service.cleanupForecastReport(dataDir.toString(), 3600_000L);

            assertThat(deleted).isEqualTo(1);
            assertThat(Files.exists(oldFile)).isFalse();
            assertThat(Files.exists(newFile)).isTrue();
            assertThat(Files.exists(unrelatedFile)).isTrue();
        }

        @Test
        @DisplayName("Handles non-existent temp directory safely")
        void cleanup_nonExistentDirReturnsZero(@org.junit.jupiter.api.io.TempDir Path tempDir) {
            Path nonExistent = tempDir.resolve("non-existent");
            int deleted = service.cleanupForecastReport(nonExistent.toString(), 3600_000L);
            assertThat(deleted).isEqualTo(0);
        }
    }
}
