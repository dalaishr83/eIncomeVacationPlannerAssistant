package com.holidayleave.assistant.analysis;

import com.holidayleave.assistant.model.LeaveAnalysisResult;
import com.holidayleave.assistant.model.LeaveAnalysisResult.MonthlyTrend;
import com.holidayleave.assistant.model.LeaveRecord;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.Collections;
import java.util.stream.Collectors;

/**
 * Pure analytics: compute consumed/remaining/utilization/by_month/by_type/streak/trend.
 * All methods are stateless — input is always a fresh list of LeaveRecord.
 */
@Service
public class LeaveAnalysisService {

    /**
     * Analyse leave data for a single employee and year.
     *
     * @param allRecords  All loaded records (will be filtered internally)
     * @param employeeName  Employee to analyze
     * @param year  Year to analyze
     * @return  LeaveAnalysisResult with all computed metrics
     */
    public LeaveAnalysisResult analyse(List<LeaveRecord> allRecords, String employeeName, int year) {
        List<LeaveRecord> records = allRecords.stream()
                .filter(r -> r.employeeName().equalsIgnoreCase(employeeName) && r.year() == year)
                .collect(Collectors.toList());

        double entitlement = records.stream().mapToDouble(LeaveRecord::days).sum();
        double consumed = consumedToDate(records);
        double remaining = Math.max(0, entitlement - consumed);
        double utilizationPct = entitlement > 0 ? (consumed / entitlement) * 100.0 : 0.0;
        Map<Integer, Double> byMonth = computeByMonth(records);
        Map<String, Double> byType = computeByType(records);
        int longestStreak = computeLongestStreak(records);
        double avgPerMonth = entitlement / 12.0;
        List<MonthlyTrend> trend = computeMonthlyTrend(records);

        return new LeaveAnalysisResult(
                employeeName, year, entitlement, consumed, remaining, utilizationPct,
                byMonth, byType, longestStreak, avgPerMonth, trend
        );
    }

    /**
     * Consumed-to-date: sum of days for records where start_date <= today.
     */
    public double consumedToDate(List<LeaveRecord> records) {
        LocalDate today = LocalDate.now();
        return records.stream()
                .filter(r -> !r.startDate().isAfter(today))
                .mapToDouble(LeaveRecord::days)
                .sum();
    }

    // ── Private analytics ─────────────────────────────────────────────────────

    /**
     * Distributes days proportionally across months for cross-month records.
     * days_for_month = record.days × (overlap_working_days / total_working_days_in_record)
     *
     * Uses working-day counts (Mon–Fri only) for both the overlap and the total, so the
     * ratio preserves the actual weekday distribution rather than the calendar-day density.
     * This prevents inflation when a span contains more non-working days in one month slice
     * than another (e.g. a long span covering several weekends and public-holiday periods).
     */
    private Map<Integer, Double> computeByMonth(List<LeaveRecord> records) {
        Map<Integer, Double> result = new TreeMap<>();
        for (LeaveRecord record : records) {
            long totalWorkingDays = countWorkingDays(record.startDate(), record.endDate());
            // Iterate by month overlap
            for (int m = 1; m <= 12; m++) {
                LocalDate monthStart = LocalDate.of(record.year(), m, 1);
                LocalDate monthEnd = monthStart.withDayOfMonth(monthStart.lengthOfMonth());
                LocalDate overlapStart = record.startDate().isBefore(monthStart) ? monthStart : record.startDate();
                LocalDate overlapEnd   = record.endDate().isAfter(monthEnd) ? monthEnd : record.endDate();
                if (!overlapStart.isAfter(overlapEnd)) {
                    long workingOverlap = countWorkingDays(overlapStart, overlapEnd);
                    double share = totalWorkingDays > 0
                            ? record.days() * ((double) workingOverlap / totalWorkingDays)
                            : 0;
                    result.merge(m, share, Double::sum);
                }
            }
        }
        return result;
    }

    /**
     * Counts Monday–Friday days in the inclusive date range [start, end].
     * Consistent with how PlannerExcelReader counts working days per span
     * and with VacationCreationService.countWeekdays().
     */
    private long countWorkingDays(LocalDate start, LocalDate end) {
        long count = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            if (d.getDayOfWeek().getValue() <= 5) count++;
        }
        return count;
    }

    private Map<String, Double> computeByType(List<LeaveRecord> records) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (LeaveRecord r : records) {
            result.merge(r.leaveType(), r.days(), Double::sum);
        }
        return result;
    }

    /**
     * Merge consecutive records with gap ≤ 2 calendar days (bridge weekends).
     * Streak length = (streak_end - streak_start).days + 1.
     */
    private int computeLongestStreak(List<LeaveRecord> records) {
        if (records.isEmpty()) return 0;
        List<LeaveRecord> sorted = new ArrayList<>(records);
        Collections.sort(sorted, new Comparator<LeaveRecord>() {
            @Override public int compare(LeaveRecord a, LeaveRecord b) {
                return a.startDate().compareTo(b.startDate());
            }
        });

        int longest = 0;
        LocalDate streakStart = sorted.get(0).startDate();
        LocalDate streakEnd   = sorted.get(0).endDate();

        for (int i = 1; i < sorted.size(); i++) {
            LeaveRecord curr = sorted.get(i);
            long gap = streakEnd.until(curr.startDate()).getDays();
            if (gap <= 2) {
                // Extend streak
                if (curr.endDate().isAfter(streakEnd)) streakEnd = curr.endDate();
            } else {
                int len = (int)(streakStart.until(streakEnd).getDays() + 1);
                if (len > longest) longest = len;
                streakStart = curr.startDate();
                streakEnd   = curr.endDate();
            }
        }
        int len = (int)(streakStart.until(streakEnd).getDays() + 1);
        if (len > longest) longest = len;
        return longest;
    }

    private List<MonthlyTrend> computeMonthlyTrend(List<LeaveRecord> records) {
        Map<String, Double> trend = new TreeMap<>();
        for (LeaveRecord r : records) {
            String key = r.startDate().format(DateTimeFormatter.ofPattern("yyyy-MM"));
            Double existing = trend.get(key);
            trend.put(key, (existing != null ? existing : 0.0) + r.days());
        }
        List<MonthlyTrend> result = new ArrayList<>();
        for (Map.Entry<String, Double> e : trend.entrySet()) {
            result.add(new MonthlyTrend(e.getKey(), e.getValue()));
        }
        return result;
    }
}
