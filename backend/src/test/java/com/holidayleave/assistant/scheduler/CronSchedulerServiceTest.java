package com.holidayleave.assistant.scheduler;

import com.holidayleave.assistant.excel.PlannerExcelReader;
import com.holidayleave.assistant.model.LeaveRecord;
import com.holidayleave.assistant.scheduler.CronExpressionStore.CronEntry;
import com.holidayleave.assistant.service.AppState;
import com.holidayleave.assistant.service.AuditService;
import com.holidayleave.assistant.service.SlackNotificationService;
import com.holidayleave.assistant.service.TeamForecastService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class CronSchedulerServiceTest {

    @Mock private CronExpressionStore store;
    @Mock private TeamForecastService teamForecastService;
    @Mock private SlackNotificationService slackNotificationService;
    @Mock private AuditService auditService;
    @Mock private AppState appState;
    @Mock private PlannerExcelReader plannerExcelReader;

    @InjectMocks
    private CronSchedulerService schedulerService;

    @Test
    @DisplayName("validateAndSyncWorkingCron creates working-cron entries and marks endDateAjusted true when holiday is hit")
    void testValidateAndSyncWorkingCronWithHoliday(@TempDir Path tempDir) throws IOException {
        String dataDir = tempDir.toString();
        when(appState.getDataDir()).thenReturn(dataDir);

        File f1 = tempDir.resolve("eIndkomst vacation 2026.xlsx").toFile();
        assertTrue(f1.createNewFile());

        // Friday 2026-09-11 is a Public Holiday
        List<LeaveRecord> records = Collections.singletonList(
                new LeaveRecord("Alice", LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 11), 1, "P", null)
        );
        when(plannerExcelReader.load(f1.getAbsolutePath())).thenReturn(records);

        // User expression scheduled for Fridays at 11:30
        CronEntry userEntry = new CronEntry("30 11 * * 5", "Indian Team");
        when(store.readAll()).thenReturn(Collections.singletonList(userEntry));
        when(store.readWorkingAll()).thenReturn(Collections.emptyList());

        schedulerService.validateAndSyncWorkingCron();

        // Verify saveWorkingAll called
        verify(store, times(1)).saveWorkingAll(argThat(list -> {
            if (list.size() != 1) return false;
            CronEntry we = list.get(0);
            return we.getId().equals(userEntry.getId())
                    && we.getTeam().equals("Indian Team")
                    && we.isEndDateAjusted();
        }));
    }

    @Test
    @DisplayName("validateAndSyncWorkingCron avoids re-adjusting already adjusted working cron entry")
    void testValidateAndSyncWorkingCronSkipsAlreadyAdjusted() throws IOException {
        CronEntry userEntry = new CronEntry("30 11 * * 5", "Indian Team");
        CronEntry workingEntry = new CronEntry(userEntry);
        workingEntry.setExpression("30 11 10 9 *");
        workingEntry.setEndDateAjusted(true);

        when(store.readAll()).thenReturn(Collections.singletonList(userEntry));
        when(store.readWorkingAll()).thenReturn(Collections.singletonList(workingEntry));

        schedulerService.validateAndSyncWorkingCron();

        verify(store, times(1)).saveWorkingAll(argThat(list -> {
            if (list.size() != 1) return false;
            CronEntry we = list.get(0);
            return we.getExpression().equals("30 11 10 9 *") && we.isEndDateAjusted();
        }));
    }
}
