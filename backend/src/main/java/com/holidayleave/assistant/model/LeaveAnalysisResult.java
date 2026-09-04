package com.holidayleave.assistant.model;

import java.util.List;
import java.util.Map;

public final class LeaveAnalysisResult {
    private final String employeeName;
    private final int year;
    private final double entitlement;
    private final double consumed;
    private final double remaining;
    private final double utilizationPct;
    private final Map<Integer, Double> byMonth;
    private final Map<String, Double> byType;
    private final int longestStreak;
    private final double avgPerMonth;
    private final List<MonthlyTrend> monthlyTrend;

    public LeaveAnalysisResult(String employeeName, int year, double entitlement,
                                double consumed, double remaining, double utilizationPct,
                                Map<Integer, Double> byMonth, Map<String, Double> byType,
                                int longestStreak, double avgPerMonth, List<MonthlyTrend> monthlyTrend) {
        this.employeeName  = employeeName;
        this.year          = year;
        this.entitlement   = entitlement;
        this.consumed      = consumed;
        this.remaining     = remaining;
        this.utilizationPct = utilizationPct;
        this.byMonth       = byMonth;
        this.byType        = byType;
        this.longestStreak = longestStreak;
        this.avgPerMonth   = avgPerMonth;
        this.monthlyTrend  = monthlyTrend;
    }

    public String employeeName()        { return employeeName; }
    public int year()                   { return year; }
    public double entitlement()         { return entitlement; }
    public double consumed()            { return consumed; }
    public double remaining()           { return remaining; }
    public double utilizationPct()      { return utilizationPct; }
    public Map<Integer, Double> byMonth() { return byMonth; }
    public Map<String, Double> byType()   { return byType; }
    public int longestStreak()          { return longestStreak; }
    public double avgPerMonth()         { return avgPerMonth; }
    public List<MonthlyTrend> monthlyTrend() { return monthlyTrend; }

    public static final class MonthlyTrend {
        private final String yearMonth;
        private final double days;
        public MonthlyTrend(String yearMonth, double days) {
            this.yearMonth = yearMonth;
            this.days = days;
        }
        public String yearMonth() { return yearMonth; }
        public double days()      { return days; }
    }
}
