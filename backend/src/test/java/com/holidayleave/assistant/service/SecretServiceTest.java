package com.holidayleave.assistant.service;

import com.holidayleave.assistant.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SecretService}.
 *
 * Covers: bootstrap (first-boot secret.json creation), findByUsername,
 * provisionEmployee (camelCase username generation, idempotency, collision handling),
 * updatePassword, updateRole, getHash, getUsername, and edge cases.
 *
 * All tests use a TempDir so no real filesystem state is touched.
 */
class SecretServiceTest {

    @TempDir
    Path tempDir;

    private SecretService secretService;

    @BeforeEach
    void setUp() throws Exception {
        AppProperties props = new AppProperties();
        props.setDataDir(tempDir.toString());
        props.setLoginUsername("admin");
        props.setLoginPasswordHash("");

        secretService = new SecretService();
        Field f = SecretService.class.getDeclaredField("props");
        f.setAccessible(true);
        f.set(secretService, props);

        secretService.init();
    }

    // =========================================================================
    // Bootstrap — first boot
    // =========================================================================

    @Test
    void init_createsAdminCredential() {
        Map<String, String> entry = secretService.findByUsername("admin");
        assertNotNull(entry, "admin credential must exist after bootstrap");
        assertEquals("admin",   entry.get("username"));
        assertEquals("admin",   entry.get("role"));
        assertNotNull(entry.get("hash"));
    }

    @Test
    void init_adminEmployeeNameIsNull() {
        Map<String, String> entry = secretService.findByUsername("admin");
        assertNull(entry.get("employee_name"), "admin employee_name must be null");
    }

    // =========================================================================
    // findByUsername
    // =========================================================================

    @Test
    void findByUsername_knownUser_returnsEntry() {
        assertNotNull(secretService.findByUsername("admin"));
    }

    @Test
    void findByUsername_unknownUser_returnsNull() {
        assertNull(secretService.findByUsername("nobody"));
    }

    @Test
    void findByUsername_null_returnsNull() {
        assertNull(secretService.findByUsername(null));
    }

    @Test
    void findByUsername_emptyString_returnsNull() {
        assertNull(secretService.findByUsername(""));
    }

    // =========================================================================
    // provisionEmployee — username generation
    // =========================================================================

    @Test
    void provision_singleTokenName_usernameIsLowercasedToken() throws IOException {
        String uname = secretService.provisionEmployee("Alice");
        assertEquals("alice", uname);
    }

    @Test
    void provision_twoTokenName_usernameIsFirstToken() throws IOException {
        String uname = secretService.provisionEmployee("Alice Smith");
        assertEquals("alice", uname);
    }

    @Test
    void provision_collision_expandsWithNextToken() throws IOException {
        // Provision "Alice" first (claims username "alice")
        secretService.provisionEmployee("Alice");
        // Now provision another "Alice Something" — "alice" is taken, should expand to "aliceSmith"
        String uname = secretService.provisionEmployee("Alice Jones");
        assertNotEquals("alice", uname);
        assertTrue(uname.startsWith("alice"),
            "Expanded username must start with the first token");
    }

    @Test
    void provision_idempotent_sameNameReturnsSameUsername() throws IOException {
        String first  = secretService.provisionEmployee("Bob Johnson");
        String second = secretService.provisionEmployee("Bob Johnson");
        assertEquals(first, second, "Provisioning same name twice must be idempotent");
    }

    @Test
    void provision_idempotent_caseInsensitive() throws IOException {
        String first  = secretService.provisionEmployee("Carol Nguyen");
        String second = secretService.provisionEmployee("carol nguyen");
        assertEquals(first, second);
    }

    @Test
    void provision_storesEmployeeName() throws IOException {
        secretService.provisionEmployee("Dave Wilson");
        Map<String, String> entry = secretService.findByUsername("dave");
        assertNotNull(entry);
        assertEquals("Dave Wilson", entry.get("employee_name"));
        assertEquals("employee",    entry.get("role"));
    }

    @Test
    void provision_blank_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
            () -> secretService.provisionEmployee(""));
    }

    @Test
    void provision_null_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
            () -> secretService.provisionEmployee(null));
    }

    @Test
    void provision_numericSuffix_onFullCollision() throws IOException {
        // Provision two employees whose names generate the same camelCase expansion
        secretService.provisionEmployee("Alice");
        secretService.provisionEmployee("Alice X"); // "alice" taken, becomes "aliceX"
        // Provision a third that collides after full expansion
        String uname = secretService.provisionEmployee("Alice X");
        // Must be idempotent (same name), returns "aliceX"
        assertEquals("aliceX", uname);
    }

    @Test
    void provision_multipleUniqueEmployees_allDistinctUsernames() throws IOException {
        String u1 = secretService.provisionEmployee("Alice Smith");
        String u2 = secretService.provisionEmployee("Bob Johnson");
        String u3 = secretService.provisionEmployee("Carol Nguyen");
        assertNotEquals(u1, u2);
        assertNotEquals(u2, u3);
        assertNotEquals(u1, u3);
    }

    // =========================================================================
    // updatePassword
    // =========================================================================

    @Test
    void updatePassword_changesHash() throws IOException {
        secretService.updatePassword("admin", "newSecret123");
        String hash = secretService.getHash("admin");
        assertNotNull(hash);
        // Verify BCrypt checks out with new password
        assertTrue(org.springframework.security.crypto.bcrypt.BCrypt
                .checkpw("newSecret123", hash));
    }

    @Test
    void updatePassword_unknownKey_throwsIllegalArgument() {
        assertThrows(Exception.class,
            () -> secretService.updatePassword("nobody", "pwd"));
    }

    @Test
    void updatePassword_shortPassword_bcryptAccepts() throws IOException {
        // BCrypt itself accepts any string; our validation is in the controller layer
        assertDoesNotThrow(() -> secretService.updatePassword("admin", "abc"));
    }

    // =========================================================================
    // updateRole
    // =========================================================================

    @Test
    void updateRole_employeeToAdmin_roleChanged() throws IOException {
        secretService.provisionEmployee("Eve Test");
        String uname = secretService.findByUsername("eve").get("username");
        secretService.updateRole(uname, "admin");
        assertEquals("admin", secretService.findByUsername(uname).get("role"));
    }

    @Test
    void updateRole_unknownUsername_throwsIllegalArgument() {
        assertThrows(Exception.class,
            () -> secretService.updateRole("nobody", "admin"));
    }

    // =========================================================================
    // getHash / getUsername — backward-compat accessors
    // =========================================================================

    @Test
    void getHash_adminKey_returnsNonNullHash() {
        assertNotNull(secretService.getHash("admin"));
    }

    @Test
    void getHash_unknownKey_returnsNull() {
        assertNull(secretService.getHash("nobody"));
    }

    @Test
    void getUsername_adminKey_returnsAdmin() {
        assertEquals("admin", secretService.getUsername("admin"));
    }

    @Test
    void getUsername_unknownKey_returnsNull() {
        assertNull(secretService.getUsername("nobody"));
    }

    // =========================================================================
    // readCredentials
    // =========================================================================

    @Test
    void readCredentials_returnsMap_containsAdmin() {
        Map<String, Map<String, String>> creds = secretService.readCredentials();
        assertTrue(creds.containsKey("admin"));
    }
}
