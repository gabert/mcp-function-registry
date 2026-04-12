package com.github.gabert.llm.mcp.ldoc.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.gabert.llm.mcp.ldoc.core.AppConfig;
import com.github.gabert.llm.mcp.ldoc.model.MethodInfo;
import com.github.gabert.llm.mcp.ldoc.model.ParameterInfo;
import com.github.gabert.llm.mcp.ldoc.model.ToolDescriptor;
import org.neo4j.driver.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stores method nodes and CALLS relationships in Neo4j.
 *
 * Schema:
 *   (:Method {globalId, repository, module, qualifiedSignature,
 *             package, className, methodName, signature, returnType,
 *             visibility, sourceFile, bodyHash, summary, purposeSummary,
 *             internalDocumentation, toolDescriptor (JSON string, null for non-public),
 *             existingJavadoc, parameters, generatedAt})
 *   (:Method)-[:CALLS]->(:Method)
 *
 * Traversal examples (Cypher):
 *   // Who calls this method (callers chain up to controllers):
 *   MATCH path = (caller)-[:CALLS*]->(m:Method {methodName: "findById"})
 *   RETURN path
 *
 *   // What does this method call (composition tree):
 *   MATCH path = (m:Method {methodName: "processOrder"})-[:CALLS*]->(callee)
 *   RETURN path
 */
public class Neo4jGraphStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Neo4jGraphStore.class);

    private final Driver driver;
    private final ObjectMapper mapper = new ObjectMapper();

    public Neo4jGraphStore(AppConfig config) {
        String uri      = config.get("ldoc.neo4j.uri");
        String user     = config.get("ldoc.neo4j.user");
        String password = config.get("ldoc.neo4j.password");
        AuthToken auth = (user == null || user.isBlank())
                ? AuthTokens.none()
                : AuthTokens.basic(user, password);
        this.driver = GraphDatabase.driver(uri, auth);
        ensureConstraints();
        log.info("Connected to Neo4j: {}", uri);
    }

    private void ensureConstraints() {
        try (Session session = driver.session()) {
            session.run("""
                    CREATE CONSTRAINT method_globalId IF NOT EXISTS
                    FOR (m:Method) REQUIRE m.globalId IS UNIQUE
                    """);
        }
    }

    /** Upsert the Method node with all metadata and LLM-generated content. */
    public void upsertMethod(MethodInfo method) {
        Map<String, Object> params = new HashMap<>();
        params.put("globalId",           method.getId());
        params.put("repository",         method.getCoordinate().getRepository());
        params.put("module",             method.getCoordinate().getModule());
        params.put("qualifiedSignature", method.getCoordinate().getQualifiedSignature());
        params.put("package",            method.getPackageName());
        params.put("className",          method.getClassName());
        params.put("methodName",         method.getMethodName());
        params.put("signature",          method.getSignature());
        params.put("returnType",         method.getReturnType());
        params.put("sourceFile",         method.getSourceFile());
        params.put("bodyHash",           method.getBodyHash());
        params.put("summary",            nullToEmpty(method.getSummary()));
        params.put("purposeSummary",     nullToEmpty(method.getPurposeSummary()));
        params.put("internalDocumentation", nullToEmpty(method.getInternalDocumentation()));
        params.put("toolDescriptor",     serializeToolDescriptor(method.getToolDescriptor()));
        params.put("visibility",         method.getVisibility() != null ? method.getVisibility().name() : "");
        params.put("existingJavadoc",    nullToEmpty(method.getExistingJavadoc()));
        params.put("parameters",         serializeParams(method.getParameters()));
        params.put("generatedAt",        Instant.now().toString());
        params.put("name", method.getClassName() + "#" + method.getMethodName());

        try (Session session = driver.session()) {
            session.run("""
                    MERGE (m:Method {globalId: $globalId})
                    SET m.repository         = $repository,
                        m.module             = $module,
                        m.qualifiedSignature = $qualifiedSignature,
                        m.package            = $package,
                        m.className          = $className,
                        m.methodName         = $methodName,
                        m.signature          = $signature,
                        m.returnType         = $returnType,
                        m.sourceFile         = $sourceFile,
                        m.bodyHash           = $bodyHash,
                        m.summary            = $summary,
                        m.purposeSummary     = $purposeSummary,
                        m.internalDocumentation = $internalDocumentation,
                        m.toolDescriptor     = $toolDescriptor,
                        m.visibility         = $visibility,
                        m.existingJavadoc    = $existingJavadoc,
                        m.parameters         = $parameters,
                        m.generatedAt        = $generatedAt,
                        m.name           = $name
                    """, params);
        }
    }

    /**
     * Creates CALLS relationships from caller to each callee.
     * Both nodes must already exist (call upsertMethod for all methods first).
     */
    public void upsertCallEdges(MethodInfo caller) {
        if (caller.getCalleeIds() == null || caller.getCalleeIds().isEmpty()) return;
        List<String> calleeIds = caller.getCalleeIds();
        try (Session session = driver.session()) {
            for (int i = 0; i < calleeIds.size(); i++) {
                session.run("""
                        MATCH (caller:Method {globalId: $callerId})
                        MATCH (callee:Method {globalId: $calleeId})
                        MERGE (caller)-[r:CALLS]->(callee)
                        SET r.order = $order
                        """,
                        Map.of("callerId", caller.getId(), "calleeId", calleeIds.get(i), "order", i));
            }
        }
    }
    private String serializeParams(List<ParameterInfo> params) {
        if (params == null || params.isEmpty()) return "[]";
        try {
            return mapper.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private String serializeToolDescriptor(ToolDescriptor descriptor) {
        if (descriptor == null) return "";
        try {
            return mapper.writeValueAsString(descriptor);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize ToolDescriptor: {}", e.getMessage());
            return "";
        }
    }

    private String nullToEmpty(String s) {
        return s != null ? s : "";
    }

    @Override
    public void close() {
        driver.close();
    }
}
