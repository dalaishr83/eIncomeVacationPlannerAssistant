package com.holidayleave.assistant.controller;

import com.holidayleave.assistant.excel.PlannerExcelReader;
import com.holidayleave.assistant.service.AppState;
import com.holidayleave.assistant.service.SyncService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import javax.servlet.http.HttpSession;
import java.io.File;
import java.util.Collections;
import java.util.List;

/**
 * Main page controller — serves the role-appropriate page template.
 * Admin → admin/dashboard, Employee → employee-page.
 */
@Controller
public class IndexController {

    @Autowired private AppState appState;
    @Autowired private PlannerExcelReader reader;
    @Autowired private SyncService syncService;

    @GetMapping("/")
    public String index(Model model, HttpSession session) {
        // Initialize loaded files on first load if not set
        if (appState.getLoadedFiles().isEmpty()) {
            initLoadedFiles();
        }
        model.addAttribute("appTitle", "Holiday Leave Assistant");

        String employeeName = (String) session.getAttribute("employee_name");
        model.addAttribute("greetingName", employeeName);

        String loginUsername = (String) session.getAttribute("username");
        model.addAttribute("loginUsername", loginUsername);

        // Expose the active filename to every page template (dashboard, employee-page)
        List<String> active = appState.getActiveFiles();
        if (!active.isEmpty()) {
            model.addAttribute("activeFilename", new File(active.get(0)).getName());
        }

        String role = (String) session.getAttribute("role");
        if ("admin".equals(role)) {
            model.addAttribute("currentPage", "dashboard");
            return "admin/dashboard";
        }
        return "employee-page";
    }

    /**
     * Discovers all master Excel files and activates the current-calendar-year file
     * preferentially. Falls back to the newest file (sorted descending by name) when
     * no current-year file is available.
     */
    private void initLoadedFiles() {
        syncService.forceSync();
        List<String> paths = appState.discoverExcelPaths();
        if (paths.isEmpty()) return;

        // Prefer the current-year file; fall back to newest (paths[0] = newest after sort).
        String currentYearPath = appState.resolveCurrentYearFilePath();
        String activeFile = (currentYearPath != null && new File(currentYearPath).exists())
                ? currentYearPath
                : paths.get(0);

        appState.setLoadedFiles(paths);
        appState.setActiveFiles(Collections.singletonList(activeFile));
        appState.refreshKnownFiles();
    }
}
