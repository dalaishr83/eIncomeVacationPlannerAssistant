package com.holidayleave.assistant.excel;

import com.holidayleave.assistant.model.LeaveRecord;
import com.holidayleave.assistant.model.VacationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link WorkingExcelWriter}.
 *
 * All tests build real in-memory XLSX files using {@link ExcelTestHelper}
 * so they exercise the actual POI write path.
 *
 * Covers:
 *
 * getLock():
 *  - Returns the same lock for the same year across calls
 *  - Returns different locks for different years
 *  - Is thread-safe for concurrent registrations
 *
 * addVacation():
 *  - Writes leave code for a single weekday
 *  - Returns count equal to number of weekdays written
 *  - Writes leave code for a multi-day span (weekdays only)
 *  - Skips weekend days in the span
 *  - Throws ExcelWriteConflictException when a cell already has a code
 *  - Throws IOException when employee not found in sheet
 *  - File is still readable after write (atomic save round-trip)
 *
 * deleteVacation():
 *  - Clears one cell and returns 1
 *  - Clears multiple cells and returns the correct count
 *  - Skips weekends in the range
 *  - Throws ExcelDeleteNotFoundException when no cells are cleared
 *  - Throws IOException when employee not found
 *  - Written then deleted: cell is cleared
 *
 * Exception types:
 *  - ExcelWriteConflictException carries conflictDate and existingCode
 *  - ExcelDeleteNotFoundException message preserved
 */
class WorkingExcelWriterTest {

    private WorkingExcelWriter writer;

    @TempDir
    Path tempDir;

    /** A full 2024 planner with Alice (offset 0) and Bob (offset 1). */
    private Path plannerFile;

    /** Reusable "Vacation" type. */
    private VacationType vacationType;

    @BeforeEach
    void setUp() throws IOException {
        writer = new WorkingExcelWriter();
        plannerFile = tempDir.resolve("eIndkomst vacation 2024.xlsx");
        ExcelTestHelper.writeFullYearPlanner(plannerFile, 2024);
        vacationType = new VacationType("V", "Vacation", "FF92D050");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // getLock()
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getLock()")
    class GetLock {

        @Test
        @DisplayName("same year → same lock instance")
        void getLock_sameYear_sameLockInstance() {
            ReentrantLock l1 = writer.getLock(2024);
            ReentrantLock l2 = writer.getLock(2024);
            assertThat(l1).isSameAs(l2);
        }

        @Test
        @DisplayName("different years → different lock instances")
        void getLock_differentYears_differentLocks() {
            ReentrantLock l2024 = writer.getLock(2024);
            ReentrantLock l2025 = writer.getLock(2025);
            assertThat(l2024).isNotSameAs(l2025);
        }

        @Test
        @DisplayName("concurrent registrations for same year return the same lock")
        void getLock_concurrent_sameYear_sameLock() throws InterruptedException {
            int threads = 8;
            ReentrantLock[] locks = new ReentrantLock[threads];
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done  = new CountDownLatch(threads);

            for (int i = 0; i < threads; i++) {
                final int idx = i;
                new Thread(() -> {
                    try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    locks[idx] = writer.getLock(2024);
                    done.countDown();
                }).start();
            }
            start.countDown();
            done.await();

            for (int i = 1; i < threads; i++) {
                assertThat(locks[i]).isSameAs(locks[0]);
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // addVacation()
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("addVacation()")
    class AddVacation {

        @Test
        @DisplayName("writes a single weekday cell and returns 1")
        void addVacation_singleWeekday_returnsOne() throws IOException {
            // Mon 1 Apr 2024
            LeaveRecord record = new LeaveRecord("Alice",
                    LocalDate.of(2024, 4, 1), LocalDate.of(2024, 4, 1), 1.0, "Vacation", null);

            int written = writer.addVacation(plannerFile.toString(), record, vacationType);

            assertThat(written).isEqualTo(1);
        }

        @Test
        @DisplayName("writes a 3-weekday span and returns 3")
        void addVacation_threeWeekdays_returnsThree() throws IOException {
            // Mon-Wed 1-3 Apr 2024
            LeaveRecord record = new LeaveRecord("Alice",
                    LocalDate.of(2024, 4, 1), LocalDate.of(2024, 4, 3), 3.0, "Vacation", null);

            int written = writer.addVacation(plannerFile.toString(), record, vacationType);

            assertThat(written).isEqualTo(3);
        }

        @Test
        @DisplayName("span crossing a weekend skips Sat+Sun — only weekdays counted")
        void addVacation_spanCrossingWeekend_skipsWeekends() throws IOException {
            // Thu 4 Apr → Mon 8 Apr 2024  (Sat 6 + Sun 7 skipped = 3 weekdays: Thu, Fri, Mon)
            LeaveRecord record = new LeaveRecord("Alice",
                    LocalDate.of(2024, 4, 4), LocalDate.of(2024, 4, 8), 3.0, "Vacation", null);

            int written = writer.addVacation(plannerFile.toString(), record, vacationType);

            assertThat(written).isEqualTo(3);
        }

        @Test
        @DisplayName("throws ExcelWriteConflictException when cell already occupied")
        void addVacation_conflictingCell_throwsConflict() throws IOException {
            // First write succeeds
            LeaveRecord r1 = new LeaveRecord("Alice",
                    LocalDate.of(2024, 4, 1), LocalDate.of(2024, 4, 1), 1.0, "Vacation", null);
            writer.addVacation(plannerFile.toString(), r1, vacationType);

            // Second write on same date must throw
            LeaveRecord r2 = new LeaveRecord("Alice",
                    LocalDate.of(2024, 4, 1), LocalDate.of(2024, 4, 1), 1.0, "Vacation", null);

            assertThatThrownBy(() -> writer.addVacation(plannerFile.toString(), r2, vacationType))
                    .isInstanceOf(WorkingExcelWriter.ExcelWriteConflictException.class);
        }

        @Test
        @DisplayName("ExcelWriteConflictException carries conflictDate and existingCode")
        void addVacation_conflictException_hasDateAndCode() throws IOException {
            LeaveRecord r1 = new LeaveRecord("Alice",
                    LocalDate.of(2024, 4, 1), LocalDate.of(2024, 4, 1), 1.0, "Vacation", null);
            writer.addVacation(plannerFile.toString(), r1, vacationType);

            LeaveRecord r2 = new LeaveRecord("Alice",
                    LocalDate.of(2024, 4, 1), LocalDate.of(2024, 4, 1), 1.0, "Vacation", null);

            WorkingExcelWriter.ExcelWriteConflictException ex =
                    (WorkingExcelWriter.ExcelWriteConflictException)
                            catchThrowable(() -> writer.addVacation(plannerFile.toString(), r2, vacationType));

            assertThat(ex).isNotNull();
            assertThat(ex.getConflictDate()).isEqualTo(LocalDate.of(2024, 4, 1));
            assertThat(ex.getExistingCode()).isEqualTo("V");
        }

        @Test
        @DisplayName("throws IOException when employee not found in sheet")
        void addVacation_unknownEmployee_throwsIOException() {
            LeaveRecord record = new LeaveRecord("NoSuchPerson",
                    LocalDate.of(2024, 4, 1), LocalDate.of(2024, 4, 1), 1.0, "Vacation", null);

            assertThatThrownBy(() -> writer.addVacation(plannerFile.toString(), record, vacationType))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("NoSuchPerson");
        }

        @Test
        @DisplayName("file is readable after write (atomic save round-trip)")
        void addVacation_fileReadableAfterWrite() throws IOException {
            LeaveRecord record = new LeaveRecord("Alice",
                    LocalDate.of(2024, 4, 1), LocalDate.of(2024, 4, 1), 1.0, "Vacation", null);
            writer.addVacation(plannerFile.toString(), record, vacationType);

            // PlannerExcelReader should be able to load the modified file
            PlannerExcelReader reader = new PlannerExcelReader();
            assertThatCode(() -> reader.load(plannerFile.toString()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Bob can also receive leave (second employee row)")
        void addVacation_secondEmployee_written() throws IOException {
            LeaveRecord record = new LeaveRecord("Bob",
                    LocalDate.of(2024, 5, 6), LocalDate.of(2024, 5, 6), 1.0, "Vacation", null);

            int written = writer.addVacation(plannerFile.toString(), record, vacationType);
            assertThat(written).isEqualTo(1);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // deleteVacation()
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("deleteVacation()")
    class DeleteVacation {

        @Test
        @DisplayName("clears a single pre-written cell and returns 1")
        void deleteVacation_singleCell_returnsOne() throws IOException {
            // Write first
            LeaveRecord record = new LeaveRecord("Alice",
                    LocalDate.of(2024, 4, 1), LocalDate.of(2024, 4, 1), 1.0, "Vacation", null);
            writer.addVacation(plannerFile.toString(), record, vacationType);

            // Now delete
            int cleared = writer.deleteVacation(plannerFile.toString(), "Alice",
                    LocalDate.of(2024, 4, 1), LocalDate.of(2024, 4, 1));

            assertThat(cleared).isEqualTo(1);
        }

        @Test
        @DisplayName("clears a 3-day span and returns 3")
        void deleteVacation_threedays_returnsThree() throws IOException {
            // Write 3 consecutive weekdays
            LeaveRecord record = new LeaveRecord("Alice",
                    LocalDate.of(2024, 4, 1), LocalDate.of(2024, 4, 3), 3.0, "Vacation", null);
            writer.addVacation(plannerFile.toString(), record, vacationType);

            int cleared = writer.deleteVacation(plannerFile.toString(), "Alice",
                    LocalDate.of(2024, 4, 1), LocalDate.of(2024, 4, 3));

            assertThat(cleared).isEqualTo(3);
        }

        @Test
        @DisplayName("span crossing a weekend only clears weekday cells")
        void deleteVacation_crossingWeekend_skipsWeekends() throws IOException {
            // Write Thu 4 Apr → Mon 8 Apr (3 weekdays)
            LeaveRecord record = new LeaveRecord("Alice",
                    LocalDate.of(2024, 4, 4), LocalDate.of(2024, 4, 8), 3.0, "Vacation", null);
            writer.addVacation(plannerFile.toString(), record, vacationType);

            int cleared = writer.deleteVacation(plannerFile.toString(), "Alice",
                    LocalDate.of(2024, 4, 4), LocalDate.of(2024, 4, 8));

            assertThat(cleared).isEqualTo(3);
        }

        @Test
        @DisplayName("throws ExcelDeleteNotFoundException when no cells are cleared")
        void deleteVacation_noCells_throwsNotFoundException() {
            // No leave written — cells are blank → nothing to clear
            assertThatThrownBy(() -> writer.deleteVacation(plannerFile.toString(), "Alice",
                    LocalDate.of(2024, 4, 1), LocalDate.of(2024, 4, 1)))
                    .isInstanceOf(WorkingExcelWriter.ExcelDeleteNotFoundException.class);
        }

        @Test
        @DisplayName("throws IOException when employee not found in sheet")
        void deleteVacation_unknownEmployee_throwsIOException() {
            assertThatThrownBy(() -> writer.deleteVacation(plannerFile.toString(), "NoSuchPerson",
                    LocalDate.of(2024, 4, 1), LocalDate.of(2024, 4, 1)))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("NoSuchPerson");
        }

        @Test
        @DisplayName("cell is blank after add-then-delete round-trip")
        void deleteVacation_afterAdd_cellIsBlank() throws IOException {
            LocalDate day = LocalDate.of(2024, 4, 1);
            LeaveRecord record = new LeaveRecord("Alice", day, day, 1.0, "Vacation", null);

            // Add then delete
            writer.addVacation(plannerFile.toString(), record, vacationType);
            writer.deleteVacation(plannerFile.toString(), "Alice", day, day);

            // After delete the cell should be blank → trying to write again must NOT throw a conflict
            assertThatCode(() -> writer.addVacation(plannerFile.toString(), record, vacationType))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("file remains readable after delete (atomic save round-trip)")
        void deleteVacation_fileReadableAfterDelete() throws IOException {
            LocalDate day = LocalDate.of(2024, 4, 1);
            LeaveRecord record = new LeaveRecord("Alice", day, day, 1.0, "Vacation", null);
            writer.addVacation(plannerFile.toString(), record, vacationType);
            writer.deleteVacation(plannerFile.toString(), "Alice", day, day);

            PlannerExcelReader reader = new PlannerExcelReader();
            assertThatCode(() -> reader.load(plannerFile.toString()))
                    .doesNotThrowAnyException();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Exception type contracts
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Exception types")
    class ExceptionTypes {

        @Test
        @DisplayName("ExcelWriteConflictException is a RuntimeException")
        void conflictException_isRuntimeException() {
            WorkingExcelWriter.ExcelWriteConflictException ex =
                    new WorkingExcelWriter.ExcelWriteConflictException(LocalDate.of(2024, 1, 2), "V");
            assertThat(ex).isInstanceOf(RuntimeException.class);
            assertThat(ex.getConflictDate()).isEqualTo(LocalDate.of(2024, 1, 2));
            assertThat(ex.getExistingCode()).isEqualTo("V");
            assertThat(ex.getMessage()).contains("2024-01-02").contains("V");
        }

        @Test
        @DisplayName("ExcelDeleteNotFoundException is a RuntimeException")
        void deleteNotFoundException_isRuntimeException() {
            WorkingExcelWriter.ExcelDeleteNotFoundException ex =
                    new WorkingExcelWriter.ExcelDeleteNotFoundException("nothing to delete");
            assertThat(ex).isInstanceOf(RuntimeException.class);
            assertThat(ex.getMessage()).isEqualTo("nothing to delete");
        }
    }
}
