package com.holidayleave.assistant.controller;

import com.holidayleave.assistant.excel.PlannerExcelReader;
import com.holidayleave.assistant.service.AppState;
import com.holidayleave.assistant.service.SyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link IndexController}.
 *
 * Covers:
 *  - Admin role   → returns "admin/dashboard"
 *  - Employee role → returns "employee-page"
 *  - No role (null) → returns "employee-page"
 *  - initLoadedFiles() called only when loadedFiles is empty
 *  - initLoadedFiles() NOT called when loadedFiles already populated
 *  - Model attributes: appTitle, greetingName, loginUsername, currentPage
 *  - discoverExcelPaths empty → setLoadedFiles/setActiveFiles NOT called
 *  - discoverExcelPaths non-empty → setLoadedFiles, setActiveFiles, refreshKnownFiles called
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IndexControllerTest {

    @Mock private AppState appState;
    @Mock private PlannerExcelReader reader;
    @Mock private SyncService syncService;

    @InjectMocks
    private IndexController controller;

    private MockHttpSession adminSession;
    private MockHttpSession employeeSession;
    private MockHttpSession noRoleSession;
    private Model model;

    @BeforeEach
    void setUp() {
        model = new ExtendedModelMap();

        adminSession = new MockHttpSession();
        adminSession.setAttribute("role", "admin");
        adminSession.setAttribute("username", "admin-user");
        adminSession.setAttribute("employee_name", "Admin Person");

        employeeSession = new MockHttpSession();
        employeeSession.setAttribute("role", "employee");
        employeeSession.setAttribute("username", "alice");
        employeeSession.setAttribute("employee_name", "Alice");

        noRoleSession = new MockHttpSession();
        // no role attribute set
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Template routing
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Template routing")
    class TemplateRouting {

        @Test
        @DisplayName("admin role returns 'admin/dashboard'")
        void index_adminRole_returnsAdminDashboard() {
            when(appState.getLoadedFiles()).thenReturn(
                    Collections.singletonList("/data/vacation.xlsx"));

            String view = controller.index(model, adminSession);

            assertThat(view).isEqualTo("admin/dashboard");
        }

        @Test
        @DisplayName("employee role returns 'employee-page'")
        void index_employeeRole_returnsEmployeePage() {
            when(appState.getLoadedFiles()).thenReturn(
                    Collections.singletonList("/data/vacation.xlsx"));

            String view = controller.index(model, employeeSession);

            assertThat(view).isEqualTo("employee-page");
        }

        @Test
        @DisplayName("null role returns 'employee-page'")
        void index_nullRole_returnsEmployeePage() {
            when(appState.getLoadedFiles()).thenReturn(
                    Collections.singletonList("/data/vacation.xlsx"));

            String view = controller.index(model, noRoleSession);

            assertThat(view).isEqualTo("employee-page");
        }

        @Test
        @DisplayName("unrecognised role returns 'employee-page'")
        void index_unknownRole_returnsEmployeePage() {
            MockHttpSession session = new MockHttpSession();
            session.setAttribute("role", "superadmin"); // unknown
            when(appState.getLoadedFiles()).thenReturn(
                    Collections.singletonList("/data/vacation.xlsx"));

            String view = controller.index(model, session);

            assertThat(view).isEqualTo("employee-page");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Model attributes
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Model attributes")
    class ModelAttributes {

        @BeforeEach
        void setUp() {
            when(appState.getLoadedFiles()).thenReturn(
                    Collections.singletonList("/data/vacation.xlsx"));
        }

        @Test
        @DisplayName("appTitle is always set to 'Holiday Leave Assistant'")
        void index_appTitleAttribute_alwaysSet() {
            controller.index(model, adminSession);

            assertThat(model.asMap()).containsEntry("appTitle", "Holiday Leave Assistant");
        }

        @Test
        @DisplayName("greetingName is populated from session employee_name")
        void index_greetingName_fromSession() {
            controller.index(model, employeeSession);

            assertThat(model.asMap()).containsEntry("greetingName", "Alice");
        }

        @Test
        @DisplayName("greetingName is null when session has no employee_name")
        void index_greetingName_nullWhenMissing() {
            controller.index(model, noRoleSession);

            assertThat(model.asMap()).containsEntry("greetingName", null);
        }

        @Test
        @DisplayName("loginUsername is populated from session username")
        void index_loginUsername_fromSession() {
            controller.index(model, adminSession);

            assertThat(model.asMap()).containsEntry("loginUsername", "admin-user");
        }

        @Test
        @DisplayName("loginUsername is null when session has no username")
        void index_loginUsername_nullWhenMissing() {
            controller.index(model, noRoleSession);

            assertThat(model.asMap()).containsEntry("loginUsername", null);
        }

        @Test
        @DisplayName("currentPage is set to 'dashboard' only for admin role")
        void index_currentPage_setForAdmin() {
            controller.index(model, adminSession);

            assertThat(model.asMap()).containsEntry("currentPage", "dashboard");
        }

        @Test
        @DisplayName("currentPage is NOT set for employee role")
        void index_currentPage_notSetForEmployee() {
            controller.index(model, employeeSession);

            assertThat(model.asMap()).doesNotContainKey("currentPage");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // initLoadedFiles behaviour
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("initLoadedFiles() behaviour")
    class InitLoadedFiles {

        @Test
        @DisplayName("NOT called when loadedFiles is already populated")
        void index_loadsAlreadySet_doesNotCallForceSync() {
            when(appState.getLoadedFiles()).thenReturn(
                    Collections.singletonList("/data/vacation.xlsx"));

            controller.index(model, adminSession);

            verify(syncService, never()).forceSync();
        }

        @Test
        @DisplayName("called when loadedFiles is empty — triggers forceSync + discoverExcelPaths")
        void index_loadedFilesEmpty_callsForceSync() {
            when(appState.getLoadedFiles()).thenReturn(Collections.emptyList());
            when(appState.discoverExcelPaths()).thenReturn(Collections.emptyList());

            controller.index(model, adminSession);

            verify(syncService).forceSync();
            verify(appState).discoverExcelPaths();
        }

        @Test
        @DisplayName("when discoverExcelPaths returns paths: setLoadedFiles, setActiveFiles, refreshKnownFiles called")
        void index_discoveredPaths_setsLoadedAndActiveFiles() {
            when(appState.getLoadedFiles()).thenReturn(Collections.emptyList());
            when(appState.discoverExcelPaths()).thenReturn(
                    Arrays.asList("/data/vacation-2024.xlsx", "/data/vacation-2023.xlsx"));

            controller.index(model, adminSession);

            verify(appState).setLoadedFiles(
                    Arrays.asList("/data/vacation-2024.xlsx", "/data/vacation-2023.xlsx"));
            // setActiveFiles should get a list with just the first (newest) path
            verify(appState).setActiveFiles(
                    Collections.singletonList("/data/vacation-2024.xlsx"));
            verify(appState).refreshKnownFiles();
        }

        @Test
        @DisplayName("when discoverExcelPaths returns single path: correctly passed to setActiveFiles")
        void index_singleDiscoveredPath_setsActiveFilesWithSingleElement() {
            when(appState.getLoadedFiles()).thenReturn(Collections.emptyList());
            when(appState.discoverExcelPaths()).thenReturn(
                    Collections.singletonList("/data/vacation-2024.xlsx"));

            controller.index(model, adminSession);

            verify(appState).setActiveFiles(Collections.singletonList("/data/vacation-2024.xlsx"));
        }

        @Test
        @DisplayName("when discoverExcelPaths returns empty list: setLoadedFiles NOT called")
        void index_emptyDiscoveredPaths_doesNotSetLoadedFiles() {
            when(appState.getLoadedFiles()).thenReturn(Collections.emptyList());
            when(appState.discoverExcelPaths()).thenReturn(Collections.emptyList());

            controller.index(model, adminSession);

            verify(appState, never()).setLoadedFiles(any());
            verify(appState, never()).setActiveFiles(any());
            verify(appState, never()).refreshKnownFiles();
        }

        @Test
        @DisplayName("forceSync is called before discoverExcelPaths (ordering check)")
        void index_forceSyncBeforeDiscover() {
            when(appState.getLoadedFiles()).thenReturn(Collections.emptyList());
            when(appState.discoverExcelPaths()).thenReturn(Collections.emptyList());

            org.mockito.InOrder order = inOrder(syncService, appState);

            controller.index(model, adminSession);

            order.verify(syncService).forceSync();
            order.verify(appState).discoverExcelPaths();
        }
    }
}
