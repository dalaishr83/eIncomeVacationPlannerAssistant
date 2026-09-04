package com.holidayleave.assistant.service;

import com.holidayleave.assistant.config.AppProperties;
import com.holidayleave.assistant.model.PendingVacation;
import com.holidayleave.assistant.model.PendingVacation.WizardState;
import com.holidayleave.assistant.model.VacationType;
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
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link VacationCreationService}.
 *
 * Tests the full "add vacation" wizard state machine:
 *  IDLE → NEED_DATES_FORM → (form submission) → CONFIRM → SAVED
 *  IDLE → NEED_EMP → NEED_DATES_FORM → ...
 * Plus legacy text-input fallback, past-date validation, form-submission parsing,
 * cancellation at each step, and the countWeekdays static helper.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VacationCreationServiceTest {

    @Mock private VacationTypeService vacationTypeService;
    @Mock private RestrictedVacationTypeService restrictedVacationTypeService;
    @Mock private AppProperties props;

    @InjectMocks
    private VacationCreationService service;

    private List<String> employees;
    private List<VacationType> types;

    @BeforeEach
    void setUp() {
        employees = Arrays.asList("Alice Smith", "Bob Johnson", "Carol Nguyen");
        types = Arrays.asList(
            new VacationType("V",  "Vacation",                "FF92D050"),
            new VacationType("P",  "Public Holiday",          "FFFF0000"),
            new VacationType("PC", "Personal Choice Holiday", "FFFFFF00"),
            new VacationType("H",  "Half-day Vacation",       "FFFFC000"),
            new VacationType("E",  "Education",               "FF00B0F0"),
            new VacationType("O",  "Other",                   "FFD3D3D3")
        );
        when(vacationTypeService.findAll()).thenReturn(types);
        when(restrictedVacationTypeService.getRestrictedTypes()).thenReturn(Collections.emptyList());
        when(restrictedVacationTypeService.isRestricted(any())).thenReturn(false);
        // Default: override disabled (production behaviour)
        when(props.isCrossYearBookingOverride()).thenReturn(false);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Full happy-path via new form submission
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void fullWizard_formSubmission_happyPath() {
        PendingVacation pv = new PendingVacation("add"); // state = IDLE

        // IDLE: employee in message → NEED_DATES_FORM + vacation_form response
        VacationCreationService.WizardResult r1 = service.process(pv, "Add vacation for Alice Smith", employees);
        assertFalse(r1.confirmed());
        assertFalse(r1.cancelled());
        assertEquals(WizardState.NEED_DATES_FORM, pv.getState());
        assertEquals("vacation_form", r1.type());
        assertTrue(r1.reply().contains("Alice Smith"));

        // NEED_DATES_FORM: combined form submission with a future month date
        LocalDate futureStart = LocalDate.now().withDayOfMonth(1).plusMonths(1);
        LocalDate futureEnd   = futureStart.plusDays(4);
        String formMsg = "__VACATION_FORM__:V|" + futureStart + "|" + futureEnd;
        VacationCreationService.WizardResult r2 = service.process(pv, formMsg, employees);
        assertFalse(r2.confirmed());
        assertEquals(WizardState.CONFIRM, pv.getState());
        assertEquals("Vacation", pv.getLeaveType());
        assertEquals("V", pv.getLeaveCode());
        assertEquals(futureStart, pv.getStartDate());
        assertEquals(futureEnd, pv.getEndDate());
        assertTrue(r2.reply().contains("Vacation"));

        // CONFIRM: yes
        VacationCreationService.WizardResult r3 = service.process(pv, "yes", employees);
        assertTrue(r3.confirmed());
        assertFalse(r3.cancelled());
        assertEquals(WizardState.SAVED, pv.getState());
    }

    @Test
    void fullWizard_formSubmission_confirmWithY() {
        PendingVacation pv = new PendingVacation("add");
        service.process(pv, "Add vacation for Alice Smith", employees);
        LocalDate start = LocalDate.now().withDayOfMonth(1).plusMonths(1);
        LocalDate end   = start.plusDays(4);
        service.process(pv, "__VACATION_FORM__:V|" + start + "|" + end, employees);

        VacationCreationService.WizardResult r = service.process(pv, "y", employees);
        assertTrue(r.confirmed());
    }

    @Test
    void fullWizard_formSubmission_confirmWithConfirmKeyword() {
        PendingVacation pv = new PendingVacation("add");
        service.process(pv, "Add vacation for Alice Smith", employees);
        LocalDate start = LocalDate.now().withDayOfMonth(1).plusMonths(1);
        LocalDate end   = start.plusDays(4);
        service.process(pv, "__VACATION_FORM__:V|" + start + "|" + end, employees);

        VacationCreationService.WizardResult r = service.process(pv, "confirm", employees);
        assertTrue(r.confirmed());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // vacation_form response shape
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void idleWithEmployee_returnsVacationFormType() {
        PendingVacation pv = new PendingVacation("add");
        VacationCreationService.WizardResult r = service.process(pv, "Add vacation for Alice Smith", employees);
        assertEquals("vacation_form", r.type());
    }

    @Test
    void idleWithEmployee_replyIsJson_containsTypes() {
        PendingVacation pv = new PendingVacation("add");
        VacationCreationService.WizardResult r = service.process(pv, "Add vacation for Alice Smith", employees);
        // reply should be JSON containing the employee name and type list
        assertTrue(r.reply().contains("Alice Smith"));
        assertTrue(r.reply().contains("\"wizardType\""));
        assertTrue(r.reply().contains("\"add\""));
    }

    @Test
    void idleWithEmployee_restrictedTypesExcluded() {
        when(restrictedVacationTypeService.getRestrictedTypes()).thenReturn(Collections.singletonList("PC"));
        PendingVacation pv = new PendingVacation("add");
        VacationCreationService.WizardResult r = service.process(pv, "Add vacation for Alice Smith", employees);
        // PC should not appear in the types payload
        assertFalse(r.reply().contains("\"PC\""));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Past-date validation (new requirement)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void isPastDate_dateInPreviousMonth_returnsTrue() {
        LocalDate pastDate = LocalDate.now().withDayOfMonth(1).minusMonths(1);
        assertTrue(VacationCreationService.isPastDate(pastDate));
    }

    @Test
    void isPastDate_firstDayOfCurrentMonth_returnsFalse() {
        LocalDate firstOfMonth = LocalDate.now().withDayOfMonth(1);
        assertFalse(VacationCreationService.isPastDate(firstOfMonth));
    }

    @Test
    void isPastDate_futureDate_returnsFalse() {
        LocalDate future = LocalDate.now().plusMonths(3);
        assertFalse(VacationCreationService.isPastDate(future));
    }

    @Test
    void formSubmission_pastStartDate_rejectedWithMessage() {
        PendingVacation pv = new PendingVacation("add");
        service.process(pv, "Add vacation for Alice Smith", employees);

        LocalDate pastDate = LocalDate.now().withDayOfMonth(1).minusMonths(1);
        String formMsg = "__VACATION_FORM__:V|" + pastDate + "|" + pastDate.plusDays(4);
        VacationCreationService.WizardResult r = service.process(pv, formMsg, employees);

        assertFalse(r.confirmed());
        assertFalse(r.cancelled());
        assertTrue(r.reply().toLowerCase().contains("past date"));
    }

    @Test
    void legacyNeedStart_pastDate_rejectedWithMessage() {
        // Legacy text-entry path still enforces past-date rule
        PendingVacation pv = new PendingVacation("add");
        pv.setEmployeeName("Alice Smith");
        pv.setState(WizardState.NEED_START);
        pv.setLeaveType("Vacation");
        pv.setLeaveCode("V");

        LocalDate pastDate = LocalDate.now().withDayOfMonth(1).minusMonths(1);
        VacationCreationService.WizardResult r = service.process(pv, pastDate.toString(), employees);

        assertFalse(r.confirmed());
        assertTrue(r.reply().toLowerCase().contains("past date"));
        assertEquals(WizardState.NEED_START, pv.getState());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Form submission parsing — error cases
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void isFormSubmission_withPrefix_returnsTrue() {
        assertTrue(VacationCreationService.isFormSubmission("__VACATION_FORM__:V|2027-01-01|2027-01-05"));
    }

    @Test
    void isFormSubmission_withoutPrefix_returnsFalse() {
        assertFalse(VacationCreationService.isFormSubmission("V"));
        assertFalse(VacationCreationService.isFormSubmission("cancel"));
        assertFalse(VacationCreationService.isFormSubmission(null));
    }

    @Test
    void formSubmission_malformedPayload_rendersFormAgain() {
        PendingVacation pv = new PendingVacation("add");
        service.process(pv, "Add vacation for Alice Smith", employees);

        // Only two parts instead of three
        VacationCreationService.WizardResult r = service.process(pv, "__VACATION_FORM__:V|2027-01-01", employees);
        // Should re-render the form
        assertEquals("vacation_form", r.type());
        assertFalse(r.confirmed());
    }

    @Test
    void formSubmission_unknownTypeCode_rendersFormAgain() {
        PendingVacation pv = new PendingVacation("add");
        service.process(pv, "Add vacation for Alice Smith", employees);

        LocalDate start = LocalDate.now().withDayOfMonth(1).plusMonths(1);
        VacationCreationService.WizardResult r = service.process(pv,
            "__VACATION_FORM__:XXXX|" + start + "|" + start.plusDays(4), employees);
        assertEquals("vacation_form", r.type());
    }

    @Test
    void formSubmission_endBeforeStart_errorMessage() {
        PendingVacation pv = new PendingVacation("add");
        service.process(pv, "Add vacation for Alice Smith", employees);

        LocalDate start = LocalDate.now().withDayOfMonth(1).plusMonths(1);
        LocalDate end   = start.minusDays(1);   // end before start
        VacationCreationService.WizardResult r = service.process(pv,
            "__VACATION_FORM__:V|" + start + "|" + end, employees);
        assertTrue(r.reply().toLowerCase().contains("start date cannot be greater"));
        assertFalse(r.confirmed());
    }

    @Test
    void formSubmission_weekendOnly_noWorkingDays_errorMessage() {
        PendingVacation pv = new PendingVacation("add");
        service.process(pv, "Add vacation for Alice Smith", employees);

        // Find next Saturday
        LocalDate sat = LocalDate.now().withDayOfMonth(1).plusMonths(1);
        while (sat.getDayOfWeek().getValue() != 6) sat = sat.plusDays(1);
        LocalDate sun = sat.plusDays(1);

        VacationCreationService.WizardResult r = service.process(pv,
            "__VACATION_FORM__:V|" + sat + "|" + sun, employees);
        assertTrue(r.reply().toLowerCase().contains("working day"));
        assertFalse(r.confirmed());
    }

    @Test
    void formSubmission_restrictedType_errorMessage() {
        when(restrictedVacationTypeService.isRestricted("PC")).thenReturn(true);
        PendingVacation pv = new PendingVacation("add");
        service.process(pv, "Add vacation for Alice Smith", employees);

        LocalDate start = LocalDate.now().withDayOfMonth(1).plusMonths(1);
        VacationCreationService.WizardResult r = service.process(pv,
            "__VACATION_FORM__:PC|" + start + "|" + start.plusDays(4), employees);
        assertTrue(r.reply().toLowerCase().contains("disabled"));
        assertFalse(r.confirmed());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // IDLE → NEED_EMP path (no employee in initial message)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void idle_noEmployeeInMessage_askForEmployee() {
        PendingVacation pv = new PendingVacation("add");
        VacationCreationService.WizardResult r = service.process(pv, "Add vacation", employees);
        assertFalse(r.confirmed());
        assertEquals(WizardState.NEED_EMP, pv.getState());
        assertTrue(r.reply().contains("employee"));
    }

    @Test
    void needEmp_validEmployee_advancesToNeedDatesForm() {
        PendingVacation pv = new PendingVacation("add");
        service.process(pv, "Add vacation", employees); // → NEED_EMP

        VacationCreationService.WizardResult r = service.process(pv, "Alice Smith", employees);
        assertEquals(WizardState.NEED_DATES_FORM, pv.getState());
        assertEquals("Alice Smith", pv.getEmployeeName());
        assertEquals("vacation_form", r.type());
    }

    @Test
    void needEmp_unknownEmployee_staysInNeedEmp() {
        PendingVacation pv = new PendingVacation("add");
        service.process(pv, "Add vacation", employees); // → NEED_EMP

        VacationCreationService.WizardResult r = service.process(pv, "Zephyr Unknown", employees);
        assertFalse(r.confirmed());
        assertFalse(r.cancelled());
        assertEquals(WizardState.NEED_EMP, pv.getState());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Legacy NEED_TYPE / NEED_START / NEED_END paths (fallback)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void legacyNeedType_exactCodeMatch_advancesToNeedStart() {
        PendingVacation pv = new PendingVacation("add");
        pv.setEmployeeName("Alice Smith");
        pv.setState(WizardState.NEED_TYPE);

        VacationCreationService.WizardResult r = service.process(pv, "V", employees);
        assertEquals(WizardState.NEED_START, pv.getState());
        assertEquals("Vacation", pv.getLeaveType());
        assertEquals("V", pv.getLeaveCode());
    }

    @Test
    void legacyNeedType_leaveTypeByLabel() {
        PendingVacation pv = new PendingVacation("add");
        pv.setEmployeeName("Bob Johnson");
        pv.setState(WizardState.NEED_TYPE);

        VacationCreationService.WizardResult r = service.process(pv, "Vacation", employees);
        assertEquals(WizardState.NEED_START, pv.getState());
        assertEquals("V", pv.getLeaveCode());
    }

    @Test
    void legacyNeedType_leaveTypeByPartialLabel() {
        PendingVacation pv = new PendingVacation("add");
        pv.setEmployeeName("Carol Nguyen");
        pv.setState(WizardState.NEED_TYPE);

        VacationCreationService.WizardResult r = service.process(pv, "education", employees);
        assertEquals(WizardState.NEED_START, pv.getState());
        assertEquals("E", pv.getLeaveCode());
    }

    @Test
    void legacyNeedType_unknownType_rendersFormAgain() {
        PendingVacation pv = new PendingVacation("add");
        pv.setEmployeeName("Alice Smith");
        pv.setState(WizardState.NEED_TYPE);

        VacationCreationService.WizardResult r = service.process(pv, "XYZ999", employees);
        assertFalse(r.confirmed());
        // Now re-renders the form instead of plain-text error
        assertEquals("vacation_form", r.type());
    }

    @Test
    void legacyNeedType_restrictedType_rendersFormAgain() {
        when(restrictedVacationTypeService.isRestricted("PC")).thenReturn(true);
        PendingVacation pv = new PendingVacation("add");
        pv.setEmployeeName("Alice Smith");
        pv.setState(WizardState.NEED_TYPE);

        VacationCreationService.WizardResult r = service.process(pv, "PC", employees);
        assertEquals("vacation_form", r.type());
    }

    @Test
    void legacyNeedStart_invalidDateFormat_staysInNeedStart() {
        PendingVacation pv = new PendingVacation("add");
        pv.setEmployeeName("Alice Smith");
        pv.setState(WizardState.NEED_TYPE);
        service.process(pv, "V", employees); // → NEED_START

        VacationCreationService.WizardResult r = service.process(pv, "01/06/2026", employees);
        assertEquals(WizardState.NEED_START, pv.getState());
        assertTrue(r.reply().contains("YYYY-MM-DD"));
    }

    @Test
    void legacyNeedEnd_endBeforeStart_errorMessage() {
        PendingVacation pv = new PendingVacation("add");
        pv.setEmployeeName("Alice Smith");
        pv.setState(WizardState.NEED_TYPE);
        service.process(pv, "V", employees);

        LocalDate start = LocalDate.now().withDayOfMonth(1).plusMonths(1).plusDays(4);
        service.process(pv, start.toString(), employees); // → NEED_END

        VacationCreationService.WizardResult r = service.process(pv, start.minusDays(1).toString(), employees);
        assertEquals(WizardState.NEED_END, pv.getState());
        assertTrue(r.reply().toLowerCase().contains("end date"));
    }

    @Test
    void legacyNeedEnd_sameStartAndEnd_oneDay() {
        PendingVacation pv = new PendingVacation("add");
        pv.setEmployeeName("Alice Smith");
        pv.setState(WizardState.NEED_TYPE);
        service.process(pv, "V", employees);

        LocalDate start = LocalDate.now().withDayOfMonth(1).plusMonths(1);
        // Advance to a weekday
        while (start.getDayOfWeek().getValue() > 5) start = start.plusDays(1);
        service.process(pv, start.toString(), employees);

        VacationCreationService.WizardResult r = service.process(pv, start.toString(), employees);
        assertEquals(WizardState.CONFIRM, pv.getState());
        assertEquals(1.0, pv.getDays(), 0.001);
        assertTrue(r.reply().contains("1 working day"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Cancel at each stage
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void cancel_atNeedEmp_wizardCancelled() {
        PendingVacation pv = new PendingVacation("add");
        service.process(pv, "Add vacation", employees); // → NEED_EMP

        VacationCreationService.WizardResult r = service.process(pv, "cancel", employees);
        assertTrue(r.cancelled());
        assertFalse(r.confirmed());
        assertEquals(WizardState.CANCELLED, pv.getState());
    }

    @Test
    void cancel_atNeedDatesForm_wizardCancelled() {
        PendingVacation pv = new PendingVacation("add");
        service.process(pv, "Add vacation for Alice Smith", employees); // → NEED_DATES_FORM

        VacationCreationService.WizardResult r = service.process(pv, "abort", employees);
        assertTrue(r.cancelled());
    }

    @Test
    void cancel_atNeedStart_wizardCancelled() {
        PendingVacation pv = new PendingVacation("add");
        pv.setEmployeeName("Alice Smith");
        pv.setState(WizardState.NEED_TYPE);
        service.process(pv, "V", employees); // → NEED_START

        VacationCreationService.WizardResult r = service.process(pv, "stop", employees);
        assertTrue(r.cancelled());
    }

    @Test
    void cancel_atConfirm_no_cancelsWizard() {
        PendingVacation pv = new PendingVacation("add");
        service.process(pv, "Add vacation for Alice Smith", employees);
        LocalDate start = LocalDate.now().withDayOfMonth(1).plusMonths(1);
        LocalDate end   = start.plusDays(4);
        service.process(pv, "__VACATION_FORM__:V|" + start + "|" + end, employees);

        VacationCreationService.WizardResult r = service.process(pv, "no", employees);
        assertTrue(r.cancelled());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Pre-seeded employee (employee-role)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void preSeededEmployee_needDatesFormIsFirstStep() {
        PendingVacation pv = new PendingVacation("add");
        pv.setEmployeeName("Alice Smith");
        pv.setState(WizardState.NEED_DATES_FORM);

        // Sending initial trigger message arrives at NEED_DATES_FORM
        // Non-form message should be treated as a cancel-or-re-render
        VacationCreationService.WizardResult r = service.process(pv, "add vacation", employees);
        // "add vacation" is not a form submission → re-renders form (legacy path: unrecognised type)
        assertEquals("vacation_form", r.type());
        assertFalse(r.confirmed());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // countWeekdays() static helper
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void countWeekdays_monToFri_returns5() {
        long days = VacationCreationService.countWeekdays(
            LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 5));
        assertEquals(5, days);
    }

    @Test
    void countWeekdays_satToSun_returns0() {
        long days = VacationCreationService.countWeekdays(
            LocalDate.of(2026, 6, 6), LocalDate.of(2026, 6, 7));
        assertEquals(0, days);
    }

    @Test
    void countWeekdays_singleMonday_returns1() {
        long days = VacationCreationService.countWeekdays(
            LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 1));
        assertEquals(1, days);
    }

    @Test
    void countWeekdays_singleSaturday_returns0() {
        long days = VacationCreationService.countWeekdays(
            LocalDate.of(2026, 6, 6), LocalDate.of(2026, 6, 6));
        assertEquals(0, days);
    }

    @Test
    void countWeekdays_twoWeeks_returns10() {
        long days = VacationCreationService.countWeekdays(
            LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 12));
        assertEquals(10, days);
    }

    @Test
    void countWeekdays_spanAcrossMonths() {
        // 2026-06-29 (Mon) to 2026-07-03 (Fri) = 5 weekdays
        long days = VacationCreationService.countWeekdays(
            LocalDate.of(2026, 6, 29), LocalDate.of(2026, 7, 3));
        assertEquals(5, days);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Edge cases
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void idle_emptyEmployeeList_doesNotThrow() {
        PendingVacation pv = new PendingVacation("add");
        assertDoesNotThrow(() -> service.process(pv, "Add vacation for Alice Smith", Collections.emptyList()));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Cross-year booking window — validateCrossYearWindow()
    // ══════════════════════════════════════════════════════════════════════════

    /** Simulates today = a Q4 date so cross-year booking is in the allowed window. */
    @Test
    void validateCrossYearWindow_duringQ4_q1EndDate_returnsNull() {
        // We can only test the static helper directly, using dates that
        // match the current calendar.  This test is skipped when not in Q4.
        int month = LocalDate.now().getMonthValue();
        org.junit.jupiter.api.Assumptions.assumeTrue(month >= 10,
                "Skipped outside Q4 — cross-year booking window only open Oct–Dec");

        int nextYear = LocalDate.now().getYear() + 1;
        LocalDate start = LocalDate.of(LocalDate.now().getYear(), 12, 1);
        LocalDate end   = LocalDate.of(nextYear, 2, 28);

        VacationCreationService.WizardResult result =
                VacationCreationService.validateCrossYearWindow(start, end, false);
        assertNull(result, "Should return null (valid) during Q4 for a Q1 end date");
    }

    @Test
    void validateCrossYearWindow_endDateBeyondQ1_rejected() {
        // End-date cap check is not calendar-dependent — runs unconditionally.
        int nextYear = LocalDate.now().getYear() + 1;
        LocalDate start = LocalDate.of(LocalDate.now().getYear(), 12, 1);
        LocalDate end   = LocalDate.of(nextYear, 4, 15); // April — outside Q1

        // override=true so we reach the Q1-cap check regardless of current month
        VacationCreationService.WizardResult result =
                VacationCreationService.validateCrossYearWindow(start, end, true);
        assertNotNull(result, "Should reject end date in Q2 of next year");
        assertTrue(result.reply().contains("Q1") || result.reply().contains("March"),
                "Error message should mention Q1 / March");
    }

    @Test
    void validateCrossYearWindow_notQ4_advanceBookingRejected() {
        int month = LocalDate.now().getMonthValue();
        org.junit.jupiter.api.Assumptions.assumeTrue(month < 10,
                "Skipped during Q4 — test targets non-Q4 months");

        int nextYear = LocalDate.now().getYear() + 1;
        LocalDate start = LocalDate.of(LocalDate.now().getYear(), month, 1);
        LocalDate end   = LocalDate.of(nextYear, 1, 31);

        // override=false → Q4 check applies
        VacationCreationService.WizardResult result =
                VacationCreationService.validateCrossYearWindow(start, end, false);
        assertNotNull(result, "Should reject advance booking outside Q4");
        assertTrue(result.reply().contains("Q4") || result.reply().contains("October"),
                "Error message should mention Q4");
    }

    @Test
    void validateCrossYearWindow_override_true_skipsQ4Check() {
        // Unconditional: override=true must allow booking regardless of current month.
        int nextYear = LocalDate.now().getYear() + 1;
        // Use a start month that is never Q4 (January always satisfies month < 10).
        LocalDate start = LocalDate.of(LocalDate.now().getYear(), 1, 15);
        LocalDate end   = LocalDate.of(nextYear, 2, 28);

        VacationCreationService.WizardResult result =
                VacationCreationService.validateCrossYearWindow(start, end, true);
        assertNull(result,
                "override=true must bypass the Q4 check and return null (valid)");
    }

    @Test
    void validateCrossYearWindow_override_false_normalQ4EnforcedOutsideQ4() {
        int month = LocalDate.now().getMonthValue();
        org.junit.jupiter.api.Assumptions.assumeTrue(month < 10,
                "Skipped during Q4 — test targets non-Q4 months only");

        int nextYear = LocalDate.now().getYear() + 1;
        LocalDate start = LocalDate.of(LocalDate.now().getYear(), month, 1);
        LocalDate end   = LocalDate.of(nextYear, 1, 31);

        VacationCreationService.WizardResult result =
                VacationCreationService.validateCrossYearWindow(start, end, false);
        assertNotNull(result, "override=false outside Q4 must still block the booking");
    }

    @Test
    void handleFormSubmission_crossYearInQ4_advancesToConfirm() {
        int month = LocalDate.now().getMonthValue();
        org.junit.jupiter.api.Assumptions.assumeTrue(month >= 10,
                "Skipped outside Q4");

        int currentYear = LocalDate.now().getYear();
        int nextYear    = currentYear + 1;

        PendingVacation pv = new PendingVacation("add");
        pv.setEmployeeName("Alice Smith");
        pv.setState(PendingVacation.WizardState.NEED_DATES_FORM);

        String msg = "__VACATION_FORM__:V|" + currentYear + "-12-01|" + nextYear + "-02-28";
        VacationCreationService.WizardResult result =
                service.process(pv, msg, employees);

        // Must reach CONFIRM, not vacation_prompt error
        assertEquals("vacation_prompt", result.type());
        assertTrue(result.reply().contains("confirm") || result.reply().contains("Confirm")
                || result.reply().contains("yes"),
                "Should ask for confirmation");
        assertEquals(PendingVacation.WizardState.CONFIRM, pv.getState());
    }

    @Test
    void handleFormSubmission_crossYearOverride_allowsBookingOutsideQ4() {
        // Unconditional: with override=true the service must reach CONFIRM
        // regardless of the current calendar month.
        when(props.isCrossYearBookingOverride()).thenReturn(true);

        int currentYear = LocalDate.now().getYear();
        int nextYear    = currentYear + 1;

        PendingVacation pv = new PendingVacation("add");
        pv.setEmployeeName("Alice Smith");
        pv.setState(PendingVacation.WizardState.NEED_DATES_FORM);

        // Use January of current year as start so isPastDate fires if we're in a past month;
        // use first day of next month as start to ensure it's always in the future.
        LocalDate start = LocalDate.now().withDayOfMonth(1).plusMonths(1);
        LocalDate end   = LocalDate.of(nextYear, 2, 28);

        String msg = "__VACATION_FORM__:V|" + start + "|" + end;
        VacationCreationService.WizardResult result =
                service.process(pv, msg, employees);

        assertEquals(PendingVacation.WizardState.CONFIRM, pv.getState(),
                "override=true must reach CONFIRM regardless of current month");
        assertFalse(result.reply().contains("Q4"),
                "Should not show the Q4 restriction message when override is enabled");
    }
}
