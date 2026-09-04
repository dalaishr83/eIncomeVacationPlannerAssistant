package com.holidayleave.assistant.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import com.holidayleave.assistant.service.AuthInterceptor;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AuthInterceptor}.
 *
 * Covers:
 *  - Unauthenticated request to page  -> redirect to /login
 *  - Unauthenticated request to API   -> 401 + JSON error body
 *  - Authenticated non-admin to /admin page  -> redirect to /
 *  - Authenticated non-admin to /api/admin   -> 403 + JSON error body
 *  - Authenticated admin to /admin    -> preHandle returns true (allowed)
 *  - Authenticated employee to non-admin API -> preHandle returns true
 *  - /reports/ path treated as API path for error responses
 *  - Null session edge case
 */
class AuthInterceptorTest {

    private AuthInterceptor interceptor;
    private MockHttpServletResponse response;
    private Object handler = new Object(); // dummy handler

    @BeforeEach
    void setUp() {
        interceptor = new AuthInterceptor();
        response = new MockHttpServletResponse();
    }

    // =========================================================================
    // Unauthenticated
    // =========================================================================

    @Test
    void unauthenticated_pagePath_redirectsToLogin() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/");
        req.setSession(new MockHttpSession()); // no logged_in attribute

        boolean proceed = interceptor.preHandle(req, response, handler);

        assertFalse(proceed);
        assertEquals("/login", response.getRedirectedUrl());
    }

    @Test
    void unauthenticated_apiPath_returns401WithJson() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/chat");
        req.setSession(new MockHttpSession());

        boolean proceed = interceptor.preHandle(req, response, handler);

        assertFalse(proceed);
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("Unauthorised"));
    }

    @Test
    void unauthenticated_nullSession_redirectsToLogin() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/admin/settings");
        // No session set — getSession(false) will return null

        boolean proceed = interceptor.preHandle(req, response, handler);

        assertFalse(proceed);
        assertEquals("/login", response.getRedirectedUrl());
    }

    @Test
    void unauthenticated_reportsPath_returns401WithJson() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/reports/leave-2026.html");
        req.setSession(new MockHttpSession());

        boolean proceed = interceptor.preHandle(req, response, handler);

        assertFalse(proceed);
        assertEquals(401, response.getStatus());
        // /reports/ is treated as API path — response body should be JSON
        assertTrue(response.getContentAsString().contains("Unauthorised"));
    }

    // =========================================================================
    // Authenticated employee — non-admin paths
    // =========================================================================

    @Test
    void authenticatedEmployee_regularPath_allowed() throws Exception {
        MockHttpServletRequest req = requestWithSession("GET", "/", "employee", false);

        boolean proceed = interceptor.preHandle(req, response, handler);

        assertTrue(proceed);
    }

    @Test
    void authenticatedEmployee_apiPath_allowed() throws Exception {
        MockHttpServletRequest req = requestWithSession("POST", "/api/chat", "employee", false);

        boolean proceed = interceptor.preHandle(req, response, handler);

        assertTrue(proceed);
    }

    // =========================================================================
    // Authenticated employee — admin-only paths
    // =========================================================================

    @Test
    void authenticatedEmployee_adminPagePath_redirectsToRoot() throws Exception {
        MockHttpServletRequest req = requestWithSession("GET", "/admin/settings", "employee", false);

        boolean proceed = interceptor.preHandle(req, response, handler);

        assertFalse(proceed);
        assertEquals("/", response.getRedirectedUrl());
    }

    @Test
    void authenticatedEmployee_adminApiPath_returns403WithJson() throws Exception {
        MockHttpServletRequest req = requestWithSession("POST", "/api/admin/settings/restricted-types", "employee", false);

        boolean proceed = interceptor.preHandle(req, response, handler);

        assertFalse(proceed);
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("Forbidden"));
    }

    @Test
    void authenticatedEmployee_adminAuditLog_returns403() throws Exception {
        MockHttpServletRequest req = requestWithSession("GET", "/api/admin/audit-log", "employee", false);

        boolean proceed = interceptor.preHandle(req, response, handler);

        assertFalse(proceed);
        assertEquals(403, response.getStatus());
    }

    // =========================================================================
    // Authenticated admin — all paths
    // =========================================================================

    @Test
    void authenticatedAdmin_adminPagePath_allowed() throws Exception {
        MockHttpServletRequest req = requestWithSession("GET", "/admin/settings", "admin", true);

        boolean proceed = interceptor.preHandle(req, response, handler);

        assertTrue(proceed);
    }

    @Test
    void authenticatedAdmin_adminApiPath_allowed() throws Exception {
        MockHttpServletRequest req = requestWithSession("GET", "/api/admin/audit-log", "admin", true);

        boolean proceed = interceptor.preHandle(req, response, handler);

        assertTrue(proceed);
    }

    @Test
    void authenticatedAdmin_regularPath_allowed() throws Exception {
        MockHttpServletRequest req = requestWithSession("GET", "/", "admin", true);

        boolean proceed = interceptor.preHandle(req, response, handler);

        assertTrue(proceed);
    }

    // =========================================================================
    // Path prefix edge cases
    // =========================================================================

    @Test
    void adminPathPrefix_exactlyAdmin_isAdminOnly() throws Exception {
        MockHttpServletRequest req = requestWithSession("GET", "/admin", "employee", false);
        boolean proceed = interceptor.preHandle(req, response, handler);
        assertFalse(proceed);
    }

    @Test
    void adminPathPrefix_apiAdminFiles_isAdminOnly() throws Exception {
        MockHttpServletRequest req = requestWithSession("DELETE", "/api/admin/files", "employee", false);
        boolean proceed = interceptor.preHandle(req, response, handler);
        assertFalse(proceed);
        assertEquals(403, response.getStatus());
    }

    @Test
    void nonAdminApiPath_apiUpload_notAdminOnly() throws Exception {
        MockHttpServletRequest req = requestWithSession("POST", "/api/upload", "employee", false);
        boolean proceed = interceptor.preHandle(req, response, handler);
        assertTrue(proceed);
    }

    // =========================================================================
    // Helper
    // =========================================================================

    private MockHttpServletRequest requestWithSession(String method, String path,
                                                       String role, boolean isAdmin) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("logged_in", true);
        session.setAttribute("role", role);
        req.setSession(session);
        return req;
    }
}
