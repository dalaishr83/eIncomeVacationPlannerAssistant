package com.holidayleave.assistant.controller;

import com.holidayleave.assistant.config.AppProperties;
import com.holidayleave.assistant.excel.PlannerExcelReader;
import com.holidayleave.assistant.excel.WorkingExcelWriter;
import com.holidayleave.assistant.llm.OpenAIAdapter;
import com.holidayleave.assistant.model.LeaveRecord;
import com.holidayleave.assistant.model.PendingVacation;
import com.holidayleave.assistant.model.VacationType;
import com.holidayleave.assistant.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ChatController}.
 *
 * Covers all 8 routing branches of POST /api/chat:
 *  1. Empty / blank message → 400
 *  2. Active delete wizard  → handleDeleteWizard
 *  3. Active add wizard     → handleAddWizard
 *  4. Add intent            → employee role guard + pre-seeding + wizard
 *  5. Delete intent         → employee role guard + pre-seeding + wizard
 *  6. Report intent         → name resolution + history fallback
 *  7. Plain LLM ask
 *  8. LLMServiceException   → 502
 *
 * Also covers:
 *  - POST /api/clear-history
 *  - handleAddWizard confirmed write (ownership check, audit, sync, slack, cache eviction)
 *  - handleDeleteWizard confirmed write (ownership check, audit, sync, slack, cache eviction)
 *  - handleAddWizard/handleDeleteWizard 403 ownership guard
 *  - Wizard cancel flows
 *  - Exception paths → 500
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatControllerTest {

    @Mock private HolidayAgent agent;
    @Mock private VacationCreationService creationService;
    @Mock private VacationDeletionService deletionService;
    @Mock private ReportGenerator reportGenerator;
    @Mock private AppState appState;
    @Mock private PlannerExcelReader reader;
    @Mock private WorkingExcelWriter writer;
    @Mock private VacationTypeService typeService;
    @Mock private AuditService auditService;
    @Mock private SyncService syncService;
    @Mock private SlackNotificationService slackNotificationService;
    @Mock private YearFileValidator yearFileValidator;
    @Mock private AppProperties props;

    @InjectMocks
    private ChatController controller;

    @TempDir
    Path tempDir;

    private MockHttpSession adminSession;
    private MockHttpSession employeeSession;

    // Sample records and employee names shared across tests
    private List<LeaveRecord> sampleRecords;
    private List<String> sampleNames;

    @BeforeEach
    void setUp() throws Exception {
        adminSession = new MockHttpSession();
        adminSession.setAttribute("session_id", "admin-session");
        adminSession.setAttribute("role", "admin");
        adminSession.setAttribute("username", "admin");
        adminSession.setAttribute("employee_name", null);

        employeeSession = new MockHttpSession();
        employeeSession.setAttribute("session_id", "emp-session");
        employeeSession.setAttribute("role", "employee");
        employeeSession.setAttribute("username", "alice");
        employeeSession.setAttribute("employee_name", "Alice");

        LeaveRecord r1 = new LeaveRecord("Alice",
                LocalDate.of(2024, 6, 1), LocalDate.of(2024, 6, 5), 5.0, "Annual Leave", "Holiday");
        LeaveRecord r2 = new LeaveRecord("Bob",
                LocalDate.of(2024, 7, 1), LocalDate.of(2024, 7, 3), 3.0, "Sick Leave", "Illness");
        sampleRecords = Arrays.asList(r1, r2);
        sampleNames = Arrays.asList("Alice", "Bob");

        // Default stubs that apply broadly
        when(appState.getLoadedFiles()).thenReturn(Collections.emptyList());
        when(appState.discoverExcelPaths()).thenReturn(Collections.emptyList());
        when(reader.getEmployeeNames(anyList())).thenReturn(sampleNames);
        when(appState.getPendingVacation(anyString())).thenReturn(null);
        when(appState.getWorkingDir()).thenReturn(tempDir.toString());
        when(appState.getDataDir()).thenReturn(tempDir.toString());
        when(appState.getConversationHistory(anyString())).thenReturn(Collections.emptyList());

        // Default: year-file validation always passes (all files present).
        // Individual tests that need to simulate missing files can override this stub.
        when(yearFileValidator.validate(any(LocalDate.class), any(LocalDate.class), anyString(), anyString()))
                .thenAnswer(inv -> {
                    // Build a valid result for any date range used in tests.
                    LocalDate s = inv.getArgument(0);
                    LocalDate e = inv.getArgument(1);
                    List<YearFileValidator.YearStatus> statuses = new ArrayList<>();
                    for (int y = s.getYear(); y <= e.getYear(); y++) {
                        statuses.add(new YearFileValidator.YearStatus(y,
                                tempDir + "/eIndkomst vacation " + y + ".xlsx",
                                tempDir + "/working/eIndkomst vacation " + y + ".xlsx",
                                true, true));
                    }
                    return YearFileValidator.Result.valid(statuses);
                });

        // By default none of the intent detectors fire
        when(agent.isAddVacationIntent(anyString())).thenReturn(false);
        when(agent.isDeleteVacationIntent(anyString())).thenReturn(false);
        when(agent.isReportIntent(anyString())).thenReturn(false);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // POST /api/clear-history
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/clear-history")
    class ClearHistory {

        @Test
        @DisplayName("clears history and returns confirmation message")
        void clearHistory_callsClearAndReturnsMessage() {
            ResponseEntity<Map<String, Object>> resp = controller.clearHistory(adminSession);

            verify(appState).clearHistory("admin-session");
            assertThat(resp.getStatusCodeValue()).isEqualTo(200);
            assertThat(resp.getBody()).containsKey("message");
            assertThat(resp.getBody().get("message").toString())
                    .contains("Conversation history cleared");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Branch 1 — Empty / blank message
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Branch 1 — empty message → 400")
    class EmptyMessage {

        @Test
        @DisplayName("null message returns 400")
        void chat_nullMessage_returns400() {
            Map<String, String> body = new HashMap<>();
            body.put("message", null);

            ResponseEntity<Map<String, Object>> resp = controller.chat(body, adminSession);

            assertThat(resp.getStatusCodeValue()).isEqualTo(400);
            assertThat(resp.getBody()).containsKey("error");
        }

        @Test
        @DisplayName("empty string message returns 400")
        void chat_emptyMessage_returns400() {
            ResponseEntity<Map<String, Object>> resp = controller.chat(msgBody(""), adminSession);

            assertThat(resp.getStatusCodeValue()).isEqualTo(400);
            assertThat(resp.getBody()).containsKey("error");
        }

        @Test
        @DisplayName("blank whitespace message returns 400")
        void chat_blankMessage_returns400() {
            ResponseEntity<Map<String, Object>> resp = controller.chat(msgBody("   "), adminSession);

            assertThat(resp.getStatusCodeValue()).isEqualTo(400);
            assertThat(resp.getBody()).containsKey("error");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Branch 7 — Plain LLM ask (no wizard / no intent match)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Branch 7 — Plain LLM ask")
    class LlmAsk {

        @Test
        @DisplayName("non-intent message is forwarded to agent.ask")
        void chat_noIntent_callsAgentAsk() {
            when(agent.ask(eq("How many days off does Alice have?"), any(), any(), anyString()))
                    .thenReturn("Alice has 5 days off.");

            ResponseEntity<Map<String, Object>> resp =
                    controller.chat(msgBody("How many days off does Alice have?"), adminSession);

            assertThat(resp.getStatusCodeValue()).isEqualTo(200);
            assertThat(resp.getBody().get("reply")).isEqualTo("Alice has 5 days off.");
            assertThat(resp.getBody().get("type")).isEqualTo("text");
        }

        @Test
        @DisplayName("agent.ask reply is returned verbatim")
        void chat_agentAskReplyReturnedVerbatim() {
            String expected = "Alice: 10 days remaining.";
            when(agent.ask(anyString(), any(), any(), anyString())).thenReturn(expected);

            ResponseEntity<Map<String, Object>> resp =
                    controller.chat(msgBody("Tell me about Alice"), adminSession);

            assertThat(resp.getBody().get("reply")).isEqualTo(expected);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Branch 8 — LLMServiceException → 502
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Branch 8 — LLMServiceException → 502")
    class LlmServiceException {

        @Test
        @DisplayName("LLMServiceException from agent.ask returns 502")
        void chat_llmException_returns502() {
            when(agent.ask(anyString(), any(), any(), anyString()))
                    .thenThrow(new OpenAIAdapter.LLMServiceException("OpenAI unavailable"));

            ResponseEntity<Map<String, Object>> resp =
                    controller.chat(msgBody("Tell me about leave"), adminSession);

            assertThat(resp.getStatusCodeValue()).isEqualTo(502);
            assertThat(resp.getBody()).containsKey("error");
            assertThat(resp.getBody().get("error").toString()).contains("OpenAI unavailable");
        }

        @Test
        @DisplayName("generic RuntimeException from agent.ask returns 500")
        void chat_genericException_returns500() {
            when(agent.ask(anyString(), any(), any(), anyString()))
                    .thenThrow(new RuntimeException("Something broke"));

            ResponseEntity<Map<String, Object>> resp =
                    controller.chat(msgBody("Tell me about leave"), adminSession);

            assertThat(resp.getStatusCodeValue()).isEqualTo(500);
            assertThat(resp.getBody()).containsKey("error");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Branch 6 — Report intent
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Branch 6 — Report intent")
    class ReportIntent {

        private File fakeXlsx;

        @BeforeEach
        void setUp() throws Exception {
            when(agent.isReportIntent(anyString())).thenReturn(true);
            // Create the file on disk so File.exists() returns true in loadMasterRecords()
            fakeXlsx = tempDir.resolve("fake.xlsx").toFile();
            fakeXlsx.createNewFile();
            when(appState.getLoadedFiles()).thenReturn(
                    Collections.singletonList(fakeXlsx.getAbsolutePath()));
            // Stub reader.load so records are non-empty for report generation path
            when(reader.load(fakeXlsx.getAbsolutePath())).thenReturn(sampleRecords);
        }

        @Test
        @DisplayName("resolves employee name and generates report")
        void chat_reportIntent_generatesReport() throws Exception {
            when(agent.resolveEmployeeName(anyString(), any(), any())).thenReturn("Alice");
            when(reportGenerator.generate(any(), eq("Alice"), anyInt()))
                    .thenReturn("/reports/alice-2024.html");

            ResponseEntity<Map<String, Object>> resp =
                    controller.chat(msgBody("generate report for Alice"), adminSession);

            assertThat(resp.getStatusCodeValue()).isEqualTo(200);
            assertThat(resp.getBody().get("reply").toString())
                    .contains("report-file:")
                    .contains("/reports/alice-2024.html");
            assertThat(resp.getBody().get("type")).isEqualTo("report");
        }

        @Test
        @DisplayName("falls back to conversation history when name not in message")
        void chat_reportIntent_fallsBackToHistory() throws Exception {
            when(agent.resolveEmployeeName(anyString(), any(), any())).thenReturn(null);
            when(agent.resolveEmployeeNameFromHistory(any(), any(), any())).thenReturn("Bob");
            when(reportGenerator.generate(any(), eq("Bob"), anyInt()))
                    .thenReturn("/reports/bob-2024.html");

            ResponseEntity<Map<String, Object>> resp =
                    controller.chat(msgBody("generate report for her"), adminSession);

            assertThat(resp.getStatusCodeValue()).isEqualTo(200);
            assertThat(resp.getBody().get("type")).isEqualTo("report");
        }

        @Test
        @DisplayName("asks for employee name when name cannot be resolved")
        void chat_reportIntent_nameUnresolved_asksForName() throws Exception {
            when(agent.resolveEmployeeName(anyString(), any(), any())).thenReturn(null);
            when(agent.resolveEmployeeNameFromHistory(any(), any(), any())).thenReturn(null);

            ResponseEntity<Map<String, Object>> resp =
                    controller.chat(msgBody("generate a report"), adminSession);

            assertThat(resp.getStatusCodeValue()).isEqualTo(200);
            assertThat(resp.getBody().get("reply").toString())
                    .contains("Which employee");
            assertThat(resp.getBody().get("type")).isEqualTo("text");
        }

        @Test
        @DisplayName("generates report even when records are empty (zero-leave employee)")
        void chat_reportIntent_emptyRecords_stillGeneratesReport() throws Exception {
            // No loaded files → empty records, but name resolution succeeds.
            // The report should still be generated (zero-leave employee in a future year).
            when(appState.getLoadedFiles()).thenReturn(Collections.emptyList());
            when(agent.resolveEmployeeName(anyString(), any(), any())).thenReturn("Alice");
            when(agent.extractYearPublic(anyString(), any())).thenReturn(2027);
            when(reportGenerator.generate(any(), eq("Alice"), eq(2027)))
                    .thenReturn("/reports/alice-2027.html");

            ResponseEntity<Map<String, Object>> resp =
                    controller.chat(msgBody("generate report for Alice in 2027"), adminSession);

            assertThat(resp.getStatusCodeValue()).isEqualTo(200);
            assertThat(resp.getBody().get("type")).isEqualTo("report");
            assertThat(resp.getBody().get("reply").toString()).contains("report-file:");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Branch 4 — Add vacation intent
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Branch 4 — Add vacation intent")
    class AddVacationIntent {

        @BeforeEach
        void setUp() {
            when(agent.isAddVacationIntent(anyString())).thenReturn(true);
        }

        @Test
        @DisplayName("admin role: starts add wizard and stores pending")
        void chat_addIntent_adminRole_startsWizard() {
            VacationCreationService.WizardResult result =
                    new VacationCreationService.WizardResult("Which employee?", "vacation_prompt", false, false);
            when(creationService.process(any(PendingVacation.class), anyString(), any()))
                    .thenReturn(result);

            ResponseEntity<Map<String, Object>> resp =
                    controller.chat(msgBody("add vacation"), adminSession);

            assertThat(resp.getStatusCodeValue()).isEqualTo(200);
            assertThat(resp.getBody().get("reply")).isEqualTo("Which employee?");
            verify(appState).setPendingVacation(eq("admin-session"), any(PendingVacation.class));
        }

        @Test
        @DisplayName("employee role: own name — pre-seeds employee and starts wizard")
        void chat_addIntent_employeeRole_ownName_preSeedsEmployee() {
            when(agent.resolveEmployeeName(anyString(), any(), any())).thenReturn("Alice");
            VacationCreationService.WizardResult result =
                    new VacationCreationService.WizardResult("What type of leave?", "vacation_prompt", false, false);
            when(creationService.process(any(PendingVacation.class), anyString(), any()))
                    .thenReturn(result);

            ResponseEntity<Map<String, Object>> resp =
                    controller.chat(msgBody("add vacation for Alice"), employeeSession);

            assertThat(resp.getStatusCodeValue()).isEqualTo(200);
            // pending state should be stored
            verify(appState).setPendingVacation(eq("emp-session"), any(PendingVacation.class));
        }

        @Test
        @DisplayName("employee role: mentions different employee — returns authorization error")
        void chat_addIntent_employeeRole_differentEmployee_returns403Message() {
            when(agent.resolveEmployeeName(anyString(), any(), any())).thenReturn("Bob");

            ResponseEntity<Map<String, Object>> resp =
                    controller.chat(msgBody("add vacation for Bob"), employeeSession);

            assertThat(resp.getStatusCodeValue()).isEqualTo(200);
            assertThat(resp.getBody().get("reply").toString())
                    .contains("Cannot add")
                    .contains("Bob");
            // Wizard must NOT be started
            verify(creationService, never()).process(any(), anyString(), any());
        }

        @Test
        @DisplayName("wizard result cancelled → removes pending vacation")
        void chat_addIntent_wizardCancelled_removesPending() {
            VacationCreationService.WizardResult result =
                    new VacationCreationService.WizardResult("Cancelled.", "text", false, true);
            when(creationService.process(any(PendingVacation.class), anyString(), any()))
                    .thenReturn(result);

            controller.chat(msgBody("cancel"), adminSession);

            verify(appState).removePendingVacation("admin-session");
            verify(appState, never()).setPendingVacation(anyString(), any());
        }

        @Test
        @DisplayName("employee role: null resolveEmployeeName — pre-seeds own name without auth error")
        void chat_addIntent_employeeRole_unresolvedName_noAuthError() {
            when(agent.resolveEmployeeName(anyString(), any(), any())).thenReturn(null);
            VacationCreationService.WizardResult result =
                    new VacationCreationService.WizardResult("What type?", "vacation_prompt", false, false);
            when(creationService.process(any(PendingVacation.class), anyString(), any()))
                    .thenReturn(result);

            ResponseEntity<Map<String, Object>> resp =
                    controller.chat(msgBody("add vacation"), employeeSession);

            assertThat(resp.getStatusCodeValue()).isEqualTo(200);
            // No authorization error
            assertThat(resp.getBody()).doesNotContainKey("error");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Branch 5 — Delete vacation intent
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Branch 5 — Delete vacation intent")
    class DeleteVacationIntent {

        @BeforeEach
        void setUp() {
            when(agent.isDeleteVacationIntent(anyString())).thenReturn(true);
        }

        @Test
        @DisplayName("admin role: starts delete wizard and stores pending")
        void chat_deleteIntent_adminRole_startsWizard() {
            VacationDeletionService.WizardResult result =
                    new VacationDeletionService.WizardResult("Which employee?", "vacation_prompt", false, false);
            when(deletionService.process(any(PendingVacation.class), anyString(), any(), any()))
                    .thenReturn(result);

            ResponseEntity<Map<String, Object>> resp =
                    controller.chat(msgBody("delete vacation"), adminSession);

            assertThat(resp.getStatusCodeValue()).isEqualTo(200);
            verify(appState).setPendingVacation(eq("admin-session"), any(PendingVacation.class));
        }

        @Test
        @DisplayName("employee role: mentions different employee — returns authorization error")
        void chat_deleteIntent_employeeRole_differentEmployee_returnsAuthError() {
            when(agent.resolveEmployeeName(anyString(), any(), any())).thenReturn("Bob");

            ResponseEntity<Map<String, Object>> resp =
                    controller.chat(msgBody("delete vacation for Bob"), employeeSession);

            assertThat(resp.getStatusCodeValue()).isEqualTo(200);
            assertThat(resp.getBody().get("reply").toString())
                    .contains("Cannot delete")
                    .contains("Bob");
            verify(deletionService, never()).process(any(), anyString(), any(), any());
        }

        @Test
        @DisplayName("wizard result cancelled → removes pending vacation")
        void chat_deleteIntent_wizardCancelled_removesPending() {
            VacationDeletionService.WizardResult result =
                    new VacationDeletionService.WizardResult("Cancelled.", "text", false, true);
            when(deletionService.process(any(PendingVacation.class), anyString(), any(), any()))
                    .thenReturn(result);

            controller.chat(msgBody("cancel"), adminSession);

            verify(appState).removePendingVacation("admin-session");
        }

        @Test
        @DisplayName("employee role: own name resolved — no auth error, starts wizard")
        void chat_deleteIntent_employeeRole_ownName_startsWizard() {
            when(agent.resolveEmployeeName(anyString(), any(), any())).thenReturn("Alice");
            VacationDeletionService.WizardResult result =
                    new VacationDeletionService.WizardResult("Start date?", "vacation_prompt", false, false);
            when(deletionService.process(any(PendingVacation.class), anyString(), any(), any()))
                    .thenReturn(result);

            ResponseEntity<Map<String, Object>> resp =
                    controller.chat(msgBody("delete vacation for Alice"), employeeSession);

            assertThat(resp.getStatusCodeValue()).isEqualTo(200);
            assertThat(resp.getBody()).doesNotContainKey("error");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Branch 2 — Active DELETE wizard (handleDeleteWizard)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Branch 2 — Active delete wizard")
    class ActiveDeleteWizard {

        private PendingVacation pending;

        @BeforeEach
        void setUp() {
            pending = new PendingVacation("delete");
            pending.setEmployeeName("Alice");
            pending.setStartDate(LocalDate.of(2024, 6, 1));
            pending.setEndDate(LocalDate.of(2024, 6, 5));
            when(appState.getPendingVacation("admin-session")).thenReturn(pending);
            when(appState.getPendingVacation("emp-session")).thenReturn(pending);
        }

        @Test
        @DisplayName("non-confirmed step continues wizard")
        void chat_activeDeleteWizard_continuesWizard() {
            VacationDeletionService.WizardResult result =
                    new VacationDeletionService.WizardResult("End date?", "vacation_prompt", false, false);
            when(deletionService.process(eq(pending), anyString(), any(), any()))
                    .thenReturn(result);

            ResponseEntity<Map<String, Object>> resp =
                    controller.chat(msgBody("2024-06-01"), adminSession);

            assertThat(resp.getStatusCodeValue()).isEqualTo(200);
            assertThat(resp.getBody().get("reply")).isEqualTo("End date?");
        }

        @Test
        @DisplayName("confirmed write: calls writer, audit, sync, slack, and removes pending")
        void chat_activeDeleteWizard_confirmed_writesAndCleans() throws Exception {
            VacationDeletionService.WizardResult result =
                    new VacationDeletionService.WizardResult("Deleted.", "text", true, false);
            when(deletionService.process(eq(pending), anyString(), any(), any()))
                    .thenReturn(result);
            when(writer.deleteVacation(anyString(), anyString(), any(), any()))
                    .thenReturn(3);

            ResponseEntity<Map<String, Object>> resp =
                    controller.chat(msgBody("yes"), adminSession);

            assertThat(resp.getStatusCodeValue()).isEqualTo(200);
            assertThat(resp.getBody().get("reply").toString()).contains("3 cells cleared");
            verify(writer).deleteVacation(anyString(), eq("Alice"), any(), any());
            verify(auditService).log(eq("vacation_deleted"), anyString(), eq("Alice"), anyString(), eq("success"), eq("chat"));
            verify(syncService).triggerSync();
            verify(slackNotificationService).notifyVacationDeleted(eq(pending), anyString(), anyString());
            verify(appState).removePendingVacation("admin-session");
        }

        @Test
        @DisplayName("employee role: ownership mismatch → 403 and removes pending")
        void chat_activeDeleteWizard_employeeOwnershipMismatch_returns403() throws Exception {
            pending.setEmployeeName("Bob"); // session user is Alice
            VacationDeletionService.WizardResult result =
                    new VacationDeletionService.WizardResult("Confirmed.", "text", true, false);
            when(deletionService.process(eq(pending), anyString(), any(), any()))
                    .thenReturn(result);

            ResponseEntity<Map<String, Object>> resp =
                    controller.chat(msgBody("yes"), employeeSession);

            assertThat(resp.getStatusCodeValue()).isEqualTo(403);
            assertThat(resp.getBody()).containsKey("error");
            verify(writer, never()).deleteVacation(anyString(), anyString(), any(), any());
            verify(appState).removePendingVacation("emp-session");
        }

        @Test
        @DisplayName("wizard cancelled in active wizard → removes pending")
        void chat_activeDeleteWizard_cancelled_removesPending() {
            VacationDeletionService.WizardResult result =
                    new VacationDeletionService.WizardResult("Cancelled.", "text", false, true);
            when(deletionService.process(eq(pending), anyString(), any(), any()))
                    .thenReturn(result);

            controller.chat(msgBody("cancel"), adminSession);

            verify(appState).removePendingVacation("admin-session");
        }

        @Test
        @DisplayName("cross-year delete: deletionService receives full records from discoverExcelPaths")
        void chat_activeDeleteWizard_crossYear_usesFullRecords() throws Exception {
            // Pending spans 2026-12-10 → 2027-02-14 (cross-year)
            PendingVacation crossYearPending = new PendingVacation("delete");
            crossYearPending.setEmployeeName("Alice");
            crossYearPending.setStartDate(LocalDate.of(2026, 12, 10));
            crossYearPending.setEndDate(LocalDate.of(2027, 2, 14));
            when(appState.getPendingVacation("admin-session")).thenReturn(crossYearPending);

            // Two master files discovered on disk (both years)
            File master2026 = tempDir.resolve("eIndkomst vacation 2026.xlsx").toFile();
            File master2027 = tempDir.resolve("eIndkomst vacation 2027.xlsx").toFile();
            master2026.createNewFile();
            master2027.createNewFile();
            when(appState.discoverExcelPaths()).thenReturn(
                    Arrays.asList(master2026.getAbsolutePath(), master2027.getAbsolutePath()));

            LeaveRecord rec2026 = new LeaveRecord("Alice",
                    LocalDate.of(2026, 12, 10), LocalDate.of(2026, 12, 31), 16.0, "Annual Leave", "Holiday");
            LeaveRecord rec2027 = new LeaveRecord("Alice",
                    LocalDate.of(2027, 1, 1), LocalDate.of(2027, 2, 14), 33.0, "Annual Leave", "Holiday");
            when(reader.load(master2026.getAbsolutePath())).thenReturn(Collections.singletonList(rec2026));
            when(reader.load(master2027.getAbsolutePath())).thenReturn(Collections.singletonList(rec2027));

            // deletionService confirms the delete
            VacationDeletionService.WizardResult result =
                    new VacationDeletionService.WizardResult("Deleted.", "text", true, false);
            // Capture the records list passed to deletionService.process
            when(deletionService.process(eq(crossYearPending), anyString(), any(),
                    argThat(list -> list != null && list.size() == 2)))
                    .thenReturn(result);

            when(writer.deleteVacation(anyString(), anyString(), any(), any())).thenReturn(5);

            ResponseEntity<Map<String, Object>> resp =
                    controller.chat(msgBody("yes"), adminSession);

            assertThat(resp.getStatusCodeValue()).isEqualTo(200);
            // deletionService must have been called with both years' records
            verify(deletionService).process(eq(crossYearPending), anyString(), any(),
                    argThat(list -> list != null && list.size() == 2));
            // writer must be called once per year segment
            verify(writer, times(2)).deleteVacation(anyString(), eq("Alice"), any(), any());
        }

        @Test
        @DisplayName("cross-year delete: deletionService receives empty list when no files discovered")
        void chat_activeDeleteWizard_crossYear_noDiscoveredFiles_emptyRecords() {
            // discoverExcelPaths already returns empty by default (setUp)
            PendingVacation crossYearPending = new PendingVacation("delete");
            crossYearPending.setEmployeeName("Alice");
            crossYearPending.setStartDate(LocalDate.of(2026, 12, 10));
            crossYearPending.setEndDate(LocalDate.of(2027, 2, 14));
            when(appState.getPendingVacation("admin-session")).thenReturn(crossYearPending);

            // deletionService returns non-confirmed (continues wizard) when called with empty records
            VacationDeletionService.WizardResult result =
                    new VacationDeletionService.WizardResult("Please confirm.", "vacation_prompt", false, false);
            when(deletionService.process(eq(crossYearPending), anyString(), any(),
                    argThat(list -> list != null && list.isEmpty())))
                    .thenReturn(result);

            ResponseEntity<Map<String, Object>> resp =
                    controller.chat(msgBody("yes"), adminSession);

            assertThat(resp.getStatusCodeValue()).isEqualTo(200);
            verify(deletionService).process(eq(crossYearPending), anyString(), any(),
                    argThat(list -> list != null && list.isEmpty()));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Branch 3 — Active ADD wizard (handleAddWizard)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Branch 3 — Active add wizard")
    class ActiveAddWizard {

        private PendingVacation pending;

        @BeforeEach
        void setUp() throws Exception {
            pending = new PendingVacation("add");
            pending.setEmployeeName("Alice");
            pending.setLeaveCode("AL");
            pending.setLeaveType("Annual Leave");
            pending.setStartDate(LocalDate.of(2024, 8, 1));
            pending.setEndDate(LocalDate.of(2024, 8, 5));
            pending.setDays(5.0);
            when(appState.getPendingVacation("admin-session")).thenReturn(pending);
            when(appState.getPendingVacation("emp-session")).thenReturn(pending);

            // Create a real master file in tempDir so ensureWorkingCopy has something to copy
            File masterFile = tempDir.resolve("eIndkomst vacation 2024.xlsx").toFile();
            masterFile.createNewFile();
        }

        @Test
        @DisplayName("non-confirmed step continues wizard")
        void chat_activeAddWizard_continuesWizard() {
            VacationCreationService.WizardResult result =
                    new VacationCreationService.WizardResult("Start date?", "vacation_prompt", false, false);
            when(creationService.process(eq(pending), anyString(), any()))
                    .thenReturn(result);

            ResponseEntity<Map<String, Object>> resp =
                    controller.chat(msgBody("Annual Leave"), adminSession);

            assertThat(resp.getStatusCodeValue()).isEqualTo(200);
            assertThat(resp.getBody().get("reply")).isEqualTo("Start date?");
        }

        @Test
        @DisplayName("confirmed write: calls writer, audit, sync, slack, and removes pending")
        void chat_activeAddWizard_confirmed_writesAndCleans() throws Exception {
            VacationCreationService.WizardResult result =
                    new VacationCreationService.WizardResult("Vacation added!", "text", true, false);
            when(creationService.process(eq(pending), anyString(), any()))
                    .thenReturn(result);
            VacationType vtype = new VacationType("AL", "Annual Leave", "#00FF00");
            when(typeService.findByCode("AL")).thenReturn(Optional.of(vtype));
            when(writer.addVacation(anyString(), any(LeaveRecord.class), eq(vtype)))
                    .thenReturn(5);

            ResponseEntity<Map<String, Object>> resp =
                    controller.chat(msgBody("yes"), adminSession);

            assertThat(resp.getStatusCodeValue()).isEqualTo(200);
            assertThat(resp.getBody().get("reply").toString())
                    .contains("Alice")
                    .contains("Annual Leave");
            verify(writer).addVacation(anyString(), any(LeaveRecord.class), eq(vtype));
            verify(reader).evict(anyString());
            verify(auditService).log(eq("vacation_added"), anyString(), eq("Alice"), anyString(), eq("success"), eq("chat"));
            verify(syncService).triggerSync();
            verify(slackNotificationService).notifyPcVacationAdded(any(LeaveRecord.class), eq("AL"), anyString());
            verify(appState).removePendingVacation("admin-session");
        }

        @Test
        @DisplayName("confirmed write: unknown leave code throws and returns 500")
        void chat_activeAddWizard_confirmed_unknownLeaveCode_returns500() {
            VacationCreationService.WizardResult result =
                    new VacationCreationService.WizardResult("Confirmed.", "text", true, false);
            when(creationService.process(eq(pending), anyString(), any()))
                    .thenReturn(result);
            when(typeService.findByCode("AL")).thenReturn(Optional.empty());

            ResponseEntity<Map<String, Object>> resp =
                    controller.chat(msgBody("yes"), adminSession);

            assertThat(resp.getStatusCodeValue()).isEqualTo(500);
        }

        @Test
        @DisplayName("employee role: ownership mismatch → 403 and removes pending")
        void chat_activeAddWizard_employeeOwnershipMismatch_returns403() throws Exception {
            pending.setEmployeeName("Bob"); // session user is Alice
            VacationCreationService.WizardResult result =
                    new VacationCreationService.WizardResult("Confirmed.", "text", true, false);
            when(creationService.process(eq(pending), anyString(), any()))
                    .thenReturn(result);

            ResponseEntity<Map<String, Object>> resp =
                    controller.chat(msgBody("yes"), employeeSession);

            assertThat(resp.getStatusCodeValue()).isEqualTo(403);
            verify(writer, never()).addVacation(anyString(), any(), any());
            verify(appState).removePendingVacation("emp-session");
        }

        @Test
        @DisplayName("wizard cancelled in active wizard → removes pending")
        void chat_activeAddWizard_cancelled_removesPending() {
            VacationCreationService.WizardResult result =
                    new VacationCreationService.WizardResult("Cancelled.", "text", false, true);
            when(creationService.process(eq(pending), anyString(), any()))
                    .thenReturn(result);

            controller.chat(msgBody("cancel"), adminSession);

            verify(appState).removePendingVacation("admin-session");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Edge cases
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("session with no session_id attribute → still handles message")
        void chat_noSessionId_handlesMessageGracefully() {
            MockHttpSession noIdSession = new MockHttpSession();
            // session_id is null; getPendingVacation(null) should return null
            when(appState.getPendingVacation(null)).thenReturn(null);
            when(agent.ask(anyString(), any(), any(), anyString())).thenReturn("Hello");

            ResponseEntity<Map<String, Object>> resp =
                    controller.chat(msgBody("Hello"), noIdSession);

            assertThat(resp.getStatusCodeValue()).isEqualTo(200);
        }

        @Test
        @DisplayName("response always contains both 'reply' and 'type' keys on success")
        void chat_successfulReply_alwaysHasBothKeys() {
            when(agent.ask(anyString(), any(), any(), anyString())).thenReturn("Some answer");

            ResponseEntity<Map<String, Object>> resp =
                    controller.chat(msgBody("Any question"), adminSession);

            assertThat(resp.getBody()).containsKeys("reply", "type");
        }

        @Test
        @DisplayName("loadMasterRecords aggregates records from all loaded files")
        void chat_loadMasterRecords_aggregatesMultipleFiles() throws Exception {
            // Two real temp files so File.exists() returns true
            File f1 = tempDir.resolve("file1.xlsx").toFile();
            File f2 = tempDir.resolve("file2.xlsx").toFile();
            f1.createNewFile();
            f2.createNewFile();

            when(appState.getLoadedFiles()).thenReturn(Arrays.asList(f1.getAbsolutePath(), f2.getAbsolutePath()));
            when(reader.load(f1.getAbsolutePath())).thenReturn(Collections.singletonList(sampleRecords.get(0)));
            when(reader.load(f2.getAbsolutePath())).thenReturn(Collections.singletonList(sampleRecords.get(1)));
            when(agent.ask(anyString(), argThat(list -> list != null && list.size() == 2), any(), anyString()))
                    .thenReturn("Both employees found");

            ResponseEntity<Map<String, Object>> resp =
                    controller.chat(msgBody("Tell me about all employees"), adminSession);

            assertThat(resp.getStatusCodeValue()).isEqualTo(200);
            verify(reader).load(f1.getAbsolutePath());
            verify(reader).load(f2.getAbsolutePath());
        }

        @Test
        @DisplayName("delete wizard: resolveLeaveTypeForDelete returns Unknown when no matching record")
        void chat_deleteWizard_resolveLeaveType_noMatch_returnsUnknown() throws Exception {
            PendingVacation pending = new PendingVacation("delete");
            pending.setEmployeeName("Alice");
            pending.setStartDate(LocalDate.of(2025, 1, 1));
            pending.setEndDate(LocalDate.of(2025, 1, 5));
            when(appState.getPendingVacation("admin-session")).thenReturn(pending);

            VacationDeletionService.WizardResult result =
                    new VacationDeletionService.WizardResult("Deleted.", "text", true, false);
            when(deletionService.process(eq(pending), anyString(), any(), any()))
                    .thenReturn(result);
            when(writer.deleteVacation(anyString(), anyString(), any(), any())).thenReturn(0);

            // sampleRecords have dates in 2024, pending is in 2025 — no overlap
            ResponseEntity<Map<String, Object>> resp =
                    controller.chat(msgBody("yes"), adminSession);

            assertThat(resp.getStatusCodeValue()).isEqualTo(200);
            // notifyVacationDeleted should have been called with "Unknown"
            verify(slackNotificationService).notifyVacationDeleted(eq(pending), eq("Unknown"), anyString());
        }

        @Test
        @DisplayName("delete wizard: resolveLeaveTypeForDelete matches overlapping record")
        void chat_deleteWizard_resolveLeaveType_matchingRecord() throws Exception {
            PendingVacation pending = new PendingVacation("delete");
            pending.setEmployeeName("Alice");
            pending.setStartDate(LocalDate.of(2024, 6, 1));
            pending.setEndDate(LocalDate.of(2024, 6, 5));
            when(appState.getPendingVacation("admin-session")).thenReturn(pending);

            // Use discoverExcelPaths so loadAllAvailableMasterRecords() returns sampleRecords
            // (resolveLeaveTypeForDelete now uses fullRecords from loadAllAvailableMasterRecords).
            File f1 = tempDir.resolve("master.xlsx").toFile();
            f1.createNewFile();
            when(appState.discoverExcelPaths()).thenReturn(Collections.singletonList(f1.getAbsolutePath()));
            when(reader.load(f1.getAbsolutePath())).thenReturn(sampleRecords);

            VacationDeletionService.WizardResult result =
                    new VacationDeletionService.WizardResult("Deleted.", "text", true, false);
            when(deletionService.process(eq(pending), anyString(), any(), any()))
                    .thenReturn(result);
            when(writer.deleteVacation(anyString(), anyString(), any(), any())).thenReturn(5);

            ResponseEntity<Map<String, Object>> resp =
                    controller.chat(msgBody("yes"), adminSession);

            assertThat(resp.getStatusCodeValue()).isEqualTo(200);
            // Alice's record has "Annual Leave" — should be passed to slack
            verify(slackNotificationService).notifyVacationDeleted(eq(pending), eq("Annual Leave"), anyString());
        }

        @Test
        @DisplayName("actingUser falls back to 'admin' when username not in session")
        void chat_actingUser_fallsBackToAdmin() throws Exception {
            // Session with no username attribute
            MockHttpSession noUserSession = new MockHttpSession();
            noUserSession.setAttribute("session_id", "no-user-session");
            noUserSession.setAttribute("role", "admin");

            PendingVacation pending = new PendingVacation("delete");
            pending.setEmployeeName("Alice");
            pending.setStartDate(LocalDate.of(2025, 3, 1));
            pending.setEndDate(LocalDate.of(2025, 3, 3));
            when(appState.getPendingVacation("no-user-session")).thenReturn(pending);

            VacationDeletionService.WizardResult result =
                    new VacationDeletionService.WizardResult("Deleted.", "text", true, false);
            when(deletionService.process(eq(pending), anyString(), any(), any()))
                    .thenReturn(result);
            when(writer.deleteVacation(anyString(), anyString(), any(), any())).thenReturn(1);

            controller.chat(msgBody("yes"), noUserSession);

            // auditService should receive "admin" as the acting user
            verify(auditService).log(anyString(), eq("admin"), anyString(), anyString(), anyString(), anyString());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private Map<String, String> msgBody(String message) {
        Map<String, String> body = new HashMap<>();
        body.put("message", message);
        return body;
    }
}
