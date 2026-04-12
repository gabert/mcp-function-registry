package com.github.gabert.llm.mcp.ldoc.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.Map;

/**
 * Parsed shape of the LLM's JSON response.
 *
 * Three artifacts per method:
 * <ul>
 *   <li>{@code purposeSummary} — discovery-oriented, embedded in Qdrant for semantic search.
 *       As long as needed for useful retrieval.</li>
 *   <li>{@code developerDoc} — extended behavioral summary for humans. Call-chain-aware:
 *       covers not just the method body but what its callees do.</li>
 *   <li>{@code capabilityCard} — structured contract for AI coding agents
 *       (null for non-public methods). Signature and parameter names/types are injected
 *       deterministically after parsing; the LLM contributes descriptions only.</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LLMResponse(
        String purposeSummary,
        String developerDoc,
        CapabilityCardResponse capabilityCard,
        CodeHealthResponse codeHealth
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CodeHealthResponse(
            String rating,
            String note
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CapabilityCardResponse(
            Map<String, String> parameterDescriptions,
            @JsonDeserialize(using = StringOrArrayDeserializer.class) String preconditions,
            @JsonDeserialize(using = StringOrArrayDeserializer.class) String returns,
            @JsonDeserialize(using = StringOrArrayDeserializer.class) @JsonProperty("throws") String throwsDoc,
            @JsonDeserialize(using = StringOrArrayDeserializer.class) String sideEffects
    ) {}
}
