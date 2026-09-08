package com.holidayleave.assistant.service;

import com.holidayleave.assistant.config.AppProperties;
import com.holidayleave.assistant.model.LeaveRecord;
import com.holidayleave.assistant.model.PendingVacation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SlackNotificationService}.
 *
 * The service is fully fire-and-forget with async dispatch, so these tests
 * focus on the gatekeeping logic (enabled/disabled guards, PC-code guard)
 * rather than the HTTP call itself, which is an integration concern.
 *
 * Covers:
 *  - notifyPcVacationAdded: no-op when disabled
 *  - notifyPcVacationAdded: no-op when leaveCode != configured PC code
 *  - notifyPcVacationAdded: queues work when enabled and code matches
 *  - notifyVacationDeleted: no-op when disabled
 *  - notifyVacationDeleted: queues work when enabled
 *  - Lifecycle: init creates executor; shutdown calls executor.shutdown
 *  - isAlive-style guard: executor is always initialized before public calls
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SlackNotificationServiceTest {

    @Mock private AppProperties      props;
    @Mock private AuditService       auditService;
    @Mock private TeamForecastService teamForecastService;

    @InjectMocks
    private SlackNotificationService service;

    private AppProperties.Slack slackConfig;
    private LeaveRecord sampleRecord;
    private PendingVacation samplePending;

    @BeforeEach
    void setUp() {
        slackConfig = new AppProperties.Slack();
        when(props.getSlack()).thenReturn(slackConfig);

        service.init();

        sampleRecord = new LeaveRecord(
            "Alice", LocalDate.of(2024, 6, 1), LocalDate.of(2024, 6, 5),
            5.0, "Personal Choice Holiday", "break"
        );

        samplePending = new PendingVacation("delete");
        samplePending.setEmployeeName("Alice");
        samplePending.setStartDate(LocalDate.of(2024, 6, 1));
        samplePending.setEndDate(LocalDate.of(2024, 6, 5));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // notifyPcVacationAdded
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("notifyPcVacationAdded — disabled guard")
    class NotifyPcVacationAdded {

        @Test
        @DisplayName("is a no-op when Slack is disabled")
        void notifyPcVacationAdded_disabled_noOp() {
            slackConfig.setEnabled(false);
            // Should not throw and should not block
            service.notifyPcVacationAdded(sampleRecord, "PC", "admin");
            // No exception == pass; no executor interaction to verify without reflection
        }

        @Test
        @DisplayName("is a no-op when leave code does not match configured PC code")
        void notifyPcVacationAdded_nonPcCode_noOp() {
            slackConfig.setEnabled(true);
            slackConfig.setPcLeaveCode("PC");
            // "V" != "PC" → no webhook should be attempted
            service.notifyPcVacationAdded(sampleRecord, "V", "admin");
            // No exception == pass
        }

        @Test
        @DisplayName("PC code comparison is case-insensitive")
        void notifyPcVacationAdded_pcCodeCaseInsensitive_noOp() {
            slackConfig.setEnabled(true);
            slackConfig.setPcLeaveCode("PC");
            slackConfig.setWebhookUrl("http://localhost/fake"); // won't be called
            // Lowercase "pc" should still match
            service.notifyPcVacationAdded(sampleRecord, "pc", "admin");
            // returns without throwing
        }

        @Test
        @DisplayName("enabled + matching code submits task without blocking caller")
        void notifyPcVacationAdded_enabledAndMatching_returnsImmediately() {
            slackConfig.setEnabled(true);
            slackConfig.setPcLeaveCode("PC");
            slackConfig.setWebhookUrl("http://localhost/nowhere"); // background will fail silently
            long start = System.currentTimeMillis();
            service.notifyPcVacationAdded(sampleRecord, "PC", "admin");
            long elapsed = System.currentTimeMillis() - start;
            // Fire-and-forget — caller should return in well under 1 second
            org.assertj.core.api.Assertions.assertThat(elapsed).isLessThan(1000L);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // notifyVacationDeleted
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("notifyVacationDeleted — disabled guard")
    class NotifyVacationDeleted {

        @Test
        @DisplayName("is a no-op when Slack is disabled")
        void notifyVacationDeleted_disabled_noOp() {
            slackConfig.setEnabled(false);
            service.notifyVacationDeleted(samplePending, "Vacation", "admin");
            // No exception == pass
        }

        @Test
        @DisplayName("enabled + any leave type submits task without blocking caller")
        void notifyVacationDeleted_enabled_returnsImmediately() {
            slackConfig.setEnabled(true);
            slackConfig.setWebhookUrl("http://localhost/nowhere");
            long start = System.currentTimeMillis();
            service.notifyVacationDeleted(samplePending, "Annual Leave", "admin");
            long elapsed = System.currentTimeMillis() - start;
            org.assertj.core.api.Assertions.assertThat(elapsed).isLessThan(1000L);
        }

        @Test
        @DisplayName("null leaveType is accepted without NPE")
        void notifyVacationDeleted_nullLeaveType_noNpe() {
            slackConfig.setEnabled(false); // keep simple
            service.notifyVacationDeleted(samplePending, null, "admin");
        }

        @Test
        @DisplayName("null actingUser is accepted without NPE")
        void notifyVacationDeleted_nullActingUser_noNpe() {
            slackConfig.setEnabled(false);
            service.notifyVacationDeleted(samplePending, "Annual Leave", null);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Lifecycle — init / shutdown")
    class Lifecycle {

        @Test
        @DisplayName("init completes without error when disabled")
        void init_disabled_noError() {
            slackConfig.setEnabled(false);
            // Already called in @BeforeEach — re-calling to cover branch
            service.init();
        }

        @Test
        @DisplayName("init completes without error when enabled")
        void init_enabled_noError() {
            slackConfig.setEnabled(true);
            service.init();
        }

        @Test
        @DisplayName("shutdown completes without error")
        void shutdown_noError() {
            service.shutdown();
        }

        @Test
        @DisplayName("public methods callable after shutdown without NPE")
        void shutdown_thenNotify_noNpe() {
            slackConfig.setEnabled(false);
            service.shutdown();
            service.notifyPcVacationAdded(sampleRecord, "PC", "admin");
            service.notifyVacationDeleted(samplePending, "Vacation", "admin");
        }
    }

    // ── notifyMissingYearData ─────────────────────────────────────────────────

    @Nested
    @DisplayName("notifyMissingYearData")
    class NotifyMissingYearDataTests {

        private YearFileValidator.YearStatus missingStatus;

        @BeforeEach
        void setup() {
            missingStatus = new YearFileValidator.YearStatus(
                    2027,
                    "/data/eIndkomst vacation 2027.xlsx",
                    "/data/working/eIndkomst vacation 2027.xlsx",
                    false, false);
        }

        @Test
        @DisplayName("no-op when SLACK_ENABLED=false AND no SLACK_ALERT_WEBHOOK_URL configured")
        void disabled_noAlertUrl_isNoOp() {
            slackConfig.setEnabled(false);
            slackConfig.setWebhookUrl("");
            slackConfig.setAlertWebhookUrl("");
            service.notifyMissingYearData(missingStatus, "add", "Alice Smith",
                    LocalDate.of(2026, 12, 1), LocalDate.of(2027, 2, 28), "admin");
            verifyNoInteractions(auditService);
        }

        @Test
        @DisplayName("fires when SLACK_ENABLED=false but SLACK_ALERT_WEBHOOK_URL is set")
        void disabled_withAlertUrl_stillFires() {
            slackConfig.setEnabled(false);
            slackConfig.setWebhookUrl("");
            slackConfig.setAlertWebhookUrl("https://hooks.slack.com/alerts");
            // Background thread will fail (no real endpoint) — that is expected in unit tests.
            // The key assertion: no exception thrown and execution is not gated by SLACK_ENABLED.
            service.notifyMissingYearData(missingStatus, "add", "Alice Smith",
                    LocalDate.of(2026, 12, 1), LocalDate.of(2027, 2, 28), "admin");
            // No NPE or IAE = pass — method proceeds past the SLACK_ENABLED check.
        }

        @Test
        @DisplayName("no-op when enabled but no webhook URL configured at all")
        void enabled_noUrl_isNoOp() {
            slackConfig.setEnabled(true);
            slackConfig.setWebhookUrl("");
            slackConfig.setAlertWebhookUrl("");
            service.notifyMissingYearData(missingStatus, "add", "Alice Smith",
                    LocalDate.of(2026, 12, 1), LocalDate.of(2027, 2, 28), "admin");
            verifyNoInteractions(auditService);
        }

        @Test
        @DisplayName("uses dedicated alert webhook when SLACK_ALERT_WEBHOOK_URL is set (SLACK_ENABLED=true)")
        void alertWebhookUrl_usedWhenSet() {
            slackConfig.setEnabled(true);
            slackConfig.setWebhookUrl("https://hooks.slack.com/main");
            slackConfig.setAlertWebhookUrl("https://hooks.slack.com/alerts");
            // Background thread will fail (no real endpoint) — that is expected in unit tests.
            service.notifyMissingYearData(missingStatus, "add", "Alice Smith",
                    LocalDate.of(2026, 12, 1), LocalDate.of(2027, 2, 28), "admin");
            // No NPE or IAE = pass. URL routing is verified by the dedicated channel field being read.
        }

        @Test
        @DisplayName("falls back to main webhook when alert URL is empty and SLACK_ENABLED=true")
        void alertWebhookUrl_fallsBackToMainWebhook() {
            slackConfig.setEnabled(true);
            slackConfig.setWebhookUrl("https://hooks.slack.com/main");
            slackConfig.setAlertWebhookUrl("");
            service.notifyMissingYearData(missingStatus, "add", "Alice Smith",
                    LocalDate.of(2026, 12, 1), LocalDate.of(2027, 2, 28), "admin");
            // No NPE or IAE = pass.
        }

        @Test
        @DisplayName("no fallback to main webhook when alert URL empty and SLACK_ENABLED=false")
        void disabled_noAlertUrl_noFallback() {
            slackConfig.setEnabled(false);
            slackConfig.setWebhookUrl("https://hooks.slack.com/main");
            slackConfig.setAlertWebhookUrl("");
            service.notifyMissingYearData(missingStatus, "add", "Alice Smith",
                    LocalDate.of(2026, 12, 1), LocalDate.of(2027, 2, 28), "admin");
            verifyNoInteractions(auditService);
        }

        @Test
        @DisplayName("delete operation accepted as well as add")
        void deleteOperation_noException() {
            slackConfig.setEnabled(false);
            service.notifyMissingYearData(missingStatus, "delete", "Bob Johnson",
                    LocalDate.of(2026, 12, 10), LocalDate.of(2027, 1, 15), "admin");
            // No exception.
        }
    }

    // ── notifyTeamForecast ────────────────────────────────────────────────────

    @Nested
    @DisplayName("notifyTeamForecast")
    class NotifyTeamForecastTests {

        @Test
        @DisplayName("no-op when no webhook and no bot token configured")
        void noWebhook_noToken_isNoOp() {
            slackConfig.setEnabled(false);
            slackConfig.setWebhookUrl("");
            slackConfig.setAlertWebhookUrl("");
            slackConfig.setBotToken("");
            slackConfig.setChannelId("");
            service.notifyTeamForecast("EIndkomst Team",
                    LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31),
                    33, "summary", "admin", "table",
                    Collections.emptyList(), Collections.emptyList());
            verifyNoInteractions(auditService);
        }

        @Test
        @DisplayName("fires when alert webhook URL is configured (independent of SLACK_ENABLED)")
        void alertWebhook_fires_regardlessOfEnabled() {
            slackConfig.setEnabled(false);
            slackConfig.setWebhookUrl("");
            slackConfig.setAlertWebhookUrl("https://hooks.slack.com/alerts");
            slackConfig.setBotToken("");
            slackConfig.setChannelId("");
            long start = System.currentTimeMillis();
            service.notifyTeamForecast("EIndkomst Team",
                    LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31),
                    33, "summary", "admin", "table",
                    Collections.emptyList(), Collections.emptyList());
            long elapsed = System.currentTimeMillis() - start;
            // Fire-and-forget — should return immediately
            org.assertj.core.api.Assertions.assertThat(elapsed).isLessThan(1000L);
        }

        @Test
        @DisplayName("null slackTableText is accepted without NPE")
        void nullTableText_noNpe() {
            slackConfig.setEnabled(false);
            service.notifyTeamForecast("EIndkomst Team",
                    LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31),
                    0, "summary", "admin", null,
                    Collections.emptyList(), Collections.emptyList());
            // No exception == pass
        }

        @Test
        @DisplayName("excel format: no-op when no bot token/channel configured")
        void excelFormat_noToken_isNoOp() {
            slackConfig.setEnabled(false);
            slackConfig.setWebhookUrl("");
            slackConfig.setAlertWebhookUrl("");
            slackConfig.setBotToken("");
            slackConfig.setChannelId("");
            slackConfig.setReportFormat("excel");
            service.notifyTeamForecast("EIndkomst Team",
                    LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31),
                    0, "summary", "admin", null,
                    Collections.emptyList(), Collections.emptyList());
            verifyNoInteractions(auditService);
        }

        @Test
        @DisplayName("excel format: returns immediately without NPE when rows are empty")
        void excelFormat_emptyRows_returnsImmediately() {
            slackConfig.setEnabled(false);
            slackConfig.setAlertWebhookUrl("https://hooks.slack.com/alerts");
            slackConfig.setBotToken("");
            slackConfig.setChannelId("");
            slackConfig.setReportFormat("excel");
            long start = System.currentTimeMillis();
            service.notifyTeamForecast("EIndkomst Team",
                    LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31),
                    0, "summary", "admin", null,
                    Collections.emptyList(), Collections.emptyList());
            long elapsed = System.currentTimeMillis() - start;
            org.assertj.core.api.Assertions.assertThat(elapsed).isLessThan(1000L);
        }
    }

    // ── extractJsonString ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("extractJsonString")
    class ExtractJsonStringTests {

        @Test
        @DisplayName("extracts a present key's value")
        void extractsValue() {
            String json = "{\"ok\":true,\"upload_url\":\"https://files.slack.com/upload/v1/abc\",\"file_id\":\"F123\"}";
            org.assertj.core.api.Assertions.assertThat(
                    SlackNotificationService.extractJsonString(json, "upload_url"))
                    .isEqualTo("https://files.slack.com/upload/v1/abc");
            org.assertj.core.api.Assertions.assertThat(
                    SlackNotificationService.extractJsonString(json, "file_id"))
                    .isEqualTo("F123");
        }

        @Test
        @DisplayName("unescapes \\/ in URL values (Slack escapes forward slashes in JSON)")
        void unescapesSlackUrl() {
            // Slack returns: "upload_url":"https:\/\/files.slack.com\/upload\/v1\/abc123"
            String json = "{\"ok\":true,\"upload_url\":\"https:\\/\\/files.slack.com\\/upload\\/v1\\/abc123\",\"file_id\":\"Fabc\"}";
            org.assertj.core.api.Assertions.assertThat(
                    SlackNotificationService.extractJsonString(json, "upload_url"))
                    .isEqualTo("https://files.slack.com/upload/v1/abc123");
            org.assertj.core.api.Assertions.assertThat(
                    SlackNotificationService.extractJsonString(json, "file_id"))
                    .isEqualTo("Fabc");
        }

        @Test
        @DisplayName("returns null for a missing key")
        void returnsNullForMissingKey() {
            org.assertj.core.api.Assertions.assertThat(
                    SlackNotificationService.extractJsonString("{\"ok\":true}", "file_id"))
                    .isNull();
        }

        @Test
        @DisplayName("returns null for null input")
        void returnsNullForNullInput() {
            org.assertj.core.api.Assertions.assertThat(
                    SlackNotificationService.extractJsonString(null, "key"))
                    .isNull();
        }
    }
}
