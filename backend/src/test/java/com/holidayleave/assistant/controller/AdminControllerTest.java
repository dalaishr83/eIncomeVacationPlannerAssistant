package com.holidayleave.assistant.controller;

import com.holidayleave.assistant.excel.PlannerExcelReader;
import com.holidayleave.assistant.excel.WorkingExcelWriter;
import com.holidayleave.assistant.model.AuditLogEntry;
import com.holidayleave.assistant.model.VacationType;
import com.holidayleave.assistant.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import java.io.IOException;
import java.util.*;

import static java.util.Optional.of;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AdminController}.
 *
 * Covers: audit-log API, restricted-types GET/POST, employee/admin credentials,
 * promote/demote, password reset, file delete validation, PC records endpoint.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminControllerTest {

    @Mock private AppState                      appState;
    @Mock private PlannerExcelReader            reader;
    @Mock private WorkingExcelWriter            writer;
    @Mock private VacationTypeService           typeService;
    @Mock private RestrictedVacationTypeService restrictedTypeService;
    @Mock private SecretService                 secretService;
    @Mock private AuditService                  auditService;
    @Mock private SyncService                   syncService;
    @InjectMocks private AdminController controller;

    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        session = new MockHttpSession();
        session.setAttribute("username", "admin");
        when(appState.getLoadedFiles()).thenReturn(Collections.emptyList());
        when(appState.getDataDir()).thenReturn("/tmp/data");
        when(appState.getWorkingDir()).thenReturn("/tmp/working");
    }

    // =========================================================================
    // Audit log
    // =========================================================================

    @Test
    void getAuditLog_returnsEntries() {
        List<AuditLogEntry> entries = Collections.singletonList(
            new AuditLogEntry("test_event", "2026-01-01T00:00:00Z", "admin", null, "d", "ok", "api"));
        when(auditService.readAll()).thenReturn(entries);

        ResponseEntity<Map<String, Object>> resp = controller.getAuditLog();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(entries, resp.getBody().get("entries"));
    }

    @Test
    void getAuditLog_noEntries_returnsEmptyList() {
        when(auditService.readAll()).thenReturn(Collections.emptyList());
        ResponseEntity<Map<String, Object>> resp = controller.getAuditLog();
        assertTrue(((List<?>) resp.getBody().get("entries")).isEmpty());
    }

    // =========================================================================
    // Restricted types
    // =========================================================================

    @Test
    void getRestrictedTypes_returnsCodesAndVacationTypes() {
        when(restrictedTypeService.getRestrictedTypes()).thenReturn(Arrays.asList("PC", "H"));
        when(typeService.findAll()).thenReturn(Collections.singletonList(
                new VacationType("V", "Vacation", "FF92D050")));

        ResponseEntity<Map<String, Object>> resp = controller.getRestrictedTypes();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(Arrays.asList("PC", "H"), resp.getBody().get("restricted_types"));
        assertNotNull(resp.getBody().get("vacation_types"));
    }

    @Test
    void setRestrictedTypes_validBody_returnsOk() throws IOException {
        when(restrictedTypeService.getRestrictedTypes()).thenReturn(Arrays.asList("PC"));

        Map<String, Object> body = new HashMap<>();
        body.put("restricted_types", Arrays.asList("PC", "H"));

        ResponseEntity<Map<String, Object>> resp = controller.setRestrictedTypes(body, session);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(restrictedTypeService).setRestrictedTypes(any());
    }

    @Test
    void setRestrictedTypes_notAList_returns400() throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("restricted_types", "PC");  // string, not list

        ResponseEntity<Map<String, Object>> resp = controller.setRestrictedTypes(body, session);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    // =========================================================================
    // Employee credentials
    // =========================================================================

    @Test
    void getEmployeeCredentials_returnsOnlyEmployeeRoleEntries() {
        Map<String, Map<String, String>> all = new LinkedHashMap<>();
        Map<String, String> emp = new LinkedHashMap<>();
        emp.put("username", "aliceSmith"); emp.put("role", "employee"); emp.put("employee_name", "Alice Smith");
        Map<String, String> adm = new LinkedHashMap<>();
        adm.put("username", "admin"); adm.put("role", "admin"); adm.put("employee_name", null);
        all.put("aliceSmith", emp);
        all.put("admin", adm);
        when(secretService.readCredentials()).thenReturn(all);

        ResponseEntity<Map<String, Object>> resp = controller.getEmployeeCredentials();
        List<?> employees = (List<?>) resp.getBody().get("employees");
        assertEquals(1, employees.size());
    }

    @Test
    void getAdminCredentials_returnsOnlyAdminRoleEntriesWithEmployeeName() {
        Map<String, Map<String, String>> all = new LinkedHashMap<>();
        Map<String, String> adm = new LinkedHashMap<>();
        adm.put("username", "adminDave"); adm.put("role", "admin"); adm.put("employee_name", "Dave Admin");
        Map<String, String> plainAdmin = new LinkedHashMap<>();
        plainAdmin.put("username", "admin"); plainAdmin.put("role", "admin"); plainAdmin.put("employee_name", null);
        all.put("adminDave", adm);
        all.put("admin", plainAdmin);
        when(secretService.readCredentials()).thenReturn(all);

        ResponseEntity<Map<String, Object>> resp = controller.getAdminCredentials();
        List<?> admins = (List<?>) resp.getBody().get("admins");
        assertEquals(1, admins.size());
    }

    // =========================================================================
    // Promote / Demote
    // =========================================================================

    @Test
    void promoteToAdmin_notList_returns400() {
        Map<String, Object> body = Collections.singletonMap("usernames", "alice");
        ResponseEntity<Map<String, Object>> resp = controller.promoteToAdmin(body, session);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void promoteToAdmin_emptyList_returns400() {
        Map<String, Object> body = Collections.singletonMap("usernames", Collections.emptyList());
        ResponseEntity<Map<String, Object>> resp = controller.promoteToAdmin(body, session);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void promoteToAdmin_unknownUsername_returns400() {
        when(secretService.findByUsername("nobody")).thenReturn(null);
        Map<String, Object> body = Collections.singletonMap("usernames", Collections.singletonList("nobody"));
        ResponseEntity<Map<String, Object>> resp = controller.promoteToAdmin(body, session);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody().get("error").toString().contains("Unknown username"));
    }

    @Test
    void promoteToAdmin_validUsername_returns200() throws IOException {
        Map<String, String> entry = new HashMap<>();
        entry.put("username", "bob"); entry.put("role", "employee");
        when(secretService.findByUsername("bob")).thenReturn(entry);

        Map<String, Object> body = Collections.singletonMap("usernames", Collections.singletonList("bob"));
        ResponseEntity<Map<String, Object>> resp = controller.promoteToAdmin(body, session);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(secretService).updateRole("bob", "admin");
    }

    @Test
    void demoteToEmployee_validUsername_setsEmployeeRole() throws IOException {
        Map<String, String> entry = new HashMap<>();
        entry.put("username", "dave"); entry.put("role", "admin");
        when(secretService.findByUsername("dave")).thenReturn(entry);

        Map<String, Object> body = Collections.singletonMap("usernames", Collections.singletonList("dave"));
        ResponseEntity<Map<String, Object>> resp = controller.demoteToEmployee(body, session);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(secretService).updateRole("dave", "employee");
    }

    // =========================================================================
    // Password reset
    // =========================================================================

    @Test
    void resetPassword_missingRole_returns400() {
        ResponseEntity<Map<String, Object>> resp = controller.resetPassword(new HashMap<>(), session);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void resetPassword_shortPassword_returns400() {
        Map<String, String> body = new HashMap<>();
        body.put("role", "admin");
        body.put("new_password", "abc");  // < 6 chars

        ResponseEntity<Map<String, Object>> resp = controller.resetPassword(body, session);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void resetPassword_unknownCredKey_returns400() {
        when(secretService.findByUsername("nobody")).thenReturn(null);

        Map<String, String> body = new HashMap<>();
        body.put("role",         "nobody");
        body.put("new_password", "password123");

        ResponseEntity<Map<String, Object>> resp = controller.resetPassword(body, session);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void resetPassword_validRequest_returns200() throws IOException {
        Map<String, String> entry = Collections.singletonMap("username", "admin");
        when(secretService.findByUsername("admin")).thenReturn(entry);

        Map<String, String> body = new HashMap<>();
        body.put("role",         "admin");
        body.put("new_password", "newpass123");

        ResponseEntity<Map<String, Object>> resp = controller.resetPassword(body, session);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(secretService).updatePassword("admin", "newpass123");
    }

    // =========================================================================
    // File delete
    // =========================================================================

    @Test
    void deleteFile_missingFilename_returns400() {
        ResponseEntity<Map<String, Object>> resp = controller.deleteFile(new HashMap<>(), session);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void deleteFile_pathTraversalAttempt_returns400() {
        Map<String, String> body = Collections.singletonMap("filename", "../../etc/passwd");
        ResponseEntity<Map<String, Object>> resp = controller.deleteFile(body, session);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void deleteFile_backslashInFilename_returns400() {
        Map<String, String> body = Collections.singletonMap("filename", "folder\\file.xlsx");
        ResponseEntity<Map<String, Object>> resp = controller.deleteFile(body, session);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void deleteFile_fileNotOnDisk_returns404() {
        Map<String, String> body = Collections.singletonMap("filename", "nonexistent.xlsx");
        ResponseEntity<Map<String, Object>> resp = controller.deleteFile(body, session);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    // =========================================================================
    // PC Approvals
    // =========================================================================

    @Test
    void getPcRecords_noLoadedFiles_returnsEmptyList() {
        ResponseEntity<Map<String, Object>> resp = controller.getPcRecords();
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(((List<?>) resp.getBody().get("pc_records")).isEmpty());
    }

    @Test
    void approvePc_notList_returns400() {
        Map<String, Object> body = Collections.singletonMap("approvals", "not-a-list");
        ResponseEntity<Map<String, Object>> resp = controller.approvePc(body, session);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void approvePc_emptyApprovalsList_returns200WithZeroApproved() {
        // approvePc calls typeService.findByCode("V") even with empty list
        when(typeService.findByCode("V")).thenReturn(Optional.of(new VacationType("V", "Vacation", "FF92D050")));
        Map<String, Object> body = Collections.singletonMap("approvals", Collections.emptyList());
        ResponseEntity<Map<String, Object>> resp = controller.approvePc(body, session);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(0, resp.getBody().get("approved"));
    }
}
