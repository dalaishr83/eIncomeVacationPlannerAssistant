package com.holidayleave.assistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.holidayleave.assistant.config.AppProperties;
import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * File-backed store for restricted (disabled) vacation type codes.
 * Persisted in /data/restrictedVacationType/restricted-vacation-types.json.
 * A restricted type is hidden from employee UI and rejected server-side on vacation add.
 */
@Service
public class RestrictedVacationTypeService {

    private static final Logger log = LoggerFactory.getLogger(RestrictedVacationTypeService.class);

    @Autowired
    private AppProperties props;

    private final ObjectMapper mapper = new ObjectMapper();
    private Path restrictedFilePath;

    @PostConstruct
    public void init() throws IOException {
        Path dataDir = Paths.get(props.getDataDir()).toAbsolutePath();
        restrictedFilePath = dataDir.resolve("restrictedVacationType")
                                    .resolve("restricted-vacation-types.json");
        Files.createDirectories(restrictedFilePath.getParent());

        if (!Files.exists(restrictedFilePath)) {
            // Default: no types restricted on first boot.
            save(new ArrayList<String>());
            log.info("RestrictedVacationTypeService: bootstrapped restricted-vacation-types.json");
        }
    }

    /** Returns the set of restricted type codes (upper-cased). */
    public List<String> getRestrictedTypes() {
        try {
            String[] arr = mapper.readValue(restrictedFilePath.toFile(), String[].class);
            List<String> result = new ArrayList<>();
            for (String s : arr) result.add(s.toUpperCase());
            return result;
        } catch (IOException e) {
            log.error("Failed to read restricted-vacation-types.json", e);
            return new ArrayList<>();
        }
    }

    /** Returns true if the given type code is currently restricted. */
    public boolean isRestricted(String code) {
        if (code == null) return false;
        for (String r : getRestrictedTypes()) {
            if (r.equalsIgnoreCase(code)) return true;
        }
        return false;
    }

    /** Replaces the entire restricted-types list. Codes are stored upper-cased. */
    public void setRestrictedTypes(List<String> codes) throws IOException {
        List<String> upper = new ArrayList<>();
        for (String c : codes) upper.add(c.toUpperCase());
        save(upper);
    }

    private void save(List<String> codes) throws IOException {
        Path tmp = restrictedFilePath.getParent().resolve("." + UUID.randomUUID() + ".tmp");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), codes);
            Files.move(tmp, restrictedFilePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }
}
