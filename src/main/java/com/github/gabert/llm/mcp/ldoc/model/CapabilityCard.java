package com.github.gabert.llm.mcp.ldoc.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Structured local contract for a public method, consumed by AI coding agents.
 *
 * Seven fields describing what the method does, its parameters, what it requires,
 * what it returns, what can go wrong, and what side effects it has.
 *
 * Generated only for {@link Visibility#PUBLIC} methods. Parameter names and types
 * are deterministic from the AST; the LLM contributes only descriptions.
 */
public record CapabilityCard(
        String signature,
        String summary,
        List<ParameterInfo> parameters,
        String preconditions,
        String returns,
        @JsonProperty("throws") String throwsDoc,
        String sideEffects
) {}
