package com.holidayleave.assistant.service;

import com.holidayleave.assistant.config.AppProperties;
import com.holidayleave.assistant.model.VacationType;
import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

@Service
public class VacationTypeService {

    private static final Logger log = LoggerFactory.getLogger(VacationTypeService.class);

    private static final List<VacationType> DEFAULTS;
    static {
        DEFAULTS = new ArrayList<>();
        DEFAULTS.add(new VacationType("V",  "Vacation",                "FF92D050"));
        DEFAULTS.add(new VacationType("P",  "Public Holiday",          "FFFF0000"));
        DEFAULTS.add(new VacationType("PC", "Personal Choice Holiday", "FFFFFF00"));
        DEFAULTS.add(new VacationType("H",  "Half-day Vacation",       "FFFFC000"));
        DEFAULTS.add(new VacationType("E",  "Education",               "FF00B0F0"));
        DEFAULTS.add(new VacationType("O",  "Other",                   "FFD3D3D3"));
        DEFAULTS.add(new VacationType("A",  "Available",               "FFFFFFFF"));
    }

    @Autowired
    private AppProperties props;

    private final ObjectMapper mapper = new ObjectMapper();
    private Path typesFilePath;

    @PostConstruct
    public void init() throws IOException {
        // Resolve to absolute so it ends up in the same place regardless of working directory
        typesFilePath = Paths.get(props.getDataDir()).toAbsolutePath().resolve("vacation_types.json");
        Files.createDirectories(typesFilePath.getParent());
        if (!Files.exists(typesFilePath)) {
            save(new ArrayList<>(DEFAULTS));
        }
    }

    public List<VacationType> findAll() {
        try {
            VacationType[] arr = mapper.readValue(typesFilePath.toFile(), VacationType[].class);
            return new ArrayList<>(Arrays.asList(arr));
        } catch (IOException e) {
            log.error("Failed to read vacation_types.json, returning defaults", e);
            return new ArrayList<>(DEFAULTS);
        }
    }

    public Optional<VacationType> findByCode(String code) {
        for (VacationType t : findAll()) {
            if (t.code().equalsIgnoreCase(code)) return Optional.of(t);
        }
        return Optional.empty();
    }

    public Optional<VacationType> findByLabel(String label) {
        for (VacationType t : findAll()) {
            if (t.label().equalsIgnoreCase(label)) return Optional.of(t);
        }
        return Optional.empty();
    }

    public VacationType add(VacationType type) throws IllegalStateException {
        List<VacationType> types = findAll();
        for (VacationType t : types) {
            if (t.code().equalsIgnoreCase(type.code())) {
                throw new IllegalStateException("Vacation type code '" + type.code() + "' already exists.");
            }
        }
        types.add(type);
        try { save(types); } catch (IOException e) { throw new RuntimeException(e); }
        return type;
    }

    public VacationType update(String code, String newLabel, String newColor) throws NoSuchElementException {
        List<VacationType> types = findAll();
        for (int i = 0; i < types.size(); i++) {
            if (types.get(i).code().equalsIgnoreCase(code)) {
                VacationType updated = new VacationType(
                        types.get(i).code(),
                        newLabel != null ? newLabel : types.get(i).label(),
                        newColor != null ? newColor : types.get(i).color()
                );
                types.set(i, updated);
                try { save(types); } catch (IOException e) { throw new RuntimeException(e); }
                return updated;
            }
        }
        throw new NoSuchElementException("Vacation type code '" + code + "' not found.");
    }

    private void save(List<VacationType> types) throws IOException {
        Path tmp = typesFilePath.getParent().resolve("." + UUID.randomUUID() + ".tmp");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), types);
            Files.move(tmp, typesFilePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }
}
