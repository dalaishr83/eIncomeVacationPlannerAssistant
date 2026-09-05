package com.holidayleave.assistant.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Encapsulates all backend logic for the two new Settings panels:
 *
 * <ul>
 *   <li><b>Sync Indian Holiday</b> — discovers .xlsx files under {@code DATA_DIR/holiday-upload/}</li>
 *   <li><b>Mapped Employee City</b> — reads Indian employees from the active Master Excel
 *       (Col A = name, Col B = country code "IN"), and manages
 *       {@code DATA_DIR/holiday-mapping/employee-mapping.json} and
 *       {@code DATA_DIR/holiday-mapping/indian-city.json}.</li>
 * </ul>
 *
 * <p>All paths are derived from the {@code dataDir} supplied by {@link AppState} — nothing
 * is hard-coded to {@code /data/...}.
 */
@Service
public class HolidaySettingsService {

    private static final Logger log = LoggerFactory.getLogger(HolidaySettingsService.class);

    private static final String HOLIDAY_UPLOAD_SUBDIR  = "holiday-upload";
    private static final String HOLIDAY_MAPPING_SUBDIR = "holiday-mapping";
    private static final String MAPPING_FILENAME       = "employee-mapping.json";
    private static final String CITY_FILENAME          = "indian-city.json";

    private final ObjectMapper jackson = new ObjectMapper();

    // ── Holiday upload directory ───────────────────────────────────────────────

    /**
     * Returns the names (not full paths) of all .xlsx files found in
     * {@code DATA_DIR/holiday-upload/}.
     */
    public List<String> listHolidayFiles(String dataDir) {
        List<String> result = new ArrayList<>();
        File dir = Paths.get(dataDir, HOLIDAY_UPLOAD_SUBDIR).toFile();
        if (!dir.exists()) {
            try { Files.createDirectories(dir.toPath()); } catch (IOException ignored) {}
            return result;
        }
        File[] files = dir.listFiles(f -> f.isFile() && f.getName().toLowerCase().endsWith(".xlsx"));
        if (files != null) {
            Arrays.sort(files, Comparator.comparing(File::getName));
            for (File f : files) result.add(f.getName());
        }
        return result;
    }

    // ── Employee-city mapping ─────────────────────────────────────────────────

    /**
     * Returns the full contents of employee-mapping.json as a parsed map:
     * {@code { "employees": { "<name>": { "city": [...], "country": "IN" } } }}.
     * Returns an empty structure (never null) if the file does not exist.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> readMapping(String dataDir) {
        Path path = mappingPath(dataDir);
        if (!path.toFile().exists()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("employees", new LinkedHashMap<String, Object>());
            return empty;
        }
        try {
            return jackson.readValue(path.toFile(),
                    new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            log.warn("Failed to read employee mapping: {}", e.getMessage());
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("employees", new LinkedHashMap<String, Object>());
            return empty;
        }
    }

    /**
     * Persists the full mapping map back to employee-mapping.json.
     * Creates the {@code holiday-mapping/} directory if it does not exist.
     *
     * @param dataDir absolute data directory
     * @param mapping the full map including the "employees" key
     * @throws IOException on write failure
     */
    public void writeMapping(String dataDir, Map<String, Object> mapping) throws IOException {
        Path dir  = Paths.get(dataDir, HOLIDAY_MAPPING_SUBDIR);
        Files.createDirectories(dir);
        Path file = dir.resolve(MAPPING_FILENAME);
        jackson.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), mapping);
        log.info("Employee mapping saved to {}", file);
    }

    // ── City list ─────────────────────────────────────────────────────────────

    /**
     * Returns the city map from {@code DATA_DIR/holiday-mapping/indian-city.json}
     * as {@code { label -> comma-separated city string }}.
     * If the file does not exist an empty seed {@code {"cities":{}}} is written and
     * an empty map is returned.  No hard-coded city names are ever introduced.
     */
    public Map<String, String> readCities(String dataDir) {
        Path dir  = Paths.get(dataDir, HOLIDAY_MAPPING_SUBDIR);
        Path file = dir.resolve(CITY_FILENAME);
        if (!file.toFile().exists()) {
            try {
                Files.createDirectories(dir);
                Map<String, Object> seed = new LinkedHashMap<>();
                seed.put("cities", new LinkedHashMap<String, String>());
                jackson.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), seed);
                log.info("Created empty city list at {}", file);
            } catch (IOException e) {
                log.warn("Could not create empty city list: {}", e.getMessage());
            }
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> data = jackson.readValue(file.toFile(),
                    new TypeReference<Map<String, Object>>() {});
            Object raw = data.get("cities");
            if (raw instanceof Map) {
                Map<String, String> result = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
                    result.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
                return result;
            }
        } catch (IOException e) {
            log.warn("Failed to read city list: {}", e.getMessage());
        }
        return new LinkedHashMap<>();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Path mappingPath(String dataDir) {
        return Paths.get(dataDir, HOLIDAY_MAPPING_SUBDIR, MAPPING_FILENAME);
    }
}
