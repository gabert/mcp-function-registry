package com.github.gabert.llm.mcp.ldoc.model;

/**
 * One parameter of a {@link ToolDescriptor}. Carries the native (language-specific)
 * type string alongside the LLM-generated natural-language description.
 *
 * The native type is the source-of-record for code-generating consumers. A JSON-Schema
 * projection can be derived from it on demand for tool-use protocol compatibility.
 */
public class ToolParameter {

    private String name;
    private String nativeType;
    private String description;

    public ToolParameter() {}

    public ToolParameter(String name, String nativeType, String description) {
        this.name = name;
        this.nativeType = nativeType;
        this.description = description;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getNativeType() { return nativeType; }
    public void setNativeType(String nativeType) { this.nativeType = nativeType; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
