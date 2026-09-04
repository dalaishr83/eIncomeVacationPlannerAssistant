package com.holidayleave.assistant.llm;

import java.util.List;
import java.util.Map;

/**
 * Abstract LLM service interface (Dependency Inversion).
 * Concrete implementations swap providers by changing LLM_BASE_URL.
 */
public interface LLMService {

    /**
     * Send a chat completion request.
     *
     * @param systemPrompt  The system prompt
     * @param context       JSON string of the data context
     * @param question      The user question
     * @param history       Prior conversation turns (may be null or empty)
     * @return              The LLM's text response
     */
    String ask(String systemPrompt, String context, String question, List<Map<String, String>> history);

    /**
     * Check if the LLM service is configured and reachable.
     */
    boolean isAvailable();
}
