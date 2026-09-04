package com.holidayleave.assistant.controller;

import com.holidayleave.assistant.service.SecretService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AuthController}.
 *
 * Covers: GET /login redirect for logged-in users, login page served for guests,
 * successful admin login, successful employee login, invalid credentials,
 * null hash / null username in credential entry, logout, and the internal
 * timingSafeEquals method (tested indirectly via resolveEntry).
 *
 * No Spring MVC context — controller methods are invoked directly.
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock  private SecretService secretService;
    @InjectMocks private AuthController controller;

    private MockHttpSession session;
    private Model           model;

    @BeforeEach
    void setUp() {
        session = new MockHttpSession();
        model   = new ConcurrentModel();
    }

    // =========================================================================
    // GET /login
    // =========================================================================

    @Test
    void loginPage_notLoggedIn_returnsLoginView() {
        String view = controller.loginPage(model, session);
        assertEquals("login", view);
    }

    @Test
    void loginPage_alreadyLoggedIn_redirectsToRoot() {
        session.setAttribute("logged_in", true);
        String view = controller.loginPage(model, session);
        assertEquals("redirect:/", view);
    }

    // =========================================================================
    // POST /login — successful login
    // =========================================================================

    @Test
    void doLogin_validAdminCredentials_redirectsToRootAndSetsSession() {
        String hash = BCrypt.hashpw("secret", BCrypt.gensalt());
        Map<String, String> entry = makeEntry("admin", hash, "admin", null);
        when(secretService.findByUsername("admin")).thenReturn(entry);

        String view = controller.doLogin("admin", "secret",
                new MockHttpServletRequest(), session, model);

        assertEquals("redirect:/", view);
        assertEquals(true,    session.getAttribute("logged_in"));
        assertEquals("admin", session.getAttribute("role"));
        assertEquals("admin", session.getAttribute("username"));
        assertNull(session.getAttribute("employee_name"));
        assertNotNull(session.getAttribute("session_id"));
    }

    @Test
    void doLogin_validEmployeeCredentials_setsEmployeeNameInSession() {
        String hash = BCrypt.hashpw("pass1234", BCrypt.gensalt());
        Map<String, String> entry = makeEntry("aliceSmith", hash, "employee", "Alice Smith");
        when(secretService.findByUsername("aliceSmith")).thenReturn(entry);

        controller.doLogin("aliceSmith", "pass1234",
                new MockHttpServletRequest(), session, model);

        assertEquals("employee",    session.getAttribute("role"));
        assertEquals("aliceSmith",  session.getAttribute("username"));
        assertEquals("Alice Smith", session.getAttribute("employee_name"));
    }

    @Test
    void doLogin_sessionIdIsRandomUuid_noHyphens() {
        String hash = BCrypt.hashpw("pw", BCrypt.gensalt());
        when(secretService.findByUsername("admin")).thenReturn(makeEntry("admin", hash, "admin", null));

        controller.doLogin("admin", "pw", new MockHttpServletRequest(), session, model);

        String sid = (String) session.getAttribute("session_id");
        assertNotNull(sid);
        assertFalse(sid.contains("-"), "session_id must not contain hyphens");
    }

    // =========================================================================
    // POST /login — failed login
    // =========================================================================

    @Test
    void doLogin_wrongPassword_returnsLoginViewWithError() {
        String hash = BCrypt.hashpw("correct", BCrypt.gensalt());
        when(secretService.findByUsername("admin")).thenReturn(makeEntry("admin", hash, "admin", null));

        String view = controller.doLogin("admin", "wrong",
                new MockHttpServletRequest(), session, model);

        assertEquals("login", view);
        assertNotNull(model.getAttribute("error"));
        assertNull(session.getAttribute("logged_in"));
    }

    @Test
    void doLogin_unknownUsername_returnsLoginViewWithError() {
        when(secretService.findByUsername("unknown")).thenReturn(null);

        String view = controller.doLogin("unknown", "pw",
                new MockHttpServletRequest(), session, model);

        assertEquals("login", view);
        assertNotNull(model.getAttribute("error"));
    }

    @Test
    void doLogin_nullHash_returnsLoginViewWithError() {
        Map<String, String> entry = makeEntry("admin", null, "admin", null);
        when(secretService.findByUsername("admin")).thenReturn(entry);

        String view = controller.doLogin("admin", "any",
                new MockHttpServletRequest(), session, model);

        assertEquals("login", view);
    }

    @Test
    void doLogin_nullStoredUsername_returnsLoginViewWithError() {
        String hash = BCrypt.hashpw("pw", BCrypt.gensalt());
        Map<String, String> entry = makeEntry(null, hash, "admin", null);
        when(secretService.findByUsername("admin")).thenReturn(entry);

        String view = controller.doLogin("admin", "pw",
                new MockHttpServletRequest(), session, model);

        assertEquals("login", view);
    }

    @Test
    void doLogin_usernameCaseMismatch_returnsLoginViewWithError() {
        // timingSafeEquals rejects "Admin" vs "admin" (lengths equal but chars differ)
        String hash = BCrypt.hashpw("pw", BCrypt.gensalt());
        Map<String, String> entry = makeEntry("admin", hash, "admin", null);
        when(secretService.findByUsername("Admin")).thenReturn(entry);

        String view = controller.doLogin("Admin", "pw",
                new MockHttpServletRequest(), session, model);

        assertEquals("login", view);
    }

    // =========================================================================
    // GET /logout
    // =========================================================================

    @Test
    void logout_invalidatesSessionAndRedirects() {
        session.setAttribute("logged_in", true);
        String view = controller.logout(session);
        assertEquals("redirect:/login", view);
        assertTrue(session.isInvalid());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Map<String, String> makeEntry(String username, String hash, String role, String employeeName) {
        Map<String, String> m = new HashMap<>();
        m.put("username",      username);
        m.put("hash",          hash);
        m.put("role",          role);
        m.put("employee_name", employeeName);
        return m;
    }
}
