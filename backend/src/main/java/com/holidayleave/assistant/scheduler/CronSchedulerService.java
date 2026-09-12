package com.holidayleave.assistant.scheduler;

import com.holidayleave.assistant.scheduler.CronDateRangeResolver.DateRange;
import com.holidayleave.assistant.scheduler.CronExpressionStore.CronEntry;
import com.holidayleave.assistant.service.AuditService;
import com.holidayleave.assistant.service.SlackNotificationService;
import com.holidayleave.assistant.service.TeamForecastService;
import com.holidayleave.assistant.service.TeamForecastService.TeamForecastResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Manages the lifecycle of cron-scheduled Team Forecast report jobs.
 *
 * <p>Call flow when a cron expression fires:
 * <ol>
 *   <li>{@link CronDateRangeResolver#resolve} determines start/end dates.</li>
 *   <li>{@link TeamForecastService#generateForecast} generates the report
 *       (identical to what {@code AdminController.generateTeamForecast()} does).</li>
 *   <li>{@link SlackNotificationService#notifyTeamForecast} delivers it to Slack
 *       (the existing workflow, unchanged).</li>
 * </ol>
 *
 * <p>The scheduler is stopped by default on application start.  The Admin UI
 * controls start/stop explicitly.
 */
@Service
public class CronSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(CronSchedulerService.class);
    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    @Autowired private CronExpressionStore      store;
    @Autowired private TeamForecastService      teamForecastService;
    @Autowired private SlackNotificationService slackNotificationService;
    @Autowired private AuditService             auditService;
    @Autowired private com.holidayleave.assistant.service.AppState appState;
    @Autowired private com.holidayleave.assistant.service.PublicHolidayCache publicHolidayCache;
    @Autowired private com.holidayleave.assistant.config.AppProperties appProperties;

    /** ThreadPoolTaskScheduler shared across all active tasks. */
    private ThreadPoolTaskScheduler taskScheduler;

    /**
     * Map of expression-id → live ScheduledFuture.
     * Only populated when the scheduler is running.
     */
    private final Map<String, ScheduledFuture<?>> activeFutures = new ConcurrentHashMap<>();

    /** Live handle for the background hourly validation task. */
    private ScheduledFuture<?> hourlyValidationFuture = null;

    private volatile boolean running  = false;
    private volatile String  lastFired = null;

    // ── Status DTO ────────────────────────────────────────────────────────────

    public static final class StatusInfo {
        public final String  status;
        public final int     scheduled;
        public final String  lastFired;

        StatusInfo(String status, int scheduled, String lastFired) {
            this.status    = status;
            this.scheduled = scheduled;
            this.lastFired = lastFired;
        }
    }

    public StatusInfo getStatus() {
        return new StatusInfo(
                running ? "running" : "stopped",
                activeFutures.size(),
                lastFired);
    }

    public synchronized boolean isRunning() {
        return running;
    }

    // ── Start / Stop ──────────────────────────────────────────────────────────

    /**
     * Starts the specified cron expressions by id.
     * Updates their persisted state in {@code cron.json} to "running".
     *
     * @param ids collection of cron entry IDs to start
     * @return number of expressions actively scheduled
     */
    public synchronized int startEntries(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalStateException("No cron expressions selected. Please select at least one expression.");
        }
        List<CronEntry> userEntries = store.readAll();
        if (userEntries.isEmpty()) {
            throw new IllegalStateException("No cron expressions configured. Add at least one expression first.");
        }

        Set<String> idSet = new HashSet<>(ids);
        boolean anyUpdated = false;
        for (CronEntry entry : userEntries) {
            if (idSet.contains(entry.getId())) {
                entry.setState("running");
                anyUpdated = true;
            }
        }

        if (anyUpdated) {
            try {
                store.saveAll(userEntries);
            } catch (IOException e) {
                log.error("CronSchedulerService: failed to persist cron.json on start: {}", e.getMessage(), e);
            }
        }

        ensureSchedulerRunning();
        validateAndSyncWorkingCron();
        int count = rescheduleFromWorkingStore();

        log.info("CronSchedulerService: started entries {}, total active: {}", ids, count);
        auditService.log("cron_scheduler_started", "system", null,
                "Scheduler started for " + ids.size() + " expression(s), total active: " + count, "success", "cron");
        return count;
    }

    /**
     * Starts all configured expressions (legacy fallback / convenience).
     */
    public synchronized int start() {
        List<CronEntry> userEntries = store.readAll();
        if (userEntries.isEmpty()) {
            throw new IllegalStateException("No cron expressions configured. Add at least one expression first.");
        }
        List<String> allIds = new ArrayList<>();
        for (CronEntry e : userEntries) {
            if (e.getId() != null) allIds.add(e.getId());
        }
        return startEntries(allIds);
    }

    /**
     * Stops the specified cron expressions by id.
     * Updates their persisted state in {@code cron.json} to "stopped".
     *
     * @param ids collection of cron entry IDs to stop
     * @return number of remaining active expressions
     */
    public synchronized int stopEntries(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalStateException("No cron expressions selected to stop.");
        }
        List<CronEntry> userEntries = store.readAll();
        Set<String> idSet = new HashSet<>(ids);
        boolean anyUpdated = false;
        for (CronEntry entry : userEntries) {
            if (idSet.contains(entry.getId())) {
                entry.setState("stopped");
                anyUpdated = true;
            }
        }

        if (anyUpdated) {
            try {
                store.saveAll(userEntries);
            } catch (IOException e) {
                log.error("CronSchedulerService: failed to persist cron.json on stop: {}", e.getMessage(), e);
            }
        }

        for (String id : idSet) {
            ScheduledFuture<?> future = activeFutures.remove(id);
            if (future != null) {
                future.cancel(false);
            }
        }

        if (running) {
            validateAndSyncWorkingCron();
            rescheduleFromWorkingStore();
            if (activeFutures.isEmpty()) {
                tearDownScheduler();
            }
        }

        log.info("CronSchedulerService: stopped entries {}, remaining active: {}", ids, activeFutures.size());
        auditService.log("cron_scheduler_stopped", "system", null,
                "Scheduler stopped for " + ids.size() + " expression(s)", "success", "cron");
        return activeFutures.size();
    }

    /**
     * Stops all active cron expressions and shuts down the thread pool.
     */
    public synchronized void stop() {
        List<CronEntry> userEntries = store.readAll();
        for (CronEntry entry : userEntries) {
            entry.setState("stopped");
        }
        try {
            store.saveAll(userEntries);
        } catch (IOException e) {
            log.error("CronSchedulerService: failed to persist cron.json on stopAll: {}", e.getMessage(), e);
        }

        tearDownScheduler();
        validateAndSyncWorkingCron();
        log.info("CronSchedulerService stopped all");
        auditService.log("cron_scheduler_stopped", "system", null,
                "Scheduler stopped all", "success", "cron");
    }

    private void ensureSchedulerRunning() {
        if (!running || taskScheduler == null) {
            taskScheduler = buildTaskScheduler();
            taskScheduler.initialize();

            long intervalSeconds = appProperties != null && appProperties.getCronValidationIntervalSeconds() > 0
                    ? appProperties.getCronValidationIntervalSeconds()
                    : 3600L;
            long intervalMillis = intervalSeconds * 1000L;
            log.info("CronSchedulerService: background validation task configured to run every {}s ({}ms)", intervalSeconds, intervalMillis);

            hourlyValidationFuture = taskScheduler.scheduleWithFixedDelay(new Runnable() {
                @Override
                public void run() {
                    try {
                        log.info("CronSchedulerService: background validation running...");
                        boolean changed = validateAndSyncWorkingCron();
                        if (changed) {
                            log.info("CronSchedulerService: working-cron.json changed — rescheduling live tasks");
                            rescheduleFromWorkingStore();
                        }
                    } catch (Exception e) {
                        log.error("CronSchedulerService: background validation error: {}", e.getMessage(), e);
                    }
                }
            }, intervalMillis);

            running = true;
        }
    }

    private void tearDownScheduler() {
        if (hourlyValidationFuture != null) {
            hourlyValidationFuture.cancel(false);
            hourlyValidationFuture = null;
        }
        for (ScheduledFuture<?> future : activeFutures.values()) {
            future.cancel(false);
        }
        activeFutures.clear();
        if (taskScheduler != null) {
            taskScheduler.destroy();
            taskScheduler = null;
        }
        running = false;
    }

    // ── Expression management (live registration/cancellation) ────────────────

    /**
     * Called by {@link com.holidayleave.assistant.controller.CronController} after
     * a new entry has been persisted.  If the scheduler is currently running,
     * registers the new entry as a live task immediately.
     */
    public synchronized void onEntryAdded(CronEntry entry) {
        if (running) {
            validateAndSyncWorkingCron();
            if ("running".equalsIgnoreCase(entry.getState())) {
                rescheduleFromWorkingStore();
            }
            log.debug("CronSchedulerService: live-updated after entry add for id={}", entry.getId());
        }
    }

    /**
     * Called by {@link com.holidayleave.assistant.controller.CronController} after
     * an entry has been deleted from the store.  Cancels the live task if present.
     */
    public synchronized void onEntryDeleted(String id) {
        ScheduledFuture<?> future = activeFutures.remove(id);
        if (future != null) {
            future.cancel(false);
            log.debug("CronSchedulerService: cancelled task for id={}", id);
        }
        if (running) {
            validateAndSyncWorkingCron();
            rescheduleFromWorkingStore();
        }
    }

    /**
     * Validates cron expressions from {@code cron.json} against public holidays and weekends.
     * Generates or updates {@code working-cron.json}. If an expression's target fire date
     * falls on a weekend or public holiday, prepones it to the nearest earlier working day
     * and sets {@code endDateAjusted = true}. If {@code endDateAjusted} is already true,
     * avoids re-adjusting.
     *
     * @return {@code true} if the working store was changed (expression preponed or entry
     *         set changed), {@code false} if nothing changed and live tasks must not be reset.
     */
    public synchronized boolean validateAndSyncWorkingCron() {
        if (store == null) return false;
        List<CronEntry> originalList = store.readAll();
        if (originalList.isEmpty()) {
            try {
                store.saveWorkingAll(new ArrayList<>());
            } catch (IOException ignored) {}
            return false;
        }

        List<CronEntry> existingWorkingList = store.readWorkingAll();
        Map<String, CronEntry> existingWorkingMap = new HashMap<>();
        for (CronEntry we : existingWorkingList) {
            if (we.getId() != null) {
                existingWorkingMap.put(we.getId(), we);
            }
        }

        Set<Date> publicHolidays = publicHolidayCache != null
                ? publicHolidayCache.getHolidays()
                : Collections.emptySet();

        ZoneId cronZone = ZoneId.of(appProperties != null ? appProperties.getCronTimezone() : "Asia/Kolkata");
        LocalDateTime now = LocalDateTime.now(cronZone);
        List<CronEntry> updatedWorkingList = new ArrayList<>();
        boolean anyAdjusted = false;

        for (CronEntry orig : originalList) {
            CronEntry workingEntry = new CronEntry(orig);
            workingEntry.setState(orig.getState());
            workingEntry.setOriginalExpression(orig.getExpression());
            CronEntry previousWorking = existingWorkingMap.get(orig.getId());

            // Check if already adjusted in working configuration
            if (previousWorking != null && previousWorking.isEndDateAjusted()) {
                workingEntry.setExpression(previousWorking.getExpression());
                workingEntry.setEndDateAjusted(true);
                workingEntry.setOriginalExpression(previousWorking.getOriginalExpression());
            } else {
                String adjustedExpr = CronDateRangeResolver.preponeCronExpressionIfNeeded(
                        orig.getExpression(), now, publicHolidays);
                if (adjustedExpr != null && !adjustedExpr.equals(orig.getExpression())) {
                    workingEntry.setExpression(adjustedExpr);
                    workingEntry.setEndDateAjusted(true);
                    anyAdjusted = true;
                    log.info("CronSchedulerService: preponed cron for id={} team='{}' from '{}' to '{}'",
                            orig.getId(), orig.getTeam(), orig.getExpression(), adjustedExpr);
                    auditService.log("cron_fire_date_preponed", "system", null,
                            "Cron expression for team '" + orig.getTeam() + "' adjusted from '" +
                            orig.getExpression() + "' to '" + adjustedExpr + "' (preponed to earlier working day)",
                            "success", "cron");
                } else {
                    workingEntry.setEndDateAjusted(false);
                }
            }
            updatedWorkingList.add(workingEntry);
        }

        // Also consider a change if the set of tracked IDs differs (entry added/removed)
        Set<String> prevIds = existingWorkingMap.keySet();
        Set<String> newIds  = new HashSet<>();
        for (CronEntry e : updatedWorkingList) {
            if (e.getId() != null) newIds.add(e.getId());
        }
        boolean idSetChanged = !prevIds.equals(newIds);

        try {
            store.saveWorkingAll(updatedWorkingList);
        } catch (IOException e) {
            log.error("CronSchedulerService: failed to write working-cron.json: {}", e.getMessage(), e);
        }
        return anyAdjusted || idSetChanged;
    }

    /**
     * Reads all enabled entries from {@code working-cron.json} and registers tasks.
     */
    private int rescheduleFromWorkingStore() {
        if (!running || taskScheduler == null) return 0;

        for (ScheduledFuture<?> f : activeFutures.values()) {
            f.cancel(false);
        }
        activeFutures.clear();

        List<CronEntry> workingEntries = store.readWorkingAll();
        int count = 0;
        for (CronEntry entry : workingEntries) {
            if (entry.isEnabled() && "running".equalsIgnoreCase(entry.getState())) {
                scheduleEntry(entry);
                count++;
            }
        }
        return count;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @PreDestroy
    public void shutdown() {
        if (running) {
            try { stop(); } catch (Exception ignored) {}
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Registers a single cron task for the supplied entry.
     * Prepends "0 " to convert the stored 5-field Unix expression to Spring's
     * required 6-field format ({@code "0 <min> <hour> <dom> <month> <dow>"})).
     */
    private void scheduleEntry(final CronEntry entry) {
        String springCron = "0 " + entry.getExpression();
        TaskScheduler ts  = taskScheduler; // capture reference (lambda safety)
        try {
            java.util.TimeZone tz = java.util.TimeZone.getTimeZone(
                    appProperties != null ? appProperties.getCronTimezone() : "Asia/Kolkata");
            CronTrigger trigger = new CronTrigger(springCron, tz);
            ScheduledFuture<?> future = ts.schedule(
                    new Runnable() {
                        @Override public void run() {
                            executeForecast(entry);
                        }
                    },
                    trigger);
            activeFutures.put(entry.getId(), future);
            log.info("CronSchedulerService: registered live trigger for id={} expr='{}' (spring='{}')",
                    entry.getId(), entry.getExpression(), springCron);
        } catch (Exception e) {
            log.error("CronSchedulerService: failed to schedule id={} expr='{}': {}",
                    entry.getId(), entry.getExpression(), e.getMessage(), e);
        }
    }

    /**
     * Executed on each cron fire: routes to housekeeping cleanup or forecast report delivery.
     */
    private void executeForecast(CronEntry entry) {
        ZoneId cronZone = ZoneId.of(appProperties != null ? appProperties.getCronTimezone() : "Asia/Kolkata");
        LocalDate fireDate = LocalDate.now(cronZone);
        log.info("CronSchedulerService: firing for id={} team='{}' expr='{}' date={}",
                entry.getId(), entry.getTeam(), entry.getExpression(), fireDate);
        lastFired = LocalDateTime.now(cronZone).format(ISO_FMT);

        // If this entry is configured for Cleanup housekeeping
        if ("Cleanup".equalsIgnoreCase(entry.getTeam())) {
            String dataDir = appState != null && appState.getDataDir() != null
                    ? appState.getDataDir()
                    : (appProperties != null && appProperties.getDataDir() != null ? appProperties.getDataDir() : "data");
            // Delete forecast Excel files older than 1 hour (3,600,000 ms)
            long oneHourMillis = 3600_000L;
            try {
                int deletedCount = teamForecastService.cleanupForecastReport(dataDir, oneHourMillis);
                auditService.log("cron_cleanup_executed", "system", null,
                        "id=" + entry.getId() + " expr=" + entry.getExpression() + " deletedFiles=" + deletedCount,
                        "success", "cron");
            } catch (Exception ex) {
                log.error("CronSchedulerService: Cleanup execution failed for id={}: {}", entry.getId(), ex.getMessage(), ex);
                auditService.log("cron_cleanup_failed", "system", null,
                        "id=" + entry.getId() + " error=" + ex.getMessage(),
                        "error", "cron");
            }
            return;
        }

        // 1. Resolve start/end dates from the cron expression + fire date using the cached holiday set
        Set<Date> publicHolidays = publicHolidayCache != null
                ? publicHolidayCache.getHolidays()
                : Collections.emptySet();

        String exprForRange = entry.getOriginalExpression() != null ? entry.getOriginalExpression() : entry.getExpression();
        DateRange range = CronDateRangeResolver.resolve(exprForRange, fireDate, publicHolidays);

        log.info("CronSchedulerService: forecast report date range resolved for team='{}' (fireDate={}, cronStartDate={}, cronEndDate={}, originalExpr='{}', workingExpr='{}')",
                entry.getTeam(), fireDate, range.startDate, range.endDate, entry.getOriginalExpression(), entry.getExpression());

        // Log and audit if end date was adjusted
        if (!fireDate.equals(range.endDate)) {
            String reason = CronDateRangeResolver.isWeekend(fireDate) ? "weekend" : "public holiday";
            log.info("Cron end date {} falls on a {} and has been adjusted to nearest previous working day: {}",
                    fireDate, reason, range.endDate);
            auditService.log("cron_end_date_adjusted", "system", null,
                    "Cron end date " + fireDate + " falls on a " + reason +
                    ". Cron end date has been adjusted to the nearest previous working day: " + range.endDate,
                    "success", "cron");
        }

        // 2. Generate the forecast (same call as AdminController.generateTeamForecast)
        TeamForecastResult result;
        try {
            result = teamForecastService.generateForecast(entry.getTeam(), range.startDate, range.endDate);
        } catch (IOException e) {
            log.error("CronSchedulerService: forecast generation failed for id={}: {}",
                    entry.getId(), e.getMessage(), e);
            auditService.log("cron_forecast_failed", "system", null,
                    "id=" + entry.getId() + " team=" + entry.getTeam()
                    + " error=" + e.getMessage(), "error", "cron");
            return;
        }

        // 3. Send to Slack (same call as AdminController.generateTeamForecast)
        try {
            slackNotificationService.notifyTeamForecast(
                    entry.getTeam(), range.startDate, range.endDate,
                    result.getRowCount(), result.getSummaryText(),
                    "cron-scheduler",
                    result.getSlackTableText(),
                    result.getRows(), result.getMonths());
        } catch (Exception e) {
            log.warn("CronSchedulerService: Slack notification failed for id={}: {}",
                    entry.getId(), e.getMessage());
        }

        auditService.log("cron_forecast_generated", "system", null,
                "id=" + entry.getId() + " team=" + entry.getTeam()
                + " start=" + range.startDate + " end=" + range.endDate
                + " rows=" + result.getRowCount(), "success", "cron");
    }

    private static ThreadPoolTaskScheduler buildTaskScheduler() {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(4);
        s.setThreadNamePrefix("cron-forecast-");
        s.setDaemon(true);
        return s;
    }
}
