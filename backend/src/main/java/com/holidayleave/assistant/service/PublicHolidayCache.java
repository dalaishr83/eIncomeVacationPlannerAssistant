package com.holidayleave.assistant.service;

import com.holidayleave.assistant.config.AppProperties;
import com.holidayleave.assistant.excel.PlannerExcelReader;
import com.holidayleave.assistant.scheduler.CronDateRangeResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Date;
import java.util.Set;

/**
 * In-memory cache for the public holiday date set.
 *
 * <p>Loaded once at application startup from all Master Excel files in {@code DATA_DIR}.
 * Refreshed after every successful working-file → master-Excel synchronisation so that
 * newly written public-holiday records are immediately visible to the cron scheduler.
 *
 * <p>The cache intentionally avoids reloading on every {@code CronSchedulerService}
 * validation tick because the public-holiday dataset is static for the duration of
 * a calendar year and the repeated Master Excel I/O was an unnecessary overhead.
 */
@Component
public class PublicHolidayCache {

    private static final Logger log = LoggerFactory.getLogger(PublicHolidayCache.class);

    @Autowired private AppState       appState;
    @Autowired private AppProperties  appProperties;
    @Autowired private PlannerExcelReader plannerExcelReader;

    private volatile Set<Date> holidays = Collections.emptySet();

    @PostConstruct
    public void init() {
        refresh();
    }

    /**
     * Reloads the public holiday set from all Master Excel files in {@code DATA_DIR}.
     * Called at startup and after each successful working-to-master synchronisation.
     */
    public void refresh() {
        ZoneId zone = ZoneId.of(appProperties != null ? appProperties.getCronTimezone() : "Asia/Kolkata");
        Set<Date> loaded = CronDateRangeResolver.loadPublicHolidays(
                appState.getDataDir(), plannerExcelReader, zone);
        this.holidays = Collections.unmodifiableSet(loaded);
        log.info("PublicHolidayCache: refreshed — {} dates cached", this.holidays.size());
    }

    /**
     * Returns the cached set of public holiday dates.
     * The returned set is immutable; callers must not attempt to modify it.
     */
    public Set<Date> getHolidays() {
        return holidays;
    }
}
