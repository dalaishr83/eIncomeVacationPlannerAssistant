package com.holidayleave.assistant.service;

import com.holidayleave.assistant.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RestrictedVacationTypeService}.
 *
 * Uses a real temp directory — the service writes/reads a JSON file.
 *
 * Covers:
 *  - init(): bootstraps empty restricted list
 *  - getRestrictedTypes(): returns upper-cased codes
 *  - isRestricted(): true/false, null input, case-insensitive
 *  - setRestrictedTypes(): replaces list, upper-cases on save
 *  - Boundary: empty list, single element, multiple elements
 *  - Round-trip: set then get returns same codes
 */
@ExtendWith(MockitoExtension.class)
class RestrictedVacationTypeServiceTest {

    @Mock private AppProperties props;

    @InjectMocks
    private RestrictedVacationTypeService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        when(props.getDataDir()).thenReturn(tempDir.toString());
        service.init();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // init
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("init — bootstrap")
    class Init {

        @Test
        @DisplayName("bootstraps with empty restricted list on first run")
        void init_bootstrapsEmpty() {
            assertThat(service.getRestrictedTypes()).isEmpty();
        }

        @Test
        @DisplayName("does not reset existing list on second init")
        void init_doesNotOverwriteExisting() throws IOException {
            service.setRestrictedTypes(Arrays.asList("V", "PC"));
            service.init(); // re-init should keep existing file
            assertThat(service.getRestrictedTypes()).containsExactlyInAnyOrder("V", "PC");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // getRestrictedTypes
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getRestrictedTypes")
    class GetRestrictedTypes {

        @Test
        @DisplayName("returns empty list when none restricted")
        void getRestrictedTypes_empty() {
            assertThat(service.getRestrictedTypes()).isEmpty();
        }

        @Test
        @DisplayName("returns codes upper-cased regardless of how they were stored")
        void getRestrictedTypes_returnedUpperCased() throws IOException {
            service.setRestrictedTypes(Arrays.asList("v", "pc"));
            List<String> result = service.getRestrictedTypes();
            assertThat(result).containsExactlyInAnyOrder("V", "PC");
        }

        @Test
        @DisplayName("returns all set codes")
        void getRestrictedTypes_returnsAllCodes() throws IOException {
            service.setRestrictedTypes(Arrays.asList("A", "B", "C"));
            assertThat(service.getRestrictedTypes()).hasSize(3);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // isRestricted
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("isRestricted")
    class IsRestricted {

        @Test
        @DisplayName("returns false when list is empty")
        void isRestricted_emptyList_returnsFalse() {
            assertThat(service.isRestricted("V")).isFalse();
        }

        @Test
        @DisplayName("returns true for restricted code")
        void isRestricted_restrictedCode_returnsTrue() throws IOException {
            service.setRestrictedTypes(Arrays.asList("V", "PC"));
            assertThat(service.isRestricted("V")).isTrue();
        }

        @Test
        @DisplayName("returns false for non-restricted code")
        void isRestricted_nonRestrictedCode_returnsFalse() throws IOException {
            service.setRestrictedTypes(Collections.singletonList("PC"));
            assertThat(service.isRestricted("V")).isFalse();
        }

        @Test
        @DisplayName("null code always returns false")
        void isRestricted_nullCode_returnsFalse() throws IOException {
            service.setRestrictedTypes(Arrays.asList("V", "PC"));
            assertThat(service.isRestricted(null)).isFalse();
        }

        @Test
        @DisplayName("case-insensitive match — lowercase query matches upper-cased stored code")
        void isRestricted_caseInsensitive() throws IOException {
            service.setRestrictedTypes(Collections.singletonList("PC"));
            assertThat(service.isRestricted("pc")).isTrue();
            assertThat(service.isRestricted("Pc")).isTrue();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // setRestrictedTypes
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("setRestrictedTypes")
    class SetRestrictedTypes {

        @Test
        @DisplayName("replaces the entire list")
        void setRestrictedTypes_replacesExisting() throws IOException {
            service.setRestrictedTypes(Collections.singletonList("V"));
            service.setRestrictedTypes(Arrays.asList("PC", "H"));
            assertThat(service.getRestrictedTypes()).containsExactlyInAnyOrder("PC", "H");
            assertThat(service.isRestricted("V")).isFalse();
        }

        @Test
        @DisplayName("persists empty list — clears all restrictions")
        void setRestrictedTypes_emptyList_clearsAll() throws IOException {
            service.setRestrictedTypes(Arrays.asList("V", "PC"));
            service.setRestrictedTypes(Collections.<String>emptyList());
            assertThat(service.getRestrictedTypes()).isEmpty();
        }

        @Test
        @DisplayName("codes are stored upper-cased")
        void setRestrictedTypes_codesStoredUpperCase() throws IOException {
            service.setRestrictedTypes(Arrays.asList("v", "pc", "h"));
            assertThat(service.getRestrictedTypes()).containsExactlyInAnyOrder("V", "PC", "H");
        }

        @Test
        @DisplayName("single code round-trip")
        void setRestrictedTypes_singleCode_roundTrip() throws IOException {
            service.setRestrictedTypes(Collections.singletonList("E"));
            assertThat(service.isRestricted("E")).isTrue();
        }
    }
}
