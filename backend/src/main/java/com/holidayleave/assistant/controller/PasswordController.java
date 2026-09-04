package com.holidayleave.assistant.controller;

import com.holidayleave.assistant.service.SecretService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Provides a self-service password-change endpoint for logged-in employees.
 *
 * POST /api/change-password
 *   Body (JSON): { "currentPassword": "…", "newPassword": "…" }
 *   Response:    { "success": true }  or  { "error": "…" }
 *
 * The endpoint:
 *  - Reads the username from the active session (never from the request body).
 *  - Verifies the submitted current password against the stored BCrypt hash.
 *  - On success, re-hashes the new password with BCrypt and persists it.
 *  - Never stores plain-text passwords.
 */
@RestController
public class PasswordController {

    @Autowired
    private SecretService secretService;

    @PostMapping("/api/change-password")
    public ResponseEntity<Map<String, Object>> changePassword(
            @RequestBody Map<String, String> body,
            HttpSession session) {

        // 1. Require an active authenticated session.
        if (!Boolean.TRUE.equals(session.getAttribute("logged_in"))) {
            return ResponseEntity.status(401).body(err("Unauthorised"));
        }

        String username = (String) session.getAttribute("username");
        if (username == null) {
            return ResponseEntity.status(401).body(err("Unauthorised"));
        }

        String currentPassword = body.get("currentPassword");
        String newPassword     = body.get("newPassword");

        if (currentPassword == null || currentPassword.isEmpty() ||
                newPassword == null || newPassword.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(err("Both current and new passwords are required."));
        }

        // 2. Verify current password.
        Map<String, String> entry = secretService.findByUsername(username);
        if (entry == null) {
            return ResponseEntity.status(401).body(err("Unauthorised"));
        }

        String storedHash = entry.get("hash");
        boolean matches;
        try {
            matches = storedHash != null && BCrypt.checkpw(currentPassword, storedHash);
        } catch (Exception e) {
            matches = false;
        }

        if (!matches) {
            return ResponseEntity.status(400)
                    .body(err("Current password is incorrect."));
        }

        // 3. Persist the new hashed password.
        try {
            secretService.updatePassword(username, newPassword);
        } catch (Exception e) {
            return ResponseEntity.status(500)
                    .body(err("Failed to update password. Please try again."));
        }

        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("success", true);
        return ResponseEntity.ok(ok);
    }

    private static Map<String, Object> err(String message) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("error", message);
        return r;
    }
}
