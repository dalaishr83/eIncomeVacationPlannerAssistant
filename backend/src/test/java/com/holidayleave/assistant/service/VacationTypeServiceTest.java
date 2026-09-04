package com.holidayleave.assistant.service;

import com.holidayleave.assistant.config.AppProperties;
import com.holidayleave.assistant.model.VacationType;
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
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link VacationTypeService}.
 *
 * Uses a real temp directory so the JSON file-backed persistence is exercised
 * without mocking the filesystem (the service relies entirely on Files / ObjectMapper).
 *
 * Covers:
 *  - init(): bootstraps vacation_types.json with 7 default types
 *  - findAll(): reads persisted list
 *  - findByCode(): case-insensitive lookup — found / not-found
 *  - findByLabel(): case-insensitive lookup — found / not-found
 *  - add(): adds a new type, duplicate code throws IllegalStateException
 *  - update(): updates label and/or color; unknown code throws NoSuchElementException
 *  - Boundary: null code/label/color in update preserves existing values
 */
@ExtendWith(MockitoExtension.class)
class VacationTypeServiceTest {

    @Mock private AppProperties props;

    @InjectMocks
    private VacationTypeService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        when(props.getDataDir()).thenReturn(tempDir.toString());
        service.init();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // init / bootstrap
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("init — bootstrap defaults")
    class Init {

        @Test
        @DisplayName("bootstraps 7 default types on first run")
        void init_bootstrapsSevenDefaults() {
            assertThat(service.findAll()).hasSize(7);
        }

        @Test
        @DisplayName("does not overwrite existing file on second init")
        void init_doesNotOverwriteExisting() throws IOException {
            // Add a custom type, then re-init — should still see 8 types (not reset to 7)
            service.add(new VacationType("X", "Extra", "FF000000"));
            service.init(); // second init
            assertThat(service.findAll()).hasSize(8);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // findAll
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("findAll")
    class FindAll {

        @Test
        @DisplayName("returns all persisted types")
        void findAll_returnsAllTypes() {
            List<VacationType> types = service.findAll();
            assertThat(types).isNotEmpty();
        }

        @Test
        @DisplayName("default types include Vacation and Public Holiday")
        void findAll_defaultTypesPresent() {
            List<VacationType> types = service.findAll();
            assertThat(types).extracting(VacationType::code)
                .contains("V", "P", "PC", "H", "E", "O", "A");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // findByCode
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("findByCode")
    class FindByCode {

        @Test
        @DisplayName("returns type for exact code match")
        void findByCode_exactMatch() {
            Optional<VacationType> result = service.findByCode("V");
            assertThat(result).isPresent();
            assertThat(result.get().label()).isEqualTo("Vacation");
        }

        @Test
        @DisplayName("case-insensitive — lowercase code matches uppercase stored code")
        void findByCode_caseInsensitive() {
            Optional<VacationType> result = service.findByCode("v");
            assertThat(result).isPresent();
        }

        @Test
        @DisplayName("returns empty for unknown code")
        void findByCode_unknownCode_empty() {
            Optional<VacationType> result = service.findByCode("UNKNOWN");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("returns empty for null code")
        void findByCode_nullCode_empty() {
            Optional<VacationType> result = service.findByCode(null);
            assertThat(result).isEmpty();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // findByLabel
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("findByLabel")
    class FindByLabel {

        @Test
        @DisplayName("returns type for exact label match")
        void findByLabel_exactMatch() {
            Optional<VacationType> result = service.findByLabel("Vacation");
            assertThat(result).isPresent();
            assertThat(result.get().code()).isEqualTo("V");
        }

        @Test
        @DisplayName("case-insensitive label lookup")
        void findByLabel_caseInsensitive() {
            Optional<VacationType> result = service.findByLabel("vacation");
            assertThat(result).isPresent();
        }

        @Test
        @DisplayName("returns empty for unknown label")
        void findByLabel_unknownLabel_empty() {
            Optional<VacationType> result = service.findByLabel("Nonexistent");
            assertThat(result).isEmpty();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // add
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("add")
    class Add {

        @Test
        @DisplayName("adds a new type and persists it")
        void add_addsNewType() {
            VacationType newType = new VacationType("Z", "Zero Day", "FF112233");
            service.add(newType);

            Optional<VacationType> found = service.findByCode("Z");
            assertThat(found).isPresent();
            assertThat(found.get().label()).isEqualTo("Zero Day");
        }

        @Test
        @DisplayName("returns the added type")
        void add_returnsAddedType() {
            VacationType newType = new VacationType("T", "Training", "FF445566");
            VacationType result = service.add(newType);
            assertThat(result).isSameAs(newType);
        }

        @Test
        @DisplayName("duplicate code throws IllegalStateException")
        void add_duplicateCode_throwsIllegalState() {
            VacationType dup = new VacationType("V", "Duplicate Vacation", "FF000000");
            assertThatThrownBy(() -> service.add(dup))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V");
        }

        @Test
        @DisplayName("duplicate code check is case-insensitive")
        void add_duplicateCodeCaseInsensitive_throwsIllegalState() {
            VacationType dup = new VacationType("v", "lowercase v", "FF000000");
            assertThatThrownBy(() -> service.add(dup))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("count increases by one after add")
        void add_countIncreasedByOne() {
            int before = service.findAll().size();
            service.add(new VacationType("Q", "Quarantine", "FF999999"));
            assertThat(service.findAll()).hasSize(before + 1);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // update
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        @DisplayName("updates label and color of existing type")
        void update_updatesLabelAndColor() {
            service.update("V", "Annual Vacation", "FF001122");

            VacationType updated = service.findByCode("V").get();
            assertThat(updated.label()).isEqualTo("Annual Vacation");
            assertThat(updated.color()).isEqualTo("FF001122");
        }

        @Test
        @DisplayName("null label preserves existing label")
        void update_nullLabel_preservesLabel() {
            String original = service.findByCode("V").get().label();
            service.update("V", null, "FFAABBCC");
            assertThat(service.findByCode("V").get().label()).isEqualTo(original);
        }

        @Test
        @DisplayName("null color preserves existing color")
        void update_nullColor_preservesColor() {
            String original = service.findByCode("V").get().color();
            service.update("V", "New Label", null);
            assertThat(service.findByCode("V").get().color()).isEqualTo(original);
        }

        @Test
        @DisplayName("update returns the updated type")
        void update_returnsUpdatedType() {
            VacationType result = service.update("V", "Updated", "FF000000");
            assertThat(result.label()).isEqualTo("Updated");
        }

        @Test
        @DisplayName("unknown code throws NoSuchElementException")
        void update_unknownCode_throwsNoSuchElement() {
            assertThatThrownBy(() -> service.update("NOCODE", "Label", "Color"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("NOCODE");
        }

        @Test
        @DisplayName("code is case-insensitive in update lookup")
        void update_caseInsensitiveCodeLookup() {
            service.update("v", "From lowercase", "FF000000");
            assertThat(service.findByCode("V").get().label()).isEqualTo("From lowercase");
        }
    }
}
