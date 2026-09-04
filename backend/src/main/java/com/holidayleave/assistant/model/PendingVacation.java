package com.holidayleave.assistant.model;

import java.time.LocalDate;

/**
 * In-progress wizard state for the vacation creation/deletion chat wizard.
 * Serialized to a plain map and stored server-side in AppState.pendingVacations[sessionId].
 */
public class PendingVacation {

    public enum WizardState {
        // Creation states
        IDLE, NEED_EMP, NEED_TYPE, NEED_START, NEED_END, NEED_DATES_FORM, CONFIRM, SAVED, CANCELLED,
        // Deletion states
        DELETE_IDLE, DELETE_NEED_EMP, DELETE_NEED_START, DELETE_NEED_END, DELETE_NEED_DATES_FORM, DELETE_CONFIRM, DELETED, DELETE_CANCELLED
    }

    private WizardState state;
    private String employeeName;
    private String leaveType;   // label
    private String leaveCode;   // short code
    private LocalDate startDate;
    private LocalDate endDate;
    private double days;
    private String reason;
    private String wizardType;  // "add" or "delete"

    public PendingVacation() {}

    public PendingVacation(String wizardType) {
        this.wizardType = wizardType;
        this.state = wizardType.equals("delete") ? WizardState.DELETE_IDLE : WizardState.IDLE;
        this.days = 0.0;
    }

    // Getters and setters
    public WizardState getState() { return state; }
    public void setState(WizardState state) { this.state = state; }

    public String getEmployeeName() { return employeeName; }
    public void setEmployeeName(String employeeName) { this.employeeName = employeeName; }

    public String getLeaveType() { return leaveType; }
    public void setLeaveType(String leaveType) { this.leaveType = leaveType; }

    public String getLeaveCode() { return leaveCode; }
    public void setLeaveCode(String leaveCode) { this.leaveCode = leaveCode; }

    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }

    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }

    public double getDays() { return days; }
    public void setDays(double days) { this.days = days; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getWizardType() { return wizardType; }
    public void setWizardType(String wizardType) { this.wizardType = wizardType; }
}
