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

    /** ThreadPoolTaskScheduler shared across all active tasks. */
    private ThreadPoolTaskScheduler taskScheduler;

    /**
     * Map of expression-id → live ScheduledFuture.
     * Only populated when the scheduler is running.
     */
    private final Map<String, ScheduledFuture<?>> activeFutures = new ConcurrentHashMap<>();

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
        List<CronEntry> entries = store.readAll();
        if (entries.isEmpty()) {
            throw new IllegalStateException("No cron expressions configured. Add at least one expression first.");
        }

        taskScheduler = buildTaskScheduler();
        taskScheduler.initialize();

        int count = 0;
        for (CronEntry entry : entries) {
            if (entry.isEnabled()) {
                scheduleEntry(entry);
                count++;
            }
        }
        running = true;
        log.info("CronSchedulerService started with {} expression(s)", count);
        auditService.log("cron_scheduler_started", "system", null,
                "Scheduler started with " + count + " expression(s)", "success", "cron");
        return count;
    }

    /**
     * Stops the scheduler: cancels all futures and shuts down the thread pool.
     *
     * @throws IllegalStateException if the scheduler is not running
     */
    public synchronized void stop() {
        if (!running) {
            throw new IllegalStateException("Scheduler is not running.");
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
    public void onEntryAdded(CronEntry entry) {
        if (running && entry.isEnabled()) {
            scheduleEntry(entry);
            log.debug("CronSchedulerService: live-added task for id={}", entry.getId());
        }
    }

    /**
     * Called by {@link com.holidayleave.assistant.controller.CronController} after
     * an entry has been deleted from the store.  Cancels the live task if present.
     */
    public void onEntryDeleted(String id) {
        ScheduledFuture<?> future = activeFutures.remove(id);
        if (future != null) {
            future.cancel(false);
            log.debug("CronSchedulerService: cancelled task for id={}", id);
        }
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
        ScheduledFuture<?> future = ts.schedule(
                new Runnable() {
                    @Override public void run() {
                        executeForecast(entry);
                    }
                },
                new CronTrigger(springCron));
        activeFutures.put(entry.getId(), future);
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

        // 1. Resolve start/end dates from the cron expression + fire date
        DateRange range = CronDateRangeResolver.resolve(entry.getExpression(), fireDate);

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
