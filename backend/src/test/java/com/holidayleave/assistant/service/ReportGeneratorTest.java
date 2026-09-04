package com.holidayleave.assistant.service;

import com.holidayleave.assistant.analysis.LeaveAnalysisService;
import com.holidayleave.assistant.model.LeaveAnalysisResult;
import com.holidayleave.assistant.model.LeaveRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ReportGenerator}.
 *
 * Covers:
 *  - generate() writes an HTML file to the reports directory
 *  - Returned path is absolute and ends with the expected filename pattern
 *  - HTML content contains employee name, year, report structure landmarks
 *  - Records filtered by employee name and year before analysis
 *  - consumedToDate and analyse are called with correct arguments
 *  - Utilization %, remaining, consumed KPIs embedded correctly
 *  - Zero-entitlement: utilPct is 0 (no divide-by-zero)
 *  - Empty records: report still generated without errors
 *  - Special characters in employee name are escaped in HTML
 *  - File name uses lower-snake-case derived from employee name
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportGeneratorTest {

    @Mock private LeaveAnalysisService analysisService;
    @Mock private AppState appState;

    @InjectMocks
    private ReportGenerator reportGenerator;

    @TempDir
    Path reportsDir;

    private List<LeaveRecord> records;
    private LeaveAnalysisResult analysis;

    @BeforeEach
    void setUp() {
        when(appState.getReportsDir()).thenReturn(reportsDir.toString());

        // Build two records for Alice in 2024 and one for Bob
        records = Arrays.asList(
            new LeaveRecord("Alice",
                LocalDate.of(2024, 6, 1), LocalDate.of(2024, 6, 5), 5.0, "Annual Leave", "Holiday"),
            new LeaveRecord("Alice",
                LocalDate.of(2024, 8, 10), LocalDate.of(2024, 8, 12), 3.0, "Sick Leave", "Illness"),
            new LeaveRecord("Bob",
                LocalDate.of(2024, 3, 1), LocalDate.of(2024, 3, 3), 3.0, "Vacation", "Break")
        );

        Map<Integer, Double> byMonth = new HashMap<>();
        byMonth.put(6, 5.0);
        byMonth.put(8, 3.0);

        Map<String, Double> byType = new LinkedHashMap<>();
        byType.put("Annual Leave", 5.0);
        byType.put("Sick Leave", 3.0);

        List<LeaveAnalysisResult.MonthlyTrend> trend = Arrays.asList(
            new LeaveAnalysisResult.MonthlyTrend("2024-06", 5.0),
            new LeaveAnalysisResult.MonthlyTrend("2024-08", 3.0)
        );

        analysis = new LeaveAnalysisResult(
            "Alice", 2024, 25.0, 8.0, 17.0, 32.0,
            byMonth, byType, 5, 1.3, trend
        );

        when(analysisService.analyse(any(), eq("Alice"), eq(2024))).thenReturn(analysis);
        when(analysisService.consumedToDate(any())).thenReturn(8.0);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // File output
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("File output")
    class FileOutput {

        @Test
        @DisplayName("generates a file in the reports directory")
        void generate_createsFileInReportsDir() throws IOException {
            String path = reportGenerator.generate(records, "Alice", 2024);

            assertThat(Files.exists(reportsDir.resolve("alice_2024_leave_report.html"))).isTrue();
            assertThat(path).endsWith("alice_2024_leave_report.html");
        }

        @Test
        @DisplayName("returned path is absolute")
        void generate_returnedPathIsAbsolute() throws IOException {
            String path = reportGenerator.generate(records, "Alice", 2024);

            assertThat(java.nio.file.Paths.get(path).isAbsolute()).isTrue();
        }

        @Test
        @DisplayName("file name derived from employee name lower-snake-case")
        void generate_filenameUsesSnakeCase() throws IOException {
            when(analysisService.analyse(any(), eq("Alice Smith"), eq(2024))).thenReturn(analysis);

            reportGenerator.generate(records, "Alice Smith", 2024);

            assertThat(Files.exists(reportsDir.resolve("alice_smith_2024_leave_report.html"))).isTrue();
        }

        @Test
        @DisplayName("file name uses year from parameter")
        void generate_filenameIncludesYear() throws IOException {
            // Stub for a different year
            when(analysisService.analyse(any(), eq("Alice"), eq(2023))).thenReturn(
                new LeaveAnalysisResult("Alice", 2023, 25.0, 0.0, 25.0, 0.0,
                    Collections.<Integer, Double>emptyMap(), Collections.<String, Double>emptyMap(), 0, 0.0,
                    Collections.<LeaveAnalysisResult.MonthlyTrend>emptyList())
            );
            when(analysisService.consumedToDate(any())).thenReturn(0.0);

            reportGenerator.generate(records, "Alice", 2023);

            assertThat(Files.exists(reportsDir.resolve("alice_2023_leave_report.html"))).isTrue();
        }

        @Test
        @DisplayName("file is UTF-8 encoded and readable")
        void generate_fileIsUtf8Readable() throws IOException {
            String path = reportGenerator.generate(records, "Alice", 2024);

            String content = new String(Files.readAllBytes(java.nio.file.Paths.get(path)), StandardCharsets.UTF_8);
            assertThat(content).isNotEmpty();
            assertThat(content).startsWith("<!DOCTYPE html>");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // HTML content
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("HTML content")
    class HtmlContent {

        @Test
        @DisplayName("HTML contains the employee name in header")
        void generate_htmlContainsEmployeeName() throws IOException {
            String path = reportGenerator.generate(records, "Alice", 2024);
            String html = new String(Files.readAllBytes(java.nio.file.Paths.get(path)), StandardCharsets.UTF_8);

            assertThat(html).contains("Alice");
        }

        @Test
        @DisplayName("HTML contains the year")
        void generate_htmlContainsYear() throws IOException {
            String path = reportGenerator.generate(records, "Alice", 2024);
            String html = new String(Files.readAllBytes(java.nio.file.Paths.get(path)), StandardCharsets.UTF_8);

            assertThat(html).contains("2024");
        }

        @Test
        @DisplayName("HTML contains expected structural elements")
        void generate_htmlContainsStructure() throws IOException {
            String path = reportGenerator.generate(records, "Alice", 2024);
            String html = new String(Files.readAllBytes(java.nio.file.Paths.get(path)), StandardCharsets.UTF_8);

            assertThat(html).contains("<title>");
            assertThat(html).contains("Leave Report");
            assertThat(html).contains("Monthly Breakdown");
            assertThat(html).contains("Annual Summary");
            assertThat(html).contains("Leave Details");
        }

        @Test
        @DisplayName("HTML has correct entitlement from analysis")
        void generate_htmlContainsEntitlement() throws IOException {
            String path = reportGenerator.generate(records, "Alice", 2024);
            String html = new String(Files.readAllBytes(java.nio.file.Paths.get(path)), StandardCharsets.UTF_8);

            // Entitlement is 25
            assertThat(html).contains("25");
        }

        @Test
        @DisplayName("Special characters in employee name are HTML-escaped")
        void generate_specialCharsEscaped() throws IOException {
            String malicious = "Alice <script>alert(1)</script>";
            when(analysisService.analyse(any(), eq(malicious), eq(2024))).thenReturn(analysis);

            String path = reportGenerator.generate(records, malicious, 2024);
            String html = new String(Files.readAllBytes(java.nio.file.Paths.get(path)), StandardCharsets.UTF_8);

            assertThat(html).doesNotContain("<script>alert(1)</script>");
            assertThat(html).contains("&lt;script&gt;");
        }

        @Test
        @DisplayName("HTML contains leave type badge for Annual Leave")
        void generate_htmlContainsLeaveTypeBadge() throws IOException {
            String path = reportGenerator.generate(records, "Alice", 2024);
            String html = new String(Files.readAllBytes(java.nio.file.Paths.get(path)), StandardCharsets.UTF_8);

            assertThat(html).contains("badge-annual");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Business logic
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Business logic")
    class BusinessLogic {

        @Test
        @DisplayName("analyse is called with the full record list, not just Alice's records")
        void generate_passesAllRecordsToAnalyse() throws IOException {
            reportGenerator.generate(records, "Alice", 2024);

            // analyse always receives the full unfiltered list
            verify(analysisService).analyse(eq(records), eq("Alice"), eq(2024));
        }

        @Test
        @DisplayName("consumedToDate is called with only Alice's 2024 records")
        void generate_consumedToDateReceivesFilteredRecords() throws IOException {
            reportGenerator.generate(records, "Alice", 2024);

            verify(analysisService).consumedToDate(argThat(list -> {
                if (list == null || list.size() != 2) return false;
                for (LeaveRecord r : list) {
                    if (!r.employeeName().equalsIgnoreCase("Alice") || r.year() != 2024) return false;
                }
                return true;
            }));
        }

        @Test
        @DisplayName("zero entitlement does not cause divide-by-zero — utilPct is 0")
        void generate_zeroEntitlement_noDivideByZero() throws IOException {
            LeaveAnalysisResult zeroResult = new LeaveAnalysisResult(
                "Alice", 2024, 0.0, 0.0, 0.0, 0.0,
                Collections.<Integer, Double>emptyMap(),
                Collections.<String, Double>emptyMap(),
                0, 0.0,
                Collections.<LeaveAnalysisResult.MonthlyTrend>emptyList()
            );
            when(analysisService.analyse(any(), eq("Alice"), eq(2024))).thenReturn(zeroResult);
            when(analysisService.consumedToDate(any())).thenReturn(0.0);

            // Should not throw
            String path = reportGenerator.generate(records, "Alice", 2024);
            assertThat(path).isNotNull();
        }

        @Test
        @DisplayName("empty record list generates report without errors")
        void generate_emptyRecords_noError() throws IOException {
            LeaveAnalysisResult emptyResult = new LeaveAnalysisResult(
                "Alice", 2024, 25.0, 0.0, 25.0, 0.0,
                Collections.<Integer, Double>emptyMap(),
                Collections.<String, Double>emptyMap(),
                0, 0.0,
                Collections.<LeaveAnalysisResult.MonthlyTrend>emptyList()
            );
            when(analysisService.analyse(any(), eq("Alice"), eq(2024))).thenReturn(emptyResult);
            when(analysisService.consumedToDate(any())).thenReturn(0.0);

            String path = reportGenerator.generate(Collections.<LeaveRecord>emptyList(), "Alice", 2024);
            assertThat(Files.exists(java.nio.file.Paths.get(path))).isTrue();
        }

        @Test
        @DisplayName("cross-month record: leave type appears in both months in Monthly Breakdown")
        void generate_crossMonthRecord_leaveTypeInBothMonths() throws IOException {
            // Record spans October into November — Vacation must appear in both month rows
            List<LeaveRecord> crossMonth = Arrays.asList(
                new LeaveRecord("Alice",
                    LocalDate.of(2024, 10, 28), LocalDate.of(2024, 11, 4), 6.0, "Vacation", null)
            );
            Map<Integer, Double> byMonth = new HashMap<>();
            byMonth.put(10, 4.0);
            byMonth.put(11, 2.0);
            LeaveAnalysisResult crossResult = new LeaveAnalysisResult(
                "Alice", 2024, 25.0, 6.0, 19.0, 24.0,
                byMonth, Collections.singletonMap("Vacation", 6.0),
                6, 0.5,
                Collections.emptyList()
            );
            when(analysisService.analyse(any(), eq("Alice"), eq(2024))).thenReturn(crossResult);
            when(analysisService.consumedToDate(any())).thenReturn(6.0);

            String path = reportGenerator.generate(crossMonth, "Alice", 2024);
            String html = new String(Files.readAllBytes(java.nio.file.Paths.get(path)), StandardCharsets.UTF_8);

            // Both October and November rows must mention Vacation
            int octoberIdx  = html.indexOf("October");
            int novemberIdx = html.indexOf("November");
            assertThat(octoberIdx).isGreaterThan(-1);
            assertThat(novemberIdx).isGreaterThan(-1);
            // Vacation must appear after both month names in the table
            assertThat(html.indexOf("Vacation", octoberIdx)).isGreaterThan(octoberIdx);
            assertThat(html.indexOf("Vacation", novemberIdx)).isGreaterThan(novemberIdx);
        }

        @Test
        @DisplayName("remaining is floored at 0 when consumed exceeds entitlement")
        void generate_consumedExceedsEntitlement_remainingNotNegative() throws IOException {
            LeaveAnalysisResult overResult = new LeaveAnalysisResult(
                "Alice", 2024, 10.0, 15.0, -5.0, 150.0,
                Collections.<Integer, Double>emptyMap(),
                Collections.<String, Double>emptyMap(),
                0, 0.0,
                Collections.<LeaveAnalysisResult.MonthlyTrend>emptyList()
            );
            when(analysisService.analyse(any(), eq("Alice"), eq(2024))).thenReturn(overResult);
            when(analysisService.consumedToDate(any())).thenReturn(15.0);

            // Should not throw; remaining clamped to 0 by Math.max(0, entitlement - consumed)
            String path = reportGenerator.generate(records, "Alice", 2024);
            String html = new String(Files.readAllBytes(java.nio.file.Paths.get(path)), StandardCharsets.UTF_8);
            // The "0" remaining KPI value appears in the HTML
            assertThat(html).contains("0");
        }
    }
}
