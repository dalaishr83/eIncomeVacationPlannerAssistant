package com.holidayleave.assistant.scheduler;

import com.holidayleave.assistant.scheduler.CronExpressionStore.CronEntry;
import com.holidayleave.assistant.service.AppState;
import com.holidayleave.assistant.service.AuditService;
import com.holidayleave.assistant.service.PublicHolidayCache;
import com.holidayleave.assistant.service.SlackNotificationService;
import com.holidayleave.assistant.service.TeamForecastService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class CronSchedulerServiceTest {

    @Mock private CronExpressionStore store;
    @Mock private TeamForecastService teamForecastService;
    @Mock private SlackNotificationService slackNotificationService;
    @Mock private AuditService auditService;
    @Mock private AppState appState;
    @Mock private PublicHolidayCache publicHolidayCache;

    @InjectMocks
    private CronSchedulerService schedulerService;

    @Test
    @DisplayName("validateAndSyncWorkingCron creates working-cron entries and marks endDateAjusted true when holiday is hit")
    void testValidateAndSyncWorkingCronWithHoliday(@TempDir Path tempDir) throws IOException {
        // Compute the next Friday from now to use as the public holiday date.
        // This ensures the test is time-independent: the "next fire" of "30 11 * * 5"
        // will always land on this Friday regardless of when the test runs.
        java.time.LocalDate nextFriday = java.time.LocalDate.now()
                .with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.FRIDAY));

        // Build a holiday set containing that Friday and hand it to the cache mock.
        java.util.Date holidayDate = CronDateRangeResolver.normalizeToDate(nextFriday);
        when(publicHolidayCache.getHolidays()).thenReturn(Collections.singleton(holidayDate));

        // User expression scheduled for Fridays at 11:30
        CronEntry userEntry = new CronEntry("30 11 * * 5", "Indian Team");
        when(store.readAll()).thenReturn(Collections.singletonList(userEntry));
        when(store.readWorkingAll()).thenReturn(Collections.emptyList());

        boolean changed = schedulerService.validateAndSyncWorkingCron();
        assertTrue(changed, "Expected validateAndSyncWorkingCron to return true when an expression is preponed");

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

        boolean changed = schedulerService.validateAndSyncWorkingCron();
        assertFalse(changed, "Expected validateAndSyncWorkingCron to return false when no change occurred");

        verify(store, times(1)).saveWorkingAll(argThat(list -> {
            if (list.size() != 1) return false;
            CronEntry we = list.get(0);
            return we.getExpression().equals("30 11 10 9 *") && we.isEndDateAjusted();
        }));
    }

    @Test
    @DisplayName("executeForecast routes Cleanup entry to cleanupForecastReport and does not generate report or send Slack notification")
    void executeForecast_routesCleanupToHousekeepingUtility() throws Exception {
        CronEntry cleanupEntry = new CronEntry("0 1 * * *", "Cleanup");

        // Use reflection to invoke private executeForecast(CronEntry)
        java.lang.reflect.Method method = CronSchedulerService.class.getDeclaredMethod("executeForecast", CronEntry.class);
        method.setAccessible(true);
        method.invoke(schedulerService, cleanupEntry);

        verify(teamForecastService, times(1)).cleanupForecastReport(anyString(), eq(3600_000L));
        verify(teamForecastService, never()).generateForecast(anyString(), any(), any());
        verify(slackNotificationService, never()).notifyTeamForecast(anyString(), any(), any(), anyInt(), anyString(), anyString(), anyString(), anyList(), anyList());
        verify(auditService, times(1)).log(eq("cron_cleanup_executed"), eq("system"), isNull(), contains("deletedFiles="), eq("success"), eq("cron"));
    }

    @Test
    @DisplayName("startEntries sets state to running only for selected IDs and persists")
    void testStartEntriesSelective() throws IOException {
        CronEntry entry1 = new CronEntry("30 8 * * 5", "Indian Team");
        CronEntry entry2 = new CronEntry("30 9 * * 5", "EIndkomst Team");
        assertEquals("stopped", entry1.getState());
        assertEquals("stopped", entry2.getState());

        when(store.readAll()).thenReturn(Arrays.asList(entry1, entry2));
        when(store.readWorkingAll()).thenReturn(Arrays.asList(new CronEntry(entry1), new CronEntry(entry2)));

        int count = schedulerService.startEntries(Collections.singletonList(entry1.getId()));

        assertEquals("running", entry1.getState());
        assertEquals("stopped", entry2.getState());
        verify(store, atLeastOnce()).saveAll(argThat(list -> {
            boolean hasRunning1 = false;
            boolean hasStopped2 = false;
            for (CronEntry e : list) {
                if (e.getId().equals(entry1.getId()) && "running".equals(e.getState())) hasRunning1 = true;
                if (e.getId().equals(entry2.getId()) && "stopped".equals(e.getState())) hasStopped2 = true;
            }
            return hasRunning1 && hasStopped2;
        }));

        schedulerService.stop();
    }

    @Test
    @DisplayName("stopEntries sets state to stopped only for selected IDs and cancels their tasks")
    void testStopEntriesSelective() throws IOException {
        CronEntry entry1 = new CronEntry("30 8 * * 5", "Indian Team");
        entry1.setState("running");
        CronEntry entry2 = new CronEntry("30 9 * * 5", "EIndkomst Team");
        entry2.setState("running");

        when(store.readAll()).thenReturn(Arrays.asList(entry1, entry2));
        when(store.readWorkingAll()).thenReturn(Arrays.asList(new CronEntry(entry1), new CronEntry(entry2)));

        schedulerService.startEntries(Arrays.asList(entry1.getId(), entry2.getId()));

        int remaining = schedulerService.stopEntries(Collections.singletonList(entry1.getId()));
        assertEquals("stopped", entry1.getState());
        assertEquals("running", entry2.getState());

        schedulerService.stop();
    }
}
