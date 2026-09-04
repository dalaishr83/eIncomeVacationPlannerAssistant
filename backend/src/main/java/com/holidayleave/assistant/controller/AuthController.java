package com.holidayleave.assistant.controller;

import com.holidayleave.assistant.service.AppState;
import com.holidayleave.assistant.service.SecretService;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Handles GET /login, POST /login, GET /logout.
 *
 * Credentials are loaded from SecretService (file-backed JSON, username-keyed).
 * On successful login the session receives:
 *   - logged_in     = true
 *   - role          = "admin" | "employee"
 *   - username      = the credential username (used for accurate audit logging)
 *   - employee_name = the full Excel name, or null for admin
 *   - session_id    = random UUID (used for pending-vacation keying)
 */
@Controller
public class AuthController {

    @Autowired
    private SecretService secretService;

    @Autowired
    private AppState appState;

    @GetMapping("/login")
    public String loginPage(Model model, HttpSession session) {
        if (Boolean.TRUE.equals(session.getAttribute("logged_in"))) {
            return "redirect:/";
        }
        return "login";
    }

    @PostMapping("/login")
    public String doLogin(@RequestParam String username,
                          @RequestParam String password,
                          HttpServletRequest request,
                          HttpSession session,
                          Model model) {

        Map<String, String> entry = resolveEntry(username, password);

        if (entry != null) {
            session.setAttribute("logged_in",     true);
            session.setAttribute("role",          entry.get("role"));
            session.setAttribute("username",      entry.get("username"));
            session.setAttribute("employee_name", entry.get("employee_name")); // null for admin
            session.setAttribute("session_id",    UUID.randomUUID().toString().replace("-", ""));
            return "redirect:/";
        }

        model.addAttribute("error", "Invalid username or password.");
        return "login";
    }

    @GetMapping("/logout")
    public String logout(HttpSession session) {
        String sessionId = (String) session.getAttribute("session_id");
        if (sessionId != null) {
            appState.removeSessionHistory(sessionId);
        }
        session.invalidate();
        return "redirect:/login";
    }

    /**
     * Looks up the submitted username directly in the credential store and verifies
     * the password with BCrypt. Returns the matching credential entry map, or null.
     */
    private Map<String, String> resolveEntry(String username, String password) {
        Map<String, String> entry = secretService.findByUsername(username);
        if (entry == null) return null;
        String storedUsername = entry.get("username");
        String storedHash     = entry.get("hash");
        if (storedUsername == null || storedHash == null) return null;
        if (!timingSafeEquals(username, storedUsername)) return null;
        try {
            if (BCrypt.checkpw(password, storedHash)) return entry;
        } catch (Exception ignored) {}
        return null;
    }

    /** Timing-safe string comparison to prevent timing attacks. */
    private boolean timingSafeEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
