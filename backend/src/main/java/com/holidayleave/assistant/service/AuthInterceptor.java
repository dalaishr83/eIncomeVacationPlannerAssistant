package com.holidayleave.assistant.service;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces authentication and role-based authorization on every request.
 *
 * Rules:
 *  - All requests require session.logged_in = true, else 401 (API) / redirect to /login (page).
 *  - Routes under /admin/** and /api/admin/** additionally require session.role = "admin".
 *    Unauthorized API access returns 403; unauthorized page access redirects to /.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        HttpSession session  = request.getSession(false);
        boolean loggedIn     = session != null && Boolean.TRUE.equals(session.getAttribute("logged_in"));
        String  role         = session != null ? (String) session.getAttribute("role") : null;
        String  path         = request.getRequestURI();

        // ── 1. Authentication check ───────────────────────────────────────────
        if (!loggedIn) {
            if (isApiPath(path)) {
                response.setStatus(401);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"Unauthorised\"}");
            } else {
                response.sendRedirect("/login");
            }
            return false;
        }

        // ── 2. Admin-only authorization check ────────────────────────────────
        if (isAdminOnlyPath(path) && !"admin".equals(role)) {
            if (isApiPath(path)) {
                response.setStatus(403);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"Forbidden — admin access required\"}");
            } else {
                response.sendRedirect("/");
            }
            return false;
        }

        return true;
    }

    /** True for any path that requires admin role. */
    private boolean isAdminOnlyPath(String path) {
        return path.startsWith("/admin") || path.startsWith("/api/admin");
    }

    /** True for API and reports paths (return JSON errors instead of redirects). */
    private boolean isApiPath(String path) {
        return path.startsWith("/api/") || path.startsWith("/reports/");
    }
}
