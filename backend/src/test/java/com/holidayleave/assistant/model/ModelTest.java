package com.holidayleave.assistant.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for all model classes:
 *   FileInfo, LeaveRecord, VacationType, PendingVacation, AuditLogEntry.
 *
 * Models are pure value objects — tests verify construction, accessors,
 * immutability constraints, derived fields, and Jackson round-trip
 * serialisation where annotations are present.
 */
class ModelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // =========================================================================
    // FileInfo
    // =========================================================================

    @Test
    void fileInfo_getters_returnConstructorValues() {
        FileInfo fi = new FileInfo("plan.xlsx", "/data/plan.xlsx", true);
        assertEquals("plan.xlsx",      fi.getName());
        assertEquals("/data/plan.xlsx", fi.getPath());
        assertTrue(fi.isActive());
    }

    @Test
    void fileInfo_inactive_isActiveFalse() {
        FileInfo fi = new FileInfo("old.xlsx", "/data/old.xlsx", false);
        assertFalse(fi.isActive());
    }

    @Test
    void fileInfo_nullNameAndPath_accepted() {
        FileInfo fi = new FileInfo(null, null, false);
        assertNull(fi.getName());
        assertNull(fi.getPath());
    }

    // =========================================================================
    // LeaveRecord
    // =========================================================================

    @Test
    void leaveRecord_getters_returnConstructorValues() {
        LocalDate start = LocalDate.of(2026, 3, 2);
        LocalDate end   = LocalDate.of(2026, 3, 6);
        LeaveRecord r = new LeaveRecord("Alice Smith", start, end, 5.0, "V", "Holiday");

        assertEquals("Alice Smith", r.employeeName());
        assertEquals(start,          r.startDate());
        assertEquals(end,            r.endDate());
        assertEquals(5.0,            r.days(), 0.001);
        assertEquals("V",            r.leaveType());
        assertEquals("Holiday",      r.reason());
    }

    @Test
    void leaveRecord_year_derivedFromStartDate() {
        LeaveRecord r = new LeaveRecord("Bob", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 1), 1, "P", null);
        assertEquals(2026, r.year());
    }

    @Test
    void leaveRecord_yearBoundary_december31() {
        LeaveRecord r = new LeaveRecord("Carol", LocalDate.of(2025, 12, 31), LocalDate.of(2025, 12, 31), 1, "V", null);
        assertEquals(2025, r.year());
    }

    @Test
    void leaveRecord_yearBoundary_january1() {
        LeaveRecord r = new LeaveRecord("Dave", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 1), 1, "V", null);
        assertEquals(2027, r.year());
    }

    @Test
    void leaveRecord_halfDay_daysIsPointFive() {
        LeaveRecord r = new LeaveRecord("Eve", LocalDate.of(2026, 4, 6), LocalDate.of(2026, 4, 6), 0.5, "H", null);
        assertEquals(0.5, r.days(), 0.001);
    }

    @Test
    void leaveRecord_nullReason_accepted() {
        LeaveRecord r = new LeaveRecord("Fred", LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 5), 1, "V", null);
        assertNull(r.reason());
    }

    @Test
    void leaveRecord_zeroDays_accepted() {
        LeaveRecord r = new LeaveRecord("Grace", LocalDate.of(2026, 6, 6), LocalDate.of(2026, 6, 6), 0, "A", null);
        assertEquals(0.0, r.days(), 0.001);
    }

    // =========================================================================
    // VacationType
    // =========================================================================

    @Test
    void vacationType_getters_returnConstructorValues() {
        VacationType vt = new VacationType("V", "Vacation", "FF92D050");
        assertEquals("V",        vt.code());
        assertEquals("Vacation", vt.label());
        assertEquals("FF92D050", vt.color());
    }

    @Test
    void vacationType_jacksonRoundTrip() throws Exception {
        VacationType original = new VacationType("PC", "Personal Choice Holiday", "FFFFFF00");
        String json = MAPPER.writeValueAsString(original);

        assertTrue(json.contains("\"code\":\"PC\""));
        assertTrue(json.contains("\"label\":\"Personal Choice Holiday\""));
        assertTrue(json.contains("\"color\":\"FFFFFF00\""));

        VacationType deserialized = MAPPER.readValue(json, VacationType.class);
        assertEquals("PC",                       deserialized.code());
        assertEquals("Personal Choice Holiday",  deserialized.label());
        assertEquals("FFFFFF00",                 deserialized.color());
    }

    @Test
    void vacationType_allCodes_roundTrip() throws Exception {
        String[] codes  = {"V", "P", "PC", "H", "E", "O", "A"};
        String[] labels = {"Vacation", "Public Holiday", "Personal Choice Holiday",
                           "Half-day Vacation", "Education", "Other", "Available"};
        for (int i = 0; i < codes.length; i++) {
            VacationType vt  = new VacationType(codes[i], labels[i], "FFAABBCC");
            String json      = MAPPER.writeValueAsString(vt);
            VacationType rt  = MAPPER.readValue(json, VacationType.class);
            assertEquals(codes[i],  rt.code());
            assertEquals(labels[i], rt.label());
        }
    }

    @Test
    void vacationType_nullFieldsAccepted() {
        VacationType vt = new VacationType(null, null, null);
        assertNull(vt.code());
        assertNull(vt.label());
        assertNull(vt.color());
    }

    // =========================================================================
    // PendingVacation
    // =========================================================================

    @Test
    void pendingVacation_addWizardType_initialStateIsIdle() {
        PendingVacation pv = new PendingVacation("add");
        assertEquals(PendingVacation.WizardState.IDLE, pv.getState());
        assertEquals("add", pv.getWizardType());
    }

    @Test
    void pendingVacation_deleteWizardType_initialStateIsDeleteIdle() {
        PendingVacation pv = new PendingVacation("delete");
        assertEquals(PendingVacation.WizardState.DELETE_IDLE, pv.getState());
        assertEquals("delete", pv.getWizardType());
    }

    @Test
    void pendingVacation_initialDaysIsZero() {
        PendingVacation pv = new PendingVacation("add");
        assertEquals(0.0, pv.getDays(), 0.001);
    }

    @Test
    void pendingVacation_settersAndGetters() {
        PendingVacation pv = new PendingVacation("add");
        LocalDate start = LocalDate.of(2026, 6, 1);
        LocalDate end   = LocalDate.of(2026, 6, 5);

        pv.setEmployeeName("Alice Smith");
        pv.setLeaveType("Vacation");
        pv.setLeaveCode("V");
        pv.setStartDate(start);
        pv.setEndDate(end);
        pv.setDays(5);
        pv.setReason("Annual leave");
        pv.setState(PendingVacation.WizardState.CONFIRM);

        assertEquals("Alice Smith",                       pv.getEmployeeName());
        assertEquals("Vacation",                          pv.getLeaveType());
        assertEquals("V",                                 pv.getLeaveCode());
        assertEquals(start,                               pv.getStartDate());
        assertEquals(end,                                 pv.getEndDate());
        assertEquals(5.0,                                 pv.getDays(), 0.001);
        assertEquals("Annual leave",                      pv.getReason());
        assertEquals(PendingVacation.WizardState.CONFIRM, pv.getState());
    }

    @Test
    void pendingVacation_allAddStatesExist() {
        // Ensure all expected creation wizard states are defined
        assertNotNull(PendingVacation.WizardState.IDLE);
        assertNotNull(PendingVacation.WizardState.NEED_EMP);
        assertNotNull(PendingVacation.WizardState.NEED_TYPE);
        assertNotNull(PendingVacation.WizardState.NEED_START);
        assertNotNull(PendingVacation.WizardState.NEED_END);
        assertNotNull(PendingVacation.WizardState.CONFIRM);
        assertNotNull(PendingVacation.WizardState.SAVED);
        assertNotNull(PendingVacation.WizardState.CANCELLED);
    }

    @Test
    void pendingVacation_allDeleteStatesExist() {
        assertNotNull(PendingVacation.WizardState.DELETE_IDLE);
        assertNotNull(PendingVacation.WizardState.DELETE_NEED_EMP);
        assertNotNull(PendingVacation.WizardState.DELETE_NEED_START);
        assertNotNull(PendingVacation.WizardState.DELETE_NEED_END);
        assertNotNull(PendingVacation.WizardState.DELETE_CONFIRM);
        assertNotNull(PendingVacation.WizardState.DELETED);
        assertNotNull(PendingVacation.WizardState.DELETE_CANCELLED);
    }

    @Test
    void pendingVacation_noArgConstructor_doesNotThrow() {
        assertDoesNotThrow((org.junit.jupiter.api.function.Executable) PendingVacation::new);
    }

    // =========================================================================
    // AuditLogEntry — Jackson serialisation / @JsonProperty / @JsonAlias
    // =========================================================================

    @Test
    void auditLogEntry_getters_returnConstructorValues() {
        AuditLogEntry e = new AuditLogEntry("vacation_added", "2026-01-01T10:00:00Z",
                "admin", "Alice Smith", "Added 5d [V]", "success", "chat");
        assertEquals("vacation_added", e.getEventType());
        assertEquals("2026-01-01T10:00:00Z", e.getTimestamp());
        assertEquals("admin",       e.getUser());
        assertEquals("Alice Smith", e.getEmployee());
        assertEquals("Added 5d [V]", e.getDetails());
        assertEquals("success",     e.getStatus());
        assertEquals("chat",        e.getSource());
    }

    @Test
    void auditLogEntry_serialisedWithSnakeCaseEventType() throws Exception {
        AuditLogEntry e = new AuditLogEntry("vacation_deleted", "2026-06-01T08:00:00Z",
                "admin", "Bob", "Deleted via chat", "success", "chat");
        String json = MAPPER.writeValueAsString(e);
        assertTrue(json.contains("\"event_type\""),
                "Serialised JSON must use event_type key, got: " + json);
        assertFalse(json.contains("\"eventType\""),
                "Serialised JSON must NOT use camelCase eventType");
    }

    @Test
    void auditLogEntry_deserialisesSnakeCaseEventType() throws Exception {
        String json = "{\"event_type\":\"file_deleted\",\"timestamp\":\"2026-01-01T00:00:00Z\"," +
                "\"user\":\"admin\",\"employee\":null,\"details\":\"del\",\"status\":\"ok\",\"source\":\"api\"}";
        AuditLogEntry e = MAPPER.readValue(json, AuditLogEntry.class);
        assertEquals("file_deleted", e.getEventType());
    }

    @Test
    void auditLogEntry_deserialisesLegacyCamelCaseAlias() throws Exception {
        // @JsonAlias("eventType") must allow legacy camelCase key
        String json = "{\"eventType\":\"legacy_event\",\"timestamp\":\"2026-01-01T00:00:00Z\"," +
                "\"user\":\"admin\",\"employee\":null,\"details\":\"d\",\"status\":\"ok\",\"source\":\"api\"}";
        AuditLogEntry e = MAPPER.readValue(json, AuditLogEntry.class);
        assertEquals("legacy_event", e.getEventType(),
                "@JsonAlias(\"eventType\") must deserialise legacy camelCase key");
    }

    @Test
    void auditLogEntry_nullEmployeeAndDetails_accepted() throws Exception {
        AuditLogEntry e = new AuditLogEntry("sync", "2026-06-01T00:00:00Z",
                "system", null, null, "ok", "system");
        String json = MAPPER.writeValueAsString(e);
        AuditLogEntry rt = MAPPER.readValue(json, AuditLogEntry.class);
        assertNull(rt.getEmployee());
        assertNull(rt.getDetails());
    }

    @Test
    void auditLogEntry_roundTrip_allFields() throws Exception {
        AuditLogEntry original = new AuditLogEntry("password_reset", "2026-05-15T14:30:00+02:00",
                "admin", "Carol Nguyen", "Reset credential: carolNguyen", "success", "api");
        String json = MAPPER.writeValueAsString(original);
        AuditLogEntry rt = MAPPER.readValue(json, AuditLogEntry.class);

        assertEquals(original.getEventType(),  rt.getEventType());
        assertEquals(original.getTimestamp(),  rt.getTimestamp());
        assertEquals(original.getUser(),       rt.getUser());
        assertEquals(original.getEmployee(),   rt.getEmployee());
        assertEquals(original.getDetails(),    rt.getDetails());
        assertEquals(original.getStatus(),     rt.getStatus());
        assertEquals(original.getSource(),     rt.getSource());
    }
}
