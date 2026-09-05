package com.holidayleave.assistant.model;

import java.util.Optional;

/**
 * Centralised enum for supported holiday master file types.
 *
 * <p>Each constant owns the filename prefix used to identify files uploaded to
 * {@code DATA_DIR/holiday-upload/}.  {@link #fromFilename(String)} is the single
 * entry-point for all filename-to-type resolution — no other class performs
 * its own filename pattern matching.
 *
 * <p>Filename conventions (wildcard {@code *} represents the year):
 * <ul>
 *   <li>{@code INDIAN_HOLIDAY}  → {@code India-holiday-*.xlsx}</li>
 *   <li>{@code DENMARK_HOLIDAY} → {@code Denmark-holiday-*.xlsx}</li>
 *   <li>{@code ROMANIA_HOLIDAY} → {@code Romania-holiday-*.xlsx}</li>
 * </ul>
 */
public enum HolidayMasterType {

    INDIAN_HOLIDAY ("India-holiday-"),
    DENMARK_HOLIDAY("Denmark-holiday-"),
    ROMANIA_HOLIDAY("Romania-holiday-");

    private final String filenamePrefix;

    HolidayMasterType(String filenamePrefix) {
        this.filenamePrefix = filenamePrefix;
    }

    /** Returns the canonical filename prefix for this holiday type. */
    public String getFilenamePrefix() {
        return filenamePrefix;
    }

    /**
     * Resolves a {@link HolidayMasterType} from the given filename by matching
     * the filename (case-insensitively) against each enum value's prefix.
     *
     * @param filename bare filename, e.g. {@code "India-holiday-2026.xlsx"}
     * @return the matched type, or {@link Optional#empty()} if unrecognised
     */
    public static Optional<HolidayMasterType> fromFilename(String filename) {
        if (filename == null || filename.trim().isEmpty()) {
            return Optional.empty();
        }
        String lower = filename.toLowerCase();
        for (HolidayMasterType type : values()) {
            if (lower.startsWith(type.filenamePrefix.toLowerCase())) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
