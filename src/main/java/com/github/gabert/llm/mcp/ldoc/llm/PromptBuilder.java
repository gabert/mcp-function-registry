package com.github.gabert.llm.mcp.ldoc.llm;

import com.github.gabert.llm.mcp.ldoc.model.MethodInfo;
import com.github.gabert.llm.mcp.ldoc.model.ParameterInfo;
import com.github.gabert.llm.mcp.ldoc.model.Visibility;

import java.util.Map;

public class PromptBuilder {

    public String build(MethodInfo method) {
        boolean isPublic = method.getVisibility() == Visibility.PUBLIC;

        StringBuilder sb = new StringBuilder();

        sb.append("You are documenting a Java method for a code-intelligence registry that serves ")
          .append("both human developers and AI coding agents. Produce accurate, caller-focused output.\n\n");

        sb.append("## Method\n");
        sb.append("Class: ").append(method.getClassName()).append("\n");
        sb.append("Signature: ").append(method.getSignature()).append("\n");
        sb.append("Return type: ").append(method.getReturnType()).append("\n");
        sb.append("Visibility: ").append(method.getVisibility() != null ? method.getVisibility().name().toLowerCase() : "unknown").append("\n");

        boolean hasParams = method.getParameters() != null && !method.getParameters().isEmpty();
        if (hasParams) {
            sb.append("Parameters:\n");
            for (ParameterInfo p : method.getParameters()) {
                sb.append("  - ").append(p.getType()).append(" ").append(p.getName()).append("\n");
            }
        }

        sb.append("\n## Method Body\n```java\n").append(method.getBody()).append("\n```\n");

        Map<String, String> calleeSummaries = method.getCalleeSummaries();
        if (calleeSummaries != null && !calleeSummaries.isEmpty()) {
            sb.append("\n## Called Methods (summaries)\n");
            for (Map.Entry<String, String> entry : calleeSummaries.entrySet()) {
                sb.append("- **").append(entry.getKey()).append("**: ").append(entry.getValue()).append("\n");
            }
        }

        sb.append("\n## Instructions\n");
        sb.append("Derive the documentation ONLY from the method body, signature, and the provided callee summaries. ");
        sb.append("Do NOT rely on method or class names as evidence of behavior — names can be misleading. ");
        sb.append("If existing Javadoc appears anywhere, ignore it entirely; assume it may be stale or wrong.\n\n");

        sb.append("Respond in JSON with these fields:\n\n");

        sb.append("1. `purposeSummary` — a DISCOVERY-oriented description written for a developer or coding agent ")
          .append("who is searching for a method to use, not reading an existing one. Answer: \"what problem does ")
          .append("this method solve, and when should I call it?\" 1–3 sentences. Lead with the business action as ")
          .append("a verb phrase (e.g. \"Hire a new employee\", \"Terminate an employment relationship\"). Include the ")
          .append("domain vocabulary a caller would naturally search for (synonyms help: hire/onboard/register, ")
          .append("terminate/fire/offboard, update/modify/edit). Mention key inputs and outputs at a CONCEPTUAL ")
          .append("level. Do NOT describe internal mechanics, control flow, validation steps, exception handling, ")
          .append("or which callees are invoked. The purpose of this field is semantic search: it must be distinct ")
          .append("enough from sibling methods (create vs update vs delete vs query) that a vector search can tell ")
          .append("them apart.\n\n");

        sb.append("2. `summary` — a developer-facing behavioral description that gives a reader an accurate mental ")
          .append("model of what the method actually does internally. Length should match the complexity of the ")
          .append("logic: a trivial getter may need one sentence, while a method with non-trivial control flow, ")
          .append("multiple side effects, or subtle invariants can warrant 10–15 sentences or more. Do not pad ")
          .append("simple methods and do not truncate complex ones. Cover: the concrete operation performed, ")
          .append("important side effects or state changes, all notable branches and validation, exceptions thrown ")
          .append("and the exact conditions that trigger them, ordering guarantees, and how callees are used when ")
          .append("that matters. Be specific (mention field names, status values, thresholds, enum values) rather ")
          .append("than generic. Avoid filler like \"this method\" or restating the signature. Do not speculate ")
          .append("beyond what the code shows.\n\n");

        sb.append("3. `internalDocumentation` — a caller-facing reference document for this method. Natural-language ")
          .append("prose (NOT a /** */ javadoc comment block, and NOT the behavioral walkthrough that goes in ")
          .append("`summary`). Written as an API contract: what a caller needs to know to use this method correctly. ")
          .append("Open with a concise verb-phrase statement of what the method does from the caller's perspective. ")
          .append("Follow with preconditions, postconditions, observable side effects, the meaning of each parameter ")
          .append("(what it represents and any constraints callers must honor), what the return value carries and ")
          .append("under what conditions, and every exception a caller must handle along with the condition that ")
          .append("triggers it. Terse, caller-focused, contract-style. For a trivial getter/setter a few sentences ")
          .append("are enough. Do NOT describe the internal implementation — that belongs in `summary`.\n\n");

        if (isPublic) {
            sb.append("4. `toolDescription` — a 1–3 sentence description suitable for an LLM tool-use descriptor, ")
              .append("written so a CODING AGENT deciding whether to call this method from code it is writing can ")
              .append("quickly judge fit. Lead with the action. Be concrete about inputs and outputs at a conceptual ")
              .append("level. Do not describe internals, control flow, or exception handling. Distinct from ")
              .append("`purposeSummary` in tone: `purposeSummary` is a search-target, `toolDescription` is a ")
              .append("\"should I pick this tool\" hint.\n\n");

            sb.append("5. `parameterDescriptions` — a JSON object mapping each parameter NAME ");
            if (hasParams) {
                sb.append("(exactly: ");
                boolean first = true;
                for (ParameterInfo p : method.getParameters()) {
                    if (!first) sb.append(", ");
                    sb.append("`").append(p.getName()).append("`");
                    first = false;
                }
                sb.append(") ");
            }
            sb.append("to a 1-sentence description of what that parameter represents and any constraints the caller ")
              .append("must honor. Every parameter listed above must appear as a key. Do not add, remove, or rename ")
              .append("keys. If the method has no parameters, return an empty object `{}`.\n\n");
        }

        sb.append("Respond ONLY with valid JSON, no markdown fences.\n");

        return sb.toString();
    }
}
