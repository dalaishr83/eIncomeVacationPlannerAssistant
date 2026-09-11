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
    @Autowired private com.holidayleave.assistant.excel.PlannerExcelReader plannerExcelReader;
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

    // ── Start / Stop ──────────────────────────────────────────────────────────

    /**
     * Starts the scheduler: reads all enabled entries from {@link CronExpressionStore}
     * and registers a live cron task for each.
     *
     * @return number of expressions actually scheduled
     * @throws IllegalStateException if the scheduler is already running
     */
    public synchronized int start() {
        if (running) {
            throw new IllegalStateException("Scheduler is already running.");
        }
        List<CronEntry> userEntries = store.readAll();
        if (userEntries.isEmpty()) {
            throw new IllegalStateException("No cron expressions configured. Add at least one expression first.");
        }

        taskScheduler = buildTaskScheduler();
        taskScheduler.initialize();

        // 1. Perform initial background validation and sync working-cron.json
        validateAndSyncWorkingCron();

        // 2. Schedule background validation task based on configured interval (in ms)
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
                    // Only reschedule live tasks when the working store actually changed
                    // (i.e. an expression was preponed due to a public holiday/weekend).
                    // Without this guard, every validation cycle cancelled and re-registered
                    // the CronTrigger futures, resetting their next-fire countdown and
                    // preventing them from ever firing.
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

        // 3. Register live cron jobs using working-cron.json as the source of truth
        int count = rescheduleFromWorkingStore();

        log.info("CronSchedulerService started with {} expression(s)", count);
        auditService.log("cron_scheduler_started", "system", null,
                "Scheduler started with " + count + " expression(s)", "success", "cron");
        return count;
    }

    /**
     * Stops the scheduler: cancels all futures, stops hourly validation, and shuts down the thread pool.
     *
     * @throws IllegalStateException if the scheduler is not running
     */
    public synchronized void stop() {
        if (!running) {
            throw new IllegalStateException("Scheduler is not running.");
        }
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
        log.info("CronSchedulerService stopped");
        auditService.log("cron_scheduler_stopped", "system", null,
                "Scheduler stopped", "success", "cron");
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
            rescheduleFromWorkingStore();
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

        Set<Date> publicHolidays = Collections.emptySet();
        if (appState != null && plannerExcelReader != null) {
            publicHolidays = CronDateRangeResolver.loadPublicHolidays(appState.getDataDir(), plannerExcelReader);
        }

        LocalDateTime now = LocalDateTime.now();
        List<CronEntry> updatedWorkingList = new ArrayList<>();
        boolean anyAdjusted = false;

        for (CronEntry orig : originalList) {
            CronEntry workingEntry = new CronEntry(orig);
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
            if (entry.isEnabled()) {
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
            CronTrigger trigger = new CronTrigger(springCron);
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
     * Executed on each cron fire: resolves the date range, generates the forecast,
     * and delivers it to Slack using the existing workflow.
     */
    private void executeForecast(CronEntry entry) {
        LocalDate fireDate = LocalDate.now();
        log.info("CronSchedulerService: firing for id={} team='{}' expr='{}' date={}",
                entry.getId(), entry.getTeam(), entry.getExpression(), fireDate);
        lastFired = LocalDateTime.now().format(ISO_FMT);

        // 1. Load public holidays and resolve start/end dates from the cron expression + fire date
        Set<Date> publicHolidays = Collections.emptySet();
        if (appState != null && plannerExcelReader != null) {
            publicHolidays = CronDateRangeResolver.loadPublicHolidays(appState.getDataDir(), plannerExcelReader);
        }

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
