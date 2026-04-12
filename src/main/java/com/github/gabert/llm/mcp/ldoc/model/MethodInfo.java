package com.github.gabert.llm.mcp.ldoc.model;

import java.util.List;
import java.util.Map;

/**
 * Holds all extracted data for a single Java method.
 */
public class MethodInfo {

    private MethodCoordinate coordinate;  // globally unique across repos/modules
    private String packageName;
    private String className;
    private String methodName;
    private String signature;             // full declaration with modifiers, throws, param names
    private String returnType;
    private List<ParameterInfo> parameters;
    private Visibility visibility;        // source-level access modifier
    private String body;
    private List<String> calleeIds;       // globalId() of called methods (project-internal only)
    private String sourceFile;
    private String bodyHash;

    private String existingJavadoc;       // extracted from source, if present

    // Populated after LLM processing
    private String summary;               // Developer-facing: what the code does (behavioral, detailed)
    private String purposeSummary;        // Consumer-facing: why/when to call this (intent, used as RAG embedding text)
    private String internalDocumentation; // Caller-facing reference doc (replaces the old generatedJavadoc)
    private ToolDescriptor toolDescriptor; // LLM tool-use shaped descriptor (PUBLIC methods only; null otherwise)
    private Map<String, String> calleeSummaries; // globalId -> summary

    public MethodInfo() {}

    /** Shorthand: returns coordinate.globalId() */
    public String getId() { return coordinate != null ? coordinate.globalId() : null; }

    public MethodCoordinate getCoordinate() { return coordinate; }
    public void setCoordinate(MethodCoordinate coordinate) { this.coordinate = coordinate; }

    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) { this.packageName = packageName; }

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }

    public String getMethodName() { return methodName; }
    public void setMethodName(String methodName) { this.methodName = methodName; }

    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }

    public String getReturnType() { return returnType; }
    public void setReturnType(String returnType) { this.returnType = returnType; }

    public List<ParameterInfo> getParameters() { return parameters; }
    public void setParameters(List<ParameterInfo> parameters) { this.parameters = parameters; }

    public Visibility getVisibility() { return visibility; }
    public void setVisibility(Visibility visibility) { this.visibility = visibility; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public List<String> getCalleeIds() { return calleeIds; }
    public void setCalleeIds(List<String> calleeIds) { this.calleeIds = calleeIds; }

    public String getSourceFile() { return sourceFile; }
    public void setSourceFile(String sourceFile) { this.sourceFile = sourceFile; }

    public String getBodyHash() { return bodyHash; }
    public void setBodyHash(String bodyHash) { this.bodyHash = bodyHash; }

    public String getExistingJavadoc() { return existingJavadoc; }
    public void setExistingJavadoc(String existingJavadoc) { this.existingJavadoc = existingJavadoc; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getPurposeSummary() { return purposeSummary; }
    public void setPurposeSummary(String purposeSummary) { this.purposeSummary = purposeSummary; }

    public String getInternalDocumentation() { return internalDocumentation; }
    public void setInternalDocumentation(String internalDocumentation) { this.internalDocumentation = internalDocumentation; }

    public ToolDescriptor getToolDescriptor() { return toolDescriptor; }
    public void setToolDescriptor(ToolDescriptor toolDescriptor) { this.toolDescriptor = toolDescriptor; }

    public Map<String, String> getCalleeSummaries() { return calleeSummaries; }
    public void setCalleeSummaries(Map<String, String> calleeSummaries) { this.calleeSummaries = calleeSummaries; }
}
