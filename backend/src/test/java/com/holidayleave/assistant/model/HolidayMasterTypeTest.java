package com.holidayleave.assistant.model;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link HolidayMasterType#fromFilename(String)}.
 *
 * Verifies that every known filename prefix resolves to the correct enum constant,
 * that unrecognised filenames return empty, and that matching is case-insensitive.
 */
class HolidayMasterTypeTest {

    // ── Happy-path: exact canonical names ─────────────────────────────────────

    @Test
    void fromFilename_indiaHoliday_returnsIndianHoliday() {
        Optional<HolidayMasterType> result = HolidayMasterType.fromFilename("India-holiday-2026.xlsx");
        assertTrue(result.isPresent());
        assertEquals(HolidayMasterType.INDIAN_HOLIDAY, result.get());
    }

    @Test
    void fromFilename_denmarkHoliday_returnsDenmarkHoliday() {
        Optional<HolidayMasterType> result = HolidayMasterType.fromFilename("Denmark-holiday-2026.xlsx");
        assertTrue(result.isPresent());
        assertEquals(HolidayMasterType.DENMARK_HOLIDAY, result.get());
    }

    @Test
    void fromFilename_romaniaHoliday_returnsRomaniaHoliday() {
        Optional<HolidayMasterType> result = HolidayMasterType.fromFilename("Romania-holiday-2026.xlsx");
        assertTrue(result.isPresent());
        assertEquals(HolidayMasterType.ROMANIA_HOLIDAY, result.get());
    }

    // ── Case-insensitivity ────────────────────────────────────────────────────

    @Test
    void fromFilename_indiaLowerCase_returnsIndianHoliday() {
        Optional<HolidayMasterType> result = HolidayMasterType.fromFilename("india-holiday-2025.xlsx");
        assertTrue(result.isPresent());
        assertEquals(HolidayMasterType.INDIAN_HOLIDAY, result.get());
    }

    @Test
    void fromFilename_denmarkUpperCase_returnsDenmarkHoliday() {
        Optional<HolidayMasterType> result = HolidayMasterType.fromFilename("DENMARK-HOLIDAY-2025.xlsx");
        assertTrue(result.isPresent());
        assertEquals(HolidayMasterType.DENMARK_HOLIDAY, result.get());
    }

    @Test
    void fromFilename_romaniaMixedCase_returnsRomaniaHoliday() {
        Optional<HolidayMasterType> result = HolidayMasterType.fromFilename("Romania-Holiday-2025.xlsx");
        assertTrue(result.isPresent());
        assertEquals(HolidayMasterType.ROMANIA_HOLIDAY, result.get());
    }

    // ── Unrecognised filenames ────────────────────────────────────────────────

    @Test
    void fromFilename_oldNamingConvention_returnsEmpty() {
        Optional<HolidayMasterType> result = HolidayMasterType.fromFilename("Holiday List - 2026.xlsx");
        assertFalse(result.isPresent());
    }

    @Test
    void fromFilename_randomFilename_returnsEmpty() {
        assertFalse(HolidayMasterType.fromFilename("eIndkomst vacation 2025.xlsx").isPresent());
    }

    @Test
    void fromFilename_emptyString_returnsEmpty() {
        assertFalse(HolidayMasterType.fromFilename("").isPresent());
    }

    @Test
    void fromFilename_whitespaceOnly_returnsEmpty() {
        assertFalse(HolidayMasterType.fromFilename("   ").isPresent());
    }

    // ── Null safety ───────────────────────────────────────────────────────────

    @Test
    void fromFilename_null_returnsEmpty() {
        assertFalse(HolidayMasterType.fromFilename(null).isPresent());
    }

    // ── Prefix accessor ───────────────────────────────────────────────────────

    @Test
    void getFilenamePrefix_eachType_returnsExpectedPrefix() {
        assertEquals("India-holiday-",   HolidayMasterType.INDIAN_HOLIDAY.getFilenamePrefix());
        assertEquals("Denmark-holiday-", HolidayMasterType.DENMARK_HOLIDAY.getFilenamePrefix());
        assertEquals("Romania-holiday-", HolidayMasterType.ROMANIA_HOLIDAY.getFilenamePrefix());
    }
}
