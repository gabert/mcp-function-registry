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
    private MethodRange range;           // byte-offset free; used to re-slice body from disk on demand
    private String bodyHash;

    private String existingJavadoc;       // extracted from source, if present

    // Populated after LLM processing
    private String purposeSummary;        // Discovery-oriented: embedded in Qdrant for semantic search
    private String developerDoc;          // Extended behavioral summary for humans (call-chain-aware)
    private CapabilityCard capabilityCard; // Structured local contract for AI agents (PUBLIC methods only; null otherwise)
    private String codeHealthRating;      // OK | CONCERN | SMELL
    private String codeHealthNote;        // Explanation when rating is not OK
    private Map<String, String> calleeSummaries; // globalId -> purposeSummary

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

    public MethodRange getRange() { return range; }
    public void setRange(MethodRange range) { this.range = range; }

    public String getBodyHash() { return bodyHash; }
    public void setBodyHash(String bodyHash) { this.bodyHash = bodyHash; }

    public String getExistingJavadoc() { return existingJavadoc; }
    public void setExistingJavadoc(String existingJavadoc) { this.existingJavadoc = existingJavadoc; }

    public String getPurposeSummary() { return purposeSummary; }
    public void setPurposeSummary(String purposeSummary) { this.purposeSummary = purposeSummary; }

    public String getDeveloperDoc() { return developerDoc; }
    public void setDeveloperDoc(String developerDoc) { this.developerDoc = developerDoc; }

    public CapabilityCard getCapabilityCard() { return capabilityCard; }
    public void setCapabilityCard(CapabilityCard capabilityCard) { this.capabilityCard = capabilityCard; }

    public String getCodeHealthRating() { return codeHealthRating; }
    public void setCodeHealthRating(String codeHealthRating) { this.codeHealthRating = codeHealthRating; }

    public String getCodeHealthNote() { return codeHealthNote; }
    public void setCodeHealthNote(String codeHealthNote) { this.codeHealthNote = codeHealthNote; }

    public Map<String, String> getCalleeSummaries() { return calleeSummaries; }
    public void setCalleeSummaries(Map<String, String> calleeSummaries) { this.calleeSummaries = calleeSummaries; }
}
