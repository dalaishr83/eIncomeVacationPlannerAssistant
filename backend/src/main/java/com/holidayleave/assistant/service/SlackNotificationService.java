package com.holidayleave.assistant.service;

import com.holidayleave.assistant.config.AppProperties;
import com.holidayleave.assistant.model.ForecastRow;
import com.holidayleave.assistant.model.LeaveRecord;
import com.holidayleave.assistant.model.PendingVacation;
import com.holidayleave.assistant.service.YearFileValidator.YearStatus;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.util.StringUtils;

/**
 * Sends Slack Incoming Webhook notifications to the admin channel for two events:
 * <ol>
 *   <li>A PC-type vacation is <b>added</b> via the chat UI
 *       ({@link #notifyPcVacationAdded}).</li>
 *   <li>Any vacation is <b>deleted</b> via the chat UI after successful Excel
 *       write and master-file sync ({@link #notifyVacationDeleted}).</li>
 * </ol>
 *
 * <p>All notifications are fire-and-forget: they run on a daemon background thread
 * and never block or propagate exceptions into the calling request path.
 *
 * <p>Standard notifications (add/delete) are gated by {@code SLACK_ENABLED}.
 * <b>Critical admin alerts</b> ({@link #notifyMissingYearData}) are routed to
 * {@code SLACK_ALERT_WEBHOOK_URL} and are <em>independent</em> of
 * {@code SLACK_ENABLED} — they fire whenever {@code SLACK_ALERT_WEBHOOK_URL} is
 * configured, regardless of whether the main webhook toggle is on or off.
 */
@Service
public class SlackNotificationService {

    private static final Logger log = LoggerFactory.getLogger(SlackNotificationService.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd MMM yyyy");

    /** Maximum delivery attempts before giving up for this event. */
    private static final int MAX_ATTEMPTS = 3;

    @Autowired private AppProperties    props;
    @Autowired private AuditService     auditService;
    @Autowired private TeamForecastService teamForecastService;

    private ExecutorService executor;

    /** Counts consecutive Slack failures; reset to 0 on any success. */
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    /** Epoch millis until which Slack posts are suppressed after repeated failures. */
    private volatile long backoffUntil = 0L;

    @PostConstruct
    public void init() {
        executor = Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "slack-notify");
                t.setDaemon(true);
                return t;
            }
        });
        boolean mainEnabled  = props.getSlack().isEnabled();
        boolean alertEnabled = props.getSlack().getAlertWebhookUrl() != null
                && !props.getSlack().getAlertWebhookUrl().trim().isEmpty();
        if (mainEnabled) {
            log.info("SlackNotificationService enabled — PC leave and delete notifications active");
        } else {
            log.info("SlackNotificationService disabled (SLACK_ENABLED=false) — standard notifications suppressed");
        }
        if (alertEnabled) {
            log.info("Slack alert channel active (SLACK_ALERT_WEBHOOK_URL set) — critical admin alerts independent of SLACK_ENABLED");
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Queues an async Slack notification if {@code leaveCode} matches the
     * configured PC leave code and the service is enabled.
     * Returns immediately; safe to call even when disabled.
     *
     * @param record     the confirmed leave record
     * @param leaveCode  the short leave-type code (e.g. {@code "PC"})
     * @param actingUser the username who submitted the request
     */
    public void notifyPcVacationAdded(final LeaveRecord record,
                                      final String leaveCode,
                                      final String actingUser) {
        if (!props.getSlack().isEnabled()) {
            log.debug("SlackNotificationService disabled, skipping add notification for '{}'",
                    record.employeeName());
            return;
        }
        if (!props.getSlack().getPcLeaveCode().equalsIgnoreCase(leaveCode)) {
            log.debug("Leave code '{}' is not PC ({}), skipping Slack notification",
                    leaveCode, props.getSlack().getPcLeaveCode());
            return;
        }
        log.debug("Queuing Slack add notification for PC leave: employee='{}'", record.employeeName());
        executor.submit(new Runnable() {
            @Override public void run() {
                postAddWithRetry(record, leaveCode, actingUser);
            }
        });
    }

    /**
     * Queues an async Slack notification to Admin user(s) that a required vacation
     * planner file is missing for the requested year and therefore the operation
     * could not proceed.
     *
     * <p>Fires for both Add and Delete operations, for any missing year.
     * Returns immediately; safe to call even when Slack is disabled.
     *
     * @param missingStatus  the {@link YearStatus} describing the missing file
     * @param operation      "add" or "delete"
     * @param employeeName   the employee affected by the request
     * @param startDate      requested vacation start date
     * @param endDate        requested vacation end date
     * @param actingUser     the username who submitted the request
     */
    public void notifyMissingYearData(final YearStatus missingStatus,
                                      final String operation,
                                      final String employeeName,
                                      final java.time.LocalDate startDate,
                                      final java.time.LocalDate endDate,
                                      final String actingUser) {
        // Critical admin alerts are routed independently of SLACK_ENABLED.
        // Prefer the dedicated SLACK_ALERT_WEBHOOK_URL; fall back to the main webhook only
        // when SLACK_ENABLED=true (so the main webhook is intentionally active).
        String alertUrl = props.getSlack().getAlertWebhookUrl();
        final String resolvedUrl;
        if (alertUrl != null && !alertUrl.trim().isEmpty()) {
            // Dedicated alert channel — fires regardless of SLACK_ENABLED.
            resolvedUrl = alertUrl.trim();
        } else if (props.getSlack().isEnabled()
                && props.getSlack().getWebhookUrl() != null
                && !props.getSlack().getWebhookUrl().trim().isEmpty()) {
            // No dedicated alert URL: fall back to main webhook only when Slack is enabled.
            resolvedUrl = props.getSlack().getWebhookUrl().trim();
        } else {
            resolvedUrl = null;
        }
        if (resolvedUrl == null) {
            log.warn("No Slack alert webhook configured — missing-year alert for {} cannot be sent. " +
                     "Set SLACK_ALERT_WEBHOOK_URL in .env (fires regardless of SLACK_ENABLED).",
                     missingStatus.getYear());
            return;
        }
        log.debug("Queuing Slack missing-year-data notification: year={}, employee='{}', channel={}",
                missingStatus.getYear(), employeeName,
                alertUrl != null && !alertUrl.trim().isEmpty() ? "alert" : "main");
        executor.submit(new Runnable() {
            @Override public void run() {
                postMissingYearWithRetry(missingStatus, operation, employeeName,
                        startDate, endDate, actingUser, resolvedUrl);
            }
        });
    }

    /**
     * Queues an async Slack notification that a Team Vacation Forecast was generated.
     *
     * <p>When {@code SLACK_BOT_TOKEN} and {@code SLACK_CHANNEL_ID} are configured the bot sends
     * both the summary card (via {@code chat.postMessage}) and the full forecast table (via
     * the Files API v2) — no webhook URL required.  Falls back to the alert/main webhook when
     * the bot token is not set.  Safe to call when neither is configured.
     *
     * @param team           selected team name
     * @param startDate      selected start date
     * @param endDate        selected end date
     * @param rowCount       number of data rows in the generated report
     * @param summaryText    plain-text summary line
     * @param actingUser     username of the admin who triggered the report
     * @param slackTableText plain-text forecast table (may be null)
     */
    public void notifyTeamForecast(final String team,
                                   final java.time.LocalDate startDate,
                                   final java.time.LocalDate endDate,
                                   final int rowCount,
                                   final String summaryText,
                                   final String actingUser,
                                   final String slackTableText,
                                   final List<ForecastRow> rows,
                                   final List<String> months) {
        final boolean hasBotChannel = StringUtils.hasText(props.getSlack().getBotToken())
                && StringUtils.hasText(props.getSlack().getChannelId());

        // Prefer bot path: summary card + file upload, both going to the same channel.
        if (hasBotChannel) {
            log.debug("Queuing Slack team-forecast notification (bot path): team='{}', rows={}", team, rowCount);
            executor.submit(new Runnable() {
                @Override public void run() {
                    postTeamForecastWithRetry(team, startDate, endDate, rowCount,
                            summaryText, actingUser, null, slackTableText, rows, months);
                }
            });
            return;
        }

        // Fallback: webhook-only path (no file upload, summary card only)
        String alertUrl = props.getSlack().getAlertWebhookUrl();
        final String resolvedUrl;
        if (alertUrl != null && !alertUrl.trim().isEmpty()) {
            resolvedUrl = alertUrl.trim();
        } else if (props.getSlack().isEnabled()
                && props.getSlack().getWebhookUrl() != null
                && !props.getSlack().getWebhookUrl().trim().isEmpty()) {
            resolvedUrl = props.getSlack().getWebhookUrl().trim();
        } else {
            resolvedUrl = null;
        }
        if (resolvedUrl == null) {
            log.debug("No Slack bot token/channel or webhook configured — team forecast notification skipped.");
            return;
        }
        log.debug("Queuing Slack team-forecast notification (webhook path): team='{}', rows={}", team, rowCount);
        executor.submit(new Runnable() {
            @Override public void run() {
                postTeamForecastWithRetry(team, startDate, endDate, rowCount,
                        summaryText, actingUser, resolvedUrl, slackTableText, rows, months);
            }
        });
    }

    /**
     * Queues an async Slack notification that a vacation was deleted via the
     * chat UI. Fires for <em>all</em> leave types — no code guard applied.
     * Returns immediately; safe to call even when disabled.
     *
     * @param pending    the confirmed pending vacation that was deleted
     * @param actingUser the username who performed the deletion
     */
    public void notifyVacationDeleted(final PendingVacation pending,
                                      final String leaveType,
                                      final String actingUser) {
        if (!props.getSlack().isEnabled()) {
            log.debug("SlackNotificationService disabled, skipping delete notification for '{}'",
                    pending.getEmployeeName());
            return;
        }
        log.debug("Queuing Slack delete notification: employee='{}'", pending.getEmployeeName());
        executor.submit(new Runnable() {
            @Override public void run() {
                postDeleteWithRetry(pending, leaveType, actingUser);
            }
        });
    }

    // ── Add notification — private implementation ─────────────────────────────

    private void postAddWithRetry(LeaveRecord record, String leaveCode, String actingUser) {
        if (System.currentTimeMillis() < backoffUntil) {
            log.debug("Slack add notification suppressed (back-off active) for '{}'", record.employeeName());
            return;
        }

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                doPostAdd(record, leaveCode, actingUser);
                consecutiveFailures.set(0);
                return; // success
            } catch (Exception e) {
                log.warn("Slack add notification attempt {}/{} failed for '{}': {}",
                        attempt, MAX_ATTEMPTS, record.employeeName(), e.getMessage());
                auditService.log("slack_notify_failed", "system", record.employeeName(),
                        "action=add attempt=" + attempt + "/" + MAX_ATTEMPTS + " error=" + e.getMessage(),
                        "error", "slack-notify");
            }

            if (attempt < MAX_ATTEMPTS) {
                long delayMs = 5_000L * (long) Math.pow(2, attempt - 1);
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        String msg = "Slack add notification failed after " + MAX_ATTEMPTS + " attempts for '"
                + record.employeeName() + "' — no more retries";
        log.error(msg);
        auditService.log("slack_notify_failed", "system", record.employeeName(),
                msg, "error", "slack-notify");
        recordFailureAndMaybeBackoff(record.employeeName());
    }

    private void doPostAdd(LeaveRecord record, String leaveCode, String actingUser) throws Exception {
        sendWebhook(buildAddPayload(record, leaveCode, actingUser));
        log.info("Slack PC leave add notification sent for '{}' ", record.employeeName());
        auditService.log("slack_notify_sent", "system", record.employeeName(),
                "action=add employee=" + record.employeeName()
                + " leaveCode=" + leaveCode
                + " start=" + record.startDate()
                + " end=" + record.endDate(),
                "success", "slack-notify");
    }

    private String buildAddPayload(LeaveRecord record, String leaveCode, String actingUser) {
        String employee  = escape(record.employeeName());
        String leaveType = escape(record.leaveType());
        String code      = escape(leaveCode);
        String startDate = escape(record.startDate().format(FMT));
        String endDate   = escape(record.endDate().format(FMT));
        long   days      = (long) record.days();
        String dayLabel  = days == 1 ? "working day" : "working days";
        String addedBy   = escape(actingUser != null ? actingUser : "system");

        return "{"
            + "\"text\":\"New PC Leave \\u2014 " + employee + "\","
            + "\"blocks\":["
            +   "{"
            +     "\"type\":\"header\","
            +     "\"text\":{\"type\":\"plain_text\","
            +       "\"text\":\"New PC Leave Request\",\"emoji\":true}"
            +   "},"
            +   "{"
            +     "\"type\":\"section\","
            +     "\"fields\":["
            +       "{\"type\":\"mrkdwn\",\"text\":\"*Employee:*\\n" + employee  + "\"},"
            +       "{\"type\":\"mrkdwn\",\"text\":\"*Leave Type:*\\n" + leaveType + " (" + code + ")\"},"
            +       "{\"type\":\"mrkdwn\",\"text\":\"*From:*\\n" + startDate + "\"},"
            +       "{\"type\":\"mrkdwn\",\"text\":\"*To:*\\n" + endDate   + "\"},"
            +       "{\"type\":\"mrkdwn\",\"text\":\"*Duration:*\\n" + days + " " + dayLabel + "\"},"
            +       "{\"type\":\"mrkdwn\",\"text\":\"*Added by:*\\n" + addedBy + "\"}"
            +     "]"
            +   "},"
            +   "{"
            +     "\"type\":\"context\","
            +     "\"elements\":[{\"type\":\"mrkdwn\","
            +       "\"text\":\"Submitted via Holiday Leave Assistant chat\"}]"
            +   "}"
            + "]"
            + "}";
    }

    // ── Delete notification — private implementation ──────────────────────────

    private void postDeleteWithRetry(PendingVacation pending, String leaveType, String actingUser) {
        if (System.currentTimeMillis() < backoffUntil) {
            log.debug("Slack delete notification suppressed (back-off active) for '{}'",
                    pending.getEmployeeName());
            return;
        }

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                doPostDelete(pending, leaveType, actingUser);
                consecutiveFailures.set(0);
                return; // success
            } catch (Exception e) {
                log.warn("Slack delete notification attempt {}/{} failed for '{}': {}",
                        attempt, MAX_ATTEMPTS, pending.getEmployeeName(), e.getMessage());
                auditService.log("slack_notify_failed", "system", pending.getEmployeeName(),
                        "action=delete attempt=" + attempt + "/" + MAX_ATTEMPTS + " error=" + e.getMessage(),
                        "error", "slack-notify");
            }

            if (attempt < MAX_ATTEMPTS) {
                long delayMs = 5_000L * (long) Math.pow(2, attempt - 1);
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        String msg = "Slack delete notification failed after " + MAX_ATTEMPTS + " attempts for '"
                + pending.getEmployeeName() + "' — no more retries";
        log.error(msg);
        auditService.log("slack_notify_failed", "system", pending.getEmployeeName(),
                msg, "error", "slack-notify");
        recordFailureAndMaybeBackoff(pending.getEmployeeName());
    }

    private void doPostDelete(PendingVacation pending, String leaveType, String actingUser) throws Exception {
        sendWebhook(buildDeletePayload(pending, leaveType, actingUser));
        log.info("Slack delete notification sent for '{}'", pending.getEmployeeName());
        auditService.log("slack_notify_sent", "system", pending.getEmployeeName(),
                "action=delete employee=" + pending.getEmployeeName()
                + " leaveType=" + leaveType
                + " start=" + pending.getStartDate()
                + " end=" + pending.getEndDate(),
                "success", "slack-notify");
    }

    private String buildDeletePayload(PendingVacation pending, String leaveType, String actingUser) {
        String employee  = escape(pending.getEmployeeName());
        String leaveType_ = escape(leaveType != null ? leaveType : "Unknown");
        String startDate = escape(pending.getStartDate().format(FMT));
        String endDate   = escape(pending.getEndDate().format(FMT));
        long   days      = (long) pending.getDays();
        String dayLabel  = days == 1 ? "working day" : "working days";
        String deletedBy = escape(actingUser != null ? actingUser : "system");

        return "{"
            + "\"text\":\"Vacation Deleted \\u2014 " + employee + "\","
            + "\"blocks\":["
            +   "{"
            +     "\"type\":\"header\","
            +     "\"text\":{\"type\":\"plain_text\","
            +       "\"text\":\"Vacation Deleted\",\"emoji\":true}"
            +   "},"
            +   "{"
            +     "\"type\":\"section\","
            +     "\"fields\":["
            +       "{\"type\":\"mrkdwn\",\"text\":\"*Employee:*\\n" + employee  + "\"},"
            +       "{\"type\":\"mrkdwn\",\"text\":\"*Leave Type:*\\n" + leaveType_ + "\"},"
            +       "{\"type\":\"mrkdwn\",\"text\":\"*From:*\\n" + startDate + "\"},"
            +       "{\"type\":\"mrkdwn\",\"text\":\"*To:*\\n" + endDate   + "\"},"
            +       "{\"type\":\"mrkdwn\",\"text\":\"*Duration:*\\n" + days + " " + dayLabel + "\"},"
            +       "{\"type\":\"mrkdwn\",\"text\":\"*Deleted by:*\\n" + deletedBy + "\"}"
            +     "]"
            +   "},"
            +   "{"
            +     "\"type\":\"context\","
            +     "\"elements\":[{\"type\":\"mrkdwn\","
            +       "\"text\":\"Deleted via Holiday Leave Assistant chat\"}]"
            +   "}"
            + "]"
            + "}";
    }

    // ── Missing-year notification — private implementation ───────────────────

    private void postMissingYearWithRetry(YearStatus missingStatus, String operation,
                                          String employeeName,
                                          java.time.LocalDate startDate,
                                          java.time.LocalDate endDate,
                                          String actingUser,
                                          String webhookUrl) {
        if (System.currentTimeMillis() < backoffUntil) {
            log.debug("Slack missing-year notification suppressed (back-off active) for year {}",
                    missingStatus.getYear());
            return;
        }
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                doPostMissingYear(missingStatus, operation, employeeName,
                        startDate, endDate, actingUser, webhookUrl);
                consecutiveFailures.set(0);
                return;
            } catch (Exception e) {
                log.warn("Slack missing-year notification attempt {}/{} failed for year {}: {}",
                        attempt, MAX_ATTEMPTS, missingStatus.getYear(), e.getMessage());
                auditService.log("slack_notify_failed", "system", employeeName,
                        "action=missing_year_data year=" + missingStatus.getYear()
                        + " attempt=" + attempt + "/" + MAX_ATTEMPTS + " error=" + e.getMessage(),
                        "error", "slack-notify");
            }
            if (attempt < MAX_ATTEMPTS) {
                long delayMs = 5_000L * (long) Math.pow(2, attempt - 1);
                try { Thread.sleep(delayMs); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
        }
        String msg = "Slack missing-year notification failed after " + MAX_ATTEMPTS
                + " attempts for year " + missingStatus.getYear();
        log.error(msg);
        auditService.log("slack_notify_failed", "system", employeeName, msg, "error", "slack-notify");
        recordFailureAndMaybeBackoff(employeeName);
    }

    private void doPostMissingYear(YearStatus missingStatus, String operation,
                                   String employeeName,
                                   java.time.LocalDate startDate,
                                   java.time.LocalDate endDate,
                                   String actingUser,
                                   String webhookUrl) throws Exception {
        sendWebhook(buildMissingYearPayload(missingStatus, operation, employeeName,
                startDate, endDate, actingUser), webhookUrl);
        log.info("Slack missing-year-data notification sent: year={}, employee='{}'",
                missingStatus.getYear(), employeeName);
        auditService.log("slack_notify_sent", "system", employeeName,
                "action=missing_year_data year=" + missingStatus.getYear()
                + " operation=" + operation
                + " employee=" + employeeName
                + " start=" + startDate + " end=" + endDate,
                "success", "slack-notify");
    }

    private String buildMissingYearPayload(YearStatus missingStatus, String operation,
                                           String employeeName,
                                           java.time.LocalDate startDate,
                                           java.time.LocalDate endDate,
                                           String actingUser) {
        int    year        = missingStatus.getYear();
        String emp         = escape(employeeName);
        String op          = escape(operation != null ? operation.toUpperCase() : "UNKNOWN");
        String start       = escape(startDate.format(FMT));
        String end         = escape(endDate.format(FMT));
        String requestedBy = escape(actingUser != null ? actingUser : "system");
        String masterFile  = escape(missingStatus.getMasterPath());

        return "{"
            + "\"text\":\"\\u26A0\\uFE0F CRITICAL: Missing Vacation Planner Data for " + year + "\","
            + "\"blocks\":["
            +   "{"
            +     "\"type\":\"header\","
            +     "\"text\":{\"type\":\"plain_text\","
            +       "\"text\":\"\\u26A0\\uFE0F Missing Vacation Planner Data\",\"emoji\":true}"
            +   "},"
            +   "{"
            +     "\"type\":\"section\","
            +     "\"text\":{\"type\":\"mrkdwn\","
            +       "\"text\":\"The *" + op + "* vacation operation could not proceed because the "
            +           "*" + year + "* master vacation planner sheet is missing.\"}"
            +   "},"
            +   "{"
            +     "\"type\":\"section\","
            +     "\"fields\":["
            +       "{\"type\":\"mrkdwn\",\"text\":\"*Affected Employee:*\\n" + emp + "\"},"
            +       "{\"type\":\"mrkdwn\",\"text\":\"*Operation:*\\n" + op + "\"},"
            +       "{\"type\":\"mrkdwn\",\"text\":\"*Requested Date Range:*\\n" + start + " \\u2014 " + end + "\"},"
            +       "{\"type\":\"mrkdwn\",\"text\":\"*Requested by:*\\n" + requestedBy + "\"},"
            +       "{\"type\":\"mrkdwn\",\"text\":\"*Missing Year:*\\n" + year + "\"},"
            +       "{\"type\":\"mrkdwn\",\"text\":\"*Expected Master File:*\\n`" + masterFile + "`\"}"
            +     "]"
            +   "},"
            +   "{"
            +     "\"type\":\"section\","
            +     "\"text\":{\"type\":\"mrkdwn\","
            +       "\"text\":\"*Severity:* :red_circle: CRITICAL\\n"
            +           "*Required Admin Action:* Upload the *" + year + "* vacation planner Excel file "
            +           "via the File Management page so that this operation can be retried.\"}"
            +   "},"
            +   "{"
            +     "\"type\":\"context\","
            +     "\"elements\":[{\"type\":\"mrkdwn\","
            +       "\"text\":\"Triggered by Holiday Leave Assistant — operation blocked pending data provisioning\"}]"
            +   "}"
            + "]"
            + "}";
    }

    // ── Team-forecast notification — private implementation ──────────────────

    private void postTeamForecastWithRetry(String team,
                                           java.time.LocalDate startDate,
                                           java.time.LocalDate endDate,
                                           int rowCount,
                                           String summaryText,
                                           String actingUser,
                                           String webhookUrl,
                                           String slackTableText,
                                           List<ForecastRow> rows,
                                           List<String> months) {
        if (System.currentTimeMillis() < backoffUntil) {
            log.debug("Slack team-forecast notification suppressed (back-off active)");
            return;
        }
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                doPostTeamForecast(team, startDate, endDate, rowCount,
                        summaryText, actingUser, webhookUrl, slackTableText, rows, months);
                consecutiveFailures.set(0);
                return;
            } catch (Exception e) {
                log.warn("Slack team-forecast notification attempt {}/{} failed: {}",
                        attempt, MAX_ATTEMPTS, e.getMessage());
                auditService.log("slack_notify_failed", "system", null,
                        "action=team_forecast attempt=" + attempt + "/" + MAX_ATTEMPTS
                        + " error=" + e.getMessage(),
                        "error", "slack-notify");
            }
            if (attempt < MAX_ATTEMPTS) {
                long delayMs = 5_000L * (long) Math.pow(2, attempt - 1);
                try { Thread.sleep(delayMs); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
        }
        String msg = "Slack team-forecast notification failed after " + MAX_ATTEMPTS + " attempts";
        log.error(msg);
        auditService.log("slack_notify_failed", "system", null, msg, "error", "slack-notify");
        recordFailureAndMaybeBackoff("team-forecast");
    }

    private void doPostTeamForecast(String team,
                                    java.time.LocalDate startDate,
                                    java.time.LocalDate endDate,
                                    int rowCount,
                                    String summaryText,
                                    String actingUser,
                                    String webhookUrl,
                                    String slackTableText,
                                    List<ForecastRow> rows,
                                    List<String> months) throws Exception {
        // 1. Send the summary card.
        //    Bot path (webhookUrl == null): use chat.postMessage to the configured channel.
        //    Webhook path (webhookUrl != null): use the Incoming Webhook.
        if (webhookUrl == null) {
            // Bot path — summary card via chat.postMessage to the same channel as the file
            String channelId = props.getSlack().getChannelId();
            String token     = props.getSlack().getBotToken();
            String payload   = buildTeamForecastPayload(team, startDate, endDate, rowCount,
                    summaryText, actingUser);
            // Wrap the Block Kit payload for chat.postMessage (add channel field)
            String msgPayload = "{\"channel\":\"" + escape(channelId) + "\","
                    + payload.substring(1); // strip leading '{' already present
            String summaryResponse = slackApiPost(
                    "https://slack.com/api/chat.postMessage", msgPayload, token);
            if (!summaryResponse.contains("\"ok\":true")) {
                throw new RuntimeException("chat.postMessage (summary) failed: " + summaryResponse);
            }
        } else {
            // Webhook path
            sendWebhook(buildTeamForecastPayload(team, startDate, endDate, rowCount,
                    summaryText, actingUser), webhookUrl);
        }

        // 2. Deliver the forecast table according to SLACK_REPORT_FORMAT.
        //    Isolated in its own try/catch so a failed upload does NOT re-trigger the
        //    summary retry loop — the summary card already succeeded at step 1.
        boolean excelFormat = "excel".equalsIgnoreCase(props.getSlack().getReportFormat());
        if (excelFormat && rows != null && !rows.isEmpty()) {
            try {
                uploadForecastExcelFile(rows, months, team, startDate, endDate);
            } catch (Exception uploadEx) {
                log.error("Slack forecast Excel upload failed for team='{}': {}", team, uploadEx.getMessage(), uploadEx);
                auditService.log("slack_notify_failed", "system", null,
                        "action=team_forecast_excel_upload team=" + team + " error=" + uploadEx.getMessage(),
                        "error", "slack-notify");
            }
        } else if (!excelFormat && slackTableText != null && !slackTableText.isEmpty()) {
            try {
                uploadForecastFile(slackTableText, team, startDate, endDate);
            } catch (Exception uploadEx) {
                log.error("Slack forecast file upload failed for team='{}': {}", team, uploadEx.getMessage(), uploadEx);
                auditService.log("slack_notify_failed", "system", null,
                        "action=team_forecast_file_upload team=" + team + " error=" + uploadEx.getMessage(),
                        "error", "slack-notify");
            }
        }

        log.info("Slack team-forecast notification sent: team='{}', rows={}", team, rowCount);
        auditService.log("slack_notify_sent", "system", null,
                "action=team_forecast team=" + team
                + " start=" + startDate + " end=" + endDate
                + " rows=" + rowCount,
                "success", "slack-notify");
    }

    private String buildTeamForecastPayload(String team,
                                            java.time.LocalDate startDate,
                                            java.time.LocalDate endDate,
                                            int rowCount,
                                            String summaryText,
                                            String actingUser) {
        String teamEsc     = escape(team);
        String startEsc    = escape(startDate.format(FMT));
        String endEsc      = escape(endDate.format(FMT));
        String requestedBy = escape(actingUser != null ? actingUser : "system");
        String today       = escape(java.time.LocalDate.now().format(FMT));

        return "{"
            + "\"text\":\"\\uD83D\\uDCCA Team Vacation Forecast Generated \\u2014 " + teamEsc + "\","
            + "\"blocks\":["
            +   "{"
            +     "\"type\":\"header\","
            +     "\"text\":{\"type\":\"plain_text\","
            +       "\"text\":\"\\uD83D\\uDCCA Team Vacation Forecast Generated\",\"emoji\":true}"
            +   "},"
            +   "{"
            +     "\"type\":\"section\","
            +     "\"fields\":["
            +       "{\"type\":\"mrkdwn\",\"text\":\"*Team:*\\n" + teamEsc + "\"},"
            +       "{\"type\":\"mrkdwn\",\"text\":\"*Period:*\\n" + startEsc + " \\u2014 " + endEsc + "\"},"
            +       "{\"type\":\"mrkdwn\",\"text\":\"*Total Employees:*\\n" + rowCount + "\"},"
            +       "{\"type\":\"mrkdwn\",\"text\":\"*Consumed figures as of:*\\n" + today + "\"},"
            +       "{\"type\":\"mrkdwn\",\"text\":\"*Status:*\\n:white_check_mark: Report generated successfully\"},"
            +       "{\"type\":\"mrkdwn\",\"text\":\"*Requested by:*\\n" + requestedBy + "\"}"
            +     "]"
            +   "},"
            +   "{"
            +     "\"type\":\"context\","
            +     "\"elements\":[{\"type\":\"mrkdwn\","
            +       "\"text\":\"Generated via Holiday Leave Assistant — full forecast table uploaded as a file snippet\"}]"
            +   "}"
            + "]"
            + "}";
    }

    // ── Slack forecast delivery ───────────────────────────────────────────────

    /**
     * Generates an Excel workbook for the forecast, uploads it to the configured Slack channel
     * via the Files API v2 (multipart), then deletes the temp file on success.
     *
     * <p>If the upload fails the temp file is intentionally left on disk so the failure
     * can be diagnosed or the file can be retried manually.
     */
    void uploadForecastExcelFile(List<ForecastRow> rows,
                                 List<String> months,
                                 String team,
                                 java.time.LocalDate startDate,
                                 java.time.LocalDate endDate) throws Exception {
        String token     = props.getSlack().getBotToken();
        String channelId = props.getSlack().getChannelId();
        if (!StringUtils.hasText(token) || !StringUtils.hasText(channelId)) {
            log.debug("Slack bot token or channel ID not configured — skipping forecast Excel upload");
            return;
        }

        // 1. Write the Excel workbook to {DATA_DIR}/temp/
        String dataDir = props.getDataDir() != null ? props.getDataDir() : "data";
        Path excelPath = teamForecastService.writeForecastExcel(
                rows, months, team, startDate, endDate, dataDir);

        try {
            // 2. Step 1 of Files API v2: request an upload URL.
            //    files.getUploadURLExternal requires form-encoded parameters (NOT JSON).
            String filename = excelPath.getFileName().toString();
            long   fileSize = Files.size(excelPath);
            String getUrlForm = "filename=" + urlEncode(filename) + "&length=" + fileSize;
            String getUrlResponse = slackApiPostForm(
                    "https://slack.com/api/files.getUploadURLExternal", getUrlForm, token);
            if (!getUrlResponse.contains("\"ok\":true")) {
                throw new RuntimeException("files.getUploadURLExternal failed: " + getUrlResponse);
            }
            String uploadUrl = extractJsonString(getUrlResponse, "upload_url");
            String fileId    = extractJsonString(getUrlResponse, "file_id");
            if (uploadUrl == null || fileId == null) {
                throw new RuntimeException("Could not parse upload_url/file_id from: " + getUrlResponse);
            }

            // 3. Step 2 of Files API v2: upload the file content via multipart POST.
            //    The upload target URL from step 1 accepts the raw file as a multipart body.
            String boundary = "----SlackBoundary" + System.currentTimeMillis();
            byte[] fileBytes = Files.readAllBytes(excelPath);

            StringBuilder multipartHeader = new StringBuilder();
            multipartHeader.append("--").append(boundary).append("\r\n");
            multipartHeader.append("Content-Disposition: form-data; name=\"file\"; filename=\"")
                    .append(filename).append("\"\r\n");
            multipartHeader.append("Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n");
            multipartHeader.append("\r\n");
            byte[] headerBytes  = multipartHeader.toString().getBytes(StandardCharsets.UTF_8);
            byte[] footerBytes  = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
            byte[] body = new byte[headerBytes.length + fileBytes.length + footerBytes.length];
            System.arraycopy(headerBytes, 0, body, 0, headerBytes.length);
            System.arraycopy(fileBytes, 0, body, headerBytes.length, fileBytes.length);
            System.arraycopy(footerBytes, 0, body, headerBytes.length + fileBytes.length, footerBytes.length);

            URL uploadUrlObj = new URL(uploadUrl);
            HttpURLConnection uploadConn = (HttpURLConnection) uploadUrlObj.openConnection();
            uploadConn.setConnectTimeout(30_000);
            uploadConn.setReadTimeout(30_000);
            uploadConn.setDoOutput(true);
            uploadConn.setRequestMethod("POST");
            uploadConn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            uploadConn.setRequestProperty("Content-Length", String.valueOf(body.length));
            try (OutputStream os = uploadConn.getOutputStream()) {
                os.write(body);
            }
            int uploadStatus = uploadConn.getResponseCode();
            String uploadResponse = readResponse(uploadConn);
            if (uploadStatus < 200 || uploadStatus >= 300) {
                throw new RuntimeException("File upload returned HTTP " + uploadStatus + ": " + uploadResponse);
            }

            // 4. Step 3 of Files API v2: complete the upload and share into the channel.
            //    files.completeUploadExternal also requires form-encoded parameters (NOT JSON).
            String completeForm = "files=" + urlEncode("[{\"id\":\"" + escape(fileId) + "\"}]")
                    + "&channel_id=" + urlEncode(channelId)
                    + "&initial_comment=" + urlEncode("Team Forecast Excel Report \u2014 " + team);
            String completeResponse = slackApiPostForm(
                    "https://slack.com/api/files.completeUploadExternal", completeForm, token);
            if (!completeResponse.contains("\"ok\":true")) {
                throw new RuntimeException("files.completeUploadExternal failed: " + completeResponse);
            }

            log.info("Slack forecast Excel file uploaded successfully: team='{}', file='{}'", team, filename);

            // 5. Delete the temp file only after confirmed successful upload
            try {
                Files.delete(excelPath);
                log.debug("Temp forecast Excel file deleted: {}", excelPath);
            } catch (Exception deleteEx) {
                log.warn("Could not delete temp forecast Excel file '{}': {}", excelPath, deleteEx.getMessage());
            }

        } catch (Exception ex) {
            // Upload failed — leave temp file in place for diagnosis
            log.error("Slack forecast Excel upload failed (temp file retained at '{}'): {}",
                    excelPath, ex.getMessage(), ex);
            throw ex; // re-throw so the caller can log the audit entry
        }
    }

    // ── Slack forecast table delivery (plain-text) ────────────────────────────

    /**
     * Posts the full forecast table inline in Slack via {@code chat.postMessage} using
     * a {@code rich_text} block with a {@code rich_text_preformatted} element.
     *
     * <p>Why this approach:
     * <ul>
     *   <li>The Slack Files API cannot share a stored file into a private channel with a bot
     *       token: {@code completeUploadExternal} silently ignores {@code channel_id}
     *       (confirmed: {@code shares:{}} in every response); {@code files.share} requires a
     *       user token ({@code xoxp-}); {@code chat.postMessage file_ids} does not attach
     *       the file; permalink links to unshared files return an error page.</li>
     *   <li>{@code rich_text_preformatted} has a <b>10,000-character element limit</b> —
     *       well above the 3,000-char {@code section.text} mrkdwn limit that caused the
     *       original truncation, and sufficient for any realistic team size.</li>
     *   <li>Only {@code chat:write} scope is required — already granted and proven working.</li>
     * </ul>
     *
     * @param tableText  the plain-text forecast table (may include ``` code-fence markers)
     * @param team       used for logging only
     * @param startDate  unused (kept for API compatibility)
     * @param endDate    unused (kept for API compatibility)
     */
    void uploadForecastFile(String tableText,
                            String team,
                            java.time.LocalDate startDate,
                            java.time.LocalDate endDate) throws Exception {
        String token     = props.getSlack().getBotToken();
        String channelId = props.getSlack().getChannelId();
        if (!StringUtils.hasText(token) || !StringUtils.hasText(channelId)) {
            log.debug("Slack bot token or channel ID not configured — skipping forecast table post");
            return;
        }

        // Strip outer code-fence wrappers — the rich_text_preformatted element
        // renders monospace natively; the ``` markers are not needed.
        String content = tableText;
        if (content.startsWith("```\n")) content = content.substring(4);
        if (content.endsWith("\n```"))  content = content.substring(0, content.length() - 4);

        // rich_text_preformatted has a 10,000-char element limit — sufficient for any team.
        // Build the payload: a rich_text block with a single preformatted element.
        String payload = "{\"channel\":\"" + escape(channelId) + "\""
                + ",\"blocks\":[{"
                + "\"type\":\"rich_text\""
                + ",\"elements\":[{"
                + "\"type\":\"rich_text_preformatted\""
                + ",\"elements\":[{"
                + "\"type\":\"text\""
                + ",\"text\":\"" + escape(content) + "\""
                + "}]"
                + "}]"
                + "}]}";
        String response = slackApiPost(
                "https://slack.com/api/chat.postMessage", payload, token);
        log.debug("chat.postMessage (rich_text_preformatted) response: {}", response);
        if (!response.contains("\"ok\":true")) {
            throw new RuntimeException(
                    "chat.postMessage (rich_text_preformatted) failed: " + response);
        }
        log.info("Slack forecast table posted inline: team='{}'", team);
    }

    /** POST a JSON body to a Slack Web API endpoint with a Bearer token. */
    private String slackApiPost(String endpoint, String jsonBody, String token) throws Exception {
        byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
        URL url = new URL(endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bodyBytes);
        }
        int status = conn.getResponseCode();
        String body = readResponse(conn);
        if (status < 200 || status >= 300) {
            throw new RuntimeException("Slack API POST " + endpoint + " returned HTTP " + status + ": " + body);
        }
        return body;
    }

    /**
     * POST a form-encoded body ({@code application/x-www-form-urlencoded}) to a Slack Web API
     * endpoint with a Bearer token.
     *
     * <p>Required by {@code files.getUploadURLExternal} and {@code files.completeUploadExternal},
     * which do not accept a JSON body — they only recognise form-encoded parameters.
     */
    private String slackApiPostForm(String endpoint, String formBody, String token) throws Exception {
        byte[] bodyBytes = formBody.getBytes(StandardCharsets.UTF_8);
        URL url = new URL(endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bodyBytes);
        }
        int status = conn.getResponseCode();
        String body = readResponse(conn);
        if (status < 200 || status >= 300) {
            throw new RuntimeException("Slack API POST (form) " + endpoint + " returned HTTP " + status + ": " + body);
        }
        return body;
    }

    /** URL-encodes a string for use in an {@code application/x-www-form-urlencoded} body. */
    private static String urlEncode(String s) {
        if (s == null) return "";
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            // UTF-8 is always supported
            return s;
        }
    }

    /** Reads the full response body from a connection (uses error stream on failure). */
    private static String readResponse(HttpURLConnection conn) throws Exception {
        java.io.InputStream is;
        try {
            is = conn.getInputStream();
        } catch (Exception e) {
            is = conn.getErrorStream();
        }
        if (is == null) return "";
        try (java.io.BufferedReader br =
                     new java.io.BufferedReader(new java.io.InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    /**
     * Extracts a string value from a simple flat JSON object by key name.
     * Handles {@code "key":"value"} patterns and unescapes JSON escape sequences
     * ({@code \/} → {@code /}, {@code \\} → {@code \}) that Slack embeds in URLs.
     */
    static String extractJsonString(String json, String key) {
        if (json == null) return null;
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();
        // Walk forward to find the closing quote, skipping over \" and \/ escapes
        int i = start;
        StringBuilder value = new StringBuilder();
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                if (next == '"') {
                    value.append('"');  // escaped quote inside value
                    i += 2;
                } else if (next == '/') {
                    value.append('/');  // \/ → / (Slack escapes forward slashes in URLs)
                    i += 2;
                } else if (next == '\\') {
                    value.append('\\');
                    i += 2;
                } else {
                    value.append(c);
                    i++;
                }
            } else if (c == '"') {
                break;  // unescaped closing quote
            } else {
                value.append(c);
                i++;
            }
        }
        return value.length() == 0 && i >= json.length() ? null : value.toString();
    }

    // ── Shared HTTP helper ────────────────────────────────────────────────────

    /**
     * POSTs {@code payload} to the configured Slack webhook URL.
     * Throws {@link RuntimeException} on non-2xx response so callers can retry.
     */
    /** Posts to the configured main webhook URL. Used by add/delete notifications. */
    private void sendWebhook(String payload) throws Exception {
        sendWebhook(payload, props.getSlack().getWebhookUrl());
    }

    /** Posts {@code payload} to an explicit {@code webhookUrl}. Used by alert notifications. */
    private void sendWebhook(String payload, String webhookUrl) throws Exception {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        URL url = new URL(webhookUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setRequestProperty("Content-Length", String.valueOf(body.length));
        OutputStream os = conn.getOutputStream();
        try {
            os.write(body);
            os.flush();
        } finally {
            os.close();
        }
        int status = conn.getResponseCode();
        if (status < 200 || status >= 300) {
            throw new RuntimeException("Slack webhook returned HTTP " + status);
        }
    }

    // ── Shared utilities ──────────────────────────────────────────────────────

    /** Escapes characters that would break the JSON string value. */
    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private void recordFailureAndMaybeBackoff(String employeeName) {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= 3) {
            long backoffMs = 5 * 60 * 1000L; // 5 minutes
            backoffUntil = System.currentTimeMillis() + backoffMs;
            log.error("Slack notifications suppressed for {}s after {} consecutive failures",
                    backoffMs / 1000, failures);
            consecutiveFailures.set(0);
        }
    }
}
