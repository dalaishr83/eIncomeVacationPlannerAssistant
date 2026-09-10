package com.holidayleave.assistant.scheduler;

import com.holidayleave.assistant.excel.PlannerExcelReader;
import com.holidayleave.assistant.model.LeaveRecord;
import com.holidayleave.assistant.scheduler.CronDateRangeResolver.DateRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class CronDateRangeResolverTest {

    @Test
    @DisplayName("Weekly cron resolves Monday of the same ISO week and ISO week-year")
    void testResolveWeeklyIsoMonday() {
        // 2026-09-18 is Friday of ISO week 38 (2026)
        LocalDate friday = LocalDate.of(2026, 9, 18);
        LocalDate expectedMonday = LocalDate.of(2026, 9, 14);

        DateRange range = CronDateRangeResolver.resolve("30 8 * * 5", friday);
        assertEquals(expectedMonday, range.startDate);
        assertEquals(friday, range.endDate);
        assertEquals(DayOfWeek.MONDAY, range.startDate.getDayOfWeek());
        assertEquals(friday.get(WeekFields.ISO.weekOfWeekBasedYear()),
                range.startDate.get(WeekFields.ISO.weekOfWeekBasedYear()));
        assertEquals(friday.get(WeekFields.ISO.weekBasedYear()),
                range.startDate.get(WeekFields.ISO.weekBasedYear()));
    }

    @Test
    @DisplayName("ISO Week-year boundary handling (Jan 1, 2027)")
    void testIsoWeekYearBoundary() {
        // 2027-01-01 is Friday. ISO week 53 of week-based year 2026. Monday is 2026-12-28.
        LocalDate jan1 = LocalDate.of(2027, 1, 1);
        LocalDate monday = CronDateRangeResolver.resolveIsoMonday(jan1);

        assertEquals(LocalDate.of(2026, 12, 28), monday);
        assertEquals(DayOfWeek.MONDAY, monday.getDayOfWeek());
        assertEquals(jan1.get(WeekFields.ISO.weekOfWeekBasedYear()),
                monday.get(WeekFields.ISO.weekOfWeekBasedYear()));
    }

    @Test
    @DisplayName("Month range rules preserve start of month regardless of end-date adjustments")
    void testMonthlyRules() {
        LocalDate fireDate = LocalDate.of(2026, 9, 28);
        // Rule 2: End-of-month range (e.g. 28-31)
        DateRange r1 = CronDateRangeResolver.resolve("0 9 28-31 * *", fireDate);
        assertEquals(LocalDate.of(2026, 9, 1), r1.startDate);

        // Rule 3: Specific DOM
        DateRange r2 = CronDateRangeResolver.resolve("0 9 28 * *", fireDate);
        assertEquals(LocalDate.of(2026, 9, 1), r2.startDate);

        // Rule 4: Fallback
        DateRange r3 = CronDateRangeResolver.resolve("0 9 * * *", fireDate);
        assertEquals(LocalDate.of(2026, 9, 1), r3.startDate);
    }

    @Test
    @DisplayName("Loads only 'P' / 'Public Holiday' records into Set<Date>")
    void testLoadPublicHolidays(@TempDir Path tempDir) throws IOException {
        File f1 = tempDir.resolve("eIndkomst vacation 2026.xlsx").toFile();
        assertTrue(f1.createNewFile());

        PlannerExcelReader reader = mock(PlannerExcelReader.class);
        List<LeaveRecord> records = new ArrayList<>();
        records.add(new LeaveRecord("Alice", LocalDate.of(2026, 9, 28), LocalDate.of(2026, 9, 28), 1, "P", null));
        records.add(new LeaveRecord("Bob", LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 25), 1, "V", null));
        records.add(new LeaveRecord("Charlie", LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 2), 1, "Public Holiday", null));

        when(reader.load(f1.getAbsolutePath())).thenReturn(records);

        Set<Date> holidays = CronDateRangeResolver.loadPublicHolidays(tempDir.toString(), reader);

        assertEquals(2, holidays.size());
        Date d1 = CronDateRangeResolver.normalizeToDate(LocalDate.of(2026, 9, 28));
        Date d2 = CronDateRangeResolver.normalizeToDate(LocalDate.of(2026, 10, 2));
        Date nonHoliday = CronDateRangeResolver.normalizeToDate(LocalDate.of(2026, 9, 25));

        assertTrue(holidays.contains(d1));
        assertTrue(holidays.contains(d2));
        assertFalse(holidays.contains(nonHoliday));
    }

    @Test
    @DisplayName("Returns empty set gracefully if directory does not exist or has no files")
    void testLoadEmptyOrMissingDir() {
        PlannerExcelReader reader = mock(PlannerExcelReader.class);
        Set<Date> set1 = CronDateRangeResolver.loadPublicHolidays("/non/existent/path", reader);
        assertNotNull(set1);
        assertTrue(set1.isEmpty());

        Set<Date> set2 = CronDateRangeResolver.loadPublicHolidays(null, null);
        assertNotNull(set2);
        assertTrue(set2.isEmpty());
    }

    @Test
    @DisplayName("Weekend shift: Sunday or Saturday shifts back to Friday")
    void testWeekendShift() {
        LocalDate sunday = LocalDate.of(2026, 9, 27); // Sunday
        LocalDate adjusted = CronDateRangeResolver.adjustEndDateBackwards(sunday, Collections.emptySet());
        assertEquals(LocalDate.of(2026, 9, 25), adjusted); // Friday
        assertEquals(DayOfWeek.FRIDAY, adjusted.getDayOfWeek());

        LocalDate saturday = LocalDate.of(2026, 9, 26); // Saturday
        LocalDate adjustedSat = CronDateRangeResolver.adjustEndDateBackwards(saturday, Collections.emptySet());
        assertEquals(LocalDate.of(2026, 9, 25), adjustedSat);
    }

    @Test
    @DisplayName("Public holiday shift: Monday public holiday shifts back to Friday")
    void testPublicHolidayShift() {
        LocalDate monday = LocalDate.of(2026, 9, 28);
        Set<Date> holidays = new HashSet<>();
        holidays.add(CronDateRangeResolver.normalizeToDate(monday));

        LocalDate adjusted = CronDateRangeResolver.adjustEndDateBackwards(monday, holidays);
        // 2026-09-28 (Mon PH) -> 2026-09-27 (Sun) -> 2026-09-26 (Sat) -> 2026-09-25 (Fri)
        assertEquals(LocalDate.of(2026, 9, 25), adjusted);
        assertEquals(DayOfWeek.FRIDAY, adjusted.getDayOfWeek());
    }

    @Test
    @DisplayName("Cascading shift: Friday is also a public holiday -> shifts to Thursday")
    void testCascadingPublicHolidayAndWeekendShift() {
        LocalDate monday = LocalDate.of(2026, 9, 28);
        LocalDate friday = LocalDate.of(2026, 9, 25);
        Set<Date> holidays = new HashSet<>();
        holidays.add(CronDateRangeResolver.normalizeToDate(monday));
        holidays.add(CronDateRangeResolver.normalizeToDate(friday));

        LocalDate adjusted = CronDateRangeResolver.adjustEndDateBackwards(monday, holidays);
        // Mon (PH) -> Sun -> Sat -> Fri (PH) -> Thu
        assertEquals(LocalDate.of(2026, 9, 24), adjusted);
        assertEquals(DayOfWeek.THURSDAY, adjusted.getDayOfWeek());
    }

    @Test
    @DisplayName("Start date remains unchanged while end date is adjusted")
    void testStartDateRemainsUnchanged() {
        LocalDate fireDate = LocalDate.of(2026, 9, 28); // Monday
        Set<Date> holidays = new HashSet<>();
        holidays.add(CronDateRangeResolver.normalizeToDate(fireDate));

        // Weekly cron (5 = Friday)
        DateRange range = CronDateRangeResolver.resolve("30 8 * * 5", fireDate, holidays);
        // Start date should be Monday 2026-09-28
        assertEquals(LocalDate.of(2026, 9, 28), range.startDate);
        // End date adjusted to Friday 2026-09-25
        assertEquals(LocalDate.of(2026, 9, 25), range.endDate);
    }

    @Test
    @DisplayName("normalizeToDate produces midnight timestamp regardless of query time")
    void testDateNormalization() {
        LocalDate ld = LocalDate.of(2026, 9, 28);
        Date d = CronDateRangeResolver.normalizeToDate(ld);
        assertNotNull(d);

        Set<Date> set = new HashSet<>();
        set.add(d);

        assertTrue(CronDateRangeResolver.isPublicHoliday(ld, set));
        assertFalse(CronDateRangeResolver.isPublicHoliday(ld.plusDays(1), set));
    }

    @Test
    @DisplayName("preponeCronExpressionIfNeeded prepones Friday holiday to Thursday")
    void testPreponeCronExpressionFridayHoliday() {
        // Evaluate on Thursday 2026-09-10 at 10:00:00
        java.time.LocalDateTime now = java.time.LocalDateTime.of(2026, 9, 10, 10, 0, 0);
        // Friday 2026-09-11 is a public holiday
        LocalDate fridayHoliday = LocalDate.of(2026, 9, 11);
        Set<Date> holidays = new HashSet<>();
        holidays.add(CronDateRangeResolver.normalizeToDate(fridayHoliday));

        // Original cron: "30 11 * * 5" -> Next scheduled target fire date is Friday 2026-09-11 11:30
        String adjusted = CronDateRangeResolver.preponeCronExpressionIfNeeded("30 11 * * 5", now, holidays);
        assertNotNull(adjusted);
        // Preponed to Thursday 2026-09-10 (DOM=10, Month=9)
        assertEquals("30 11 10 9 *", adjusted);
    }

    @Test
    @DisplayName("preponeCronExpressionIfNeeded returns null if next fire date is already a normal working day")
    void testPreponeCronExpressionNoAdjustmentNeeded() {
        java.time.LocalDateTime now = java.time.LocalDateTime.of(2026, 9, 10, 10, 0, 0);
        // No holidays
        String adjusted = CronDateRangeResolver.preponeCronExpressionIfNeeded("30 11 * * 5", now, Collections.emptySet());
        assertNull(adjusted);
    }
}
