package com.holidayleave.assistant.model;

import java.util.Map;

/**
 * One row in the Team Vacation Forecast report.
 * Represents one employee's aggregated vacation data across all selected months (pivot layout).
 * Each month in the selected date range is a key in {@link #monthVacations}; missing months hold 0.
 */
public final class ForecastRow {

    private final String employeeName;
    /**
     * Ordered map of "MMMM yyyy" month label → vacation days for that month.
     * Keys are present for every month in the requested date range (value may be 0).
     */
    private final Map<String, Double> monthVacations;
    /** Sum of all values in {@link #monthVacations}. */
    private final double totalVacations;

    public ForecastRow(String employeeName,
                       Map<String, Double> monthVacations,
                       double totalVacations) {
        this.employeeName   = employeeName;
        this.monthVacations = monthVacations;
        this.totalVacations = totalVacations;
    }

    public String getEmployeeName()              { return employeeName; }
    public Map<String, Double> getMonthVacations() { return monthVacations; }
    public double getTotalVacations()            { return totalVacations; }
}
