package com.holidayleave.assistant.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AppProperties}.
 *
 * Verifies default values for every configuration field and the nested
 * Llm, Box, and Slack sub-configs, plus setter/getter round-trips.
 * No Spring context required — exercises the POJO directly.
 */
class AppPropertiesTest {

    // =========================================================================
    // Top-level defaults
    // =========================================================================

    @Test
    void defaults_dataDir() {
        assertEquals("data", new AppProperties().getDataDir());
    }

    @Test
    void defaults_reportOutputDir() {
        assertEquals("reports", new AppProperties().getReportOutputDir());
    }

    @Test
    void defaults_excelFilePaths_emptyString() {
        assertEquals("", new AppProperties().getExcelFilePaths());
    }

    @Test
    void defaults_syncIntervalSeconds() {
        assertEquals(300, new AppProperties().getSyncIntervalSeconds());
    }

    @Test
    void defaults_loginUsername() {
        assertEquals("admin", new AppProperties().getLoginUsername());
    }

    @Test
    void defaults_loginPasswordHash_emptyString() {
        assertEquals("", new AppProperties().getLoginPasswordHash());
    }

    // =========================================================================
    // Top-level setters
    // =========================================================================

    @Test
    void setDataDir_roundTrip() {
        AppProperties p = new AppProperties();
        p.setDataDir("/mnt/data");
        assertEquals("/mnt/data", p.getDataDir());
    }

    @Test
    void setReportOutputDir_roundTrip() {
        AppProperties p = new AppProperties();
        p.setReportOutputDir("/tmp/reports");
        assertEquals("/tmp/reports", p.getReportOutputDir());
    }

    @Test
    void setSyncIntervalSeconds_roundTrip() {
        AppProperties p = new AppProperties();
        p.setSyncIntervalSeconds(60);
        assertEquals(60, p.getSyncIntervalSeconds());
    }

    @Test
    void setLoginUsername_roundTrip() {
        AppProperties p = new AppProperties();
        p.setLoginUsername("superadmin");
        assertEquals("superadmin", p.getLoginUsername());
    }

    @Test
    void setLoginPasswordHash_roundTrip() {
        AppProperties p = new AppProperties();
        p.setLoginPasswordHash("$2a$10$abc");
        assertEquals("$2a$10$abc", p.getLoginPasswordHash());
    }

    // =========================================================================
    // Llm nested config — defaults
    // =========================================================================

    @Test
    void llm_notNull() {
        assertNotNull(new AppProperties().getLlm());
    }

    @Test
    void llm_defaults_apiKey_emptyString() {
        assertEquals("", new AppProperties().getLlm().getApiKey());
    }

    @Test
    void llm_defaults_baseUrl() {
        assertEquals("http://127.0.0.1:11434/v1", new AppProperties().getLlm().getBaseUrl());
    }

    @Test
    void llm_defaults_model() {
        assertEquals("llama3.2", new AppProperties().getLlm().getModel());
    }

    @Test
    void llm_defaults_temperature() {
        assertEquals(0.0, new AppProperties().getLlm().getTemperature(), 0.001);
    }

    @Test
    void llm_defaults_maxTokens() {
        assertEquals(1024, new AppProperties().getLlm().getMaxTokens());
    }

    @Test
    void llm_defaults_watsonxProjectId_emptyString() {
        assertEquals("", new AppProperties().getLlm().getWatsonxProjectId());
    }

    @Test
    void llm_setters_roundTrip() {
        AppProperties.Llm llm = new AppProperties().getLlm();
        llm.setApiKey("sk-test");
        llm.setBaseUrl("https://api.openai.com/v1");
        llm.setModel("gpt-4o");
        llm.setTemperature(0.7);
        llm.setMaxTokens(2048);
        llm.setWatsonxProjectId("proj-abc");

        assertEquals("sk-test",                    llm.getApiKey());
        assertEquals("https://api.openai.com/v1",  llm.getBaseUrl());
        assertEquals("gpt-4o",                     llm.getModel());
        assertEquals(0.7,                          llm.getTemperature(), 0.001);
        assertEquals(2048,                         llm.getMaxTokens());
        assertEquals("proj-abc",                   llm.getWatsonxProjectId());
    }

    // =========================================================================
    // Box nested config — defaults
    // =========================================================================

    @Test
    void box_notNull() {
        assertNotNull(new AppProperties().getBox());
    }

    @Test
    void box_defaults_enabled_false() {
        assertFalse(new AppProperties().getBox().isEnabled());
    }

    @Test
    void box_defaults_strings_emptyString() {
        AppProperties.Box box = new AppProperties().getBox();
        assertEquals("", box.getClientId());
        assertEquals("", box.getClientSecret());
        assertEquals("", box.getEnterpriseId());
        assertEquals("", box.getFolderId());
        assertEquals("", box.getJwtPrivateKey());
        assertEquals("", box.getJwtPrivateKeyPassphrase());
        assertEquals("", box.getJwtPublicKeyId());
    }

    @Test
    void box_defaults_retryBackoffSeconds() {
        assertEquals(60, new AppProperties().getBox().getRetryBackoffSeconds());
    }

    @Test
    void box_setters_roundTrip() {
        AppProperties.Box box = new AppProperties().getBox();
        box.setEnabled(true);
        box.setClientId("cid");
        box.setClientSecret("sec");
        box.setFolderId("fid");
        box.setRetryBackoffSeconds(120);

        assertTrue(box.isEnabled());
        assertEquals("cid", box.getClientId());
        assertEquals("sec", box.getClientSecret());
        assertEquals("fid", box.getFolderId());
        assertEquals(120,   box.getRetryBackoffSeconds());
    }

    // =========================================================================
    // Slack nested config — defaults
    // =========================================================================

    @Test
    void slack_notNull() {
        assertNotNull(new AppProperties().getSlack());
    }

    @Test
    void slack_defaults_enabled_false() {
        assertFalse(new AppProperties().getSlack().isEnabled());
    }

    @Test
    void slack_defaults_webhookUrl_emptyString() {
        assertEquals("", new AppProperties().getSlack().getWebhookUrl());
    }

    @Test
    void slack_defaults_pcLeaveCode() {
        assertEquals("PC", new AppProperties().getSlack().getPcLeaveCode());
    }

    @Test
    void slack_setters_roundTrip() {
        AppProperties.Slack slack = new AppProperties().getSlack();
        slack.setEnabled(true);
        slack.setWebhookUrl("https://hooks.slack.com/abc");
        slack.setPcLeaveCode("PH");

        assertTrue(slack.isEnabled());
        assertEquals("https://hooks.slack.com/abc", slack.getWebhookUrl());
        assertEquals("PH", slack.getPcLeaveCode());
    }

    // =========================================================================
    // Nested config setters on AppProperties
    // =========================================================================

    @Test
    void setLlm_replaces() {
        AppProperties p = new AppProperties();
        AppProperties.Llm newLlm = new AppProperties.Llm();
        newLlm.setModel("gpt-4");
        p.setLlm(newLlm);
        assertEquals("gpt-4", p.getLlm().getModel());
    }

    @Test
    void setBox_replaces() {
        AppProperties p = new AppProperties();
        AppProperties.Box newBox = new AppProperties.Box();
        newBox.setEnabled(true);
        p.setBox(newBox);
        assertTrue(p.getBox().isEnabled());
    }

    @Test
    void setSlack_replaces() {
        AppProperties p = new AppProperties();
        AppProperties.Slack newSlack = new AppProperties.Slack();
        newSlack.setEnabled(true);
        p.setSlack(newSlack);
        assertTrue(p.getSlack().isEnabled());
    }
}
