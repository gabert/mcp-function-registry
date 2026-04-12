package com.github.gabert.llm.mcp.ldoc.llm;

import com.github.gabert.llm.mcp.ldoc.model.MethodInfo;
import com.github.gabert.llm.mcp.ldoc.model.ParameterInfo;
import com.github.gabert.llm.mcp.ldoc.model.Visibility;

import java.util.Map;

public class PromptBuilder {

    public String build(MethodInfo method) {
        boolean isPublic = method.getVisibility() == Visibility.PUBLIC;

        StringBuilder sb = new StringBuilder();

        sb.append("You are documenting a method for a code-intelligence registry that serves ")
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

        sb.append("\n## Method Body\n```\n").append(method.getBody()).append("\n```\n");

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

        // 1. purposeSummary
        sb.append("1. `purposeSummary` — a DISCOVERY-oriented description written for a developer or coding agent ")
          .append("who is searching for a method to use, not reading an existing one. Answer: \"what problem does ")
          .append("this method solve, and when should I call it?\" Be as long as needed to make this useful for ")
          .append("semantic search — do not artificially cap the length. Lead with the business action as a verb ")
          .append("phrase (e.g. \"Hire a new employee\", \"Terminate an employment relationship\"). Include the ")
          .append("domain vocabulary a caller would naturally search for (synonyms help: hire/onboard/register, ")
          .append("terminate/fire/offboard, update/modify/edit). Mention key inputs and outputs at a CONCEPTUAL ")
          .append("level. Do NOT describe internal mechanics, control flow, validation steps, exception handling, ")
          .append("or which callees are invoked. The purpose of this field is semantic search: it must be distinct ")
          .append("enough from sibling methods (create vs update vs delete vs query) that a vector search can tell ")
          .append("them apart.\n\n");

        // 2. developerDoc
        sb.append("2. `developerDoc` — an extended behavioral summary for human developers that gives an accurate ")
          .append("mental model of what the method actually does. This is call-chain-aware: when callee summaries ")
          .append("are provided, reflect what those callees do (not just that they are called). Length should match ")
          .append("the complexity of the logic: a trivial getter may need one sentence, while a method with ")
          .append("non-trivial control flow, multiple side effects, or subtle invariants can warrant a detailed ")
          .append("walkthrough. Cover: the concrete operation performed, important side effects or state changes, ")
          .append("all notable branches and validation, exceptions thrown and the exact conditions that trigger ")
          .append("them, ordering guarantees, and how callees contribute to the overall behavior. Be specific ")
          .append("(mention field names, status values, thresholds, enum values) rather than generic. Avoid filler ")
          .append("like \"this method\" or restating the signature. Do not speculate beyond what the code shows.\n\n");

        // 3. capabilityCard (public only)
        if (isPublic) {
            sb.append("3. `capabilityCard` — a structured JSON object describing this method as a local contract ")
              .append("for AI coding agents. The card tells an agent everything it needs to know to decide whether ")
              .append("to call this method and how to call it correctly. Fields:\n\n");

            sb.append("   - `parameterDescriptions`: a JSON object mapping each parameter NAME ");
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
              .append("keys. If the method has no parameters, return an empty object `{}`.\n");
            sb.append("   - `preconditions`: what must be true before calling this method (required state, ")
              .append("parameter constraints, authentication, transaction context). \"None\" if there are no preconditions.\n");
            sb.append("   - `returns`: what the return value represents, including edge cases (null, empty, ")
              .append("special values). \"void\" if the method returns nothing.\n");
            sb.append("   - `throws`: each exception this method can throw and the exact condition that triggers it. ")
              .append("\"None\" if the method does not throw.\n");
            sb.append("   - `sideEffects`: observable effects beyond the return value — database writes, events ")
              .append("published, logs emitted, external API calls, cache mutations, file I/O. \"None\" if pure.\n\n");
        }

        // codeHealth — always present
        int healthNum = isPublic ? 4 : 3;
        sb.append(healthNum).append(". `codeHealth` — a lightweight quality assessment of this method. ")
          .append("JSON object with two fields:\n")
          .append("   - `rating`: one of `\"OK\"`, `\"CONCERN\"`, or `\"SMELL\"`.\n")
          .append("     - `OK` — the method is clean, readable, and well-structured.\n")
          .append("     - `CONCERN` — minor issues: slightly long, mildly unclear naming, minor readability issues, ")
          .append("but nothing that demands immediate action.\n")
          .append("     - `SMELL` — significant issues: mixed responsibilities, misleading names vs actual behavior, ")
          .append("deeply nested control flow, god-method patterns, silent error swallowing, ")
          .append("business logic mixed with infrastructure, or other maintainability red flags.\n")
          .append("   - `note`: if `OK`, use an empty string `\"\"`. If `CONCERN` or `SMELL`, ")
          .append("a 1-2 sentence explanation of what is wrong and why it matters. Be specific.\n\n");

        sb.append("Respond ONLY with valid JSON, no markdown fences.\n");

        return sb.toString();
    }
}
