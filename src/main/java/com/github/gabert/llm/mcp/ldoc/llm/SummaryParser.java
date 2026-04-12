package com.github.gabert.llm.mcp.ldoc.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses the LLM's JSON response into a {@link LLMResponse}. Strips markdown fences
 * if the model wrapped its output in ```json ... ``` despite instructions.
 *
 * On parse failure, logs a warning and returns a best-effort response with the raw
 * text dropped into the {@code summary} field so the pipeline can continue.
 */
public class SummaryParser {

    private static final Logger log = LoggerFactory.getLogger(SummaryParser.class);
    private final ObjectMapper mapper = new ObjectMapper();

    public LLMResponse parse(String llmResponse) {
        try {
            String json = llmResponse.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("```[a-z]*\\n?", "").trim();
            }
            return mapper.readValue(json, LLMResponse.class);
        } catch (Exception e) {
            log.warn("Failed to parse LLM response as JSON: {}", e.getMessage());
            return new LLMResponse("", llmResponse, "", null, null);
        }
    }
}
