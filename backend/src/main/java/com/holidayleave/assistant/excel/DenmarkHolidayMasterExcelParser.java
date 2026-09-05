package com.holidayleave.assistant.excel;

import org.springframework.stereotype.Component;

/**
 * Placeholder parser for Denmark Holiday Master Excel files.
 *
 * <p>Denmark holiday synchronization is a <strong>future enhancement</strong>.
 * No parsing logic is implemented at this stage.  This class exists solely as
 * an extension point so that the Spring context and the parser-routing layer
 * already reference a concrete type for the Denmark holiday flow.
 *
 * <p>When Denmark support is implemented, add a {@code parse()} method here
 * following the same contract as {@link IndianHolidayMasterExcelParser#parse}.
 */
@Component
public class DenmarkHolidayMasterExcelParser {
    // Future enhancement — no implementation yet.
}
