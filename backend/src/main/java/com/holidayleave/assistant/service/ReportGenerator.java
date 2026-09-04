package com.holidayleave.assistant.service;

import com.holidayleave.assistant.analysis.LeaveAnalysisService;
import com.holidayleave.assistant.model.LeaveAnalysisResult;
import com.holidayleave.assistant.model.LeaveRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates self-contained HTML leave report files.
 * Output: REPORT_DIR/<employee_name_snake>_<year>_leave_report.html
 */
@Service
public class ReportGenerator {

    @Autowired
    private LeaveAnalysisService analysisService;

    @Autowired
    private AppState appState;

    private static final DateTimeFormatter HUMAN_DATE =
            DateTimeFormatter.ofPattern("dd MMM yyyy");
    private static final DateTimeFormatter HUMAN_DATETIME =
            DateTimeFormatter.ofPattern("dd MMMM yyyy, HH:mm");

    /**
     * Generate an HTML report for an employee and year.
     * @return absolute path to the generated report file
     */
    public String generate(List<LeaveRecord> allRecords, String employeeName, int year) throws IOException {
        List<LeaveRecord> records = allRecords.stream()
                .filter(r -> r.employeeName().equalsIgnoreCase(employeeName) && r.year() == year)
                .sorted(Comparator.comparing(LeaveRecord::startDate))
                .collect(Collectors.toList());

        LeaveAnalysisResult analysis = analysisService.analyse(allRecords, employeeName, year);
        double consumed = analysisService.consumedToDate(records);
        double remaining = Math.max(0, analysis.entitlement() - consumed);
        double utilPct = analysis.entitlement() > 0 ? (consumed / analysis.entitlement()) * 100 : 0;

        String html = buildHtml(employeeName, year, records, analysis, consumed, remaining, utilPct);

        String fileName = employeeName.toLowerCase().replaceAll("[^a-z0-9]+", "_")
                + "_" + year + "_leave_report.html";
        Path outPath = Paths.get(appState.getReportsDir(), fileName);
        Files.createDirectories(outPath.getParent());
        java.io.BufferedWriter bw = new java.io.BufferedWriter(
            new java.io.OutputStreamWriter(new java.io.FileOutputStream(outPath.toFile()), java.nio.charset.StandardCharsets.UTF_8));
        try { bw.write(html); } finally { bw.close(); }

        return outPath.toAbsolutePath().toString();
    }

    // ── HTML builder ──────────────────────────────────────────────────────────

    private String buildHtml(String name, int year, List<LeaveRecord> records,
                              LeaveAnalysisResult analysis, double consumed,
                              double remaining, double utilPct) {

        String generatedAt = LocalDateTime.now().format(HUMAN_DATETIME);

        // ── Monthly breakdown rows (Month | Days | Types | Cumulative) ────────
        Map<Integer, Double> byMonth = analysis.byMonth();
        Map<Integer, String> typesPerMonth = buildTypesPerMonth(records);

        StringBuilder monthRows = new StringBuilder();
        double cumulative = 0.0;
        for (int m = 1; m <= 12; m++) {
            double days = byMonth.getOrDefault(m, 0.0);
            if (days <= 0) continue;
            cumulative += days;
            String monthName = java.time.Month.of(m).getDisplayName(TextStyle.FULL, Locale.ENGLISH);
            String types = typesPerMonth.getOrDefault(m, "");
            monthRows.append(String.format(
                "<tr><td>%s</td><td>%.1f</td><td>%s</td><td>%.1f</td></tr>%n",
                monthName, days, escHtml(types), cumulative));
        }

        // ── Leave detail rows ─────────────────────────────────────────────────
        StringBuilder detailRows = new StringBuilder();
        for (LeaveRecord r : records) {
            String reason = (r.reason() != null && !r.reason().trim().isEmpty()) ? escHtml(r.reason()) : "&mdash;";
            String badgeCls = badgeClass(r.leaveType());
            detailRows.append(String.format(
                "<tr><td>%s</td><td>%s</td><td>%.1f</td><td><span class='badge %s'>%s</span></td><td>%s</td></tr>%n",
                r.startDate().format(HUMAN_DATE),
                r.endDate().format(HUMAN_DATE),
                r.days(),
                badgeCls, escHtml(r.leaveType()),
                reason));
        }

        // ── Annual summary row ────────────────────────────────────────────────
        String annualRow = String.format(
            "<tr><td>%d</td><td>%.0f</td><td>%.1f</td><td>%.1f</td>"
            + "<td style='color:#16a34a;font-weight:700'>%.1f%%</td></tr>",
            year, analysis.entitlement(), consumed, remaining, utilPct);

        // ── Chart.js data — bar (non-zero months only) ────────────────────────
        List<String> barLabels = new ArrayList<>();
        List<String> barValues = new ArrayList<>();
        for (int m = 1; m <= 12; m++) {
            double days = byMonth.getOrDefault(m, 0.0);
            if (days <= 0) continue;
            barLabels.add("\"" + java.time.Month.of(m).getDisplayName(TextStyle.FULL, Locale.ENGLISH) + "\"");
            barValues.add(String.format("%.2f", days));
        }

        // ── Chart.js data — type doughnut ─────────────────────────────────────
        List<String> typeLabels = new ArrayList<>();
        List<String> typeValues = new ArrayList<>();
        analysis.byType().forEach((type, days) -> {
            typeLabels.add("\"" + escJs(type) + "\"");
            typeValues.add(String.format("%.2f", days));
        });

        // ── Chart.js data — monthly trend line ───────────────────────────────
        List<String> trendLabels = new ArrayList<>();
        List<String> trendValues = new ArrayList<>();
        for (LeaveAnalysisResult.MonthlyTrend t : analysis.monthlyTrend()) {
            trendLabels.add("\"" + escJs(t.yearMonth()) + "\"");
            trendValues.add(String.format("%.2f", t.days()));
        }

        // ── Progress bar width (capped at 100 for display) ───────────────────
        double barWidth = Math.min(utilPct, 100.0);

        // ── Build HTML ────────────────────────────────────────────────────────
        StringBuilder sb = new StringBuilder();

        sb.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n");
        sb.append("<meta charset=\"UTF-8\"/>\n");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"/>\n");
        sb.append("<title>Leave Report \u2014 ").append(escHtml(name)).append(" (").append(year).append(")</title>\n");

        // ── CSS ───────────────────────────────────────────────────────────────
        sb.append("<style>\n");
        sb.append("  *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }\n");
        sb.append("  html { font-size: 15px; }\n");
        sb.append("  body { font-family: 'Segoe UI', system-ui, -apple-system, sans-serif;\n");
        sb.append("         background: #f0f4f8; color: #1e293b; line-height: 1.65; }\n");
        sb.append("  .wrapper { max-width: 1100px; margin: 0 auto; padding: 2rem 1.5rem 4rem; }\n");
        sb.append("\n");
        sb.append("  /* Header */\n");
        sb.append("  .report-header { background: #0f2942; color: #fff; border-radius: 14px;\n");
        sb.append("    padding: 2.5rem 2.8rem; margin-bottom: 2rem; position: relative; overflow: hidden; }\n");
        sb.append("  .report-header::after { content: ''; position: absolute; right: 0; top: 0; bottom: 0;\n");
        sb.append("    width: 5px; background: linear-gradient(to bottom, #38bdf8, #6366f1); }\n");
        sb.append("  .report-header h1 { font-size: 1.9rem; font-weight: 700; margin-bottom: 0.3rem; }\n");
        sb.append("  .report-header .meta { font-size: 0.85rem; opacity: 0.72; display: flex; gap: 2rem; flex-wrap: wrap; }\n");
        sb.append("  .report-header .meta span strong { margin-right: 0.3rem; }\n");
        sb.append("\n");
        sb.append("  /* KPI cards */\n");
        sb.append("  .kpi-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));\n");
        sb.append("               gap: 1rem; margin-bottom: 2rem; }\n");
        sb.append("  .kpi-card { background: #fff; border-radius: 10px; padding: 1.2rem 1.4rem;\n");
        sb.append("               box-shadow: 0 2px 8px rgba(0,0,0,0.06); }\n");
        sb.append("  .kpi-card .kpi-label { font-size: 0.72rem; text-transform: uppercase;\n");
        sb.append("                          letter-spacing: 0.09em; color: #64748b; margin-bottom: 0.3rem; }\n");
        sb.append("  .kpi-card .kpi-value { font-size: 2rem; font-weight: 700; color: #0f2942; line-height: 1; }\n");
        sb.append("  .kpi-card .kpi-sub   { font-size: 0.78rem; color: #94a3b8; margin-top: 0.2rem; }\n");
        sb.append("  .kpi-card.accent { background: #0f2942; }\n");
        sb.append("  .kpi-card.accent .kpi-label { color: #93c5fd; }\n");
        sb.append("  .kpi-card.accent .kpi-value { color: #fff; }\n");
        sb.append("  .kpi-card.accent .kpi-sub   { color: #93c5fd; }\n");
        sb.append("\n");
        sb.append("  /* Progress bar */\n");
        sb.append("  .progress-wrap { background: #fff; border-radius: 10px; padding: 1.2rem 1.5rem;\n");
        sb.append("                    margin-bottom: 2rem; box-shadow: 0 2px 8px rgba(0,0,0,0.06); }\n");
        sb.append("  .progress-label { font-size: 0.85rem; color: #475569; margin-bottom: 0.5rem;\n");
        sb.append("                     display: flex; justify-content: space-between; }\n");
        sb.append("  .progress-bar  { height: 14px; background: #e2e8f0; border-radius: 99px; overflow: hidden; }\n");
        sb.append("  .progress-fill { height: 100%; border-radius: 99px; background: #16a34a; }\n");
        sb.append("\n");
        sb.append("  /* Section cards */\n");
        sb.append("  .card { background: #fff; border-radius: 12px; padding: 1.6rem 1.8rem;\n");
        sb.append("           margin-bottom: 1.8rem; box-shadow: 0 2px 8px rgba(0,0,0,0.06); }\n");
        sb.append("  .card h2 { font-size: 1.1rem; font-weight: 700; color: #0f2942;\n");
        sb.append("              border-bottom: 3px solid #38bdf8; padding-bottom: 0.45rem; margin-bottom: 1.2rem; }\n");
        sb.append("\n");
        sb.append("  /* Tables */\n");
        sb.append("  .data-table { width: 100%; border-collapse: collapse; font-size: 0.88rem; }\n");
        sb.append("  .data-table thead tr { background: #0f2942; color: #fff; }\n");
        sb.append("  .data-table th { padding: 0.6rem 0.9rem; text-align: left; font-weight: 600; }\n");
        sb.append("  .data-table td { padding: 0.55rem 0.9rem; border-bottom: 1px solid #e2e8f0; }\n");
        sb.append("  .data-table tbody tr:nth-child(even) { background: #f8fafc; }\n");
        sb.append("  .data-table tbody tr:hover { background: #eff6ff; }\n");
        sb.append("\n");
        sb.append("  /* Leave-type badges */\n");
        sb.append("  .badge { display: inline-block; border-radius: 5px; font-size: 0.72rem; font-weight: 700;\n");
        sb.append("            padding: 0.15rem 0.5rem; text-transform: uppercase; letter-spacing: 0.04em; }\n");
        sb.append("  .badge-annual    { background: #dbeafe; color: #1d4ed8; }\n");
        sb.append("  .badge-sick      { background: #fee2e2; color: #991b1b; }\n");
        sb.append("  .badge-casual    { background: #fef9c3; color: #854d0e; }\n");
        sb.append("  .badge-vacation  { background: #d1fae5; color: #065f46; }\n");
        sb.append("  .badge-public    { background: #fce7f3; color: #9d174d; }\n");
        sb.append("  .badge-personal  { background: #fff7ed; color: #9a3412; }\n");
        sb.append("  .badge-education { background: #ede9fe; color: #5b21b6; }\n");
        sb.append("  .badge-halfday   { background: #f0fdf4; color: #166534; }\n");
        sb.append("  .badge-other     { background: #f1f5f9; color: #475569; }\n");
        sb.append("\n");
        sb.append("  /* Charts grid */\n");
        sb.append("  .chart-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));\n");
        sb.append("                 gap: 1.5rem; margin-bottom: 2rem; }\n");
        sb.append("  .chart-card { background: #fff; border-radius: 12px; padding: 1.4rem;\n");
        sb.append("                 box-shadow: 0 2px 8px rgba(0,0,0,0.06); }\n");
        sb.append("  .chart-card h3 { font-size: 0.9rem; font-weight: 700; color: #0f2942; margin-bottom: 1rem; }\n");
        sb.append("\n");
        sb.append("  /* Footer */\n");
        sb.append("  footer { text-align: center; font-size: 0.76rem; color: #94a3b8;\n");
        sb.append("            padding: 1.5rem 0 0; border-top: 1px solid #e2e8f0; margin-top: 1.5rem; }\n");
        sb.append("\n");
        sb.append("  @media print {\n");
        sb.append("    body { background: #fff; font-size: 11pt; }\n");
        sb.append("    .wrapper { max-width: 100%; padding: 0; }\n");
        sb.append("    .report-header { border-radius: 0; -webkit-print-color-adjust: exact; print-color-adjust: exact; }\n");
        sb.append("    .card { box-shadow: none; border: 1px solid #e2e8f0; page-break-inside: avoid; }\n");
        sb.append("    .chart-grid { display: none; }\n");
        sb.append("  }\n");
        sb.append("  @media (max-width: 620px) {\n");
        sb.append("    .report-header { padding: 1.5rem 1.2rem; }\n");
        sb.append("    .report-header h1 { font-size: 1.4rem; }\n");
        sb.append("    .kpi-grid { grid-template-columns: repeat(2, 1fr); }\n");
        sb.append("  }\n");
        sb.append("</style>\n");
        sb.append("</head>\n<body>\n");
        sb.append("<div class=\"wrapper\">\n\n");

        // ── Header ────────────────────────────────────────────────────────────
        sb.append("  <!-- HEADER -->\n");
        sb.append("  <div class=\"report-header\">\n");
        sb.append("    <h1>Leave Report &mdash; ").append(escHtml(name)).append("</h1>\n");
        sb.append("    <div class=\"meta\">\n");
        sb.append("      <span><strong>Year:</strong> ").append(year).append("</span>\n");
        sb.append("      <span><strong>Generated:</strong> ").append(generatedAt).append("</span>\n");
        sb.append(String.format("      <span><strong>Entitlement:</strong> %.0f days</span>%n", analysis.entitlement()));
        sb.append("    </div>\n");
        sb.append("  </div>\n\n");

        // ── KPI Cards ─────────────────────────────────────────────────────────
        sb.append("  <!-- KPI CARDS -->\n");
        sb.append("  <div class=\"kpi-grid\">\n");
        sb.append(String.format("    <div class=\"kpi-card accent\">%n"));
        sb.append(String.format("      <div class=\"kpi-label\">Consumed</div>%n"));
        sb.append(String.format("      <div class=\"kpi-value\">%.0f</div>%n", consumed));
        sb.append(String.format("      <div class=\"kpi-sub\">days taken</div>%n"));
        sb.append(String.format("    </div>%n"));
        sb.append(String.format("    <div class=\"kpi-card\">%n"));
        sb.append(String.format("      <div class=\"kpi-label\">Remaining</div>%n"));
        sb.append(String.format("      <div class=\"kpi-value\">%.0f</div>%n", remaining));
        sb.append(String.format("      <div class=\"kpi-sub\">days left</div>%n"));
        sb.append(String.format("    </div>%n"));
        sb.append(String.format("    <div class=\"kpi-card\">%n"));
        sb.append(String.format("      <div class=\"kpi-label\">Utilization</div>%n"));
        sb.append(String.format("      <div class=\"kpi-value\" style=\"color:#16a34a\">%.1f%%</div>%n", utilPct));
        sb.append(String.format("      <div class=\"kpi-sub\">of entitlement used</div>%n"));
        sb.append(String.format("    </div>%n"));
        sb.append(String.format("    <div class=\"kpi-card\">%n"));
        sb.append(String.format("      <div class=\"kpi-label\">Entitlement</div>%n"));
        sb.append(String.format("      <div class=\"kpi-value\">%.0f</div>%n", analysis.entitlement()));
        sb.append(String.format("      <div class=\"kpi-sub\">days per year</div>%n"));
        sb.append(String.format("    </div>%n"));
        sb.append(String.format("    <div class=\"kpi-card\">%n"));
        sb.append(String.format("      <div class=\"kpi-label\">Avg / Month</div>%n"));
        sb.append(String.format("      <div class=\"kpi-value\">%.1f</div>%n", analysis.avgPerMonth()));
        sb.append(String.format("      <div class=\"kpi-sub\">days on average</div>%n"));
        sb.append(String.format("    </div>%n"));
        sb.append(String.format("    <div class=\"kpi-card\">%n"));
        sb.append(String.format("      <div class=\"kpi-label\">Longest Streak</div>%n"));
        sb.append(String.format("      <div class=\"kpi-value\">%d</div>%n", analysis.longestStreak()));
        sb.append(String.format("      <div class=\"kpi-sub\">consecutive days</div>%n"));
        sb.append(String.format("    </div>%n"));
        sb.append("  </div>\n\n");

        // ── Progress Bar ──────────────────────────────────────────────────────
        sb.append("  <!-- PROGRESS BAR -->\n");
        sb.append("  <div class=\"progress-wrap\">\n");
        sb.append("    <div class=\"progress-label\">\n");
        sb.append(String.format("      <span>Leave utilization: <strong>%.0f</strong> of <strong>%.0f</strong> days used</span>%n",
                consumed, analysis.entitlement()));
        sb.append(String.format("      <span><strong>%.1f%%</strong></span>%n", utilPct));
        sb.append("    </div>\n");
        sb.append(String.format("    <div class=\"progress-bar\"><div class=\"progress-fill\" style=\"width:%.1f%%\"></div></div>%n", barWidth));
        sb.append("  </div>\n\n");

        // ── Charts ────────────────────────────────────────────────────────────
        sb.append("  <!-- CHARTS -->\n");
        sb.append("  <div class=\"chart-grid\">\n");
        sb.append("    <div class=\"chart-card\">\n");
        sb.append("      <h3>Leave by Month</h3>\n");
        sb.append("      <canvas id=\"monthChart\" height=\"200\"></canvas>\n");
        sb.append("    </div>\n");
        sb.append("    <div class=\"chart-card\">\n");
        sb.append("      <h3>Leave by Type</h3>\n");
        sb.append("      <canvas id=\"typeChart\" height=\"200\"></canvas>\n");
        sb.append("    </div>\n");
        sb.append("    <div class=\"chart-card\" style=\"grid-column: 1 / -1;\">\n");
        sb.append("      <h3>Monthly Trend</h3>\n");
        sb.append("      <canvas id=\"trendChart\" height=\"120\"></canvas>\n");
        sb.append("    </div>\n");
        sb.append("  </div>\n\n");

        // ── Monthly Breakdown Table ───────────────────────────────────────────
        sb.append("  <!-- MONTHLY TABLE -->\n");
        sb.append("  <div class=\"card\">\n");
        sb.append("    <h2>Monthly Breakdown &mdash; ").append(year).append("</h2>\n");
        sb.append("    <table class=\"data-table\">\n");
        sb.append("      <thead><tr><th>Month</th><th>Leave Days Taken</th><th>Leave Types</th><th>Cumulative</th></tr></thead>\n");
        sb.append("      <tbody>\n").append(monthRows).append("      </tbody>\n");
        sb.append("    </table>\n");
        sb.append("  </div>\n\n");

        // ── Annual Summary ────────────────────────────────────────────────────
        sb.append("  <!-- ANNUAL SUMMARY -->\n");
        sb.append("  <div class=\"card\">\n");
        sb.append("    <h2>Annual Summary</h2>\n");
        sb.append("    <table class=\"data-table\">\n");
        sb.append("      <thead><tr><th>Year</th><th>Total Entitlement</th><th>Consumed</th><th>Remaining</th><th>Utilization</th></tr></thead>\n");
        sb.append("      <tbody><tr>").append(annualRow).append("</tr></tbody>\n");
        sb.append("    </table>\n");
        sb.append("  </div>\n\n");

        // ── Leave Details ─────────────────────────────────────────────────────
        sb.append("  <!-- LEAVE DETAILS -->\n");
        sb.append("  <div class=\"card\">\n");
        sb.append("    <h2>Leave Details &mdash; ").append(year).append("</h2>\n");
        sb.append("    <table class=\"data-table\">\n");
        sb.append("      <thead><tr><th>Start Date</th><th>End Date</th><th>Days</th><th>Leave Type</th><th>Reason</th></tr></thead>\n");
        sb.append("      <tbody>\n").append(detailRows).append("      </tbody>\n");
        sb.append("    </table>\n");
        sb.append("  </div>\n\n");

        // ── Footer ────────────────────────────────────────────────────────────
        sb.append("  <footer>\n");
        sb.append("    Holiday Leave Assistant &mdash; Report generated ").append(generatedAt);
        sb.append(" &nbsp;&bull;&nbsp; Made with IBM Bob\n");
        sb.append("  </footer>\n");
        sb.append("</div>\n\n");

        // ── Chart.js (end of body for performance) ────────────────────────────
        sb.append("<script src=\"https://cdn.jsdelivr.net/npm/chart.js@4.4.3/dist/chart.umd.min.js\"></script>\n");
        sb.append("<script>\n");
        sb.append("(function() {\n");
        sb.append("  var COLORS = [\n");
        sb.append("    '#0369a1','#0891b2','#0d9488','#059669','#16a34a',\n");
        sb.append("    '#ca8a04','#dc2626','#7c3aed','#db2777','#ea580c'\n");
        sb.append("  ];\n\n");

        // Bar chart
        sb.append("  var mCtx = document.getElementById('monthChart');\n");
        sb.append("  if (mCtx) {\n");
        sb.append("    new Chart(mCtx, {\n");
        sb.append("      type: 'bar',\n");
        sb.append("      data: {\n");
        sb.append("        labels: [").append(String.join(",", barLabels)).append("],\n");
        sb.append("        datasets: [{\n");
        sb.append("          label: 'Leave Days',\n");
        sb.append("          data: [").append(String.join(",", barValues)).append("],\n");
        sb.append("          backgroundColor: '#0369a1',\n");
        sb.append("          borderRadius: 5\n");
        sb.append("        }]\n");
        sb.append("      },\n");
        sb.append("      options: {\n");
        sb.append("        responsive: true,\n");
        sb.append("        plugins: { legend: { display: false } },\n");
        sb.append("        scales: { y: { beginAtZero: true, ticks: { stepSize: 1 } } }\n");
        sb.append("      }\n");
        sb.append("    });\n");
        sb.append("  }\n\n");

        // Doughnut chart
        sb.append("  var tCtx = document.getElementById('typeChart');\n");
        sb.append("  if (tCtx) {\n");
        sb.append("    new Chart(tCtx, {\n");
        sb.append("      type: 'doughnut',\n");
        sb.append("      data: {\n");
        sb.append("        labels: [").append(String.join(",", typeLabels)).append("],\n");
        sb.append("        datasets: [{\n");
        sb.append("          data: [").append(String.join(",", typeValues)).append("],\n");
        sb.append("          backgroundColor: COLORS.slice(0, ").append(typeLabels.size()).append("),\n");
        sb.append("          borderWidth: 2,\n");
        sb.append("          borderColor: '#fff'\n");
        sb.append("        }]\n");
        sb.append("      },\n");
        sb.append("      options: {\n");
        sb.append("        responsive: true,\n");
        sb.append("        plugins: { legend: { position: 'bottom', labels: { boxWidth: 12 } } }\n");
        sb.append("      }\n");
        sb.append("    });\n");
        sb.append("  }\n\n");

        // Trend line chart
        sb.append("  var trCtx = document.getElementById('trendChart');\n");
        sb.append("  if (trCtx) {\n");
        sb.append("    new Chart(trCtx, {\n");
        sb.append("      type: 'line',\n");
        sb.append("      data: {\n");
        sb.append("        labels: [").append(String.join(",", trendLabels)).append("],\n");
        sb.append("        datasets: [{\n");
        sb.append("          label: 'Leave Days',\n");
        sb.append("          data: [").append(String.join(",", trendValues)).append("],\n");
        sb.append("          borderColor: '#0369a1',\n");
        sb.append("          backgroundColor: 'rgba(3,105,161,0.1)',\n");
        sb.append("          fill: true,\n");
        sb.append("          tension: 0.3,\n");
        sb.append("          pointRadius: 4\n");
        sb.append("        }]\n");
        sb.append("      },\n");
        sb.append("      options: {\n");
        sb.append("        responsive: true,\n");
        sb.append("        plugins: { legend: { display: false } },\n");
        sb.append("        scales: { y: { beginAtZero: true } }\n");
        sb.append("      }\n");
        sb.append("    });\n");
        sb.append("  }\n");
        sb.append("})();\n");
        sb.append("</script>\n");
        sb.append("</body>\n</html>");

        return sb.toString();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns a comma-separated string of distinct leave types for each month.
     * A record is attributed to every month its date span overlaps, so cross-month
     * records (e.g. Oct 28 – Nov 3) correctly appear in both October and November.
     */
    private Map<Integer, String> buildTypesPerMonth(List<LeaveRecord> records) {
        Map<Integer, LinkedHashSet<String>> result = new TreeMap<>();
        for (LeaveRecord r : records) {
            int startMonth = r.startDate().getMonthValue();
            int endMonth   = r.endDate().getMonthValue();
            for (int m = startMonth; m <= endMonth; m++) {
                result.computeIfAbsent(m, k -> new LinkedHashSet<>()).add(r.leaveType());
            }
        }
        Map<Integer, String> out = new TreeMap<>();
        result.forEach((m, types) -> out.put(m, String.join(", ", types)));
        return out;
    }

    /** Maps a leave-type name to its CSS badge class. */
    private String badgeClass(String leaveType) {
        if (leaveType == null) return "badge-other";
        String lower = leaveType.toLowerCase();
        if (lower.contains("vacation"))   return "badge-vacation";
        if (lower.contains("public"))     return "badge-public";
        if (lower.contains("personal"))   return "badge-personal";
        if (lower.contains("sick"))       return "badge-sick";
        if (lower.contains("annual"))     return "badge-annual";
        if (lower.contains("casual"))     return "badge-casual";
        if (lower.contains("education"))  return "badge-education";
        if (lower.contains("half"))       return "badge-halfday";
        return "badge-other";
    }

    private String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String escJs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'");
    }
}
