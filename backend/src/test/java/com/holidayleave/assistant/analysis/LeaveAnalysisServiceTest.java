package com.holidayleave.assistant.analysis;

import com.holidayleave.assistant.model.LeaveAnalysisResult;
import com.holidayleave.assistant.model.LeaveRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LeaveAnalysisService}.
 *
 * Covers: entitlement computation, consumed-to-date, remaining, utilization,
 * byMonth distribution, byType aggregation, longest-streak bridging,
 * avgPerMonth, monthlyTrend, plus all boundary / edge cases.
 */
class LeaveAnalysisServiceTest {

    private LeaveAnalysisService service;

    @BeforeEach
    void setUp() {
        service = new LeaveAnalysisService();
    }

    // Helper factory
    private LeaveRecord rec(String name, String start, String end, double days, String type) {
        return new LeaveRecord(name, LocalDate.parse(start), LocalDate.parse(end), days, type, null);
    }

    // ===========================================================================
    // analyse() -- positive / standard flows
    // ===========================================================================

    @Test
    void analyse_singleRecord_correctEntitlementAndYear() {
        LeaveRecord r = rec("Alice", "2026-01-05", "2026-01-09", 5, "V");
        LeaveAnalysisResult result = service.analyse(Collections.singletonList(r), "Alice", 2026);
        assertEquals("Alice", result.employeeName());
        assertEquals(2026, result.year());
        assertEquals(5.0, result.entitlement(), 0.001);
    }

    @Test
    void analyse_multipleRecords_summedEntitlement() {
        List<LeaveRecord> records = Arrays.asList(
            rec("Bob", "2026-02-02", "2026-02-06", 5, "V"),
            rec("Bob", "2026-03-09", "2026-03-13", 5, "V"),
            rec("Bob", "2026-04-06", "2026-04-08", 3, "P")
        );
        LeaveAnalysisResult result = service.analyse(records, "Bob", 2026);
        assertEquals(13.0, result.entitlement(), 0.001);
    }

    @Test
    void analyse_halfDayRecord_correctEntitlement() {
        LeaveRecord h = rec("Carol", "2026-05-04", "2026-05-04", 0.5, "H");
        LeaveAnalysisResult result = service.analyse(Collections.singletonList(h), "Carol", 2026);
        assertEquals(0.5, result.entitlement(), 0.001);
    }

    @Test
    void analyse_remainingNeverNegative_whenAllConsumed() {
        LeaveRecord r = rec("Dave", "2020-01-06", "2020-01-10", 5, "V");
        LeaveAnalysisResult result = service.analyse(Collections.singletonList(r), "Dave", 2020);
        assertTrue(result.remaining() >= 0);
        assertEquals(0.0, result.remaining(), 0.001, "remaining must not be negative");
    }

    @Test
    void analyse_avgPerMonth_entitlementDividedBy12() {
        LeaveRecord r = rec("Fred", "2026-03-02", "2026-03-06", 5, "V");
        LeaveAnalysisResult result = service.analyse(Collections.singletonList(r), "Fred", 2026);
        assertEquals(5.0 / 12.0, result.avgPerMonth(), 0.001);
    }

    // ===========================================================================
    // analyse() -- filtering by employee and year
    // ===========================================================================

    @Test
    void analyse_filtersOutOtherEmployees() {
        List<LeaveRecord> all = Arrays.asList(
            rec("Alice", "2026-01-05", "2026-01-09", 5, "V"),
            rec("Bob",   "2026-01-12", "2026-01-16", 5, "V")
        );
        LeaveAnalysisResult result = service.analyse(all, "Alice", 2026);
        assertEquals(5.0, result.entitlement(), 0.001);
    }

    @Test
    void analyse_caseInsensitiveEmployeeName() {
        LeaveRecord r = rec("Alice Smith", "2026-02-02", "2026-02-06", 5, "V");
        LeaveAnalysisResult result = service.analyse(Collections.singletonList(r), "alice smith", 2026);
        assertEquals(5.0, result.entitlement(), 0.001);
    }

    @Test
    void analyse_filtersOutOtherYears() {
        List<LeaveRecord> all = Arrays.asList(
            rec("Alice", "2025-06-02", "2025-06-06", 5, "V"),
            rec("Alice", "2026-06-01", "2026-06-05", 5, "V")
        );
        LeaveAnalysisResult result = service.analyse(all, "Alice", 2026);
        assertEquals(5.0, result.entitlement(), 0.001);
    }

    @Test
    void analyse_emptyRecords_returnsZeroMetrics() {
        LeaveAnalysisResult result = service.analyse(Collections.emptyList(), "Ghost", 2026);
        assertEquals(0.0, result.entitlement(), 0.001);
        assertEquals(0.0, result.consumed(), 0.001);
        assertEquals(0.0, result.remaining(), 0.001);
        assertEquals(0.0, result.utilizationPct(), 0.001);
        assertEquals(0, result.longestStreak());
        assertTrue(result.byMonth().isEmpty());
        assertTrue(result.byType().isEmpty());
        assertTrue(result.monthlyTrend().isEmpty());
    }

    @Test
    void analyse_noMatchingEmployee_returnsZeroMetrics() {
        LeaveRecord r = rec("Alice", "2026-03-02", "2026-03-06", 5, "V");
        LeaveAnalysisResult result = service.analyse(Collections.singletonList(r), "Nobody", 2026);
        assertEquals(0.0, result.entitlement(), 0.001);
    }

    // ===========================================================================
    // consumedToDate()
    // ===========================================================================

    @Test
    void consumedToDate_pastRecordCounted() {
        LeaveRecord past = rec("Alice", "2020-01-06", "2020-01-10", 5, "V");
        double consumed = service.consumedToDate(Collections.singletonList(past));
        assertEquals(5.0, consumed, 0.001);
    }

    @Test
    void consumedToDate_futureRecordNotCounted() {
        LeaveRecord future = rec("Alice", "2099-06-01", "2099-06-05", 5, "V");
        double consumed = service.consumedToDate(Collections.singletonList(future));
        assertEquals(0.0, consumed, 0.001);
    }

    @Test
    void consumedToDate_mixedRecords_onlyPastCounted() {
        List<LeaveRecord> recs = Arrays.asList(
            rec("Alice", "2020-01-06", "2020-01-10", 5, "V"),
            rec("Alice", "2099-01-06", "2099-01-10", 5, "V")
        );
        double consumed = service.consumedToDate(recs);
        assertEquals(5.0, consumed, 0.001);
    }

    @Test
    void consumedToDate_emptyList_returnsZero() {
        assertEquals(0.0, service.consumedToDate(Collections.emptyList()), 0.001);
    }

    // ===========================================================================
    // byMonth distribution
    // ===========================================================================

    @Test
    void byMonth_singleMonthRecord_entireDaysInThatMonth() {
        LeaveRecord r = rec("Alice", "2026-03-02", "2026-03-06", 5, "V");
        LeaveAnalysisResult result = service.analyse(Collections.singletonList(r), "Alice", 2026);
        Map<Integer, Double> byMonth = result.byMonth();
        assertEquals(5.0, byMonth.getOrDefault(3, 0.0), 0.01);
    }

    @Test
    void byMonth_multipleRecordsSameMonth_aggregated() {
        List<LeaveRecord> recs = Arrays.asList(
            rec("Alice", "2026-04-06", "2026-04-08", 3, "V"),
            rec("Alice", "2026-04-20", "2026-04-22", 3, "V")
        );
        LeaveAnalysisResult result = service.analyse(recs, "Alice", 2026);
        Map<Integer, Double> byMonth = result.byMonth();
        assertEquals(6.0, byMonth.getOrDefault(4, 0.0), 0.01);
    }

    @Test
    void byMonth_crossMonthRecord_splitProportionally() {
        // 2026-03-30 (Mon) to 2026-04-03 (Fri) = 5 weekdays spanning March and April
        LeaveRecord r = rec("Alice", "2026-03-30", "2026-04-03", 5, "V");
        LeaveAnalysisResult result = service.analyse(Collections.singletonList(r), "Alice", 2026);
        Map<Integer, Double> byMonth = result.byMonth();
        // March gets 2 days (Mon 30, Tue 31), April gets 3 days (Wed 1, Thu 2, Fri 3)
        double marchDays = byMonth.getOrDefault(3, 0.0);
        double aprilDays = byMonth.getOrDefault(4, 0.0);
        assertEquals(2.0, marchDays, 0.01);
        assertEquals(3.0, aprilDays, 0.01);
        assertEquals(5.0, marchDays + aprilDays, 0.01);
    }

    @Test
    void byMonth_noRecords_emptyMap() {
        LeaveAnalysisResult result = service.analyse(Collections.emptyList(), "Alice", 2026);
        assertTrue(result.byMonth().isEmpty());
    }

    // ===========================================================================
    // byType aggregation
    // ===========================================================================

    @Test
    void byType_singleType_correctSum() {
        List<LeaveRecord> recs = Arrays.asList(
            rec("Alice", "2026-01-05", "2026-01-09", 5, "V"),
            rec("Alice", "2026-02-02", "2026-02-06", 5, "V")
        );
        LeaveAnalysisResult result = service.analyse(recs, "Alice", 2026);
        assertEquals(10.0, result.byType().getOrDefault("V", 0.0), 0.001);
    }

    @Test
    void byType_multipleTypes_eachAggregated() {
        List<LeaveRecord> recs = Arrays.asList(
            rec("Alice", "2026-01-05", "2026-01-09", 5, "V"),
            rec("Alice", "2026-02-02", "2026-02-02", 1, "P"),
            rec("Alice", "2026-03-02", "2026-03-02", 0.5, "H")
        );
        LeaveAnalysisResult result = service.analyse(recs, "Alice", 2026);
        assertEquals(5.0, result.byType().getOrDefault("V", 0.0), 0.001);
        assertEquals(1.0, result.byType().getOrDefault("P", 0.0), 0.001);
        assertEquals(0.5, result.byType().getOrDefault("H", 0.0), 0.001);
    }

    @Test
    void byType_emptyList_emptyMap() {
        LeaveAnalysisResult result = service.analyse(Collections.emptyList(), "Alice", 2026);
        assertTrue(result.byType().isEmpty());
    }

    // ===========================================================================
    // longestStreak
    // ===========================================================================

    @Test
    void longestStreak_singleRecord_spanInDays() {
        // 5 weekdays Mon-Fri = span of 5 calendar days (Jan5 to Jan9 = 4 days + 1 = 5)
        LeaveRecord r = rec("Alice", "2026-01-05", "2026-01-09", 5, "V");
        LeaveAnalysisResult result = service.analyse(Collections.singletonList(r), "Alice", 2026);
        assertEquals(5, result.longestStreak());
    }

    @Test
    void longestStreak_gapOf2_bridgesRecords() {
        // Two records with a gap of exactly 2 days (Jan7 to Jan9 gap = 2): bridged.
        // Jan 5-7 (Mon-Wed), then Jan 9 (Fri). gap = Jan7.until(Jan9).getDays() = 2 -> bridged.
        LeaveRecord r1 = rec("Alice", "2026-01-05", "2026-01-07", 3, "V");
        LeaveRecord r2 = rec("Alice", "2026-01-09", "2026-01-09", 1, "V");
        LeaveAnalysisResult result = service.analyse(Arrays.asList(r1, r2), "Alice", 2026);
        // Bridged: streak from Jan 5 to Jan 9 = 5 calendar days
        assertEquals(5, result.longestStreak());
    }

    @Test
    void longestStreak_weekendGapIsThree_notBridged() {
        // Record 1 ends Friday Jan 9; Record 2 starts Monday Jan 12.
        // gap = Jan9.until(Jan12).getDays() = 3 -> NOT bridged (threshold is <= 2).
        LeaveRecord r1 = rec("Alice", "2026-01-05", "2026-01-09", 5, "V");
        LeaveRecord r2 = rec("Alice", "2026-01-12", "2026-01-16", 5, "V");
        LeaveAnalysisResult result = service.analyse(Arrays.asList(r1, r2), "Alice", 2026);
        // Not bridged: each is 5-day streak; longest = 5
        assertEquals(5, result.longestStreak());
    }

    @Test
    void longestStreak_gapOf3_notBridged() {
        // Gap > 2 -> separate streaks
        LeaveRecord r1 = rec("Alice", "2026-01-05", "2026-01-09", 5, "V"); // ends Fri Jan 9
        LeaveRecord r2 = rec("Alice", "2026-01-13", "2026-01-16", 4, "V"); // starts Tue Jan 13, gap=3
        LeaveAnalysisResult result = service.analyse(Arrays.asList(r1, r2), "Alice", 2026);
        // Longest = r1 span: Jan 5-9 = 5 days. r2 span = 4 days.
        assertEquals(5, result.longestStreak());
    }

    @Test
    void longestStreak_emptyList_returnsZero() {
        LeaveAnalysisResult result = service.analyse(Collections.emptyList(), "Alice", 2026);
        assertEquals(0, result.longestStreak());
    }

    @Test
    void longestStreak_singleDayRecord_returnsOne() {
        LeaveRecord r = rec("Alice", "2026-06-01", "2026-06-01", 1, "V");
        LeaveAnalysisResult result = service.analyse(Collections.singletonList(r), "Alice", 2026);
        assertEquals(1, result.longestStreak());
    }

    @Test
    void longestStreak_multipleRecords_returnsLongest() {
        List<LeaveRecord> recs = Arrays.asList(
            rec("Alice", "2026-01-05", "2026-01-07", 3, "V"),   // 3-day span
            rec("Alice", "2026-06-01", "2026-06-12", 10, "V")   // 12-day span
        );
        LeaveAnalysisResult result = service.analyse(recs, "Alice", 2026);
        assertEquals(12, result.longestStreak());
    }

    // ===========================================================================
    // monthlyTrend -- uses startDate month as key
    // ===========================================================================

    @Test
    void monthlyTrend_keysAreYearMonthFormat() {
        LeaveRecord r = rec("Alice", "2026-03-02", "2026-03-06", 5, "V");
        LeaveAnalysisResult result = service.analyse(Collections.singletonList(r), "Alice", 2026);
        List<LeaveAnalysisResult.MonthlyTrend> trend = result.monthlyTrend();
        assertFalse(trend.isEmpty());
        assertEquals("2026-03", trend.get(0).yearMonth());
    }

    @Test
    void monthlyTrend_multipleMonths_sortedAscending() {
        List<LeaveRecord> recs = Arrays.asList(
            rec("Alice", "2026-05-04", "2026-05-08", 5, "V"),
            rec("Alice", "2026-02-02", "2026-02-06", 5, "V")
        );
        LeaveAnalysisResult result = service.analyse(recs, "Alice", 2026);
        List<LeaveAnalysisResult.MonthlyTrend> trend = result.monthlyTrend();
        assertEquals(2, trend.size());
        // TreeMap ensures ascending order
        assertEquals("2026-02", trend.get(0).yearMonth());
        assertEquals("2026-05", trend.get(1).yearMonth());
    }

    @Test
    void monthlyTrend_empty_returnsEmptyList() {
        LeaveAnalysisResult result = service.analyse(Collections.emptyList(), "Alice", 2026);
        assertTrue(result.monthlyTrend().isEmpty());
    }

    // ===========================================================================
    // Boundary / edge cases
    // ===========================================================================

    @Test
    void analyse_zeroEntitlement_utilizationIsZero() {
        LeaveAnalysisResult result = service.analyse(Collections.emptyList(), "Alice", 2026);
        assertEquals(0.0, result.utilizationPct(), 0.001);
    }

    @Test
    void analyse_largeDataset_doesNotThrow() {
        List<LeaveRecord> recs = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 1, 5);
        for (int i = 0; i < 50; i++) {
            while (d.getDayOfWeek().getValue() > 5) d = d.plusDays(1);
            recs.add(new LeaveRecord("Alice", d, d, 1, "V", null));
            d = d.plusDays(1);
        }
        assertDoesNotThrow(() -> service.analyse(recs, "Alice", 2026));
    }

    @Test
    void analyse_duplicateRecordsSameDay_summedNotDeduped() {
        LeaveRecord r1 = rec("Alice", "2026-03-02", "2026-03-02", 1, "V");
        LeaveRecord r2 = rec("Alice", "2026-03-02", "2026-03-02", 1, "V");
        LeaveAnalysisResult result = service.analyse(Arrays.asList(r1, r2), "Alice", 2026);
        assertEquals(2.0, result.entitlement(), 0.001);
    }

    @Test
    void analyse_halfDayAndFullDay_combinedEntitlement() {
        List<LeaveRecord> recs = Arrays.asList(
            rec("Alice", "2026-06-01", "2026-06-05", 5.0, "V"),
            rec("Alice", "2026-06-08", "2026-06-08", 0.5, "H")
        );
        LeaveAnalysisResult result = service.analyse(recs, "Alice", 2026);
        assertEquals(5.5, result.entitlement(), 0.001);
    }

    @Test
    void analyse_singleDayHalfDayRecord_byMonthCorrect() {
        LeaveRecord h = rec("Alice", "2026-04-06", "2026-04-06", 0.5, "H");
        LeaveAnalysisResult result = service.analyse(Collections.singletonList(h), "Alice", 2026);
        assertEquals(0.5, result.byMonth().getOrDefault(4, 0.0), 0.001);
    }

    @Test
    void analyse_yearBoundaryRecord_assignedCorrectYear() {
        LeaveRecord r = rec("Alice", "2026-12-31", "2026-12-31", 1, "V");
        LeaveAnalysisResult result = service.analyse(Collections.singletonList(r), "Alice", 2026);
        assertEquals(1.0, result.entitlement(), 0.001);
    }

    @Test
    void analyse_multipleEmployees_eachIsolated() {
        List<LeaveRecord> all = Arrays.asList(
            rec("Alice", "2026-01-05", "2026-01-09", 5, "V"),
            rec("Bob",   "2026-01-05", "2026-01-09", 5, "V"),
            rec("Carol", "2026-01-05", "2026-01-09", 5, "V")
        );
        for (String name : Arrays.asList("Alice", "Bob", "Carol")) {
            LeaveAnalysisResult result = service.analyse(all, name, 2026);
            assertEquals(5.0, result.entitlement(), 0.001,
                "Entitlement for " + name + " should not include other employees' days");
        }
    }

    @Test
    void analyse_utilizationPct_pastOnly() {
        // One past record (3 days), year=2020 so it's consumed
        LeaveRecord r = rec("Eve", "2020-01-06", "2020-01-08", 3, "V");
        LeaveAnalysisResult result = service.analyse(Collections.singletonList(r), "Eve", 2020);
        // entitlement=3, consumed=3, utilization=100%
        assertEquals(100.0, result.utilizationPct(), 0.1);
    }
}
