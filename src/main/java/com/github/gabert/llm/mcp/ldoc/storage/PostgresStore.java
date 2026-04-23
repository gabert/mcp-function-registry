package com.github.gabert.llm.mcp.ldoc.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.gabert.llm.mcp.ldoc.core.AppConfig;
import com.github.gabert.llm.mcp.ldoc.model.CapabilityCard;
import com.github.gabert.llm.mcp.ldoc.model.MethodCoordinate;
import com.github.gabert.llm.mcp.ldoc.model.MethodInfo;
import com.github.gabert.llm.mcp.ldoc.model.MethodRange;
import com.github.gabert.llm.mcp.ldoc.model.ParameterInfo;
import com.github.gabert.llm.mcp.ldoc.model.Visibility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PostgresStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PostgresStore.class);

    private final Connection conn;
    private final ObjectMapper mapper = new ObjectMapper();

    public PostgresStore(AppConfig config) {
        String url = config.getRequired("ldoc.postgres.url");
        String user = config.get("ldoc.postgres.user");
        String password = config.get("ldoc.postgres.password");
        try {
            this.conn = DriverManager.getConnection(url, user, password);
            this.conn.setAutoCommit(false);
            ensureSchema();
            log.info("Connected to PostgreSQL: {}", url);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to connect to PostgreSQL: " + url, e);
        }
    }

    private void ensureSchema() {
        try (var stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS methods (
                        id                  TEXT PRIMARY KEY,
                        namespace           TEXT NOT NULL,
                        language            TEXT NOT NULL,
                        qualified_signature TEXT NOT NULL,
                        package_name        TEXT,
                        class_name          TEXT,
                        method_name         TEXT,
                        signature           TEXT,
                        return_type         TEXT,
                        visibility          TEXT,
                        source_file         TEXT,
                        range_start_line    INT,
                        range_start_char    INT,
                        range_end_line      INT,
                        range_end_char      INT,
                        body_hash           TEXT,
                        existing_javadoc    TEXT,
                        purpose_summary     TEXT,
                        developer_doc       TEXT,
                        capability_card     TEXT,
                        code_health_rating  TEXT,
                        code_health_note    TEXT,
                        parameters          TEXT,
                        generated_at        TEXT
                    )
                    """);
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS call_edges (
                        caller_id TEXT NOT NULL REFERENCES methods(id) ON DELETE CASCADE,
                        callee_id TEXT NOT NULL REFERENCES methods(id) ON DELETE CASCADE,
                        ordinal   INT NOT NULL DEFAULT 0,
                        PRIMARY KEY (caller_id, callee_id)
                    )
                    """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_methods_namespace ON methods(namespace)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_call_edges_callee ON call_edges(callee_id)");
            conn.commit();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create schema", e);
        }
    }

    // ── Upsert methods ──────────────────────────────────────────────────

    public void upsertMethods(List<MethodInfo> methods) {
        if (methods == null || methods.isEmpty()) return;
        String now = Instant.now().toString();
        String sql = """
                INSERT INTO methods (id, namespace, language, qualified_signature,
                    package_name, class_name, method_name, signature, return_type,
                    visibility, source_file,
                    range_start_line, range_start_char, range_end_line, range_end_char,
                    body_hash, existing_javadoc,
                    purpose_summary, developer_doc, capability_card,
                    code_health_rating, code_health_note, parameters, generated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (id) DO UPDATE SET
                    namespace=EXCLUDED.namespace, language=EXCLUDED.language,
                    qualified_signature=EXCLUDED.qualified_signature,
                    package_name=EXCLUDED.package_name, class_name=EXCLUDED.class_name,
                    method_name=EXCLUDED.method_name, signature=EXCLUDED.signature,
                    return_type=EXCLUDED.return_type, visibility=EXCLUDED.visibility,
                    source_file=EXCLUDED.source_file,
                    range_start_line=EXCLUDED.range_start_line,
                    range_start_char=EXCLUDED.range_start_char,
                    range_end_line=EXCLUDED.range_end_line,
                    range_end_char=EXCLUDED.range_end_char,
                    body_hash=EXCLUDED.body_hash, existing_javadoc=EXCLUDED.existing_javadoc,
                    purpose_summary=EXCLUDED.purpose_summary, developer_doc=EXCLUDED.developer_doc,
                    capability_card=EXCLUDED.capability_card,
                    code_health_rating=EXCLUDED.code_health_rating,
                    code_health_note=EXCLUDED.code_health_note,
                    parameters=EXCLUDED.parameters, generated_at=EXCLUDED.generated_at
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (MethodInfo m : methods) {
                MethodRange range = m.getRange();
                int i = 1;
                ps.setString(i++, m.getId());
                ps.setString(i++, m.getCoordinate().getNamespace());
                ps.setString(i++, m.getCoordinate().getLanguage());
                ps.setString(i++, m.getCoordinate().getQualifiedSignature());
                ps.setString(i++, m.getPackageName());
                ps.setString(i++, m.getClassName());
                ps.setString(i++, m.getMethodName());
                ps.setString(i++, m.getSignature());
                ps.setString(i++, m.getReturnType());
                ps.setString(i++, m.getVisibility() != null ? m.getVisibility().name() : "");
                ps.setString(i++, m.getSourceFile());
                if (range != null) {
                    ps.setInt(i++, range.startLine());
                    ps.setInt(i++, range.startChar());
                    ps.setInt(i++, range.endLine());
                    ps.setInt(i++, range.endChar());
                } else {
                    ps.setNull(i++, Types.INTEGER);
                    ps.setNull(i++, Types.INTEGER);
                    ps.setNull(i++, Types.INTEGER);
                    ps.setNull(i++, Types.INTEGER);
                }
                ps.setString(i++, m.getBodyHash());
                ps.setString(i++, m.getExistingJavadoc());
                ps.setString(i++, m.getPurposeSummary());
                ps.setString(i++, m.getDeveloperDoc());
                ps.setString(i++, serializeCapabilityCard(m.getCapabilityCard()));
                ps.setString(i++, m.getCodeHealthRating());
                ps.setString(i++, m.getCodeHealthNote());
                ps.setString(i++, serializeParams(m.getParameters()));
                ps.setString(i, now);
                ps.addBatch();
            }
            ps.executeBatch();
            conn.commit();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to upsert methods", e);
        }
    }

    // ── Upsert call edges ───────────────────────────────────────────────

    public void upsertCallEdges(Collection<MethodInfo> callers) {
        if (callers == null || callers.isEmpty()) return;

        // Collect all caller ids to delete their old edges in one shot
        List<String> callerIds = callers.stream()
                .map(MethodInfo::getId)
                .filter(id -> id != null)
                .toList();
        if (!callerIds.isEmpty()) {
            deleteCallEdgesForCallers(callerIds);
        }

        String sql = "INSERT INTO call_edges (caller_id, callee_id, ordinal) VALUES (?,?,?) ON CONFLICT DO NOTHING";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (MethodInfo caller : callers) {
                List<String> calleeIds = caller.getCalleeIds();
                if (calleeIds == null || calleeIds.isEmpty()) continue;
                for (int i = 0; i < calleeIds.size(); i++) {
                    ps.setString(1, caller.getId());
                    ps.setString(2, calleeIds.get(i));
                    ps.setInt(3, i);
                    ps.addBatch();
                }
            }
            ps.executeBatch();
            conn.commit();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to upsert call edges", e);
        }
    }

    private void deleteCallEdgesForCallers(List<String> callerIds) {
        if (callerIds.isEmpty()) return;
        StringBuilder sb = new StringBuilder("DELETE FROM call_edges WHERE caller_id IN (");
        for (int i = 0; i < callerIds.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('?');
        }
        sb.append(')');
        try (PreparedStatement ps = conn.prepareStatement(sb.toString())) {
            for (int i = 0; i < callerIds.size(); i++) {
                ps.setString(i + 1, callerIds.get(i));
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete old call edges", e);
        }
    }

    // ── Update only LLM-generated fields ────────────────────────────────

    public void updateSummaries(List<MethodInfo> methods) {
        if (methods == null || methods.isEmpty()) return;
        String now = Instant.now().toString();
        String sql = """
                UPDATE methods SET
                    body_hash=?, purpose_summary=?, developer_doc=?,
                    capability_card=?, code_health_rating=?, code_health_note=?,
                    generated_at=?
                WHERE id=?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (MethodInfo m : methods) {
                ps.setString(1, m.getBodyHash());
                ps.setString(2, m.getPurposeSummary());
                ps.setString(3, m.getDeveloperDoc());
                ps.setString(4, serializeCapabilityCard(m.getCapabilityCard()));
                ps.setString(5, m.getCodeHealthRating());
                ps.setString(6, m.getCodeHealthNote());
                ps.setString(7, now);
                ps.setString(8, m.getId());
                ps.addBatch();
            }
            ps.executeBatch();
            conn.commit();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update summaries", e);
        }
    }

    // ── Fetch existing (for incremental change detection) ───────────────

    public record ExistingMethod(
            String bodyHash,
            String purposeSummary,
            String developerDoc,
            String capabilityCardJson,
            String codeHealthRating,
            String codeHealthNote
    ) {}

    public Map<String, ExistingMethod> fetchExistingByIds(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) return Collections.emptyMap();
        StringBuilder sb = new StringBuilder(
                "SELECT id, body_hash, purpose_summary, developer_doc, capability_card, code_health_rating, code_health_note FROM methods WHERE id IN (");
        List<String> idList = new ArrayList<>(ids);
        for (int i = 0; i < idList.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('?');
        }
        sb.append(')');
        Map<String, ExistingMethod> result = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sb.toString())) {
            for (int i = 0; i < idList.size(); i++) {
                ps.setString(i + 1, idList.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString("id"), new ExistingMethod(
                            rs.getString("body_hash"),
                            rs.getString("purpose_summary"),
                            rs.getString("developer_doc"),
                            rs.getString("capability_card"),
                            rs.getString("code_health_rating"),
                            rs.getString("code_health_note")
                    ));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to fetch existing methods", e);
        }
        return result;
    }

    public Set<String> listIds(String namespace) {
        Set<String> ids = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM methods WHERE namespace=?")) {
            ps.setString(1, namespace);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getString("id"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list method ids", e);
        }
        return ids;
    }

    // ── Delete methods ──────────────────────────────────────────────────

    public void deleteMethods(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        List<String> idList = new ArrayList<>(ids);
        StringBuilder sb = new StringBuilder("DELETE FROM methods WHERE id IN (");
        for (int i = 0; i < idList.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('?');
        }
        sb.append(')');
        try (PreparedStatement ps = conn.prepareStatement(sb.toString())) {
            for (int i = 0; i < idList.size(); i++) {
                ps.setString(i + 1, idList.get(i));
            }
            ps.executeUpdate();
            conn.commit();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete methods", e);
        }
    }

    // ── Load topology (methods + call edges for a namespace) ────────────

    public Map<String, MethodInfo> loadMethodsWithEdges(String namespace) {
        Map<String, MethodInfo> methods = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM methods WHERE namespace=? ORDER BY id")) {
            ps.setString(1, namespace);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    MethodInfo info = mapRow(rs);
                    methods.put(info.getId(), info);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load methods", e);
        }

        // Load call edges
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT e.caller_id, e.callee_id
                FROM call_edges e JOIN methods m ON e.caller_id = m.id
                WHERE m.namespace=?
                ORDER BY e.caller_id, e.ordinal
                """)) {
            ps.setString(1, namespace);
            Map<String, List<String>> calleesByCaller = new HashMap<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    calleesByCaller.computeIfAbsent(rs.getString("caller_id"), k -> new ArrayList<>())
                            .add(rs.getString("callee_id"));
                }
            }
            for (MethodInfo m : methods.values()) {
                List<String> callees = calleesByCaller.get(m.getId());
                m.setCalleeIds(callees != null ? callees : List.of());
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load call edges", e);
        }

        log.info("Loaded {} methods from PostgreSQL for namespace '{}'", methods.size(), namespace);
        return methods;
    }

    // ── Query: leaf methods (no outgoing internal calls) ────────────────

    public List<String> findLeafIds(String namespace) {
        List<String> ids = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT m.id FROM methods m
                WHERE m.namespace=?
                  AND NOT EXISTS (SELECT 1 FROM call_edges e WHERE e.caller_id = m.id)
                ORDER BY m.id
                """)) {
            ps.setString(1, namespace);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getString("id"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find leaf methods", e);
        }
        return ids;
    }

    // ── Query: callers of a method ──────────────────────────────────────

    public List<String> findCallerIds(String methodId) {
        List<String> ids = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT caller_id FROM call_edges WHERE callee_id=?")) {
            ps.setString(1, methodId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getString("caller_id"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find callers", e);
        }
        return ids;
    }

    // ── Query: callees of a method (in dataflow order) ──────────────────

    public List<String> findCalleeIds(String methodId) {
        List<String> ids = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT callee_id FROM call_edges WHERE caller_id=? ORDER BY ordinal")) {
            ps.setString(1, methodId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getString("callee_id"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find callees", e);
        }
        return ids;
    }

    // ── API query methods (used by ApiServer) ─────────────────────────

    public List<String> queryPackages() {
        List<String> result = new ArrayList<>();
        try (var ps = conn.prepareStatement("SELECT DISTINCT package_name FROM methods ORDER BY package_name")) {
            try (var rs = ps.executeQuery()) {
                while (rs.next()) result.add(rs.getString("package_name"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query packages", e);
        }
        return result;
    }

    public List<String> queryClasses(String packageName) {
        List<String> result = new ArrayList<>();
        try (var ps = conn.prepareStatement("SELECT DISTINCT class_name FROM methods WHERE package_name=? ORDER BY class_name")) {
            ps.setString(1, packageName);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) result.add(rs.getString("class_name"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query classes", e);
        }
        return result;
    }

    public List<Map<String, String>> queryMethods(String packageName, String className) {
        List<Map<String, String>> result = new ArrayList<>();
        try (var ps = conn.prepareStatement(
                "SELECT method_name, id, signature FROM methods WHERE package_name=? AND class_name=? ORDER BY method_name")) {
            ps.setString(1, packageName);
            ps.setString(2, className);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("methodName", rs.getString("method_name"));
                    m.put("globalId", rs.getString("id"));
                    m.put("signature", rs.getString("signature"));
                    result.add(m);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query methods", e);
        }
        return result;
    }

    public Map<String, Object> queryGraph(String globalId, int depth, String direction) {
        Map<String, Map<String, String>> nodes = new LinkedHashMap<>();
        List<Map<String, Object>> edges = new ArrayList<>();

        if ("callees".equals(direction) || "both".equals(direction)) {
            walkEdges(globalId, depth, true, nodes, edges);
        }
        if ("callers".equals(direction) || "both".equals(direction)) {
            walkEdges(globalId, depth, false, nodes, edges);
        }

        // Ensure root node is present
        if (!nodes.containsKey(globalId)) {
            try (var ps = conn.prepareStatement("SELECT * FROM methods WHERE id=?")) {
                ps.setString(1, globalId);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) nodes.put(globalId, methodToNodeMap(rs));
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to load root node", e);
            }
        }

        // Deduplicate edges
        Map<String, Map<String, Object>> edgeSet = new LinkedHashMap<>();
        for (var e : edges) {
            String key = e.get("source").toString() + e.get("target").toString();
            edgeSet.putIfAbsent(key, e);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", new ArrayList<>(nodes.values()));
        result.put("edges", new ArrayList<>(edgeSet.values()));
        return result;
    }

    private void walkEdges(String startId, int depth, boolean forward,
                           Map<String, Map<String, String>> nodes,
                           List<Map<String, Object>> edges) {
        String sql = forward
                ? """
                  WITH RECURSIVE chain AS (
                      SELECT caller_id, callee_id, ordinal, 1 AS depth
                      FROM call_edges WHERE caller_id=?
                      UNION ALL
                      SELECT e.caller_id, e.callee_id, e.ordinal, c.depth + 1
                      FROM call_edges e JOIN chain c ON e.caller_id = c.callee_id
                      WHERE c.depth < ?
                  )
                  SELECT DISTINCT c.caller_id, c.callee_id, c.ordinal,
                      ms.*, mt.id AS tid, mt.class_name AS t_class_name,
                      mt.method_name AS t_method_name, mt.package_name AS t_package_name,
                      mt.signature AS t_signature, mt.visibility AS t_visibility,
                      mt.existing_javadoc AS t_existing_javadoc, mt.developer_doc AS t_developer_doc,
                      mt.capability_card AS t_capability_card, mt.purpose_summary AS t_purpose_summary,
                      mt.code_health_rating AS t_code_health_rating, mt.code_health_note AS t_code_health_note,
                      mt.source_file AS t_source_file
                  FROM chain c
                  JOIN methods ms ON ms.id = c.caller_id
                  JOIN methods mt ON mt.id = c.callee_id
                  """
                : """
                  WITH RECURSIVE chain AS (
                      SELECT caller_id, callee_id, ordinal, 1 AS depth
                      FROM call_edges WHERE callee_id=?
                      UNION ALL
                      SELECT e.caller_id, e.callee_id, e.ordinal, c.depth + 1
                      FROM call_edges e JOIN chain c ON e.callee_id = c.caller_id
                      WHERE c.depth < ?
                  )
                  SELECT DISTINCT c.caller_id, c.callee_id, c.ordinal,
                      ms.*, mt.id AS tid, mt.class_name AS t_class_name,
                      mt.method_name AS t_method_name, mt.package_name AS t_package_name,
                      mt.signature AS t_signature, mt.visibility AS t_visibility,
                      mt.existing_javadoc AS t_existing_javadoc, mt.developer_doc AS t_developer_doc,
                      mt.capability_card AS t_capability_card, mt.purpose_summary AS t_purpose_summary,
                      mt.code_health_rating AS t_code_health_rating, mt.code_health_note AS t_code_health_note,
                      mt.source_file AS t_source_file
                  FROM chain c
                  JOIN methods ms ON ms.id = c.caller_id
                  JOIN methods mt ON mt.id = c.callee_id
                  """;

        try (var ps = conn.prepareStatement(sql)) {
            ps.setString(1, startId);
            ps.setInt(2, depth);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    nodes.putIfAbsent(rs.getString("id"), methodToNodeMap(rs));
                    nodes.putIfAbsent(rs.getString("tid"), targetToNodeMap(rs));
                    Map<String, Object> edge = new LinkedHashMap<>();
                    edge.put("source", rs.getString("caller_id"));
                    edge.put("target", rs.getString("callee_id"));
                    edge.put("order", rs.getInt("ordinal"));
                    edges.add(edge);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to walk edges", e);
        }
    }

    private Map<String, String> methodToNodeMap(ResultSet rs) throws SQLException {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", rs.getString("id"));
        m.put("name", rs.getString("class_name") + "#" + rs.getString("method_name"));
        m.put("className", rs.getString("class_name"));
        m.put("methodName", rs.getString("method_name"));
        m.put("package", rs.getString("package_name"));
        m.put("signature", nvl(rs.getString("signature")));
        m.put("visibility", nvl(rs.getString("visibility")));
        m.put("existingJavadoc", nvl(rs.getString("existing_javadoc")));
        m.put("developerDoc", nvl(rs.getString("developer_doc")));
        m.put("capabilityCard", nvl(rs.getString("capability_card")));
        m.put("purposeSummary", nvl(rs.getString("purpose_summary")));
        m.put("codeHealthRating", nvl(rs.getString("code_health_rating")));
        m.put("codeHealthNote", nvl(rs.getString("code_health_note")));
        m.put("sourceFile", nvl(rs.getString("source_file")));
        return m;
    }

    private Map<String, String> targetToNodeMap(ResultSet rs) throws SQLException {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", rs.getString("tid"));
        m.put("name", rs.getString("t_class_name") + "#" + rs.getString("t_method_name"));
        m.put("className", rs.getString("t_class_name"));
        m.put("methodName", rs.getString("t_method_name"));
        m.put("package", rs.getString("t_package_name"));
        m.put("signature", nvl(rs.getString("t_signature")));
        m.put("visibility", nvl(rs.getString("t_visibility")));
        m.put("existingJavadoc", nvl(rs.getString("t_existing_javadoc")));
        m.put("developerDoc", nvl(rs.getString("t_developer_doc")));
        m.put("capabilityCard", nvl(rs.getString("t_capability_card")));
        m.put("purposeSummary", nvl(rs.getString("t_purpose_summary")));
        m.put("codeHealthRating", nvl(rs.getString("t_code_health_rating")));
        m.put("codeHealthNote", nvl(rs.getString("t_code_health_note")));
        m.put("sourceFile", nvl(rs.getString("t_source_file")));
        return m;
    }

    private static String nvl(String s) { return s != null ? s : ""; }

    public List<Map<String, String>> searchFulltext(String query, int limit) {
        String[] tokens = query.toLowerCase().split("\\s+");
        if (tokens.length == 0) return List.of();

        StringBuilder sql = new StringBuilder(
                "SELECT id, package_name, class_name, method_name, signature, developer_doc, purpose_summary FROM methods WHERE ");
        List<String> params = new ArrayList<>();
        for (int i = 0; i < tokens.length; i++) {
            if (i > 0) sql.append(" AND ");
            sql.append("(method_name ILIKE ? OR class_name ILIKE ? OR signature ILIKE ? OR COALESCE(developer_doc,'') ILIKE ? OR COALESCE(purpose_summary,'') ILIKE ?)");
            String like = "%" + tokens[i] + "%";
            params.add(like);
            params.add(like);
            params.add(like);
            params.add(like);
            params.add(like);
        }
        sql.append(" LIMIT ?");

        List<Map<String, String>> result = new ArrayList<>();
        try (var ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setString(i + 1, params.get(i));
            }
            ps.setInt(params.size() + 1, limit);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("globalId", rs.getString("id"));
                    m.put("package", rs.getString("package_name"));
                    m.put("className", rs.getString("class_name"));
                    m.put("methodName", rs.getString("method_name"));
                    m.put("signature", rs.getString("signature"));
                    m.put("developerDoc", nvl(rs.getString("developer_doc")));
                    m.put("purposeSummary", nvl(rs.getString("purpose_summary")));
                    result.add(m);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed fulltext search", e);
        }
        return result;
    }

    // ── Serialization helpers ───────────────────────────────────────────

    public CapabilityCard deserializeCapabilityCard(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readValue(json, CapabilityCard.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize CapabilityCard: {}", e.getMessage());
            return null;
        }
    }

    private String serializeCapabilityCard(CapabilityCard card) {
        if (card == null) return null;
        try {
            return mapper.writeValueAsString(card);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize CapabilityCard: {}", e.getMessage());
            return null;
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

    private List<ParameterInfo> deserializeParams(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to deserialize parameters: {}", e.getMessage());
            return List.of();
        }
    }

    private MethodInfo mapRow(ResultSet rs) throws SQLException {
        MethodInfo info = new MethodInfo();
        info.setCoordinate(new MethodCoordinate(
                rs.getString("namespace"),
                rs.getString("language"),
                rs.getString("qualified_signature")
        ));
        info.setPackageName(rs.getString("package_name"));
        info.setClassName(rs.getString("class_name"));
        info.setMethodName(rs.getString("method_name"));
        info.setSignature(rs.getString("signature"));
        info.setReturnType(rs.getString("return_type"));
        info.setSourceFile(rs.getString("source_file"));
        info.setBodyHash(rs.getString("body_hash"));
        info.setExistingJavadoc(rs.getString("existing_javadoc"));
        info.setPurposeSummary(rs.getString("purpose_summary"));
        info.setDeveloperDoc(rs.getString("developer_doc"));
        info.setCodeHealthRating(rs.getString("code_health_rating"));
        info.setCodeHealthNote(rs.getString("code_health_note"));
        info.setCapabilityCard(deserializeCapabilityCard(rs.getString("capability_card")));

        String visibility = rs.getString("visibility");
        if (visibility != null && !visibility.isEmpty()) {
            try { info.setVisibility(Visibility.valueOf(visibility)); }
            catch (IllegalArgumentException ignored) {}
        }

        info.setParameters(deserializeParams(rs.getString("parameters")));

        int sl = rs.getInt("range_start_line");
        if (!rs.wasNull()) {
            info.setRange(new MethodRange(sl,
                    rs.getInt("range_start_char"),
                    rs.getInt("range_end_line"),
                    rs.getInt("range_end_char")));
        }
        return info;
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException e) {
            log.warn("Failed to close PostgreSQL connection: {}", e.getMessage());
        }
    }
}
