package com.holidayleave.assistant.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.holidayleave.assistant.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;
import java.util.Map;

/**
 * OpenAI-compatible LLM adapter.
 * Works with OpenAI, Ollama, OpenRouter, IBM Watsonx, Groq, Gemini by swapping base URL and model.
 */
@Service
public class OpenAIAdapter implements LLMService {

    private static final Logger log = LoggerFactory.getLogger(OpenAIAdapter.class);

    private static final String SYSTEM_PROMPT_TEMPLATE =
"You are a helpful, conversational assistant for employee leave management.\n" +
"Answer every factual question using ONLY the information in the <context> block below.\n" +
"Never invent, guess, or use external knowledge for leave figures.\n\n" +

"=== LEAVE TYPE CODES ===\n" +
"Codes used as keys in by_type / by_month_by_type:\n" +
"  A=Available (NOT a leave day — never count), P=Public Holiday, PC=Personal Choice Holiday,\n" +
"  V=Vacation, H=Half-day (stored as 0.5 — never re-compute), E=Education, O=Other leave.\n" +
"P and PC are DIFFERENT keys — never substitute one for the other.\n" +
"GENERIC query = user says leave/days/holiday/vacation/days off without naming a specific code → all non-A types.\n" +
"TYPE-SPECIFIC query = user explicitly names a code (P, PC, V, H, E, O) or its full label.\n" +
"'vacation' alone is GENERIC. Only 'V leave' / 'type V' / 'vacation type V' are type-specific for V.\n\n" +

"=== CONTEXT SHAPES ===\n" +
"single-employee: employee_name + entitlement_days/consumed_days/remaining_days + by_month + by_type.\n" +
"all-employees: all_employees_summary (map: name → total days) + total_employees.\n" +
"date-query: query_type=date_query + employees_on_leave array.\n\n" +

"=== DECISION RULES ===\n" +
"Read is_range_query and context_scope_month. Apply exactly one rule.\n\n" +

"RULE 1 — Generic range query (is_range_query=true, no specific code)\n" +
"  Grand total → total_all_leave_types_in_range. Per-month → by_month[month].\n" +
"  NEVER use by_type or by_month_by_type here.\n\n" +

"RULE 2 — Type-specific range query (is_range_query=true, specific code named)\n" +
"  Grand total → by_type[\"CODE\"] or 0. Per-month → by_month_by_type[month][\"CODE\"] or 0.\n" +
"  Absent key = 0 days. NEVER use by_month or total_all_leave_types_in_range here.\n\n" +

"RULE 3 — Generic single-month query (context_scope_month present, no specific code)\n" +
"  Answer → total_all_leave_types_in_month.\n\n" +

"RULE 4 — Type-specific single-month query (context_scope_month present, specific code named)\n" +
"  Answer → by_type[\"CODE\"] or 0.\n\n" +

"RULE 5 — Full-year query (neither is_range_query nor context_scope_month present)\n" +
"  Total=entitlement_days, Consumed=consumed_days, Remaining=remaining_days, By type=by_type.\n\n" +

"=== DATE-QUERY RULE ===\n" +
"When query_type=date_query: list EVERY entry in employees_on_leave (do not omit or abbreviate).\n" +
"State each employee's name and leave type. End with 'X employee(s) are on leave on [date_display].'\n" +
"If employees_on_leave is empty: say no one is on leave. Never invent employees.\n\n" +

"=== ACCURACY ===\n" +
"- Absent key in by_type or by_month_by_type = 0 days. Say '0 days' — never say unavailable.\n" +
"- 'leaves'/'leave days' = days, not span counts. Never use total_records_shown for day counts.\n" +
"- by_month distributes days proportionally across month boundaries — do not re-sum.\n" +
"- History clarifies pronouns/follow-ups but never overrides current <context> figures.\n\n" +

"=== RESPONSE ===\n" +
"Answer first. Short, conversational, no disclaimers. One sentence for simple facts.\n" +
"Whole numbers ('2 days' not '2.0'). Never expose field names, JSON keys, or internal logic.\n" +
"Respond in the same language the user writes in.\n\n" +

"<context>\n%s\n</context>\n";


    @Autowired
    private AppProperties props;

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String ask(String systemPrompt, String context, String question, List<Map<String, String>> history) {
        log.debug("LLM context for [{}]: {}", question, context);
        String template = (systemPrompt != null) ? systemPrompt : SYSTEM_PROMPT_TEMPLATE;
        String effectivePrompt = String.format(template, context);

        WebClient client = buildClient();

        ObjectNode body = mapper.createObjectNode();
        body.put("model", props.getLlm().getModel());
        body.put("temperature", props.getLlm().getTemperature());
        body.put("max_tokens", props.getLlm().getMaxTokens());

        ArrayNode messages = body.putArray("messages");

        ObjectNode sysMsg = mapper.createObjectNode();
        sysMsg.put("role", "system");
        sysMsg.put("content", effectivePrompt);
        messages.add(sysMsg);

        if (history != null) {
            for (Map<String, String> h : history) {
                ObjectNode hMsg = mapper.createObjectNode();
                hMsg.put("role", h.get("role"));
                hMsg.put("content", h.get("content"));
                messages.add(hMsg);
            }
        }

        ObjectNode userMsg = mapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", question);
        messages.add(userMsg);

        if (!props.getLlm().getWatsonxProjectId().isEmpty()) {
            body.put("project_id", props.getLlm().getWatsonxProjectId());
        }

        try {
            String uri = "/chat/completions";
            if (!props.getLlm().getWatsonxProjectId().isEmpty()) {
                uri += "?project_id=" + props.getLlm().getWatsonxProjectId();
            }
            String response = client.post()
                    .uri(uri)
                    .bodyValue(body.toString())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode root = mapper.readTree(response);
            return root.path("choices").get(0).path("message").path("content").asText();
        } catch (WebClientResponseException e) {
            log.error("LLM API error: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new LLMServiceException("LLM service error: " + e.getMessage());
        } catch (Exception e) {
            log.error("LLM call failed: {}", e.getMessage());
            throw new LLMServiceException("LLM call failed: " + e.getMessage());
        }
    }

    @Override
    public boolean isAvailable() {
        return !props.getLlm().getApiKey().isEmpty();
    }

    private WebClient buildClient() {
        return WebClient.builder()
                .baseUrl(props.getLlm().getBaseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.getLlm().getApiKey())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
    }

    public static class LLMServiceException extends RuntimeException {
        public LLMServiceException(String msg) { super(msg); }
    }
}
