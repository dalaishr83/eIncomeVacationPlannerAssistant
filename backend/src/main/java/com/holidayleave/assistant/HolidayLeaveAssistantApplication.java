package com.holidayleave.assistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties
public class HolidayLeaveAssistantApplication {

    public static void main(String[] args) {
        // Load .env file if present.
        // NOTE: dotenv-java 2.x performs $-variable substitution which corrupts bcrypt hashes.
        // We load the file manually to avoid this.
        loadDotEnvSafe();
        SpringApplication.run(HolidayLeaveAssistantApplication.class, args);
    }

    /**
     * Reads .env line by line without any variable substitution.
     * Supports: KEY=value, KEY="value", KEY='value', blank lines, # comments.
     * Also sets "app.base.dir" to the absolute directory containing the .env file
     * so that relative DATA_DIR / REPORT_OUTPUT_DIR values can be resolved correctly
     * regardless of the JVM working directory (e.g. Tomcat temp dir).
     */
    private static void loadDotEnvSafe() {
        java.io.File envFile = new java.io.File(".env");
        if (!envFile.exists()) {
            // also try one level up (in case run from backend/ subdirectory)
            envFile = new java.io.File("../.env");
        }
        if (!envFile.exists()) return;
        // Record the project root so AppState can resolve relative paths against it
        try {
            System.setProperty("app.base.dir", envFile.getCanonicalFile().getParent());
        } catch (Exception e) {
            System.setProperty("app.base.dir", envFile.getAbsoluteFile().getParent());
        }
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(envFile),
                        java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq < 1) continue;
                String key   = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                // Strip surrounding quotes if present
                if ((value.startsWith("\"") && value.endsWith("\"")) ||
                    (value.startsWith("'")  && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                // Only set if not already provided by OS environment
                if (System.getenv(key) == null && System.getProperty(key) == null) {
                    System.setProperty(key, value);
                }
            }
        } catch (Exception e) {
            System.err.println("[dotenv] Failed to load .env: " + e.getMessage());
        }
    }
}
