package com.holidayleave.assistant.service;

import com.holidayleave.assistant.analysis.LeaveAnalysisService;
import com.holidayleave.assistant.llm.LLMService;
import com.holidayleave.assistant.model.LeaveRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link HolidayAgent}.
 *
 * Covers: intent detection, employee name resolution (all 3 passes),
 * year/month extraction, context-shape selection, pronoun history resolution,
 * all-employees aggregation, generic context fallback, ask() LLM delegation,
 * plus boundary and negative scenarios.
 *
 * The LLMService and AppState are mocked â€” no real HTTP calls are made.
 */
@ExtendWith(MockitoExtension.class)
class HolidayAgentTest {

    @Mock private LLMService llmService;
    @Mock private LeaveAnalysisService analysisService;
    @Mock private AppState appState;

    @InjectMocks
    private HolidayAgent agent;

    private static final String SID = "test-session";

    private List<LeaveRecord> records;

    // â”€â”€ Helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private LeaveRecord rec(String name, String start, String end, double days, String type) {
        return new LeaveRecord(name, LocalDate.parse(start), LocalDate.parse(end), days, type, null);
    }

    private List<Map<String, String>> historyWith(String role, String content) {
        Map<String, String> msg = new HashMap<>();
        msg.put("role", role);
        msg.put("content", content);
        return Collections.singletonList(msg);
    }

    @BeforeEach
    void setUp() {
        records = new ArrayList<>(Arrays.asList(
            rec("Alice Smith",   "2026-01-05", "2026-01-09", 5, "V"),
            rec("Bob Johnson",   "2026-02-02", "2026-02-06", 5, "V"),
            rec("Carol Nguyen",  "2026-03-02", "2026-03-06", 5, "V")
        ));
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // Intent detection â€” isAddVacationIntent
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    @Test
    void isAddVacationIntent_addVacationKeyword_true() {
        assertTrue(agent.isAddVacationIntent("Please add vacation for Alice"));
    }

    @Test
    void isAddVacationIntent_bookLeaveKeyword_true() {
        assertTrue(agent.isAddVacationIntent("Book leave from Jan 5 to Jan 9"));
    }

    @Test
    void isAddVacationIntent_requestHoliday_true() {
        assertTrue(agent.isAddVacationIntent("I want to request holiday next week"));
    }

    @Test
    void isAddVacationIntent_scheduleVacation_true() {
        assertTrue(agent.isAddVacationIntent("schedule vacation for next month"));
    }

    @Test
    void isAddVacationIntent_noKeyword_false() {
        assertFalse(agent.isAddVacationIntent("How many days has Alice taken?"));
    }

    @Test
    void isAddVacationIntent_deleteKeyword_false() {
        assertFalse(agent.isAddVacationIntent("delete vacation for Bob"));
    }

    @Test
    void isAddVacationIntent_emptyMessage_false() {
        assertFalse(agent.isAddVacationIntent(""));
    }

    @Test
    void isAddVacationIntent_caseInsensitive() {
        assertTrue(agent.isAddVacationIntent("ADD VACATION for Carol"));
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // Intent detection â€” isDeleteVacationIntent
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    @Test
    void isDeleteVacationIntent_deleteVacation_true() {
        assertTrue(agent.isDeleteVacationIntent("delete vacation for Bob"));
    }

    @Test
    void isDeleteVacationIntent_removeLeave_true() {
        assertTrue(agent.isDeleteVacationIntent("remove leave for this week"));
    }

    @Test
    void isDeleteVacationIntent_cancelLeave_true() {
        assertTrue(agent.isDeleteVacationIntent("cancel leave on Monday"));
    }

    @Test
    void isDeleteVacationIntent_undoVacation_true() {
        assertTrue(agent.isDeleteVacationIntent("undo vacation I just added"));
    }

    @Test
    void isDeleteVacationIntent_eraseLeave_true() {
        assertTrue(agent.isDeleteVacationIntent("erase leave for Carol"));
    }

    @Test
    void isDeleteVacationIntent_noKeyword_false() {
        assertFalse(agent.isDeleteVacationIntent("How many days is Alice taking?"));
    }

    @Test
    void isDeleteVacationIntent_emptyMessage_false() {
        assertFalse(agent.isDeleteVacationIntent(""));
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // Intent detection â€” isReportIntent
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    @Test
    void isReportIntent_generateReport_true() {
        assertTrue(agent.isReportIntent("generate report for Alice"));
    }

    @Test
    void isReportIntent_createReport_true() {
        assertTrue(agent.isReportIntent("create report"));
    }

    @Test
    void isReportIntent_yearlyReport_true() {
        assertTrue(agent.isReportIntent("I need the yearly report for Bob"));
    }

    @Test
    void isReportIntent_htmlReport_true() {
        assertTrue(agent.isReportIntent("html report please"));
    }

    @Test
    void isReportIntent_noKeyword_false() {
        assertFalse(agent.isReportIntent("How many leave days does Carol have?"));
    }

    @Test
    void isReportIntent_emptyMessage_false() {
        assertFalse(agent.isReportIntent(""));
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // Intent detection â€” isAllEmployeesQuery
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    @Test
    void isAllEmployeesQuery_allEmployee_true() {
        assertTrue(agent.isAllEmployeesQuery("show all employee leave"));
    }

    @Test
    void isAllEmployeesQuery_everyone_true() {
        assertTrue(agent.isAllEmployeesQuery("how many days has everyone taken?"));
    }

    @Test
    void isAllEmployeesQuery_allStaff_true() {
        assertTrue(agent.isAllEmployeesQuery("all staff report please"));
    }

    @Test
    void isAllEmployeesQuery_allWorkers_true() {
        assertTrue(agent.isAllEmployeesQuery("show all workers vacation summary"));
    }

    @Test
    void isAllEmployeesQuery_specificEmployee_false() {
        assertFalse(agent.isAllEmployeesQuery("show Alice's leave"));
    }

    @Test
    void isAllEmployeesQuery_emptyMessage_false() {
        assertFalse(agent.isAllEmployeesQuery(""));
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // Employee name resolution â€” resolveEmployeeName
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    /** Pass 1: full name substring match. */
    @Test
    void resolveEmployeeName_pass1_exactFullNameMatch() {
        String result = agent.resolveEmployeeName("What leave has Alice Smith taken?", records);
        assertEquals("Alice Smith", result);
    }

    /** Pass 1: lowercase question still matches. */
    @Test
    void resolveEmployeeName_pass1_caseInsensitiveMatch() {
        String result = agent.resolveEmployeeName("what leave has alice smith taken?", records);
        assertEquals("Alice Smith", result);
    }

    /** Pass 2: first token with â‰¥ 3 chars matches first name. */
    @Test
    void resolveEmployeeName_pass2_firstNameToken() {
        String result = agent.resolveEmployeeName("How many days has Bob taken?", records);
        assertEquals("Bob Johnson", result);
    }

    /** Pass 2: second token with â‰¥ 4 chars matches last name. */
    @Test
    void resolveEmployeeName_pass2_lastNameToken() {
        String result = agent.resolveEmployeeName("Nguyen has how many vacation days?", records);
        assertEquals("Carol Nguyen", result);
    }

    /** Pass 3: multi-token scoring returns best match. */
    @Test
    void resolveEmployeeName_pass3_bestTokenScore() {
        String result = agent.resolveEmployeeName("Johnson took days off", records);
        assertEquals("Bob Johnson", result);
    }

    @Test
    void resolveEmployeeName_noMatch_returnsNull() {
        String result = agent.resolveEmployeeName("Totally unrelated question", records);
        assertNull(result);
    }

    @Test
    void resolveEmployeeName_emptyRecords_returnsNull() {
        String result = agent.resolveEmployeeName("How much leave has Alice taken?", Collections.emptyList());
        assertNull(result);
    }

    @Test
    void resolveEmployeeName_shortTokenBelowMinLength_noMatch() {
        // "Al" is 2 chars â€” below the 3-char min for first token in pass 2
        String result = agent.resolveEmployeeName("Al took some days", records);
        // Pass 1 will not match (no "Alice Smith" as substring), pass 2 needs â‰¥3 chars
        // Pass 3 requires â‰¥4 chars â€” so null
        assertNull(result);
    }

    @Test
    void resolveEmployeeName_ambiguousShortNameNotMatchedByShortToken() {
        // Token "Bob" (3 chars) matches at pass 2 index 0 (min=3)
        String result = agent.resolveEmployeeName("Bob took leave", records);
        assertEquals("Bob Johnson", result);
    }

    @Test
    void resolveEmployeeName_multipleNamesInQuestion_firstMatchReturned() {
        // "alice smith bob johnson" â€” pass 1 will match Alice Smith first
        String result = agent.resolveEmployeeName("alice smith bob johnson leave", records);
        assertEquals("Alice Smith", result);
    }

    // Natural-language phrasing equivalence â€” regression protection
    @Test
    void resolveEmployeeName_naturalLanguageVariants_allResolve() {
        String[] variants = {
            "How many days has Alice Smith taken?",
            "Show me Alice Smith's vacation",
            "alice smith vacation in 2026",
            "What is Alice Smith's remaining leave?",
        };
        for (String q : variants) {
            String result = agent.resolveEmployeeName(q, records);
            assertEquals("Alice Smith", result,
                "Expected Alice Smith from: " + q);
        }
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // Employee name resolution from history â€” resolveEmployeeNameFromHistory
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    @Test
    void resolveEmployeeNameFromHistory_findsNameInHistory() {
        List<Map<String, String>> history = historyWith("user", "Alice Smith took 5 days off");
        String result = agent.resolveEmployeeNameFromHistory(history, records);
        assertEquals("Alice Smith", result);
    }

    @Test
    void resolveEmployeeNameFromHistory_latestMatchReturned() {
        // History has Alice at index 0, Bob at index 1 â€” reverse scan returns Bob first
        List<Map<String, String>> history = new ArrayList<>();
        Map<String, String> m1 = new HashMap<>(); m1.put("role","user"); m1.put("content","Alice Smith left");
        Map<String, String> m2 = new HashMap<>(); m2.put("role","assistant"); m2.put("content","Bob Johnson is on leave");
        history.add(m1);
        history.add(m2);
        String result = agent.resolveEmployeeNameFromHistory(history, records);
        // Reverse scan: Bob is found at index 1 first
        assertEquals("Bob Johnson", result);
    }

    @Test
    void resolveEmployeeNameFromHistory_nullHistory_returnsNull() {
        assertNull(agent.resolveEmployeeNameFromHistory(null, records));
    }

    @Test
    void resolveEmployeeNameFromHistory_emptyHistory_returnsNull() {
        assertNull(agent.resolveEmployeeNameFromHistory(Collections.emptyList(), records));
    }

    @Test
    void resolveEmployeeNameFromHistory_historyWithNoEmployeeNames_returnsNull() {
        List<Map<String, String>> history = historyWith("user", "What is the weather today?");
        assertNull(agent.resolveEmployeeNameFromHistory(history, records));
    }

    @Test
    void resolveEmployeeNameFromHistory_nullContentInHistory_skipped() {
        Map<String, String> msg = new HashMap<>();
        msg.put("role", "user");
        msg.put("content", null); // null content
        List<Map<String, String>> history = Collections.singletonList(msg);
        // Should not throw, should return null
        assertNull(agent.resolveEmployeeNameFromHistory(history, records));
    }

    @Test
    void resolveEmployeeNameFromHistory_pronounInQuestion_resolvedFromHistory() {
        // Simulates "she took 3 days" follow-up â€” history has the name
        List<Map<String, String>> history = historyWith("user", "Alice Smith is on vacation");
        // This is the history-based pronoun resolution
        String result = agent.resolveEmployeeNameFromHistory(history, records);
        assertEquals("Alice Smith", result);
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // Year extraction
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    @Test
    void extractYear_explicitYear_returned() throws Exception {
        int year = invokeExtractYear("How many days in 2025?", records);
        assertEquals(2025, year);
    }

    @Test
    void extractYear_noYearInQuestion_fallsBackToMaxRecordYear() throws Exception {
        List<LeaveRecord> r = Collections.singletonList(rec("Alice", "2026-01-05", "2026-01-09", 5, "V"));
        int year = invokeExtractYear("How many days has Alice taken?", r);
        assertEquals(2026, year);
    }

    @Test
    void extractYear_noYearNoRecords_fallsBackToCurrentYear() throws Exception {
        int current = LocalDate.now().getYear();
        int year = invokeExtractYear("How many days has Alice taken?", Collections.emptyList());
        // Should be either current year or higher â€” at minimum current year
        assertTrue(year >= current);
    }

    @Test
    void extractYear_multipleYearsInQuestion_takesFirst() throws Exception {
        int year = invokeExtractYear("Compare 2025 vs 2026 leave", records);
        assertEquals(2025, year);
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // Month extraction
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    @Test
    void extractMonth_januaryFull_returns1() throws Exception {
        assertEquals(1, invokeExtractMonth("vacation in January"));
    }

    @Test
    void extractMonth_julyAbbrev_returns7() throws Exception {
        assertEquals(7, invokeExtractMonth("How many days in Jul?"));
    }

    @Test
    void extractMonth_decemberFull_returns12() throws Exception {
        assertEquals(12, invokeExtractMonth("Leave in December 2026"));
    }

    @Test
    void extractMonth_noMonth_returnsNull() throws Exception {
        assertNull(invokeExtractMonth("How many days in 2026?"));
    }

    @Test
    void extractMonth_mayFull_returns5() throws Exception {
        assertEquals(5, invokeExtractMonth("Vacation in May"));
    }

    @Test
    void extractMonth_caseInsensitive_august() throws Exception {
        assertEquals(8, invokeExtractMonth("VACATION IN AUGUST"));
    }

    @Test
    void extractMonth_allAbbreviations() throws Exception {
        String[] abbrevs = {"jan","feb","mar","apr","jun","jul","aug","sep","oct","nov","dec"};
        int[]    months  = {  1,    2,    3,    4,    6,    7,    8,    9,   10,   11,   12};
        for (int i = 0; i < abbrevs.length; i++) {
            assertEquals(months[i], invokeExtractMonth("days in " + abbrevs[i]),
                "Abbreviation " + abbrevs[i]);
        }
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // ask() â€” LLM delegation and context selection
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    // SID constant is declared at class level above setUp()

    @Test
    void ask_delegatesToLlm_returnsReply() {
        when(appState.getConversationHistory(anyString())).thenReturn(Collections.emptyList());
        when(llmService.ask(isNull(), anyString(), anyString(), anyList()))
                .thenReturn("Alice has 5 days left.");
        when(analysisService.analyse(anyList(), eq("Alice Smith"), anyInt()))
                .thenReturn(stubAnalysisResult("Alice Smith", 2026));
        when(analysisService.consumedToDate(anyList())).thenReturn(0.0);

        String reply = agent.ask("How many days does Alice Smith have left?", records, "test-session");

        assertEquals("Alice has 5 days left.", reply);
        verify(llmService).ask(isNull(), anyString(),
                eq("How many days does Alice Smith have left?"), anyList());
    }

    @Test
    void ask_addsToHistory() {
        when(appState.getConversationHistory(anyString())).thenReturn(Collections.emptyList());
        when(llmService.ask(any(), any(), any(), any())).thenReturn("OK");
        when(analysisService.analyse(anyList(), anyString(), anyInt()))
                .thenReturn(stubAnalysisResult("Alice Smith", 2026));
        when(analysisService.consumedToDate(anyList())).thenReturn(0.0);

        agent.ask("How many days does Alice Smith have left?", records, "test-session");

        verify(appState).addToHistory(eq("test-session"),
                eq("How many days does Alice Smith have left?"), eq("OK"));
    }

    @Test
    void ask_allEmployeesQuery_usesShapeB() {
        when(appState.getConversationHistory(anyString())).thenReturn(Collections.emptyList());
        when(llmService.ask(isNull(), anyString(), anyString(), anyList()))
                .thenReturn("Summary of all employees.");

        String reply = agent.ask("Show all employee leave for 2026", records, "test-session");

        assertEquals("Summary of all employees.", reply);
        // Verify that the context passed to LLM contains "all_employees_summary"
        verify(llmService).ask(isNull(), argThat(ctx -> ctx.contains("all_employees_summary")),
                anyString(), anyList());
    }

    @Test
    void ask_unknownEmployee_usesShapeC() {
        when(appState.getConversationHistory(anyString())).thenReturn(Collections.emptyList());
        when(llmService.ask(isNull(), anyString(), anyString(), anyList()))
                .thenReturn("I don't know who you mean.");

        String reply = agent.ask("How many days off does Zephyr Moonbeam have?", records, "test-session");

        assertNotNull(reply);
        // Context should contain "employees" (Shape C) not employee-specific fields
        verify(llmService).ask(isNull(), argThat(ctx -> ctx.contains("\"employees\"")),
                anyString(), anyList());
    }

    @Test
    void ask_pronounWithHistory_resolvesFromHistory() {
        // History contains Alice Smith; question uses "she" â†’ should resolve to Alice
        List<Map<String, String>> history = historyWith("user", "Alice Smith took leave");
        when(appState.getConversationHistory(anyString())).thenReturn(history);
        when(llmService.ask(isNull(), anyString(), anyString(), anyList()))
                .thenReturn("She has 3 days left.");
        when(analysisService.analyse(anyList(), eq("Alice Smith"), anyInt()))
                .thenReturn(stubAnalysisResult("Alice Smith", 2026));
        when(analysisService.consumedToDate(anyList())).thenReturn(0.0);

        String reply = agent.ask("How many days does she have left?", records, "test-session");
        assertEquals("She has 3 days left.", reply);

        // Context should be Shape A (employee-specific) not Shape C
        verify(llmService).ask(isNull(), argThat(ctx -> ctx.contains("employee_name")),
                anyString(), anyList());
    }

    @Test
    void ask_emptyRecords_doesNotThrow() {
        when(appState.getConversationHistory(anyString())).thenReturn(Collections.emptyList());
        when(llmService.ask(any(), any(), any(), any())).thenReturn("No data available.");

        assertDoesNotThrow(() -> agent.ask("How many days?", Collections.emptyList(), "test-session"));
    }

    @Test
    void ask_specificMonth_contextContainsDaysInRequestedMonth() {
        when(appState.getConversationHistory(anyString())).thenReturn(Collections.emptyList());
        when(llmService.ask(isNull(), anyString(), anyString(), anyList()))
                .thenReturn("Alice took 3 days in March.");
        when(analysisService.analyse(anyList(), eq("Alice Smith"), anyInt()))
                .thenReturn(stubAnalysisResult("Alice Smith", 2026));
        when(analysisService.consumedToDate(anyList())).thenReturn(0.0);

        agent.ask("How many days did Alice Smith take in March?", records, "test-session");

        verify(llmService).ask(isNull(),
                argThat(ctx -> ctx.contains("total_all_leave_types_in_month")),
                anyString(), anyList());
    }

    @Test
    void ask_yearExplicitInQuestion_correctYearUsed() {
        // Records are 2026; question asks for 2025
        when(appState.getConversationHistory(anyString())).thenReturn(Collections.emptyList());
        when(llmService.ask(isNull(), anyString(), anyString(), anyList()))
                .thenReturn("In 2025 Alice had 0 days.");
        when(analysisService.analyse(anyList(), eq("Alice Smith"), eq(2025)))
                .thenReturn(stubAnalysisResult("Alice Smith", 2025));
        when(analysisService.consumedToDate(anyList())).thenReturn(0.0);

        agent.ask("How many days did Alice Smith take in 2025?", records, "test-session");

        verify(analysisService).analyse(anyList(), eq("Alice Smith"), eq(2025));
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // Regression â€” natural-language query equivalence
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    /**
     * Equivalent NL queries for "how many days did Alice take in July" must all
     * resolve to the same employee and the same month.
     */
    @Test
    void regression_equivalentNLQueriesForAliceJuly_sameResolution() throws Exception {
        String[] queries = {
            "How many vacation days did Alice Smith take in July?",
            "Alice Smith's leave in july 2026",
            "Show leave for Alice Smith in July 2026",
            "alice smith july leave",
        };
        for (String q : queries) {
            String emp = agent.resolveEmployeeName(q, records);
            Integer month = invokeExtractMonth(q);
            assertEquals("Alice Smith", emp, "Employee resolution failed for: " + q);
            assertEquals(7, month, "Month resolution failed for: " + q);
        }
    }

    @Test
    void regression_allEmployeesKeywords_allTriggerShapeB() {
        String[] queries = {"all employee leave", "all staff vacation", "everyone took leave", "all workers"};
        for (String q : queries) {
            assertTrue(agent.isAllEmployeesQuery(q),
                "isAllEmployeesQuery returned false for: " + q);
        }
    }

    @Test
    void regression_deleteKeywords_allTriggerDeleteIntent() {
        String[] queries = {
            "delete vacation for Bob",
            "remove leave for Alice",
            "cancel leave on Thursday",
            "undo vacation last week",
            "erase leave entry"
        };
        for (String q : queries) {
            assertTrue(agent.isDeleteVacationIntent(q),
                "isDeleteVacationIntent returned false for: " + q);
        }
    }

    @Test
    void regression_addKeywords_allTriggerAddIntent() {
        String[] queries = {
            "add vacation for Bob",
            "book leave next week",
            "request vacation for Carol",
            "new leave for Alice",
            "create vacation entry",
            "record leave for Bob",
            "log vacation for Carol",
            "schedule vacation for Alice"
        };
        for (String q : queries) {
            assertTrue(agent.isAddVacationIntent(q),
                "isAddVacationIntent returned false for: " + q);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // extractMonths() — range detection
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @SuppressWarnings("unchecked")
    void extractMonths_singleMonth_returnsOneElement() throws Exception {
        List<Integer> result = invokeExtractMonths("leave in March");
        assertEquals(1, result.size());
        assertEquals(3, result.get(0));
    }

    @Test
    @SuppressWarnings("unchecked")
    void extractMonths_twoDistinctMonths_returnsBoth() throws Exception {
        List<Integer> result = invokeExtractMonths("from March to April");
        assertEquals(2, result.size());
        assertEquals(3, result.get(0));
        assertEquals(4, result.get(1));
    }

    @Test
    @SuppressWarnings("unchecked")
    void extractMonths_sameMonthTwice_returnsOne() throws Exception {
        List<Integer> result = invokeExtractMonths("leave in March and also March");
        assertEquals(1, result.size());
        assertEquals(3, result.get(0));
    }

    @Test
    @SuppressWarnings("unchecked")
    void extractMonths_noMonth_returnsEmptyList() throws Exception {
        List<Integer> result = invokeExtractMonths("how many days in 2026?");
        assertTrue(result.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void extractMonths_abbreviations_detected() throws Exception {
        List<Integer> result = invokeExtractMonths("from Mar to Apr");
        assertEquals(2, result.size());
        assertEquals(3, result.get(0));
        assertEquals(4, result.get(1));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ask() — range query emits is_range_query, total_all_leave_types_in_range,
    //          by_month_by_type, range_start_month_name, range_end_month_name
    //          and does NOT emit context_scope_month_name (single-month field)
    //          and does NOT emit days_in_requested_range (old name — removed)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void ask_rangeQuery_contextContainsRangeFields() {
        when(appState.getConversationHistory(anyString())).thenReturn(Collections.emptyList());
        when(llmService.ask(isNull(), anyString(), anyString(), anyList()))
                .thenReturn("range answer");
        when(analysisService.analyse(anyList(), eq("Alice Smith"), anyInt()))
                .thenReturn(stubAnalysisResult("Alice Smith", 2026));
        when(analysisService.consumedToDate(anyList())).thenReturn(0.0);

        agent.ask("How many days did Alice Smith take from March to April?", records, SID);

        verify(llmService).ask(isNull(),
                argThat(ctx -> ctx.contains("is_range_query")
                             && ctx.contains("total_all_leave_types_in_range")
                             && ctx.contains("by_month_by_type")
                             && ctx.contains("range_start_month_name")
                             && ctx.contains("range_end_month_name")
                             && !ctx.contains("context_scope_month_name")
                             && !ctx.contains("days_in_requested_range")),
                anyString(), anyList());
    }

    @Test
    void ask_singleMonthQuery_noRangeFields() {
        when(appState.getConversationHistory(anyString())).thenReturn(Collections.emptyList());
        when(llmService.ask(isNull(), anyString(), anyString(), anyList()))
                .thenReturn("single month answer");
        when(analysisService.analyse(anyList(), eq("Alice Smith"), anyInt()))
                .thenReturn(stubAnalysisResult("Alice Smith", 2026));
        when(analysisService.consumedToDate(anyList())).thenReturn(0.0);

        agent.ask("How many days did Alice Smith take in March?", records, SID);

        verify(llmService).ask(isNull(),
                argThat(ctx -> ctx.contains("context_scope_month_name")
                             && !ctx.contains("is_range_query")),
                anyString(), anyList());
    }



    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // Helpers to invoke private methods via reflection
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    private int invokeExtractYear(String question, List<LeaveRecord> recs) throws Exception {
        Method m = HolidayAgent.class.getDeclaredMethod("extractYear", String.class, List.class);
        m.setAccessible(true);
        return (int) m.invoke(agent, question, recs);
    }

    private Integer invokeExtractMonth(String question) throws Exception {
        Method m = HolidayAgent.class.getDeclaredMethod("extractMonth", String.class);
        m.setAccessible(true);
        return (Integer) m.invoke(agent, question);
    }

    @SuppressWarnings("unchecked")
    private List<Integer> invokeExtractMonths(String question) throws Exception {
        Method m = HolidayAgent.class.getDeclaredMethod("extractMonths", String.class);
        m.setAccessible(true);
        return (List<Integer>) m.invoke(agent, question);
    }




    // ═══════════════════════════════════════════════════════════════════════════
    // extractSpecificDate() — date parsing
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void extractSpecificDate_ddMonthYyyy_parsed() {
        LocalDate d = agent.extractSpecificDate("Who is on leave on 02 March 2026?", 2026);
        assertEquals(LocalDate.of(2026, 3, 2), d);
    }

    @Test
    void extractSpecificDate_MonthDdCommaYyyy_parsed() {
        LocalDate d = agent.extractSpecificDate("Who has leave on March 2, 2026?", 2026);
        assertEquals(LocalDate.of(2026, 3, 2), d);
    }

    @Test
    void extractSpecificDate_MonthDdOrdinalYear_parsed() {
        LocalDate d = agent.extractSpecificDate("Who will be on leave on March 2nd 2026?", 2026);
        assertEquals(LocalDate.of(2026, 3, 2), d);
    }

    @Test
    void extractSpecificDate_isoFormat_parsed() {
        LocalDate d = agent.extractSpecificDate("leave on 2026-03-02", 2026);
        assertEquals(LocalDate.of(2026, 3, 2), d);
    }

    @Test
    void extractSpecificDate_ddSlashMmSlashYyyy_parsed() {
        LocalDate d = agent.extractSpecificDate("off on 02/03/2026", 2026);
        assertEquals(LocalDate.of(2026, 3, 2), d);
    }

    @Test
    void extractSpecificDate_noDate_returnsNull() {
        LocalDate d = agent.extractSpecificDate("How many days does Alice have in March?", 2026);
        assertNull(d);
    }

    @Test
    void extractSpecificDate_yearOmitted_usesFallback() {
        LocalDate d = agent.extractSpecificDate("Who is on leave on 02 March?", 2026);
        assertEquals(LocalDate.of(2026, 3, 2), d);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // buildContextForDate() — date-query context shape
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void buildContextForDate_matchingEmployee_includedInOnLeave() {
        // Carol is on leave 2026-03-02 to 2026-03-06
        String ctx = agent.buildContextForDate(records, LocalDate.of(2026, 3, 2));
        assertTrue(ctx.contains("\"query_type\":\"date_query\""),  "must flag date_query");
        assertTrue(ctx.contains("\"query_date\":\"2026-03-02\""),  "must echo query_date");
        assertTrue(ctx.contains("Carol Nguyen"),                   "Carol must appear in on_leave");
        assertFalse(ctx.contains("Alice Smith"),                   "Alice not on leave on 02 Mar");
        assertFalse(ctx.contains("Bob Johnson"),                   "Bob not on leave on 02 Mar");
    }

    @Test
    void buildContextForDate_noMatchingEmployee_emptyOnLeave() {
        String ctx = agent.buildContextForDate(records, LocalDate.of(2026, 4, 1));
        assertTrue(ctx.contains("\"employees_on_leave_count\":0"));
    }

    @Test
    void buildContextForDate_typeAExcluded() {
        List<LeaveRecord> withA = new ArrayList<>(records);
        withA.add(new LeaveRecord("Dave A", LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 2), 0, "A", null));
        String ctx = agent.buildContextForDate(withA, LocalDate.of(2026, 3, 2));
        assertFalse(ctx.contains("Dave A"), "type-A records must not appear in on_leave");
    }

    @Test
    void buildContextForDate_boundaryStartDate_included() {
        String ctx = agent.buildContextForDate(records, LocalDate.of(2026, 3, 2));
        assertTrue(ctx.contains("Carol Nguyen"));
    }

    @Test
    void buildContextForDate_boundaryEndDate_included() {
        String ctx = agent.buildContextForDate(records, LocalDate.of(2026, 3, 6));
        assertTrue(ctx.contains("Carol Nguyen"));
    }

    @Test
    void buildContextForDate_dayAfterSpanEnd_notIncluded() {
        String ctx = agent.buildContextForDate(records, LocalDate.of(2026, 3, 7));
        assertFalse(ctx.contains("Carol Nguyen"));
    }


    // ═══════════════════════════════════════════════════════════════════════════
    // isDateQuery() — routing guard: false-positive prevention
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void isDateQuery_noEmployeeName_singleMonth_true() {
        // "Who is on leave on 02 March 2026?" — no employee name, no range → date query
        assertTrue(agent.isDateQuery("Who is on leave on 02 March 2026?", records));
    }

    @Test
    void isDateQuery_withEmployeeName_false() {
        // "How many days did Alice Smith take from 01 March to 05 March?" — has employee name
        assertFalse(agent.isDateQuery("How many days did Alice Smith take from 01 March to 05 March?", records));
    }

    @Test
    void isDateQuery_rangeQuery_false() {
        // "How many days from January to March?" — two months → range query not date query
        assertFalse(agent.isDateQuery("How many days from January to March 2026?", records));
    }

    @Test
    void isDateQuery_addVacationIntent_safelyIrrelevant() {
        // "Add vacation from 02 March to 05 March" — ChatController intercepts isAddVacationIntent
        // BEFORE agent.ask() is ever called, so isDateQuery is never reached for add/delete intents.
        // Both months here are "March" (same month) so extractMonths returns size=1.
        // The important safety is at the ChatController level, not here.
        // Just verify it does not throw.
        agent.isDateQuery("Add vacation from 02 March to 05 March 2026", records);
    }

    @Test
    void isDateQuery_noSpecificDate_trueButExtractReturnsNull() {
        // "Who is on leave in March?" — no specific day number → extractSpecificDate returns null
        // isDateQuery itself returns true (no employee, single month), but the calling code
        // guards with extractSpecificDate != null so this safely falls through to normal routing.
        assertTrue(agent.isDateQuery("Who is on leave in March?", records));
    }


    // ═══════════════════════════════════════════════════════════════════════════
    // ask() — date query routes to date context (no analysisService call)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void ask_dateQuery_routesToDateContext_noAnalysisServiceCall() {
        when(appState.getConversationHistory(anyString())).thenReturn(Collections.emptyList());
        when(llmService.ask(anyString(), anyString(), anyString(), anyList()))
                .thenReturn("Carol Nguyen is on leave.");

        agent.ask("Who is on leave on 02 March 2026?", records, SID);

        verify(llmService).ask(notNull(),
                argThat(ctx -> ctx.contains("\"query_type\":\"date_query\"")
                             && ctx.contains("Carol Nguyen")
                             && ctx.contains("\"query_date\":\"2026-03-02\"")),
                anyString(), anyList());
        verify(analysisService, never()).analyse(anyList(), anyString(), anyInt());
    }

    @Test
    void ask_dateQuery_semanticVariants_allRouteToDateContext() {
        String[] variants = {
            "Who is on leave on 02 March 2026?",
            "Who will be on leave on March 2nd 2026?",
            "Who has leave on March 2, 2026?",
            "Are any employees on vacation on 2026-03-02?",
        };
        for (String q : variants) {
            when(appState.getConversationHistory(anyString())).thenReturn(Collections.emptyList());
            when(llmService.ask(anyString(), anyString(), anyString(), anyList()))
                    .thenReturn("Carol Nguyen is on leave.");
            agent.ask(q, records, SID);
        }
        verify(llmService, atLeastOnce()).ask(notNull(),
                argThat(ctx -> ctx.contains("\"query_type\":\"date_query\"")),
                anyString(), anyList());
    }


    // ═══════════════════════════════════════════════════════════════════════════
    // isDateRangeQuery() — cross-employee date-range detection
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void isDateRangeQuery_fromDateToDate_true() {
        // The canonical user query: "Who are on leave from 15 March 2026 to 31 August 2026"
        assertTrue(agent.isDateRangeQuery(
                "Who are on leave from 15 March 2026 to 31 August 2026", records));
    }

    @Test
    void isDateRangeQuery_isoFormat_true() {
        assertTrue(agent.isDateRangeQuery(
                "Who is off from 2026-03-15 to 2026-08-31", records));
    }

    @Test
    void isDateRangeQuery_slashFormat_true() {
        assertTrue(agent.isDateRangeQuery(
                "Which employees are on leave from 15/03/2026 to 31/08/2026", records));
    }

    @Test
    void isDateRangeQuery_untilKeyword_true() {
        assertTrue(agent.isDateRangeQuery(
                "Who is on leave from 01 March 2026 until 30 June 2026", records));
    }

    @Test
    void isDateRangeQuery_betweenKeyword_true() {
        // The Quick Questions chip phrasing: "between <date> and <date>"
        assertTrue(agent.isDateRangeQuery(
                "Who are on leave between 31 August 2026 and 7 September 2026", records));
    }

    @Test
    void isDateRangeQuery_singleDate_false() {
        // Single-date query — no "from … to" frame → not a date-range query
        assertFalse(agent.isDateRangeQuery(
                "Who is on leave on 02 March 2026?", records));
    }

    @Test
    void isDateRangeQuery_employeeNamePresent_false() {
        // Employee name in question → per-employee query, not cross-employee range
        assertFalse(agent.isDateRangeQuery(
                "How many days did Alice Smith take from 01 March 2026 to 31 August 2026?", records));
    }

    @Test
    void isDateRangeQuery_noDateAtAll_false() {
        assertFalse(agent.isDateRangeQuery("How many days has everyone taken?", records));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // extractDateRange() — two-date extraction
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void extractDateRange_ddMonthYyyy_parsed() {
        LocalDate[] range = agent.extractDateRange(
                "Who are on leave from 15 March 2026 to 31 August 2026", 2026);
        assertNotNull(range);
        assertEquals(LocalDate.of(2026, 3, 15), range[0]);
        assertEquals(LocalDate.of(2026, 8, 31), range[1]);
    }

    @Test
    void extractDateRange_isoFormat_parsed() {
        LocalDate[] range = agent.extractDateRange(
                "Who is off from 2026-03-15 to 2026-08-31", 2026);
        assertNotNull(range);
        assertEquals(LocalDate.of(2026, 3, 15), range[0]);
        assertEquals(LocalDate.of(2026, 8, 31), range[1]);
    }

    @Test
    void extractDateRange_noRange_returnsNull() {
        LocalDate[] range = agent.extractDateRange("Who is on leave on 02 March 2026?", 2026);
        assertNull(range);
    }

    @Test
    void extractDateRange_reversedDates_normalised() {
        // "from August to March" — should be normalised to March–August
        LocalDate[] range = agent.extractDateRange(
                "from 31 August 2026 to 15 March 2026", 2026);
        assertNotNull(range);
        assertTrue(range[0].isBefore(range[1]) || range[0].isEqual(range[1]),
                "from date should be <= to date after normalisation");
    }

    @Test
    void extractDateRange_betweenKeyword_parsed() {
        // Chip phrasing: "between 31 August 2026 and 7 September 2026"
        LocalDate[] range = agent.extractDateRange(
                "Who are on leave between 31 August 2026 and 7 September 2026", 2026);
        assertNotNull(range);
        assertEquals(LocalDate.of(2026, 8, 31), range[0]);
        assertEquals(LocalDate.of(2026, 9, 7),  range[1]);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // buildContextForDateRange() — range context shape
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void buildContextForDateRange_matchingEmployee_included() {
        // Carol is on leave 2026-03-02 to 2026-03-06 — overlaps range 2026-01-01 to 2026-12-31
        String ctx = agent.buildContextForDateRange(records,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        assertTrue(ctx.contains("\"query_type\":\"date_range_query\""), "must flag date_range_query");
        assertTrue(ctx.contains("Carol Nguyen"), "Carol must appear in on_leave");
        assertTrue(ctx.contains("Alice Smith"),  "Alice must appear in on_leave");
        assertTrue(ctx.contains("Bob Johnson"),  "Bob must appear in on_leave");
    }

    @Test
    void buildContextForDateRange_noOverlap_empty() {
        // Range entirely before all records
        String ctx = agent.buildContextForDateRange(records,
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31));
        assertTrue(ctx.contains("\"employees_on_leave_count\":0"));
    }

    @Test
    void buildContextForDateRange_partialOverlap_included() {
        // Alice is on leave 2026-01-05 to 2026-01-09 — range starts 2026-01-07 (overlaps)
        String ctx = agent.buildContextForDateRange(records,
                LocalDate.of(2026, 1, 7), LocalDate.of(2026, 6, 30));
        assertTrue(ctx.contains("Alice Smith"), "Alice overlaps range start → must be included");
    }

    @Test
    void buildContextForDateRange_typeAExcluded() {
        List<LeaveRecord> withA = new ArrayList<>(records);
        withA.add(new LeaveRecord("Dave A", LocalDate.of(2026, 3, 15), LocalDate.of(2026, 3, 20),
                0, "A", null));
        String ctx = agent.buildContextForDateRange(withA,
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
        assertFalse(ctx.contains("Dave A"), "type-A records must not appear in on_leave");
    }

    @Test
    void buildContextForDateRange_contextFields_present() {
        // Use a range that covers all three test records (Jan–Dec 2026)
        String ctx = agent.buildContextForDateRange(records,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        assertTrue(ctx.contains("\"range_start\":\"2026-01-01\""),    "range_start must be set");
        assertTrue(ctx.contains("\"range_end\":\"2026-12-31\""),      "range_end must be set");
        assertTrue(ctx.contains("range_start_display"),                "range_start_display must be set");
        assertTrue(ctx.contains("range_end_display"),                  "range_end_display must be set");
        assertTrue(ctx.contains("employees_on_leave_count"),           "count must be set");
        assertTrue(ctx.contains("total_employees_checked"),            "total_checked must be set");
        assertTrue(ctx.contains("leave_type_counts"),                  "leave_type_counts must be present");
    }

    @Test
    void buildContextForDateRange_leaveTypeCounts_singleType() {
        // Carol has one "V" record in March 2026 — leave_type_counts should be {"V":1}
        String ctx = agent.buildContextForDateRange(records,
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
        assertTrue(ctx.contains("Carol Nguyen"),        "Carol must be included");
        assertTrue(ctx.contains("leave_type_counts"),   "leave_type_counts must be present");
        // The count for her single record must be 1
        assertTrue(ctx.contains("\"V\":1"),             "type V count must be 1");
    }

    @Test
    void buildContextForDateRange_leaveTypeCounts_multipleRecordsSameType() {
        // Two separate Vacation records for the same employee overlapping the range → count = 2
        List<LeaveRecord> multiRec = new ArrayList<>(records);
        multiRec.add(new LeaveRecord("Carol Nguyen",
                LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 12), 3, "V", null));
        String ctx = agent.buildContextForDateRange(multiRec,
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
        assertTrue(ctx.contains("\"V\":2"), "two overlapping V records must produce count 2");
    }

    @Test
    void buildContextForDateRange_leaveTypeCounts_multipleDistinctTypes() {
        // Employee with both Vacation and Public holiday in the range
        List<LeaveRecord> mixed = new ArrayList<>();
        mixed.add(new LeaveRecord("Dave Mix",
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 5), 5, "Vacation", null));
        mixed.add(new LeaveRecord("Dave Mix",
                LocalDate.of(2026, 4, 7), LocalDate.of(2026, 4, 7), 1, "Public holiday", null));
        String ctx = agent.buildContextForDateRange(mixed,
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30));
        assertTrue(ctx.contains("Dave Mix"),               "Dave must be included");
        assertTrue(ctx.contains("\"Vacation\":1"),         "Vacation count must be 1");
        assertTrue(ctx.contains("\"Public holiday\":1"),   "Public holiday count must be 1");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ask() — date-range query routes to date-range context (not analysisService)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void ask_dateRangeQuery_routesToRangeContext() {
        when(appState.getConversationHistory(anyString())).thenReturn(Collections.emptyList());
        when(llmService.ask(anyString(), anyString(), anyString(), anyList()))
                .thenReturn("Carol Nguyen is on leave during this period.");

        agent.ask("Who are on leave from 01 March 2026 to 06 March 2026?", records, SID);

        verify(llmService).ask(notNull(),
                argThat(ctx -> ctx.contains("\"query_type\":\"date_range_query\"")
                             && ctx.contains("Carol Nguyen")
                             && ctx.contains("\"range_start\":\"2026-03-01\"")),
                anyString(), anyList());
        verify(analysisService, never()).analyse(anyList(), anyString(), anyInt());
    }

    @Test
    void ask_dateRangeQuery_doesNotHitDateQueryPath() {
        // Single-date query must NOT go through the date-range path
        when(appState.getConversationHistory(anyString())).thenReturn(Collections.emptyList());
        when(llmService.ask(anyString(), anyString(), anyString(), anyList()))
                .thenReturn("Carol Nguyen is on leave.");

        agent.ask("Who is on leave on 02 March 2026?", records, SID);

        // Context must be the single-date shape, not the range shape
        verify(llmService).ask(notNull(),
                argThat(ctx -> ctx.contains("\"query_type\":\"date_query\"")
                             && !ctx.contains("date_range_query")),
                anyString(), anyList());
    }

    @Test
    void ask_dateRangeQuery_addsToHistory() {
        when(appState.getConversationHistory(anyString())).thenReturn(Collections.emptyList());
        when(llmService.ask(anyString(), anyString(), anyString(), anyList()))
                .thenReturn("Range answer.");

        agent.ask("Who are on leave from 01 March 2026 to 06 March 2026?", records, SID);

        verify(appState).addToHistory(
                eq(SID),
                eq("Who are on leave from 01 March 2026 to 06 March 2026?"),
                eq("Range answer."));
    }



    // ═══════════════════════════════════════════════════════════════════════════
    // buildTableDataForDateRange() — structured table rows for frontend
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void buildTableDataForDateRange_singleEmployeeSingleMonth_oneRow() {
        // Alice is on leave 2026-01-05 to 2026-01-09 (V) with days=5 — range covers January only
        List<Map<String, Object>> rows = agent.buildTableDataForDateRange(
                records, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
        assertEquals(1, rows.size(), "one row for Alice in January");
        Map<String, Object> row = rows.get(0);
        assertEquals("Alice Smith",  row.get("employee_name"));
        assertEquals("January",      row.get("month"));
        assertEquals(5L,             row.get("total_vacation")); // 5 working days
        assertEquals("V",            row.get("leave_types"));
    }

    @Test
    void buildTableDataForDateRange_threeEmployeesSingleRange_threeRows() {
        // Jan–Dec 2026 covers all three test records (each in a different month)
        List<Map<String, Object>> rows = agent.buildTableDataForDateRange(
                records, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        assertEquals(3, rows.size(), "one row per employee");
        List<String> names = new ArrayList<>();
        for (Map<String, Object> r : rows) names.add((String) r.get("employee_name"));
        assertTrue(names.contains("Alice Smith"),  "Alice must be present");
        assertTrue(names.contains("Bob Johnson"),  "Bob must be present");
        assertTrue(names.contains("Carol Nguyen"), "Carol must be present");
    }

    @Test
    void buildTableDataForDateRange_crossMonthRange_twoRows() {
        // Record spans 2026-08-31 (Mon) → 2026-09-04 (Fri): 3 working days declared.
        // Aug portion: 1 working day (Aug 31 only) → share = 3 * (1/3) = 1 → rounded 1
        // Sep portion: 2 working days (Sep 1–4)    → share = 3 * (2/3) = 2 → rounded 2
        List<LeaveRecord> crossMonth = new ArrayList<>();
        crossMonth.add(new LeaveRecord("Dave Cross",
                LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 4), 3, "Vacation", null));
        List<Map<String, Object>> rows = agent.buildTableDataForDateRange(
                crossMonth, LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 7));
        assertEquals(2, rows.size(), "one row for August and one for September");
        assertEquals("August",    rows.get(0).get("month"));
        assertEquals(1L,          rows.get(0).get("total_vacation"), "Aug: 1 proportional day");
        assertEquals("September", rows.get(1).get("month"));
        assertEquals(2L,          rows.get(1).get("total_vacation"), "Sep: 2 proportional days");
    }

    @Test
    void buildTableDataForDateRange_typeAExcluded() {
        List<LeaveRecord> withA = new ArrayList<>(records);
        withA.add(new LeaveRecord("Dave A",
                LocalDate.of(2026, 3, 15), LocalDate.of(2026, 3, 20), 0, "A", null));
        List<Map<String, Object>> rows = agent.buildTableDataForDateRange(
                withA, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
        for (Map<String, Object> row : rows) {
            assertNotEquals("Dave A", row.get("employee_name"), "type-A employee must not appear");
        }
    }

    @Test
    void buildTableDataForDateRange_noOverlap_empty() {
        List<Map<String, Object>> rows = agent.buildTableDataForDateRange(
                records, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31));
        assertTrue(rows.isEmpty(), "no rows when range does not overlap any record");
    }

    @Test
    void buildTableDataForDateRange_multipleTypesInMonth_sumsDays() {
        // 5 Vacation days + 1 Public holiday day = 6 total days in April
        List<LeaveRecord> mixed = new ArrayList<>();
        mixed.add(new LeaveRecord("Eve Multi",
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 5), 5, "Vacation", null));
        mixed.add(new LeaveRecord("Eve Multi",
                LocalDate.of(2026, 4, 7), LocalDate.of(2026, 4, 7), 1, "Public holiday", null));
        List<Map<String, Object>> rows = agent.buildTableDataForDateRange(
                mixed, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30));
        assertEquals(1, rows.size(), "one row for April");
        String leaveTypes = (String) rows.get(0).get("leave_types");
        assertTrue(leaveTypes.contains("Vacation"),       "Vacation must be listed");
        assertTrue(leaveTypes.contains("Public holiday"), "Public holiday must be listed");
        assertEquals(6L, rows.get(0).get("total_vacation"), "5 + 1 = 6 total days");
    }

    // ── Stub helpers ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    private com.holidayleave.assistant.model.LeaveAnalysisResult stubAnalysisResult(
            String name, int year) {
        return new com.holidayleave.assistant.model.LeaveAnalysisResult(
            name, year, 25.0, 5.0, 20.0, 20.0,
            new LinkedHashMap<>(),
            new LinkedHashMap<>(),
            5, 2.08, new ArrayList<>()
        );
    }
}
