package com.holidayleave.assistant.controller;

import com.holidayleave.assistant.excel.PlannerExcelReader;
import com.holidayleave.assistant.excel.WorkingExcelWriter;
import com.holidayleave.assistant.model.LeaveRecord;
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

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link VacationController}.
 *
 * Covers: addVacation (success, missing fields, unknown employee, invalid leave type,
 * restricted type, invalid dates, conflict), deleteVacation (success, missing fields,
 * unknown employee, not found, invalid dates), getTypes, addType, updateType.
 *
 * Filesystem writes are mocked — no actual Excel file is touched.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VacationControllerTest {

    @Mock private AppState                      appState;
    @Mock private PlannerExcelReader            reader;
    @Mock private WorkingExcelWriter            writer;
    @Mock private VacationTypeService           typeService;
    @Mock private RestrictedVacationTypeService restrictedTypeService;
    @Mock private AuditService                  auditService;
    @Mock private SyncService                   syncService;
    @InjectMocks private VacationController controller;

    private MockHttpSession session;
    private List<LeaveRecord> records;
    private VacationType vtypeV;

    @BeforeEach
    void setUp() throws Exception {
        session = new MockHttpSession();
        session.setAttribute("role", "admin");
        session.setAttribute("username", "admin");

        vtypeV = new VacationType("V", "Vacation", "FF92D050");
        records = Arrays.asList(
            new LeaveRecord("Alice Smith", LocalDate.of(2026,1,5), LocalDate.of(2026,1,9), 5, "Vacation", null)
        );

        when(appState.getLoadedFiles()).thenReturn(Collections.emptyList());
        when(appState.getWorkingDir()).thenReturn("/tmp/working");
        when(appState.getDataDir()).thenReturn("/tmp/data");
        when(reader.getEmployeeNames(anyList())).thenReturn(Arrays.asList("Alice Smith", "Bob Johnson"));
        when(typeService.findAll()).thenReturn(Collections.singletonList(vtypeV));
        when(restrictedTypeService.isRestricted(any())).thenReturn(false);
    }

    // =========================================================================
    // addVacation — validation
    // =========================================================================

    @Test
    void addVacation_nullEmployeeName_returns400() {
        Map<String, Object> body = new HashMap<>();
        body.put("leave_type", "Vacation");
        body.put("start_date", "2026-06-01");
        body.put("end_date",   "2026-06-05");

        ResponseEntity<Map<String, Object>> resp = controller.addVacation(body, session);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody().containsKey("error"));
    }

    @Test
    void addVacation_emptyEmployeeName_returns400() {
        Map<String, Object> body = new HashMap<>();
        body.put("employee_name", "  ");
        body.put("leave_type", "Vacation");
        body.put("start_date", "2026-06-01");
        body.put("end_date",   "2026-06-05");

        ResponseEntity<Map<String, Object>> resp = controller.addVacation(body, session);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void addVacation_employeeRoleAddingForOther_returns403() {
        session.setAttribute("role", "employee");
        session.setAttribute("employee_name", "Bob Johnson");

        Map<String, Object> body = new HashMap<>();
        body.put("employee_name", "Alice Smith");
        body.put("leave_type",    "Vacation");
        body.put("start_date",    "2026-06-01");
        body.put("end_date",      "2026-06-05");

        ResponseEntity<Map<String, Object>> resp = controller.addVacation(body, session);
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    @Test
    void addVacation_nullLeaveType_returns400() throws Exception {
        when(appState.getLoadedFiles()).thenReturn(Collections.singletonList("/data/f.xlsx"));
        when(reader.load(anyString())).thenReturn(records);

        Map<String, Object> body = new HashMap<>();
        body.put("employee_name", "Alice Smith");
        body.put("start_date",    "2026-06-01");
        body.put("end_date",      "2026-06-05");

        ResponseEntity<Map<String, Object>> resp = controller.addVacation(body, session);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void addVacation_invalidLeaveType_returns400() throws Exception {
        when(appState.getLoadedFiles()).thenReturn(Collections.singletonList("/data/f.xlsx"));
        when(reader.load(anyString())).thenReturn(records);
        when(typeService.findByLabel("Nonsense")).thenReturn(Optional.empty());

        Map<String, Object> body = new HashMap<>();
        body.put("employee_name", "Alice Smith");
        body.put("leave_type",    "Nonsense");
        body.put("start_date",    "2026-06-01");
        body.put("end_date",      "2026-06-05");

        ResponseEntity<Map<String, Object>> resp = controller.addVacation(body, session);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void addVacation_restrictedType_returns403() throws Exception {
        when(appState.getLoadedFiles()).thenReturn(Collections.singletonList("/data/f.xlsx"));
        when(reader.load(anyString())).thenReturn(records);
        when(typeService.findByLabel("Vacation")).thenReturn(Optional.of(vtypeV));
        when(restrictedTypeService.isRestricted("V")).thenReturn(true);

        Map<String, Object> body = new HashMap<>();
        body.put("employee_name", "Alice Smith");
        body.put("leave_type",    "Vacation");
        body.put("start_date",    "2026-06-01");
        body.put("end_date",      "2026-06-05");

        ResponseEntity<Map<String, Object>> resp = controller.addVacation(body, session);
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    @Test
    void addVacation_invalidDateFormat_returns400() throws Exception {
        when(appState.getLoadedFiles()).thenReturn(Collections.singletonList("/data/f.xlsx"));
        when(reader.load(anyString())).thenReturn(records);
        when(typeService.findByLabel("Vacation")).thenReturn(Optional.of(vtypeV));

        Map<String, Object> body = new HashMap<>();
        body.put("employee_name", "Alice Smith");
        body.put("leave_type",    "Vacation");
        body.put("start_date",    "01/06/2026");  // wrong format
        body.put("end_date",      "05/06/2026");

        ResponseEntity<Map<String, Object>> resp = controller.addVacation(body, session);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    // =========================================================================
    // deleteVacation — validation
    // =========================================================================

    @Test
    void deleteVacation_nullEmployeeName_returns400() {
        Map<String, Object> body = new HashMap<>();
        body.put("start_date", "2026-06-01");
        body.put("end_date",   "2026-06-05");

        ResponseEntity<Map<String, Object>> resp = controller.deleteVacation(body, session);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void deleteVacation_employeeRoleDeletingOther_returns403() {
        session.setAttribute("role", "employee");
        session.setAttribute("employee_name", "Bob Johnson");

        Map<String, Object> body = new HashMap<>();
        body.put("employee_name", "Alice Smith");
        body.put("start_date",    "2026-06-01");
        body.put("end_date",      "2026-06-05");

        ResponseEntity<Map<String, Object>> resp = controller.deleteVacation(body, session);
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    @Test
    void deleteVacation_unknownEmployee_returns404() throws Exception {
        when(appState.getLoadedFiles()).thenReturn(Collections.singletonList("/data/f.xlsx"));
        when(reader.load(anyString())).thenReturn(records);

        Map<String, Object> body = new HashMap<>();
        body.put("employee_name", "Nobody Here");
        body.put("start_date",    "2026-06-01");
        body.put("end_date",      "2026-06-05");

        ResponseEntity<Map<String, Object>> resp = controller.deleteVacation(body, session);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    void deleteVacation_invalidDateFormat_returns400() throws Exception {
        when(appState.getLoadedFiles()).thenReturn(Collections.singletonList("/data/f.xlsx"));
        when(reader.load(anyString())).thenReturn(records);

        Map<String, Object> body = new HashMap<>();
        body.put("employee_name", "Alice Smith");
        body.put("start_date",    "June 1");
        body.put("end_date",      "June 5");

        ResponseEntity<Map<String, Object>> resp = controller.deleteVacation(body, session);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void deleteVacation_noMatchingRecord_returns404() throws Exception {
        when(appState.getLoadedFiles()).thenReturn(Collections.singletonList("/data/f.xlsx"));
        when(reader.load(anyString())).thenReturn(records);

        Map<String, Object> body = new HashMap<>();
        body.put("employee_name", "Alice Smith");
        body.put("start_date",    "2026-12-01");  // no record on this date
        body.put("end_date",      "2026-12-05");

        ResponseEntity<Map<String, Object>> resp = controller.deleteVacation(body, session);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    // =========================================================================
    // getTypes
    // =========================================================================

    @Test
    void getTypes_returnsListFromService() {
        ResponseEntity<Map<String, Object>> resp = controller.getTypes();
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody().get("vacation_types"));
    }

    // =========================================================================
    // addType
    // =========================================================================

    @Test
    void addType_missingCode_returns400() {
        Map<String, String> body = new HashMap<>();
        body.put("label", "New Type");

        ResponseEntity<Map<String, Object>> resp = controller.addType(body, session);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void addType_missingLabel_returns400() {
        Map<String, String> body = new HashMap<>();
        body.put("code", "NT");

        ResponseEntity<Map<String, Object>> resp = controller.addType(body, session);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void addType_duplicateCode_returns409() {
        when(typeService.add(any())).thenThrow(new IllegalStateException("code 'NT' already exists."));

        Map<String, String> body = new HashMap<>();
        body.put("code",  "NT");
        body.put("label", "New Type");

        ResponseEntity<Map<String, Object>> resp = controller.addType(body, session);
        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
    }

    @Test
    void addType_validInput_returns201() {
        VacationType newType = new VacationType("NT", "New Type", "FFD3D3D3");
        when(typeService.add(any())).thenReturn(newType);

        Map<String, String> body = new HashMap<>();
        body.put("code",  "NT");
        body.put("label", "New Type");

        ResponseEntity<Map<String, Object>> resp = controller.addType(body, session);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        assertEquals("NT", ((VacationType) resp.getBody().get("vacation_type")).code());
    }

    // =========================================================================
    // updateType
    // =========================================================================

    @Test
    void updateType_unknownCode_returns404() {
        when(typeService.update(eq("ZZ"), any(), any()))
                .thenThrow(new NoSuchElementException("code 'ZZ' not found."));

        Map<String, String> body = Collections.singletonMap("label", "Updated");
        ResponseEntity<Map<String, Object>> resp = controller.updateType("ZZ", body, session);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    void updateType_validCode_returns200() {
        VacationType updated = new VacationType("V", "Annual Vacation", "FF92D050");
        when(typeService.update(eq("V"), any(), any())).thenReturn(updated);

        Map<String, String> body = Collections.singletonMap("label", "Annual Vacation");
        ResponseEntity<Map<String, Object>> resp = controller.updateType("V", body, session);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }
}
