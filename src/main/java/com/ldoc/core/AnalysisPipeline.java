package com.ldoc.core;

import com.ldoc.graph.TopologicalSorter;
import com.ldoc.llm.ClaudeClient;
import com.ldoc.llm.LLMResponse;
import com.ldoc.llm.PromptBuilder;
import com.ldoc.llm.SummaryParser;
import com.ldoc.llm.ToolDescriptorBuilder;
import com.ldoc.model.MethodInfo;
import com.ldoc.parser.MethodExtractor;
import com.ldoc.storage.Neo4jGraphStore;
import com.ldoc.storage.OpenAiEmbeddingClient;
import com.ldoc.storage.QdrantVectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AnalysisPipeline {

    private static final Logger log = LoggerFactory.getLogger(AnalysisPipeline.class);

    private final AppConfig config;

    public AnalysisPipeline(AppConfig config) {
        this.config = config;
    }

    public void run(Path sourceDir, String repository, String module, boolean withSummary, boolean withEmbeddings) {
        boolean doLlm = withSummary;
        boolean doEmbeddings = withEmbeddings;
        int totalPhases = doLlm ? 4 : 3; // parse, sort, [llm,] store (Qdrant folded into store phase)

        // Phase 1 — Parse
        log.info("[1/{}] Parsing source tree: {} (repo={}, module={})", totalPhases, sourceDir, repository, module);
        MethodExtractor extractor = new MethodExtractor(repository, module);
        Map<String, MethodInfo> methodMap = extractor.extractAll(sourceDir);
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
            ToolDescriptorBuilder toolDescriptorBuilder = new ToolDescriptorBuilder();
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
                method.setSummary(parsed.summary());
                method.setInternalDocumentation(parsed.internalDocumentation());
                // Non-null only for PUBLIC methods; also writes parameter descriptions
                // back onto the MethodInfo's ParameterInfo instances.
                method.setToolDescriptor(toolDescriptorBuilder.build(method, parsed));
                summaryCache.put(id, method.getSummary());

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
        } else if (method.getSummary() != null) {
            sb.append(method.getSummary());
        }
        return sb.toString();
    }
}
