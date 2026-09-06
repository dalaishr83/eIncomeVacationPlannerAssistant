package com.holidayleave.assistant.excel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IndianHolidayMasterExcelParserTest {

    @Test
    void generateCityMapping_rebuildsCitiesFromFirstTwoRowsStartingAtColumnE() throws Exception {
        Path tempDir = Files.createTempDirectory("indian-city-mapping-test");
        Path workbookPath = tempDir.resolve("India-holiday-2026.xlsx");
        Path dataDir = tempDir.resolve("data");
        Path mappingFile = dataDir.resolve("holiday-mapping/indian-city.json");
        Files.createDirectories(mappingFile.getParent());
        Files.write(mappingFile, "{\"cities\":{\"OldState\":\"oldcity\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("Holidays");
            sheet.createRow(0).createCell(4).setCellValue("Karnataka");
            sheet.getRow(0).createCell(5).setCellValue("Telangana");
            sheet.createRow(1).createCell(4).setCellValue(" Bengaluru, Mysore ");
            sheet.getRow(1).createCell(5).setCellValue(" Hyderabad ");
            try (java.io.OutputStream output = Files.newOutputStream(workbookPath)) {
                workbook.write(output);
            }
        }

        new IndianHolidayMasterExcelParser().generateCityMapping(workbookPath.toString(), dataDir.toString());

        Map<String, Object> json = new ObjectMapper().readValue(
                dataDir.resolve("holiday-mapping/indian-city.json").toFile(),
                new TypeReference<Map<String, Object>>() {});
        @SuppressWarnings("unchecked")
        Map<String, String> cities = (Map<String, String>) json.get("cities");
        assertEquals("bengaluru,mysore", cities.get("Karnataka"));
        assertEquals("hyderabad", cities.get("Telangana"));
        assertEquals(2, cities.size());
        org.junit.jupiter.api.Assertions.assertFalse(cities.containsKey("OldState"));
    }
}
