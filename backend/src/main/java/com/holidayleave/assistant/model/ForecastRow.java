package com.holidayleave.assistant.model;

/**
 * One row in the Team Vacation Forecast report.
 * Represents one employee's aggregated vacation data for a given month.
 */
public final class ForecastRow {

    private final String employeeName;
    private final String month;             // e.g. "January 2026"
    private final double totalVacations;    // days in the selected date range for this month
    private final double totalEntitled;     // full-year entitlement (sum of all records for the year)
    private final double totalConsumed;     // days consumed from year-start up to system date
    private final double totalRemaining;    // totalEntitled - totalConsumed
    private final double utilizationPct;   // (totalConsumed / totalEntitled) * 100, or 0 if entitled=0

    public ForecastRow(String employeeName, String month,
                       double totalVacations, double totalEntitled,
                       double totalConsumed, double totalRemaining,
                       double utilizationPct) {
        this.employeeName   = employeeName;
        this.month          = month;
        this.totalVacations = totalVacations;
        this.totalEntitled  = totalEntitled;
        this.totalConsumed  = totalConsumed;
        this.totalRemaining = totalRemaining;
        this.utilizationPct = utilizationPct;
    }

    public String getEmployeeName()   { return employeeName; }
    public String getMonth()          { return month; }
    public double getTotalVacations() { return totalVacations; }
    public double getTotalEntitled()  { return totalEntitled; }
    public double getTotalConsumed()  { return totalConsumed; }
    public double getTotalRemaining() { return totalRemaining; }
    public double getUtilizationPct() { return utilizationPct; }
}
