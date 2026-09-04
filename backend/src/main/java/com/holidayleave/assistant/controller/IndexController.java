package com.holidayleave.assistant.controller;

import com.holidayleave.assistant.excel.PlannerExcelReader;
import com.holidayleave.assistant.service.AppState;
import com.holidayleave.assistant.service.SyncService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import javax.servlet.http.HttpSession;
import java.util.Collections;
import java.util.List;

/**
 * Main page controller — serves the role-appropriate page template.
 * Admin → admin-page.html, Employee → employee-page.html.
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

        String role = (String) session.getAttribute("role");
        if ("admin".equals(role)) {
            model.addAttribute("currentPage", "dashboard");
            return "admin/dashboard";
        }
        return "employee-page";
    }

    private void initLoadedFiles() {
        syncService.forceSync();
        List<String> paths = appState.discoverExcelPaths();
        if (!paths.isEmpty()) {
            appState.setLoadedFiles(paths);
            appState.setActiveFiles(Collections.singletonList(paths.get(0))); // newest first
            appState.refreshKnownFiles();
        }
    }
}
