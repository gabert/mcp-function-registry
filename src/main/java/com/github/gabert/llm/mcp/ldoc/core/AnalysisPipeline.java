package com.github.gabert.llm.mcp.ldoc.core;

import com.github.gabert.llm.mcp.ldoc.llm.ClaudeClient;
import com.github.gabert.llm.mcp.ldoc.llm.LLMResponse;
import com.github.gabert.llm.mcp.ldoc.llm.PromptBuilder;
import com.github.gabert.llm.mcp.ldoc.llm.SummaryParser;
import com.github.gabert.llm.mcp.ldoc.model.CapabilityCard;
import com.github.gabert.llm.mcp.ldoc.model.ParameterInfo;
import com.github.gabert.llm.mcp.ldoc.model.Visibility;
import com.github.gabert.llm.mcp.ldoc.lsp.LspMethodExtractor;
import com.github.gabert.llm.mcp.ldoc.lsp.SourceSlicer;
import com.github.gabert.llm.mcp.ldoc.model.MethodInfo;
import com.github.gabert.llm.mcp.ldoc.parser.MethodExtractor;
import com.github.gabert.llm.mcp.ldoc.storage.PostgresStore;
import com.github.gabert.llm.mcp.ldoc.storage.OpenAiEmbeddingClient;
import com.github.gabert.llm.mcp.ldoc.storage.QdrantVectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

        // Phase 1 — Parse
        log.info("[1] Parsing source tree: {} (namespace={}, lsp={})", sourceDir, namespace, useLsp);
        Map<String, MethodInfo> methodMap = extractMethods(sourceDir, namespace, useLsp);
        log.info("Extracted {} methods", methodMap.size());

        int batch = config.getInt("ldoc.postgres.batch.size", 500);

        try (PostgresStore store = new PostgresStore(config)) {
            // Phase 2 — Store methods + call edges
            log.info("[2] Storing {} methods to PostgreSQL...", methodMap.size());
            List<MethodInfo> allMethods = new ArrayList<>(methodMap.values());
            for (int i = 0; i < allMethods.size(); i += batch) {
                store.upsertMethods(allMethods.subList(i, Math.min(i + batch, allMethods.size())));
            }
            store.upsertCallEdges(allMethods);

            if (doLlm) {
                // Phase 3 — LLM summaries (leaf-first BFS)
                log.info("[3] Generating LLM summaries (leaf-first)...");
                runLlmPhase(methodMap, store, null);
                // Write back summaries
                for (int i = 0; i < allMethods.size(); i += batch) {
                    store.updateSummaries(allMethods.subList(i, Math.min(i + batch, allMethods.size())));
                }
            }
        }

        if (doEmbeddings) {
            runEmbeddingPhase(methodMap.values());
        }

        log.info("Pipeline complete. {} methods processed.", methodMap.size());
    }

    /**
     * Counts returned by {@link #update}.
     */
    public record UpdateResult(int added, int deleted, int directlyChanged,
                               int upstreamRefreshed, int unchanged) {
        public int llmCalls() { return directlyChanged + upstreamRefreshed; }
    }

    /**
     * Incremental update. Re-extracts the source tree, detects what changed by comparing
     * fingerprints against Postgres, and re-runs the LLM only for methods that need it.
     */
    public UpdateResult update(Path sourceDir, String namespace, boolean withEmbeddings) {
        return update(sourceDir, namespace, withEmbeddings, false);
    }

    public UpdateResult update(Path sourceDir, String namespace, boolean withEmbeddings, boolean useLsp) {
        log.info("Incremental update: {} (namespace={})", sourceDir, namespace);

        // Phase 1 — fresh extraction
        Map<String, MethodInfo> newMap = extractMethods(sourceDir, namespace, useLsp);
        log.info("Extracted {} methods", newMap.size());

        int batch = config.getInt("ldoc.postgres.batch.size", 500);
        UpdateResult result;

        try (PostgresStore store = new PostgresStore(config)) {
            Map<String, PostgresStore.ExistingMethod> existing = store.fetchExistingByIds(newMap.keySet());
            Set<String> storedIds = store.listIds(namespace);

            // Classify
            Set<String> added = new HashSet<>();
            Set<String> dirtyDirect = new HashSet<>();
            for (MethodInfo m : newMap.values()) {
                PostgresStore.ExistingMethod ex = existing.get(m.getId());
                if (ex == null || ex.bodyHash() == null) {
                    added.add(m.getId());
                    dirtyDirect.add(m.getId());
                } else if (!ex.bodyHash().equals(m.getBodyHash())) {
                    dirtyDirect.add(m.getId());
                }
            }
            Set<String> deletedIds = new HashSet<>(storedIds);
            deletedIds.removeAll(newMap.keySet());

            // Carry stored summaries forward onto non-dirty methods
            for (MethodInfo m : newMap.values()) {
                if (dirtyDirect.contains(m.getId())) continue;
                PostgresStore.ExistingMethod ex = existing.get(m.getId());
                if (ex == null) continue;
                m.setPurposeSummary(ex.purposeSummary());
                m.setDeveloperDoc(ex.developerDoc());
                m.setCapabilityCard(store.deserializeCapabilityCard(ex.capabilityCardJson()));
                m.setCodeHealthRating(ex.codeHealthRating());
                m.setCodeHealthNote(ex.codeHealthNote());
            }

            // Upstream expansion: BFS on reverse CALLS
            Map<String, List<String>> reverse = new HashMap<>();
            for (MethodInfo m : newMap.values()) {
                if (m.getCalleeIds() == null) continue;
                for (String callee : m.getCalleeIds()) {
                    reverse.computeIfAbsent(callee, k -> new ArrayList<>()).add(m.getId());
                }
            }
            Set<String> dirtySet = new HashSet<>(dirtyDirect);
            Deque<String> queue = new ArrayDeque<>(dirtyDirect);
            while (!queue.isEmpty()) {
                String cur = queue.pop();
                for (String caller : reverse.getOrDefault(cur, List.of())) {
                    if (dirtySet.add(caller)) queue.push(caller);
                }
            }
            int upstreamRefreshed = dirtySet.size() - dirtyDirect.size();

            log.info("Update classification: added={}, deleted={}, directlyChanged={}, upstreamRefreshed={}, unchanged={}",
                    added.size(), deletedIds.size(), dirtyDirect.size(), upstreamRefreshed,
                    newMap.size() - dirtySet.size());

            // Persist topology + carried-over summaries, drop deleted nodes
            List<MethodInfo> allNew = new ArrayList<>(newMap.values());
            for (int i = 0; i < allNew.size(); i += batch) {
                store.upsertMethods(allNew.subList(i, Math.min(i + batch, allNew.size())));
            }
            store.upsertCallEdges(allNew);
            store.deleteMethods(deletedIds);

            if (dirtySet.isEmpty()) {
                log.info("Nothing to re-summarize.");
                result = new UpdateResult(added.size(), deletedIds.size(),
                        dirtyDirect.size(), upstreamRefreshed, newMap.size() - dirtySet.size());
            } else {
                // LLM phase on dirty set only
                log.info("Re-summarizing {} methods (leaf-first)...", dirtySet.size());
                runLlmPhase(newMap, store, dirtySet);

                // Write back summaries for dirty methods
                List<MethodInfo> dirtyList = dirtySet.stream()
                        .map(newMap::get)
                        .filter(m -> m != null)
                        .toList();
                for (int i = 0; i < dirtyList.size(); i += batch) {
                    store.updateSummaries(dirtyList.subList(i, Math.min(i + batch, dirtyList.size())));
                }

                if (withEmbeddings) {
                    runEmbeddingPhase(dirtyList);
                }

                result = new UpdateResult(added.size(), deletedIds.size(),
                        dirtyDirect.size(), upstreamRefreshed, newMap.size() - dirtySet.size());
            }
        }

        log.info("Update complete: {}", result);
        return result;
    }

    private Map<String, MethodInfo> extractMethods(Path sourceDir, String namespace, boolean useLsp) {
        String language = "java";
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
            return extractor.extractAll(projectRoot, sourceDir);
        }
        Path projectRoot = findProjectRoot(sourceDir);
        String classpath = resolveMavenClasspath(projectRoot);
        String excludeStr = config.get("ldoc.exclude.packages");
        List<String> excludePackages = (excludeStr == null || excludeStr.isBlank())
                ? List.of()
                : Arrays.stream(excludeStr.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        return new MethodExtractor(namespace, language, classpath, excludePackages).extractAll(sourceDir);
    }

    /**
     * Runs {@code mvn dependency:build-classpath} to get the full dependency classpath.
     * Returns null if Maven is not available or the project has no pom.xml.
     */
    private static String resolveMavenClasspath(Path projectRoot) {
        Path pom = projectRoot.resolve("pom.xml");
        if (!Files.exists(pom)) return null;
        try {
            Path tmpFile = Files.createTempFile("ldoc-cp-", ".txt");
            try {
                String mvnCmd = System.getProperty("os.name", "").toLowerCase().contains("win") ? "mvn.cmd" : "mvn";
                Process proc = new ProcessBuilder(mvnCmd, "dependency:build-classpath", "-q",
                        "-DincludeScope=compile", "-Dmdep.outputFile=" + tmpFile.toAbsolutePath())
                        .directory(projectRoot.toFile())
                        .redirectErrorStream(true)
                        .start();
                // Drain output so the process doesn't block
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    while (reader.readLine() != null) { /* discard */ }
                }
                int exitCode = proc.waitFor();
                if (exitCode != 0) {
                    log.warn("mvn dependency:build-classpath failed (exit={})", exitCode);
                    return null;
                }
                String output = Files.readString(tmpFile).trim();
                if (output.isEmpty()) {
                    log.warn("mvn dependency:build-classpath produced empty output");
                    return null;
                }
                log.info("Resolved Maven classpath ({} entries)", output.split(java.io.File.pathSeparator).length);
                return output;
            } finally {
                Files.deleteIfExists(tmpFile);
            }
        } catch (Exception e) {
            log.warn("Cannot resolve Maven classpath: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Leaf-first BFS: process methods with no internal callees first, then
     * methods whose callees have all been processed, and so on upward.
     *
     * @param forceIds if non-null, only process these ids (incremental update).
     *                 Methods not in forceIds still contribute cached summaries.
     */
    private void runLlmPhase(Map<String, MethodInfo> methodMap, PostgresStore store, Set<String> forceIds) {
        ClaudeClient claude = new ClaudeClient(config);
        PromptBuilder promptBuilder = new PromptBuilder();
        SummaryParser summaryParser = new SummaryParser();
        Map<String, String> summaryCache = new ConcurrentHashMap<>();

        // Determine which ids to actually process
        Set<String> toProcess;
        if (forceIds != null) {
            toProcess = forceIds;
        } else {
            toProcess = new HashSet<>(methodMap.keySet());
        }

        // Reuse cached summaries for methods we're not re-processing
        Map<String, PostgresStore.ExistingMethod> existing = store.fetchExistingByIds(methodMap.keySet());
        int reused = 0;
        for (MethodInfo method : methodMap.values()) {
            if (toProcess.contains(method.getId())) continue;
            PostgresStore.ExistingMethod ex = existing.get(method.getId());
            if (ex == null) continue;
            method.setPurposeSummary(ex.purposeSummary());
            method.setDeveloperDoc(ex.developerDoc());
            method.setCapabilityCard(store.deserializeCapabilityCard(ex.capabilityCardJson()));
            method.setCodeHealthRating(ex.codeHealthRating());
            method.setCodeHealthNote(ex.codeHealthNote());
            summaryCache.put(method.getId(), ex.purposeSummary() != null ? ex.purposeSummary() : "");
            reused++;
        }
        // Also skip methods whose bodyHash matches stored (full-run only)
        if (forceIds == null) {
            for (MethodInfo method : methodMap.values()) {
                if (summaryCache.containsKey(method.getId())) continue;
                PostgresStore.ExistingMethod ex = existing.get(method.getId());
                if (ex == null || ex.bodyHash() == null || !ex.bodyHash().equals(method.getBodyHash())) continue;
                method.setPurposeSummary(ex.purposeSummary());
                method.setDeveloperDoc(ex.developerDoc());
                method.setCapabilityCard(store.deserializeCapabilityCard(ex.capabilityCardJson()));
                method.setCodeHealthRating(ex.codeHealthRating());
                method.setCodeHealthNote(ex.codeHealthNote());
                summaryCache.put(method.getId(), ex.purposeSummary() != null ? ex.purposeSummary() : "");
                toProcess.remove(method.getId());
                reused++;
            }
        }
        if (reused > 0) log.info("Reusing cached summaries for {} unchanged methods", reused);

        // Build leaf-first BFS layers from the methods we need to process
        List<List<String>> layers = buildLeafFirstLayers(methodMap, toProcess);
        log.info("Processing {} methods across {} layers (leaf-first)", toProcess.size(), layers.size());

        int concurrency = config.getInt("ldoc.llm.concurrency", 4);
        ExecutorService exec = Executors.newFixedThreadPool(concurrency);
        try {
            for (List<String> layer : layers) {
                List<Future<?>> futures = new ArrayList<>();
                for (String id : layer) {
                    MethodInfo method = methodMap.get(id);
                    if (method == null) continue;
                    futures.add(exec.submit(() -> {
                        // Re-slice body from disk if needed (LSP two-pass or body not loaded)
                        if ((method.getBody() == null || method.getBody().isEmpty())
                                && method.getRange() != null && method.getSourceFile() != null) {
                            method.setBody(SourceSlicer.sliceFromFile(Path.of(method.getSourceFile()), method.getRange()));
                        }
                        summarizeOne(method, claude, promptBuilder, summaryParser, summaryCache);
                        return null;
                    }));
                }
                for (Future<?> f : futures) {
                    try {
                        f.get();
                    } catch (ExecutionException e) {
                        Throwable cause = e.getCause();
                        throw new RuntimeException(cause != null ? cause : e);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                }
            }
        } finally {
            exec.shutdown();
        }
    }

    /**
     * Groups method ids into layers: layer 0 = leaves (no internal callees among toProcess),
     * layer 1 = methods whose callees are all in layer 0, etc.
     * Any remaining ids (cycles) go into a final layer.
     */
    private List<List<String>> buildLeafFirstLayers(Map<String, MethodInfo> methodMap, Set<String> toProcess) {
        // In-degree within the toProcess subset
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>(); // callee -> callers (within toProcess)

        for (String id : toProcess) {
            inDegree.put(id, 0);
            dependents.put(id, new ArrayList<>());
        }
        for (String id : toProcess) {
            MethodInfo m = methodMap.get(id);
            if (m == null || m.getCalleeIds() == null) continue;
            for (String calleeId : m.getCalleeIds()) {
                if (toProcess.contains(calleeId)) {
                    inDegree.merge(id, 1, Integer::sum);
                    dependents.get(calleeId).add(id);
                }
            }
        }

        List<List<String>> layers = new ArrayList<>();
        List<String> currentLayer = new ArrayList<>();
        for (Map.Entry<String, Integer> e : inDegree.entrySet()) {
            if (e.getValue() == 0) currentLayer.add(e.getKey());
        }

        Set<String> placed = new HashSet<>();
        while (!currentLayer.isEmpty()) {
            layers.add(currentLayer);
            placed.addAll(currentLayer);
            List<String> nextLayer = new ArrayList<>();
            for (String current : currentLayer) {
                for (String dependent : dependents.getOrDefault(current, List.of())) {
                    int deg = inDegree.merge(dependent, -1, Integer::sum);
                    if (deg == 0) nextLayer.add(dependent);
                }
            }
            currentLayer = nextLayer;
        }

        // Remaining ids (in cycles) go as a final layer
        if (placed.size() < toProcess.size()) {
            List<String> remaining = new ArrayList<>();
            for (String id : toProcess) {
                if (!placed.contains(id)) remaining.add(id);
            }
            log.warn("{} methods in cycles — adding as final best-effort layer", remaining.size());
            layers.add(remaining);
        }

        return layers;
    }

    private void summarizeOne(MethodInfo method, ClaudeClient claude, PromptBuilder promptBuilder,
                              SummaryParser summaryParser, Map<String, String> summaryCache) {
        Map<String, String> calleeSummaries = new HashMap<>();
        if (method.getCalleeIds() != null) {
            for (String calleeId : method.getCalleeIds()) {
                String s = summaryCache.get(calleeId);
                if (s != null) calleeSummaries.put(calleeId, s);
            }
        }
        method.setCalleeSummaries(calleeSummaries);

        String prompt = promptBuilder.build(method);
        String response = claude.complete(prompt);
        LLMResponse parsed = summaryParser.parse(response);
        method.setPurposeSummary(parsed.purposeSummary());
        method.setDeveloperDoc(parsed.developerDoc());

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

        if (parsed.codeHealth() != null) {
            method.setCodeHealthRating(parsed.codeHealth().rating());
            method.setCodeHealthNote(parsed.codeHealth().note());
        }

        summaryCache.put(method.getId(), method.getPurposeSummary() != null ? method.getPurposeSummary() : "");
        log.info("  Summarized: {}", method.getId());
    }

    private void runEmbeddingPhase(Collection<MethodInfo> methods) {
        if (methods == null || methods.isEmpty()) return;
        OpenAiEmbeddingClient embeddingClient = new OpenAiEmbeddingClient(config);
        QdrantVectorStore qdrantStore = new QdrantVectorStore(config);
        try {
            List<MethodInfo> allMethods = new ArrayList<>(methods);
            List<String> texts = new ArrayList<>(allMethods.size());
            for (MethodInfo m : allMethods) texts.add(buildEmbeddingText(m));
            List<List<Float>> vectors = embeddingClient.embedBatch(texts);
            int qBatch = config.getInt("ldoc.qdrant.batch.size", 200);
            for (int i = 0; i < allMethods.size(); i += qBatch) {
                int end = Math.min(i + qBatch, allMethods.size());
                qdrantStore.upsertBatch(allMethods.subList(i, end), vectors.subList(i, end));
            }
        } finally {
            qdrantStore.close();
        }
    }

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
