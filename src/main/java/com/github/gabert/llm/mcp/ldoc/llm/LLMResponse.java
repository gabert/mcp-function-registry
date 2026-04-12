package com.github.gabert.llm.mcp.ldoc.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * Parsed shape of the LLM's JSON response.
 *
 * Fields:
 * <ul>
 *   <li>{@code purposeSummary} — discovery-oriented, embedded in Qdrant (always present)</li>
 *   <li>{@code summary} — behavioral description for developers (always present)</li>
 *   <li>{@code internalDocumentation} — caller-facing reference doc (always present)</li>
 *   <li>{@code toolDescription} — top-level natural-language description for the tool descriptor
 *       (null for non-public methods, where the prompt does not request it)</li>
 *   <li>{@code parameterDescriptions} — map of parameter NAME → per-parameter description
 *       (null for non-public methods; keys must match the actual parameter names)</li>
 * </ul>
 *
 * Unknown properties are ignored so additional fields do not break parsing.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LLMResponse(
        String purposeSummary,
        String summary,
        String internalDocumentation,
        String toolDescription,
        Map<String, String> parameterDescriptions
) {}
