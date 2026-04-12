package com.github.gabert.llm.mcp.ldoc.model;

import java.util.List;

/**
 * A method descriptor shaped for LLM tool-use protocols, intended as a
 * code-implementation hint for coding agents.
 *
 * The primary consumer is a coding agent that is writing <i>new code</i> and needs
 * to decide which existing method to call. It is NOT a runtime tool-use registration:
 * the agent reads the descriptor and emits a source-code call, rather than invoking
 * the method at runtime through a tool-use API.
 *
 * Because of that framing, identity is the full {@link MethodCoordinate#globalId()}
 * (unambiguous across packages, modules, and repos) rather than a short sanitized name.
 * A projection to Anthropic / OpenAI / MCP tool-schema shapes can be computed on demand;
 * this class holds the canonical form.
 *
 * Descriptors are generated only for {@link Visibility#PUBLIC} methods.
 */
public class ToolDescriptor {

    private String coordinate;        // = MethodCoordinate.globalId()
    private String displayName;       // short "ClassName.methodName" for UI
    private String language;          // "java" for now
    private String description;       // LLM-generated top-level description
    private List<ToolParameter> parameters;
    private String returnType;        // native FQN string; no JSON-Schema projection in v1

    public ToolDescriptor() {}

    public String getCoordinate() { return coordinate; }
    public void setCoordinate(String coordinate) { this.coordinate = coordinate; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<ToolParameter> getParameters() { return parameters; }
    public void setParameters(List<ToolParameter> parameters) { this.parameters = parameters; }

    public String getReturnType() { return returnType; }
    public void setReturnType(String returnType) { this.returnType = returnType; }
}
