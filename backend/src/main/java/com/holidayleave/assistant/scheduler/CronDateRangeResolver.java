package com.holidayleave.assistant.scheduler;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * Determines the report date range (startDate, endDate) based on the 5-field
 * Unix cron expression that triggered the job and the date on which it fired.
 *
 * <h3>Classification rules (applied in order):</h3>
 * <ol>
 *   <li><b>Weekly</b> — DOW field is a specific weekday or simple weekday value/name
 *       (e.g. {@code 5}, {@code 1-5}, {@code MON-FRI}) <em>and</em> DOM is {@code *}.
 *       Range: Monday of the ISO week containing {@code fireDate} → {@code fireDate}.</li>
 *   <li><b>End-of-month range</b> — DOM field is a numeric range ending at 27–31
 *       (e.g. {@code 27-31}, {@code 28-31}).
 *       Range: 1st of the month → {@code fireDate}.</li>
 *   <li><b>Specific DOM</b> — DOM field is a fixed numeric day (e.g. {@code 28}).
 *       Range: 1st of the month → {@code fireDate}.</li>
 *   <li><b>Fallback</b> — all other expressions.
 *       Range: 1st of the month → {@code fireDate}.</li>
 * </ol>
 *
 * <p>This class is stateless; all methods are static.
 */
public final class CronDateRangeResolver {

    private CronDateRangeResolver() {}

    // ── Public API ────────────────────────────────────────────────────────────

    public static final class DateRange {
        public final LocalDate startDate;
        public final LocalDate endDate;

        public DateRange(LocalDate startDate, LocalDate endDate) {
            this.startDate = startDate;
            this.endDate   = endDate;
        }
    }

    /**
     * Resolves the report date range for the given 5-field cron expression and fire date.
     *
     * @param fiveFieldExpression a 5-field Unix cron expression (min hour dom month dow)
     * @param fireDate            the date on which this expression triggered
     * @return the computed {@link DateRange}
     */
    public static DateRange resolve(String fiveFieldExpression, LocalDate fireDate) {
        String[] fields = splitFields(fiveFieldExpression);
        if (fields == null) {
            // Unparseable — fall back to month range
            return monthRange(fireDate);
        }

        // fields[0]=min, fields[1]=hour, fields[2]=DOM, fields[3]=month, fields[4]=DOW
        String dom = fields[2].trim();
        String dow = fields[4].trim();

        // ── Rule 1: Weekly ────────────────────────────────────────────────────
        // DOM is wildcard (*) and DOW restricts to specific weekdays
        if ("*".equals(dom) && !isWildcard(dow)) {
            LocalDate monday = fireDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            return new DateRange(monday, fireDate);
        }

        // ── Rule 2: End-of-month range ────────────────────────────────────────
        // DOM is a range like 27-31, 28-31, etc.
        if (isEndOfMonthRange(dom)) {
            return monthRange(fireDate);
        }

        // ── Rule 3: Specific DOM ──────────────────────────────────────────────
        // DOM is a plain integer
        if (isNumeric(dom)) {
            return monthRange(fireDate);
        }

        // ── Rule 4: Fallback ──────────────────────────────────────────────────
        return monthRange(fireDate);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Splits a 5-field cron expression into its fields.
     * Returns null if the expression does not have exactly 5 whitespace-delimited tokens.
     */
    static String[] splitFields(String expr) {
        if (expr == null) return null;
        String[] parts = expr.trim().split("\\s+");
        return (parts.length == 5) ? parts : null;
    }

    /** True if {@code field} is a pure wildcard ({@code *} or {@code ?}). */
    private static boolean isWildcard(String field) {
        return "*".equals(field) || "?".equals(field);
    }

    /**
     * True if {@code field} is a DOM range whose upper bound is ≥ 27
     * (e.g. {@code 27-31}, {@code 28-31}, {@code 25-28}).
     */
    private static boolean isEndOfMonthRange(String field) {
        if (!field.contains("-")) return false;
        String[] parts = field.split("-");
        if (parts.length != 2) return false;
        try {
            int upper = Integer.parseInt(parts[1].trim());
            return upper >= 27;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** True if {@code field} is a plain positive integer (no special chars). */
    private static boolean isNumeric(String field) {
        if (field == null || field.isEmpty()) return false;
        for (char c : field.toCharArray()) {
            if (!Character.isDigit(c)) return false;
        }
        return true;
    }

    /** Returns a range from the 1st of the fire date's month to the fire date. */
    private static DateRange monthRange(LocalDate fireDate) {
        LocalDate firstOfMonth = fireDate.withDayOfMonth(1);
        return new DateRange(firstOfMonth, fireDate);
    }
}
