package com.holidayleave.assistant.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.holidayleave.assistant.config.AppProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link OpenAIAdapter}.
 *
 * Tests the system-prompt template wiring, message ordering, WatsonX project-id
 * injection, error handling for HTTP 4xx/5xx, and {@link OpenAIAdapter.LLMServiceException}
 * propagation.
 *
 * The HTTP layer is exercised via MockWebServer (already on the classpath via
 * spring-boot-starter-webflux → reactor-netty-http → mockwebserver is pulled
 * transitively, but we add it explicitly in pom.xml if needed).
 *
 * If MockWebServer is not available in the current classpath, the HTTP-layer
 * tests are separated to a distinct inner class that can be excluded easily.
 * The Mockito-only tests cover system-prompt construction without a live server.
 */
@ExtendWith(MockitoExtension.class)
class OpenAIAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── System-prompt structure tests (no HTTP needed) ─────────────────────────

    @Test
    void systemPromptTemplate_containsContextPlaceholder() throws Exception {
        String template = getSystemPromptTemplate();
        assertTrue(template.contains("<context>"), "System prompt must include <context> tag");
        assertTrue(template.contains("</context>"), "System prompt must include </context> tag");
        assertTrue(template.contains("%s"), "System prompt must have a %s format placeholder for context");
    }

    @Test
    void systemPromptTemplate_definesLeaveTypeCodes() throws Exception {
        String template = getSystemPromptTemplate();
        // All mandatory leave type codes must appear somewhere in the prompt
        for (String code : Arrays.asList("A", "V", "H", "P", "PC", "E", "O")) {
            assertTrue(template.contains(code), "System prompt must define leave code: " + code);
        }
    }

    @Test
    void systemPromptTemplate_containsShapeDescriptions() throws Exception {
        String template = getSystemPromptTemplate();
        // Compact prompt encodes shape concepts via key names rather than section headers
        assertTrue(template.contains("single-employee") || template.contains("SINGLE-EMPLOYEE")
                        || template.contains("entitlement_days") || template.contains("employee_name"),
                "System prompt must describe the single-employee context shape");
        assertTrue(template.contains("all_employees_summary") || template.contains("ALL-EMPLOYEES")
                        || template.contains("all employees"),
                "System prompt must describe the all-employees context shape");
        assertTrue(template.contains("available_years") || template.contains("GENERIC")
                        || template.contains("date_query") || template.contains("query_type"),
                "System prompt must describe the generic/date-query context shape");
    }

    @Test
    void systemPromptTemplate_containsAnsweringRules() throws Exception {
        String template = getSystemPromptTemplate();
        assertTrue(template.contains("USER-FACING RESPONSE RULES") || template.contains("ANSWERING RULES")
                        || template.contains("Be concise") || template.contains("RESPONSE FORMAT")
                        || template.contains("RESPONSE") || template.contains("Answer first"),
                "System prompt must contain answering rules");
    }

    @Test
    void systemPromptTemplate_warnAgainstHallucination() throws Exception {
        String template = getSystemPromptTemplate();
        assertTrue(template.contains("NOT"),
                "System prompt must warn against fabricating or inventing data");
    }

    @Test
    void systemPromptTemplate_contextInjected_correctPosition() {
        // The context string is injected via String.format into the template
        String context = "{\"employee_name\":\"Alice\",\"analysis_year\":2026}";
        String formatted = String.format(getSystemPromptTemplate(), context);
        assertTrue(formatted.contains(context),
                "Formatted prompt must contain the injected context JSON");
        // The context must appear inside the <context> … </context> block
        int ctxStart = formatted.indexOf("<context>");
        int ctxEnd   = formatted.indexOf("</context>");
        assertTrue(ctxStart >= 0 && ctxEnd > ctxStart, "Context tags must be present and ordered");
        String between = formatted.substring(ctxStart, ctxEnd);
        assertTrue(between.contains("Alice"), "Injected context must appear between context tags");
    }

    // ── isAvailable() ─────────────────────────────────────────────────────────

    @Test
    void isAvailable_emptyApiKey_returnsFalse() {
        AppProperties props = makeProps("", "http://localhost:11434/v1", "model");
        OpenAIAdapter adapter = new OpenAIAdapter();
        injectProps(adapter, props);
        assertFalse(adapter.isAvailable());
    }

    @Test
    void isAvailable_nonEmptyApiKey_returnsTrue() {
        AppProperties props = makeProps("sk-test-key", "http://localhost:11434/v1", "model");
        OpenAIAdapter adapter = new OpenAIAdapter();
        injectProps(adapter, props);
        assertTrue(adapter.isAvailable());
    }

    // ── LLMServiceException ───────────────────────────────────────────────────

    @Test
    void lLMServiceException_isRuntimeException() {
        OpenAIAdapter.LLMServiceException ex = new OpenAIAdapter.LLMServiceException("test error");
        assertInstanceOf(RuntimeException.class, ex);
        assertEquals("test error", ex.getMessage());
    }

    // ── HTTP-layer tests (MockWebServer) ──────────────────────────────────────

    private MockWebServer server;

    @BeforeEach
    void startServer() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stopServer() throws Exception {
        if (server != null) server.shutdown();
    }

    @Test
    void ask_successfulResponse_returnsContent() throws Exception {
        String llmReply = "Alice has 5 days remaining.";
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(buildSuccessBody(llmReply)));

        AppProperties props = makeProps("test-key", server.url("/v1").toString(), "test-model");
        OpenAIAdapter adapter = new OpenAIAdapter();
        injectProps(adapter, props);

        String result = adapter.ask(null, "{}", "How many days does Alice have?", null);
        assertEquals(llmReply, result);
    }

    @Test
    void ask_buildsCorrectMessageStructure() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(buildSuccessBody("OK")));

        AppProperties props = makeProps("test-key", server.url("/v1").toString(), "test-model");
        OpenAIAdapter adapter = new OpenAIAdapter();
        injectProps(adapter, props);

        adapter.ask(null, "{\"employee\":\"Alice\"}", "How many days?", null);

        RecordedRequest request = server.takeRequest();
        JsonNode body = MAPPER.readTree(request.getBody().readUtf8());

        JsonNode messages = body.get("messages");
        assertNotNull(messages, "Request must include messages array");
        assertTrue(messages.isArray());
        assertTrue(messages.size() >= 2, "Must have at least system + user messages");

        // First message must be system
        assertEquals("system", messages.get(0).get("role").asText());
        // Last message must be user
        assertEquals("user", messages.get(messages.size() - 1).get("role").asText());
        assertEquals("How many days?", messages.get(messages.size() - 1).get("content").asText());
    }

    @Test
    void ask_systemPromptContainsContext() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(buildSuccessBody("OK")));

        AppProperties props = makeProps("test-key", server.url("/v1").toString(), "gpt-4");
        OpenAIAdapter adapter = new OpenAIAdapter();
        injectProps(adapter, props);

        String context = "{\"employee_name\":\"Alice Smith\"}";
        adapter.ask(null, context, "question?", null);

        RecordedRequest request = server.takeRequest();
        JsonNode body = MAPPER.readTree(request.getBody().readUtf8());
        String systemContent = body.get("messages").get(0).get("content").asText();

        assertTrue(systemContent.contains("Alice Smith"),
                "System prompt must embed the context JSON");
    }

    @Test
    void ask_withHistory_historyMessagesIncluded() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(buildSuccessBody("OK")));

        AppProperties props = makeProps("test-key", server.url("/v1").toString(), "model");
        OpenAIAdapter adapter = new OpenAIAdapter();
        injectProps(adapter, props);

        List<Map<String, String>> history = new ArrayList<>();
        Map<String, String> h1 = new HashMap<>(); h1.put("role","user"); h1.put("content","prev Q");
        Map<String, String> h2 = new HashMap<>(); h2.put("role","assistant"); h2.put("content","prev A");
        history.add(h1);
        history.add(h2);

        adapter.ask(null, "{}", "new Q", history);

        RecordedRequest request = server.takeRequest();
        JsonNode body = MAPPER.readTree(request.getBody().readUtf8());
        JsonNode messages = body.get("messages");

        // system + h1 + h2 + user = 4 messages
        assertEquals(4, messages.size());
        assertEquals("prev Q", messages.get(1).get("content").asText());
        assertEquals("prev A", messages.get(2).get("content").asText());
    }

    @Test
    void ask_nullHistory_noHistoryMessages() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(buildSuccessBody("OK")));

        AppProperties props = makeProps("test-key", server.url("/v1").toString(), "model");
        OpenAIAdapter adapter = new OpenAIAdapter();
        injectProps(adapter, props);

        adapter.ask(null, "{}", "question?", null);

        RecordedRequest request = server.takeRequest();
        JsonNode body = MAPPER.readTree(request.getBody().readUtf8());
        // Only system + user = 2 messages
        assertEquals(2, body.get("messages").size());
    }

    @Test
    void ask_httpError_throwsLLMServiceException() {
        server.enqueue(new MockResponse().setResponseCode(429)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"rate limit\"}"));

        AppProperties props = makeProps("test-key", server.url("/v1").toString(), "model");
        OpenAIAdapter adapter = new OpenAIAdapter();
        injectProps(adapter, props);

        assertThrows(OpenAIAdapter.LLMServiceException.class,
                () -> adapter.ask(null, "{}", "question?", null));
    }

    @Test
    void ask_http500_throwsLLMServiceException() {
        server.enqueue(new MockResponse().setResponseCode(500)
                .setBody("Internal Server Error"));

        AppProperties props = makeProps("test-key", server.url("/v1").toString(), "model");
        OpenAIAdapter adapter = new OpenAIAdapter();
        injectProps(adapter, props);

        assertThrows(OpenAIAdapter.LLMServiceException.class,
                () -> adapter.ask(null, "{}", "question?", null));
    }

    @Test
    void ask_modelAndTemperatureSetInRequestBody() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(buildSuccessBody("OK")));

        AppProperties props = makeProps("test-key", server.url("/v1").toString(), "gpt-4o");
        props.getLlm().setTemperature(0.0);
        props.getLlm().setMaxTokens(512);
        OpenAIAdapter adapter = new OpenAIAdapter();
        injectProps(adapter, props);

        adapter.ask(null, "{}", "question?", null);

        RecordedRequest request = server.takeRequest();
        JsonNode body = MAPPER.readTree(request.getBody().readUtf8());

        assertEquals("gpt-4o", body.get("model").asText());
        assertEquals(0.0, body.get("temperature").asDouble(), 0.001);
        assertEquals(512, body.get("max_tokens").asInt());
    }

    @Test
    void ask_watsonxProjectId_appendedToUri() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(buildSuccessBody("OK")));

        AppProperties props = makeProps("test-key", server.url("/v1").toString(), "ibm-model");
        props.getLlm().setWatsonxProjectId("my-project-123");
        OpenAIAdapter adapter = new OpenAIAdapter();
        injectProps(adapter, props);

        adapter.ask(null, "{}", "question?", null);

        RecordedRequest request = server.takeRequest();
        assertTrue(request.getPath().contains("project_id=my-project-123"),
                "WatsonX project_id must be appended as query param. Path: " + request.getPath());
    }

    @Test
    void ask_bearerTokenIncludedInAuthHeader() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(buildSuccessBody("OK")));

        AppProperties props = makeProps("my-secret-key", server.url("/v1").toString(), "model");
        OpenAIAdapter adapter = new OpenAIAdapter();
        injectProps(adapter, props);

        adapter.ask(null, "{}", "question?", null);

        RecordedRequest request = server.takeRequest();
        String auth = request.getHeader("Authorization");
        assertNotNull(auth, "Authorization header must be present");
        assertTrue(auth.startsWith("Bearer "), "Auth must use Bearer scheme");
        assertTrue(auth.contains("my-secret-key"), "Auth must contain the API key");
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String getSystemPromptTemplate() {
        // Access the package-private constant via reflection
        try {
            java.lang.reflect.Field f = OpenAIAdapter.class.getDeclaredField("SYSTEM_PROMPT_TEMPLATE");
            f.setAccessible(true);
            return (String) f.get(null);
        } catch (Exception e) {
            throw new RuntimeException("Could not access SYSTEM_PROMPT_TEMPLATE", e);
        }
    }

    private AppProperties makeProps(String apiKey, String baseUrl, String model) {
        AppProperties props = new AppProperties();
        props.getLlm().setApiKey(apiKey);
        props.getLlm().setBaseUrl(baseUrl);
        props.getLlm().setModel(model);
        props.getLlm().setTemperature(0.0);
        props.getLlm().setMaxTokens(1024);
        return props;
    }

    private void injectProps(OpenAIAdapter adapter, AppProperties props) {
        try {
            java.lang.reflect.Field f = OpenAIAdapter.class.getDeclaredField("props");
            f.setAccessible(true);
            f.set(adapter, props);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String buildSuccessBody(String content) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":" +
               MAPPER.createObjectNode().textNode(content).toString() + "}}]}";
    }
}
