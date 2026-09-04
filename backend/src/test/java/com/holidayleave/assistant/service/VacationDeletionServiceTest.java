package com.holidayleave.assistant.service;

import com.holidayleave.assistant.config.AppProperties;
import com.holidayleave.assistant.model.LeaveRecord;
import com.holidayleave.assistant.model.PendingVacation;
import com.holidayleave.assistant.model.PendingVacation.WizardState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link VacationDeletionService}.
 *
 * Tests the full delete-vacation wizard state machine:
 *   DELETE_IDLE → DELETE_NEED_DATES_FORM → (form submission) → DELETE_CONFIRM → DELETED
 *   DELETE_IDLE → DELETE_NEED_EMP → DELETE_NEED_DATES_FORM → ...
 * Plus past-date validation, form-submission parsing, coverage-validation,
 * and cancellation at each step.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VacationDeletionServiceTest {

    @Mock private AppProperties props;

    @InjectMocks
    private VacationDeletionService service;

    private List<String> employees;
    private List<LeaveRecord> allRecords;

    // Future dates anchored to current month + 1 so the past-date guard never fires
    private LocalDate futureStart;
    private LocalDate futureEnd;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.when(props.isCrossYearBookingOverride()).thenReturn(false);
        employees = Arrays.asList("Alice Smith", "Bob Johnson", "Carol Nguyen");

        futureStart = LocalDate.now().withDayOfMonth(1).plusMonths(1);
        // Advance to a Monday so futureStart..futureEnd is always Mon–Fri = exactly 5 days
        while (futureStart.getDayOfWeek().getValue() != 1) futureStart = futureStart.plusDays(1);
        futureEnd = futureStart.plusDays(4); // Mon–Fri = 5 working days

        allRecords = Arrays.asList(
            new LeaveRecord("Alice Smith", futureStart, futureEnd, 5, "V", null),
            new LeaveRecord("Bob Johnson",
                LocalDate.now().withDayOfMonth(1).plusMonths(2),
                LocalDate.now().withDayOfMonth(1).plusMonths(2).plusDays(4), 5, "V", null)
        );
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Full happy-path via new form submission
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void fullWizard_formSubmission_happyPath() {
        PendingVacation pv = new PendingVacation("delete"); // state = DELETE_IDLE

        // IDLE: employee in message → DELETE_NEED_DATES_FORM + vacation_form response
        VacationDeletionService.WizardResult r1 = service.process(pv,
            "delete vacation for Alice Smith", employees, allRecords);
        assertFalse(r1.confirmed());
        assertFalse(r1.cancelled());
        assertEquals(WizardState.DELETE_NEED_DATES_FORM, pv.getState());
        assertEquals("vacation_form", r1.type());
        assertEquals("Alice Smith", pv.getEmployeeName());

        // DELETE_NEED_DATES_FORM: form submission
        String formMsg = "__VACATION_FORM__:DELETE|" + futureStart + "|" + futureEnd;
        VacationDeletionService.WizardResult r2 = service.process(pv, formMsg, employees, allRecords);
        assertFalse(r2.confirmed());
        assertEquals(WizardState.DELETE_CONFIRM, pv.getState());
        assertEquals(5.0, pv.getDays(), 0.001);
        assertTrue(r2.reply().contains("Alice Smith"));

        // DELETE_CONFIRM: yes
        VacationDeletionService.WizardResult r3 = service.process(pv, "yes", employees, allRecords);
        assertTrue(r3.confirmed());
        assertFalse(r3.cancelled());
        assertEquals(WizardState.DELETED, pv.getState());
    }

    @Test
    void fullWizard_formSubmission_confirmWithY() {
        PendingVacation pv = new PendingVacation("delete");
        service.process(pv, "delete vacation for Alice Smith", employees, allRecords);
        service.process(pv, "__VACATION_FORM__:DELETE|" + futureStart + "|" + futureEnd, employees, allRecords);

        VacationDeletionService.WizardResult r = service.process(pv, "y", employees, allRecords);
        assertTrue(r.confirmed());
    }

    @Test
    void fullWizard_formSubmission_confirmWithConfirmKeyword() {
        PendingVacation pv = new PendingVacation("delete");
        service.process(pv, "delete vacation for Alice Smith", employees, allRecords);
        service.process(pv, "__VACATION_FORM__:DELETE|" + futureStart + "|" + futureEnd, employees, allRecords);

        VacationDeletionService.WizardResult r = service.process(pv, "confirm", employees, allRecords);
        assertTrue(r.confirmed());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // vacation_form response shape
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void idleWithEmployee_returnsVacationFormType() {
        PendingVacation pv = new PendingVacation("delete");
        VacationDeletionService.WizardResult r = service.process(pv,
            "delete vacation for Alice Smith", employees, allRecords);
        assertEquals("vacation_form", r.type());
    }

    @Test
    void idleWithEmployee_replyIsJson_containsWizardType() {
        PendingVacation pv = new PendingVacation("delete");
        VacationDeletionService.WizardResult r = service.process(pv,
            "delete vacation for Alice Smith", employees, allRecords);
        assertTrue(r.reply().contains("Alice Smith"));
        assertTrue(r.reply().contains("\"wizardType\""));
        assertTrue(r.reply().contains("\"delete\""));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Past-date validation (new requirement)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void isPastDate_dateInPreviousMonth_returnsTrue() {
        LocalDate pastDate = LocalDate.now().withDayOfMonth(1).minusMonths(1);
        assertTrue(VacationDeletionService.isPastDate(pastDate));
    }

    @Test
    void isPastDate_firstDayOfCurrentMonth_returnsFalse() {
        LocalDate firstOfMonth = LocalDate.now().withDayOfMonth(1);
        assertFalse(VacationDeletionService.isPastDate(firstOfMonth));
    }

    @Test
    void isPastDate_futureDate_returnsFalse() {
        assertFalse(VacationDeletionService.isPastDate(LocalDate.now().plusMonths(3)));
    }

    @Test
    void formSubmission_pastStartDate_rejectedWithMessage() {
        PendingVacation pv = new PendingVacation("delete");
        service.process(pv, "delete vacation for Alice Smith", employees, allRecords);

        LocalDate pastDate = LocalDate.now().withDayOfMonth(1).minusMonths(1);
        String formMsg = "__VACATION_FORM__:DELETE|" + pastDate + "|" + pastDate.plusDays(4);
        VacationDeletionService.WizardResult r = service.process(pv, formMsg, employees, allRecords);

        assertFalse(r.confirmed());
        assertFalse(r.cancelled());
        assertTrue(r.reply().toLowerCase().contains("past date"));
    }

    @Test
    void legacyNeedStart_pastDate_rejectedWithMessage() {
        PendingVacation pv = new PendingVacation("delete");
        pv.setEmployeeName("Alice Smith");
        pv.setState(WizardState.DELETE_NEED_START);

        LocalDate pastDate = LocalDate.now().withDayOfMonth(1).minusMonths(1);
        VacationDeletionService.WizardResult r = service.process(pv, pastDate.toString(), employees, allRecords);

        assertFalse(r.confirmed());
        assertTrue(r.reply().toLowerCase().contains("past date"));
        assertEquals(WizardState.DELETE_NEED_START, pv.getState());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Form submission parsing — error cases
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void isFormSubmission_withPrefix_returnsTrue() {
        assertTrue(VacationDeletionService.isFormSubmission("__VACATION_FORM__:DELETE|2027-01-01|2027-01-05"));
    }

    @Test
    void isFormSubmission_withoutPrefix_returnsFalse() {
        assertFalse(VacationDeletionService.isFormSubmission("cancel"));
        assertFalse(VacationDeletionService.isFormSubmission(null));
    }

    @Test
    void formSubmission_malformedPayload_rendersFormAgain() {
        PendingVacation pv = new PendingVacation("delete");
        service.process(pv, "delete vacation for Alice Smith", employees, allRecords);

        // Only two parts
        VacationDeletionService.WizardResult r = service.process(pv,
            "__VACATION_FORM__:DELETE|2027-01-01", employees, allRecords);
        assertEquals("vacation_form", r.type());
        assertFalse(r.confirmed());
    }

    @Test
    void formSubmission_endBeforeStart_errorMessage() {
        PendingVacation pv = new PendingVacation("delete");
        service.process(pv, "delete vacation for Alice Smith", employees, allRecords);

        LocalDate end = futureStart.minusDays(1);
        VacationDeletionService.WizardResult r = service.process(pv,
            "__VACATION_FORM__:DELETE|" + futureStart + "|" + end, employees, allRecords);
        assertTrue(r.reply().toLowerCase().contains("start date cannot be greater"));
        assertFalse(r.confirmed());
    }

    @Test
    void formSubmission_dateNotCovered_deletionAborted() {
        // Alice has futureStart–futureEnd; try to delete a date one week later (not covered)
        PendingVacation pv = new PendingVacation("delete");
        service.process(pv, "delete vacation for Alice Smith", employees, allRecords);

        LocalDate uncovered = futureEnd.plusDays(7);
        VacationDeletionService.WizardResult r = service.process(pv,
            "__VACATION_FORM__:DELETE|" + uncovered + "|" + uncovered.plusDays(4), employees, allRecords);
        assertTrue(r.cancelled());
        assertEquals(WizardState.DELETE_CANCELLED, pv.getState());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // IDLE → DELETE_NEED_EMP path
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void idle_noEmployeeInMessage_asksForEmployee() {
        PendingVacation pv = new PendingVacation("delete");
        VacationDeletionService.WizardResult r = service.process(pv, "delete vacation", employees, allRecords);
        assertFalse(r.confirmed());
        assertEquals(WizardState.DELETE_NEED_EMP, pv.getState());
    }

    @Test
    void needEmp_validEmployee_advancesToNeedDatesForm() {
        PendingVacation pv = new PendingVacation("delete");
        service.process(pv, "delete vacation", employees, allRecords); // → DELETE_NEED_EMP

        VacationDeletionService.WizardResult r = service.process(pv, "Alice Smith", employees, allRecords);
        assertEquals(WizardState.DELETE_NEED_DATES_FORM, pv.getState());
        assertEquals("Alice Smith", pv.getEmployeeName());
        assertEquals("vacation_form", r.type());
    }

    @Test
    void needEmp_unknownEmployee_staysInNeedEmp() {
        PendingVacation pv = new PendingVacation("delete");
        service.process(pv, "delete vacation", employees, allRecords);

        VacationDeletionService.WizardResult r = service.process(pv, "Zephyr Unknown", employees, allRecords);
        assertEquals(WizardState.DELETE_NEED_EMP, pv.getState());
        assertFalse(r.confirmed());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Pre-seeded employee (employee-role — skip DELETE_NEED_EMP)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void preSeededEmployee_deleteNeedDatesFormIsFirstStep() {
        PendingVacation pv = new PendingVacation("delete");
        pv.setEmployeeName("Alice Smith");
        pv.setState(WizardState.DELETE_NEED_DATES_FORM);

        // Any non-form message re-renders the form (no text fallback)
        VacationDeletionService.WizardResult r = service.process(pv, futureStart.toString(), employees, allRecords);
        assertEquals("vacation_form", r.type());
        assertEquals(WizardState.DELETE_NEED_DATES_FORM, pv.getState());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Legacy DELETE_NEED_START path (fallback)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void legacyNeedStart_invalidDateFormat_staysInNeedStart() {
        PendingVacation pv = new PendingVacation("delete");
        pv.setEmployeeName("Alice Smith");
        pv.setState(WizardState.DELETE_NEED_START);

        VacationDeletionService.WizardResult r = service.process(pv, "01/06/2026", employees, allRecords);
        assertEquals(WizardState.DELETE_NEED_START, pv.getState());
        assertTrue(r.reply().contains("YYYY-MM-DD"));
    }

    @Test
    void legacyNeedEnd_endBeforeStart_errorMessage() {
        PendingVacation pv = new PendingVacation("delete");
        pv.setEmployeeName("Alice Smith");
        pv.setState(WizardState.DELETE_NEED_START);

        service.process(pv, futureStart.plusDays(2).toString(), employees, allRecords); // → NEED_END

        VacationDeletionService.WizardResult r = service.process(pv,
            futureStart.toString(), employees, allRecords); // end before start
        assertEquals(WizardState.DELETE_NEED_END, pv.getState());
        assertTrue(r.reply().toLowerCase().contains("end date"));
    }

    @Test
    void legacyNeedEnd_dateNotCovered_deletionAborted() {
        PendingVacation pv = new PendingVacation("delete");
        pv.setEmployeeName("Alice Smith");
        pv.setState(WizardState.DELETE_NEED_START);

        LocalDate uncovered = futureEnd.plusDays(7);
        service.process(pv, uncovered.toString(), employees, allRecords);

        VacationDeletionService.WizardResult r = service.process(pv,
            uncovered.plusDays(4).toString(), employees, allRecords);
        assertTrue(r.cancelled());
        assertEquals(WizardState.DELETE_CANCELLED, pv.getState());
    }

    @Test
    void legacyNeedEnd_singleDay_matchesStart() {
        PendingVacation pv = new PendingVacation("delete");
        pv.setEmployeeName("Alice Smith");
        pv.setState(WizardState.DELETE_NEED_START);
        service.process(pv, futureStart.toString(), employees, allRecords);

        VacationDeletionService.WizardResult r = service.process(pv,
            futureStart.toString(), employees, allRecords);
        assertEquals(WizardState.DELETE_CONFIRM, pv.getState());
        assertEquals(1.0, pv.getDays(), 0.001);
    }

    @Test
    void legacyNeedEnd_weekendRange_noWorkingDays_error() {
        // Even if employee has coverage, a weekend-only range has 0 working days
        // Find a Saturday in the covered range or just in the future
        LocalDate sat = futureStart;
        while (sat.getDayOfWeek().getValue() != 6) sat = sat.plusDays(1);
        LocalDate sun = sat.plusDays(1);

        // Extend Alice's records to include the weekend date span
        List<LeaveRecord> extendedRecords = new ArrayList<>(allRecords);
        extendedRecords.add(new LeaveRecord("Alice Smith", sat, sun, 0, "V", null));

        PendingVacation pv = new PendingVacation("delete");
        pv.setEmployeeName("Alice Smith");
        pv.setState(WizardState.DELETE_NEED_START);
        service.process(pv, sat.toString(), employees, extendedRecords);

        VacationDeletionService.WizardResult r = service.process(pv, sun.toString(), employees, extendedRecords);
        assertEquals(WizardState.DELETE_NEED_END, pv.getState());
        assertTrue(r.reply().toLowerCase().contains("working day"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Warning for single-day inside a longer block
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void needEnd_singleDayInsideLongerBlock_includesNote() {
        // Alice has futureStart to futureEnd. Delete single day Wednesday (day 2 = always a weekday since start is Mon)
        LocalDate mid = futureStart.plusDays(2); // Wednesday
        PendingVacation pv = new PendingVacation("delete");
        pv.setEmployeeName("Alice Smith");
        pv.setState(WizardState.DELETE_NEED_START);
        service.process(pv, mid.toString(), employees, allRecords);

        VacationDeletionService.WizardResult r = service.process(pv, mid.toString(), employees, allRecords);
        assertEquals(WizardState.DELETE_CONFIRM, pv.getState());
        assertTrue(r.reply().contains("Note:") || r.reply().contains("block"),
            "Expected a warning about the longer block. Reply: " + r.reply());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Cancel at each stage
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void cancel_atDeleteNeedEmp_wizardCancelled() {
        PendingVacation pv = new PendingVacation("delete");
        service.process(pv, "delete vacation", employees, allRecords);

        VacationDeletionService.WizardResult r = service.process(pv, "cancel", employees, allRecords);
        assertTrue(r.cancelled());
        assertEquals(WizardState.DELETE_CANCELLED, pv.getState());
    }

    @Test
    void cancel_atNeedDatesForm_wizardCancelled() {
        PendingVacation pv = new PendingVacation("delete");
        service.process(pv, "delete vacation for Alice Smith", employees, allRecords);

        VacationDeletionService.WizardResult r = service.process(pv, "abort", employees, allRecords);
        assertTrue(r.cancelled());
    }

    @Test
    void cancel_atDeleteNeedEnd_wizardCancelled() {
        PendingVacation pv = new PendingVacation("delete");
        pv.setEmployeeName("Alice Smith");
        pv.setState(WizardState.DELETE_NEED_START);
        service.process(pv, futureStart.toString(), employees, allRecords);

        VacationDeletionService.WizardResult r = service.process(pv, "stop", employees, allRecords);
        assertTrue(r.cancelled());
    }

    @Test
    void cancel_atDeleteConfirm_no_cancelsWizard() {
        PendingVacation pv = new PendingVacation("delete");
        service.process(pv, "delete vacation for Alice Smith", employees, allRecords);
        service.process(pv, "__VACATION_FORM__:DELETE|" + futureStart + "|" + futureEnd, employees, allRecords);

        VacationDeletionService.WizardResult r = service.process(pv, "no", employees, allRecords);
        assertTrue(r.cancelled());
    }

    @Test
    void cancel_atDeleteNeedStart_quit_cancels() {
        PendingVacation pv = new PendingVacation("delete");
        pv.setEmployeeName("Alice Smith");
        pv.setState(WizardState.DELETE_NEED_START);

        VacationDeletionService.WizardResult r = service.process(pv, "quit", employees, allRecords);
        assertTrue(r.cancelled());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Negative — no records for employee
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void needEnd_noRecordsForEmployee_dateNotCovered() {
        // Carol has no records — any working day will be uncovered
        PendingVacation pv = new PendingVacation("delete");
        pv.setEmployeeName("Carol Nguyen");
        pv.setState(WizardState.DELETE_NEED_START);
        service.process(pv, futureStart.toString(), employees, allRecords);

        VacationDeletionService.WizardResult r = service.process(pv,
            futureStart.toString(), employees, allRecords);
        assertTrue(r.cancelled());
    }

    @Test
    void idle_emptyRecordList_doesNotThrow() {
        PendingVacation pv = new PendingVacation("delete");
        assertDoesNotThrow(() -> service.process(pv, "delete vacation for Alice Smith",
            employees, Collections.emptyList()));
    }

    @Test
    void formSubmission_emptyRecordList_dateCoveredCheckFails() {
        PendingVacation pv = new PendingVacation("delete");
        service.process(pv, "delete vacation for Alice Smith", employees, Collections.emptyList());

        VacationDeletionService.WizardResult r = service.process(pv,
            "__VACATION_FORM__:DELETE|" + futureStart + "|" + futureEnd, employees, Collections.emptyList());
        assertTrue(r.cancelled());
    }
}
