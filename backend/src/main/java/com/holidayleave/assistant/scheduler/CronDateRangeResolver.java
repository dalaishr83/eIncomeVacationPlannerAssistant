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
 *   <li><b>Weekly</b> — DOW field is restricted and DOM is {@code *}.
 *       Range: Monday through Friday of the next ISO week.</li>
 *   <li><b>Last day of month</b> — DOM is {@code L} and the month field is not a quarter list.
 *       Range: first through last day of the next month.</li>
 *   <li><b>Last day of quarter</b> — DOM is {@code L} and the month field lists 3, 6, 9, and 12.
 *       Range: first through last day of the next quarter.</li>
 *   <li><b>Fallback</b> — all other expressions.
 *       Range: 1st of the month → {@code fireDate}.</li>
 * </ol>
 *
 * <p>This class is stateless; all methods are static.
 */
public final class CronDateRangeResolver {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CronDateRangeResolver.class);
    private static final String PUBLIC_HOLIDAY_CODE = "P";

    private CronDateRangeResolver() {}

    // ── Public API ────────────────────────────────────────────────────────────

    public static final class DateRange {
        public final LocalDate startDate;
        public final LocalDate endDate;

        public DateRange(LocalDate startDate, LocalDate endDate) {
            this.startDate = startDate;
            this.endDate   = endDate;
        }

        @Override
        public String toString() {
            return "DateRange{startDate=" + startDate + ", endDate=" + endDate + "}";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof DateRange)) return false;
            DateRange that = (DateRange) o;
            return java.util.Objects.equals(startDate, that.startDate) && java.util.Objects.equals(endDate, that.endDate);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(startDate, endDate);
        }
    }

    /**
     * Resolves the report date range for the given 5-field cron expression and fire date
     * without public holiday / weekend end-date adjustments.
     *
     * @param fiveFieldExpression a 5-field Unix cron expression (min hour dom month dow)
     * @param fireDate            the date on which this expression triggered
     * @return the computed {@link DateRange}
     */
    public static DateRange resolve(String fiveFieldExpression, LocalDate fireDate) {
        return resolve(fiveFieldExpression, fireDate, null);
    }

    /**
     * Resolves the report date range for the given 5-field cron expression and fire date,
     * applying ISO week/year, month, or quarter rules to determine the date range, and adjusting the end date backwards
     * if it lands on a weekend (Saturday/Sunday) or public holiday contained in {@code publicHolidays}.
     *
     * <p>The start date is never changed. Only the end date is moved backwards to the nearest working day.</p>
     *
     * @param fiveFieldExpression a 5-field Unix cron expression (min hour dom month dow)
     * @param fireDate            the date on which this expression triggered
     * @param publicHolidays      set of normalized public holiday dates
     * @return the computed {@link DateRange}
     */
    public static DateRange resolve(String fiveFieldExpression, LocalDate fireDate, java.util.Set<java.util.Date> publicHolidays) {
        String[] fields = splitFields(fiveFieldExpression);
        LocalDate startDate;
        LocalDate originalEndDate;

        if (fields == null) {
            // Unparseable — fall back to month range
            startDate = getIsoFirstDayOfMonth(fireDate);
            originalEndDate = fireDate;
        } else {
            // fields[0]=min, fields[1]=hour, fields[2]=DOM, fields[3]=month, fields[4]=DOW
            String dom = fields[2].trim();
            String month = fields[3].trim();
            String dow = fields[4].trim();

            // ── Rule 1: Weekly — Monday through Friday of the next ISO week ────────
            if ("*".equals(dom) && !isWildcard(dow)) {
                LocalDate nextMonday = resolveIsoMonday(fireDate).plusWeeks(1);
                startDate = nextMonday;
                originalEndDate = nextMonday.plusDays(4);
            }
            // ── Rule 2: Last day of the next month ─────────────────────────────────
            else if ("L".equalsIgnoreCase(dom) && !isQuarterMonth(month)) {
                LocalDate firstOfNextMonth = fireDate.withDayOfMonth(1).plusMonths(1);
                startDate = firstOfNextMonth;
                originalEndDate = firstOfNextMonth.with(TemporalAdjusters.lastDayOfMonth());
            }
            // ── Rule 3: Last day of the next quarter ────────────────────────────────
            else if ("L".equalsIgnoreCase(dom) && isQuarterMonth(month)) {
                int currentQuarter = (fireDate.getMonthValue() - 1) / 3;
                LocalDate firstOfNextQuarter = LocalDate.of(fireDate.getYear(), currentQuarter * 3 + 1, 1).plusMonths(3);
                startDate = firstOfNextQuarter;
                originalEndDate = firstOfNextQuarter.plusMonths(2).with(TemporalAdjusters.lastDayOfMonth());
            }
            // ── Existing month-based rules ─────────────────────────────────────────
            else {
                startDate = getIsoFirstDayOfMonth(fireDate);
                originalEndDate = fireDate;
            }
        }

        LocalDate adjustedEndDate = adjustEndDateBackwards(originalEndDate, publicHolidays);
        return new DateRange(startDate, adjustedEndDate);
    }

    /**
     * Resolves the Monday corresponding to the ISO week and ISO week-year of the given date.
     *
     * @param fireDate the date on which cron fired
     * @return the Monday of the same ISO week and ISO week-year
     */
    public static LocalDate resolveIsoMonday(LocalDate fireDate) {
        if (fireDate == null) return null;
        int isoWeek = fireDate.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear());
        int isoWeekYear = fireDate.get(java.time.temporal.WeekFields.ISO.weekBasedYear());
        log.debug("Resolving ISO Monday for fireDate={}, ISO week={}, ISO week-year={}", fireDate, isoWeek, isoWeekYear);

        return fireDate.with(java.time.temporal.WeekFields.ISO.dayOfWeek(), 1);
    }

    /**
     * Returns the 1st day of the month for the given fire date using ISO calendar logic.
     */
    public static LocalDate getIsoFirstDayOfMonth(LocalDate fireDate) {
        if (fireDate == null) return null;
        int isoYear = fireDate.get(java.time.temporal.IsoFields.WEEK_BASED_YEAR);
        int month = fireDate.getMonthValue();
        log.debug("Resolving 1st of month for fireDate={}, month={}, ISO week-based year={}", fireDate, month, isoYear);
        return fireDate.withDayOfMonth(1);
    }

    /**
     * Loads public holidays (vacation type code 'P') from all available master Excel files
     * in the given data directory into a Set&lt;Date&gt; normalized to midnight.
     *
     * @param dataDir absolute or relative path to data directory
     * @param reader  planner excel reader instance to parse master files
     * @return consolidated set of normalized java.util.Date objects representing public holidays
     */
    /** Delegates to the zone-aware overload using the JVM default zone. */
    public static java.util.Set<java.util.Date> loadPublicHolidays(
            String dataDir,
            com.holidayleave.assistant.excel.PlannerExcelReader reader) {
        return loadPublicHolidays(dataDir, reader, java.time.ZoneId.systemDefault());
    }

    /**
     * Zone-aware variant: normalises holiday dates using the supplied {@code zone}
     * so that membership tests against the returned set are consistent regardless
     * of the JVM default time zone.
     */
    public static java.util.Set<java.util.Date> loadPublicHolidays(
            String dataDir,
            com.holidayleave.assistant.excel.PlannerExcelReader reader,
            java.time.ZoneId zone) {
        java.util.Set<java.util.Date> holidays = new java.util.HashSet<>();
        if (dataDir == null || reader == null) {
            return holidays;
        }

        java.io.File dir = new java.io.File(dataDir);
        if (!dir.exists() || !dir.isDirectory()) {
            log.warn("loadPublicHolidays: directory '{}' does not exist", dataDir);
            return holidays;
        }

        java.io.File[] files = dir.listFiles(f -> f.isFile() && f.getName().endsWith(".xlsx"));
        if (files == null || files.length == 0) {
            log.info("loadPublicHolidays: no .xlsx master files found in '{}'", dataDir);
            return holidays;
        }

        for (java.io.File file : files) {
            try {
                java.util.List<com.holidayleave.assistant.model.LeaveRecord> records = reader.load(file.getAbsolutePath());
                for (com.holidayleave.assistant.model.LeaveRecord r : records) {
                    if (isPublicHolidayRecord(r)) {
                        for (LocalDate d = r.startDate(); !d.isAfter(r.endDate()); d = d.plusDays(1)) {
                            holidays.add(normalizeToDate(d, zone));
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("loadPublicHolidays: failed to read master file '{}': {}", file.getAbsolutePath(), e.getMessage());
            }
        }

        log.info("loadPublicHolidays: loaded {} public holiday dates from {} master files in '{}'",
                holidays.size(), files.length, dataDir);
        return holidays;
    }

    /**
     * Checks if a leave record represents a public holiday.
     */
    private static boolean isPublicHolidayRecord(com.holidayleave.assistant.model.LeaveRecord record) {
        if (record == null) return false;
        String type = record.leaveType();
        return PUBLIC_HOLIDAY_CODE.equalsIgnoreCase(type) ||
               "Public Holiday".equalsIgnoreCase(type) ||
               "PublicHoliday".equalsIgnoreCase(type);
    }

    /**
     * Moves candidate end date backwards to the nearest working day if it falls on a weekend
     * or a public holiday contained in {@code publicHolidays}.
     */
    public static LocalDate adjustEndDateBackwards(LocalDate originalEndDate, java.util.Set<java.util.Date> publicHolidays) {
        if (originalEndDate == null) return null;
        LocalDate current = originalEndDate;

        while (isWeekend(current) || isPublicHoliday(current, publicHolidays)) {
            current = current.minusDays(1);
        }
        return current;
    }

    /**
     * Checks if the given date is Saturday or Sunday.
     */
    public static boolean isWeekend(LocalDate date) {
        if (date == null) return false;
        DayOfWeek dow = date.getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
    }

    /**
     * Checks if the given date is in the public holiday set.
     */
    /** Delegates to the zone-aware overload using the JVM default zone. */
    public static boolean isPublicHoliday(LocalDate date, java.util.Set<java.util.Date> publicHolidays) {
        return isPublicHoliday(date, publicHolidays, java.time.ZoneId.systemDefault());
    }

    /**
     * Zone-aware variant: normalises {@code date} to midnight in {@code zone} before
     * checking set membership, matching the zone used when the holiday set was built.
     */
    public static boolean isPublicHoliday(LocalDate date, java.util.Set<java.util.Date> publicHolidays, java.time.ZoneId zone) {
        if (date == null || publicHolidays == null || publicHolidays.isEmpty()) {
            return false;
        }
        java.util.Date normalized = normalizeToDate(date, zone);
        return publicHolidays.contains(normalized);
    }

    /**
     * Calculates the next target fire date and time for a 5-field cron expression after {@code afterDateTime}.
     *
     * @param fiveFieldExpression 5-field cron expression
     * @param afterDateTime       reference date-time (e.g. current moment)
     * @return next fire LocalDateTime or null if invalid
     */
    public static java.time.LocalDateTime calculateNextFireDateTime(String fiveFieldExpression, java.time.LocalDateTime afterDateTime) {
        if (fiveFieldExpression == null || afterDateTime == null) return null;
        String[] fields = fiveFieldExpression.trim().split("\\s+");
        String sixField = fields.length == 6 ? fiveFieldExpression.trim() : "0 " + fiveFieldExpression.trim();
        try {
            org.springframework.scheduling.support.CronExpression expr =
                    org.springframework.scheduling.support.CronExpression.parse(sixField);
            return expr.next(afterDateTime);
        } catch (Exception e) {
            log.warn("calculateNextFireDateTime: failed to parse '{}': {}", fiveFieldExpression, e.getMessage());
            return null;
        }
    }

    /**
     * Prepones / adjusts a cron expression if its next scheduled target fire date falls on a weekend
     * or a public holiday. If an adjustment is needed, calculates the nearest earlier working day and returns
     * a specific 5-field expression for that adjusted date (e.g., minute, hour, dayOfMonth, month, *), or
     * returns null if no adjustment is needed (already on a working day).
     *
     * @param originalExpression 5-field Unix cron expression
     * @param now                current evaluation date-time
     * @param publicHolidays     set of public holiday dates
     * @return the adjusted 5-field cron expression, or null if no adjustment is required
     */
    public static String preponeCronExpressionIfNeeded(String originalExpression, java.time.LocalDateTime now, java.util.Set<java.util.Date> publicHolidays) {
        if (originalExpression == null || now == null) return null;
        java.time.LocalDateTime nextFire = calculateNextFireDateTime(originalExpression, now);
        if (nextFire == null) return null;

        LocalDate targetFireDate = nextFire.toLocalDate();
        if (isWeekend(targetFireDate) || isPublicHoliday(targetFireDate, publicHolidays)) {
            LocalDate earlierWorkingDay = adjustEndDateBackwards(targetFireDate, publicHolidays);
            if (!targetFireDate.equals(earlierWorkingDay)) {
                String[] parts = splitFields(originalExpression);
                if (parts != null && parts.length == 5) {
                    String min = parts[0];
                    String hour = parts[1];
                    int day = earlierWorkingDay.getDayOfMonth();
                    int month = earlierWorkingDay.getMonthValue();
                    // Adjusted expression targeting the preponed date
                    String adjustedExpr = min + " " + hour + " " + day + " " + month + " *";
                    log.info("preponeCronExpressionIfNeeded: expression '{}' with target fire date {} is adjusted to '{}' (earlier working day {})",
                            originalExpression, targetFireDate, adjustedExpr, earlierWorkingDay);
                    return adjustedExpr;
                }
            }
        }
        return null;
    }

    /**
     * Normalizes a LocalDate to a java.util.Date representing midnight (00:00:00.000)
     * in the system default timezone to ensure consistent and reliable comparison.
     */
    /** Delegates to the zone-aware overload using the JVM default zone. */
    public static java.util.Date normalizeToDate(LocalDate localDate) {
        return normalizeToDate(localDate, java.time.ZoneId.systemDefault());
    }

    /**
     * Converts a {@link LocalDate} to a {@link java.util.Date} anchored at midnight
     * in the given {@code zone}.  Using a consistent zone ensures that holiday-set
     * membership tests produce the correct result regardless of the JVM default zone.
     */
    public static java.util.Date normalizeToDate(LocalDate localDate, java.time.ZoneId zone) {
        if (localDate == null) return null;
        return java.util.Date.from(localDate.atStartOfDay(zone).toInstant());
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Splits a 5-field cron expression into its fields.
     * Returns null if the expression does not have exactly 5 whitespace-delimited tokens.
     */
    static String[] splitFields(String expr) {
        if (expr == null) return null;
        String[] parts = expr.trim().split("\\s+");
        if (parts.length == 5) return parts;
        if (parts.length == 6) {
            // Accept Quartz's six-field form: seconds, minute, hour, DOM, month, DOW.
            return new String[] {parts[1], parts[2], parts[3], parts[4], parts[5]};
        }
        return null;
    }

    private static boolean isQuarterMonth(String field) {
        if (field == null) return false;
        for (String value : field.split(",")) {
            if (!("3".equals(value.trim()) || "6".equals(value.trim())
                    || "9".equals(value.trim()) || "12".equals(value.trim()))) {
                return false;
            }
        }
        return field.contains(",");
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
