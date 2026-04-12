package com.github.gabert.llm.mcp.ldoc.model;

public class ParameterInfo {

    private String name;
    private String type;
    private String description;  // LLM-generated, nullable — filled only for public methods

    public ParameterInfo() {}

    public ParameterInfo(String type, String name) {
        this.type = type;
        this.name = name;
    }

    public ParameterInfo(String type, String name, String description) {
        this.type = type;
        this.name = name;
        this.description = description;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
