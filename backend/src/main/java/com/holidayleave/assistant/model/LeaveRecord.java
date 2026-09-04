package com.holidayleave.assistant.model;

import java.time.LocalDate;

/**
 * Immutable core domain object. One leave entry parsed from Excel.
 */
public final class LeaveRecord {
    private final String employeeName;
    private final LocalDate startDate;
    private final LocalDate endDate;
    private final double days;
    private final String leaveType;
    private final String reason;
    private final int year;

    public LeaveRecord(String employeeName, LocalDate startDate, LocalDate endDate,
                       double days, String leaveType, String reason) {
        this.employeeName = employeeName;
        this.startDate    = startDate;
        this.endDate      = endDate;
        this.days         = days;
        this.leaveType    = leaveType;
        this.reason       = reason;
        this.year         = startDate.getYear();
    }

    public String employeeName() { return employeeName; }
    public LocalDate startDate() { return startDate; }
    public LocalDate endDate()   { return endDate; }
    public double days()         { return days; }
    public String leaveType()    { return leaveType; }
    public String reason()       { return reason; }
    public int year()            { return year; }
}
