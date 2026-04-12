package com.github.gabert.llm.mcp.ldoc.core;

import com.github.gabert.llm.mcp.ldoc.graph.TopologicalSorter;
import com.github.gabert.llm.mcp.ldoc.llm.ClaudeClient;
import com.github.gabert.llm.mcp.ldoc.llm.LLMResponse;
import com.github.gabert.llm.mcp.ldoc.llm.PromptBuilder;
import com.github.gabert.llm.mcp.ldoc.llm.SummaryParser;
import com.github.gabert.llm.mcp.ldoc.model.CapabilityCard;
import com.github.gabert.llm.mcp.ldoc.model.ParameterInfo;
import com.github.gabert.llm.mcp.ldoc.model.Visibility;
import com.github.gabert.llm.mcp.ldoc.lsp.LspMethodExtractor;
import com.github.gabert.llm.mcp.ldoc.model.MethodInfo;
import com.github.gabert.llm.mcp.ldoc.parser.MethodExtractor;
import com.github.gabert.llm.mcp.ldoc.storage.Neo4jGraphStore;
import com.github.gabert.llm.mcp.ldoc.storage.OpenAiEmbeddingClient;
import com.github.gabert.llm.mcp.ldoc.storage.QdrantVectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AnalysisPipeline {

    private static final Logger log = LoggerFactory.getLogger(AnalysisPipeline.class);

    private final AppConfig config;

    public AnalysisPipeline(AppConfig config) {
        this.config = config;
    }

    public void run(Path sourceDir, String namespace, boolean withSummary, boolean withEmbeddings) {
        run(sourceDir, namespace, withSummary, withEmbeddings, false);
    }

    public void run(Path sourceDir, String namespace, boolean withSummary, boolean withEmbeddings, boolean useLsp) {
        boolean doLlm = withSummary;
        boolean doEmbeddings = withEmbeddings;
        int totalPhases = doLlm ? 4 : 3; // parse, sort, [llm,] store (Qdrant folded into store phase)

        // Phase 1 — Parse
        log.info("[1/{}] Parsing source tree: {} (namespace={}, lsp={})", totalPhases, sourceDir, namespace, useLsp);
        Map<String, MethodInfo> methodMap;
        String language = "java"; // default for now; will come from config for multi-language
        if (useLsp) {
            String cmdString = config.getRequired("lsp.server.command");
            String languageId = config.get("lsp.language.id");
            if (languageId == null || languageId.isBlank()) languageId = "java";
            language = languageId;
            String ext = config.get("lsp.file.extension");
            if (ext == null || ext.isBlank()) ext = ".java";
            List<String> cmd = Arrays.stream(cmdString.split("\\s+")).filter(s -> !s.isBlank()).toList();

            String wsDirStr = config.get("lsp.workspace.dir");
            Path workspaceDir = (wsDirStr == null || wsDirStr.isBlank()) ? null : Path.of(wsDirStr);
            long readyTimeoutMs = config.getInt("lsp.ready.timeout.ms", 180_000);

            Path projectRoot = findProjectRoot(sourceDir);
            if (!projectRoot.equals(sourceDir.toAbsolutePath())) {
                log.info("Detected project root {} (source root: {})", projectRoot, sourceDir.toAbsolutePath());
            }

            LspMethodExtractor extractor = new LspMethodExtractor(
                    namespace, language, cmd, languageId, ext, workspaceDir, readyTimeoutMs);
            methodMap = extractor.extractAll(projectRoot, sourceDir);
        } else {
            MethodExtractor extractor = new MethodExtractor(namespace, language);
            methodMap = extractor.extractAll(sourceDir);
        }
        log.info("Extracted {} methods", methodMap.size());

        // Phase 2 — Topological sort
        log.info("[2/{}] Topological sort of call graph...", totalPhases);
        TopologicalSorter sorter = new TopologicalSorter(methodMap);
        List<String> orderedIds = sorter.sort();
        log.info("Order established for {} methods", orderedIds.size());

        if (doLlm) {
            // Phase 3 — LLM summaries
            log.info("[3/{}] Generating LLM summaries...", totalPhases);
            ClaudeClient claude = new ClaudeClient(config);
            PromptBuilder promptBuilder = new PromptBuilder();
            SummaryParser summaryParser = new SummaryParser();
            Map<String, String> summaryCache = new HashMap<>();

            for (String id : orderedIds) {
                MethodInfo method = methodMap.get(id);

                Map<String, String> calleeSummaries = new HashMap<>();
                for (String calleeId : method.getCalleeIds()) {
                    if (summaryCache.containsKey(calleeId)) {
                        calleeSummaries.put(calleeId, summaryCache.get(calleeId));
                    }
                }
                method.setCalleeSummaries(calleeSummaries);

                String prompt = promptBuilder.build(method);
                String response = claude.complete(prompt);
                LLMResponse parsed = summaryParser.parse(response);
                method.setPurposeSummary(parsed.purposeSummary());
                method.setDeveloperDoc(parsed.developerDoc());

                // Build capability card for public methods only;
                // inject signature and parameter names/types deterministically
                if (method.getVisibility() == Visibility.PUBLIC && parsed.capabilityCard() != null) {
                    LLMResponse.CapabilityCardResponse card = parsed.capabilityCard();
                    Map<String, String> paramDescs = card.parameterDescriptions() != null
                            ? card.parameterDescriptions()
                            : Collections.emptyMap();
                    List<ParameterInfo> cardParams = new ArrayList<>();
                    if (method.getParameters() != null) {
                        for (ParameterInfo p : method.getParameters()) {
                            String desc = paramDescs.getOrDefault(p.getName(), "");
                            cardParams.add(new ParameterInfo(p.getType(), p.getName(), desc));
                        }
                    }
                    method.setCapabilityCard(new CapabilityCard(
                            method.getSignature(),
                            method.getPurposeSummary(),
                            cardParams,
                            card.preconditions(),
                            card.returns(),
                            card.throwsDoc(),
                            card.sideEffects()
                    ));
                }

                // Code health
                if (parsed.codeHealth() != null) {
                    method.setCodeHealthRating(parsed.codeHealth().rating());
                    method.setCodeHealthNote(parsed.codeHealth().note());
                }

                summaryCache.put(id, method.getPurposeSummary());

                log.info("  Summarized: {}", id);
            }
        }

        // Store phase
        int storePhase = totalPhases;
        log.info("[{}/{}] Storing to Neo4j{}...", storePhase, totalPhases, doEmbeddings ? " and Qdrant" : "");
        Neo4jGraphStore neo4jStore = new Neo4jGraphStore(config);

        // First pass: upsert all Method nodes
        for (String id : orderedIds) {
            neo4jStore.upsertMethod(methodMap.get(id));
        }

        // Second pass: create CALLS edges
        for (String id : orderedIds) {
            neo4jStore.upsertCallEdges(methodMap.get(id));
        }

        neo4jStore.close();

        if (doEmbeddings) {
            OpenAiEmbeddingClient embeddingClient = new OpenAiEmbeddingClient(config);
            QdrantVectorStore qdrantStore = new QdrantVectorStore(config);
            for (String id : orderedIds) {
                MethodInfo method = methodMap.get(id);
                String embedText = buildEmbeddingText(method);
                List<Float> embedding = embeddingClient.embed(embedText);
                qdrantStore.upsert(method, embedding);
            }
            qdrantStore.close();
        }

        log.info("Pipeline complete. {} methods processed.", orderedIds.size());
    }

    /**
     * Build the text that gets embedded for semantic search.
     * Uses the discovery-oriented {@code purposeSummary} (not the behavioral {@code summary})
     * because RAG queries are typically "which method should I use to do X" rather than
     * "explain how this code works". The method identity is prepended so siblings like
     * create/update/delete stay distinct in vector space even when purposes overlap.
     * Falls back to the behavioral summary if the purpose summary is missing.
     */
    /**
     * Walk up from {@code start} looking for a build file (pom.xml, build.gradle[.kts]).
     * Build-aware LSP servers like jdtls need the project root, not the source root.
     * Falls back to the start directory if no build file is found upward.
     */
    private static Path findProjectRoot(Path start) {
        Path cur = start.toAbsolutePath().normalize();
        while (cur != null) {
            if (Files.exists(cur.resolve("pom.xml"))
                    || Files.exists(cur.resolve("build.gradle"))
                    || Files.exists(cur.resolve("build.gradle.kts"))) {
                return cur;
            }
            cur = cur.getParent();
        }
        return start.toAbsolutePath().normalize();
    }

    private String buildEmbeddingText(MethodInfo method) {
        StringBuilder sb = new StringBuilder();
        sb.append(method.getClassName()).append('.').append(method.getMethodName()).append('\n');
        if (method.getSignature() != null && !method.getSignature().isBlank()) {
            sb.append(method.getSignature()).append('\n');
        }
        sb.append('\n');
        String purpose = method.getPurposeSummary();
        if (purpose != null && !purpose.isBlank()) {
            sb.append(purpose);
        }
        return sb.toString();
    }
}
