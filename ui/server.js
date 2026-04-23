const express = require("express");
const { Pool } = require("pg");
const path = require("path");
const fs = require("fs");

// Minimal .env loader (looks at project root, one level up from ui/)
(function loadDotEnv() {
    const envPath = path.join(__dirname, "..", ".env");
    if (!fs.existsSync(envPath)) return;
    const lines = fs.readFileSync(envPath, "utf8").split(/\r?\n/);
    for (const line of lines) {
        const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/i);
        if (m && !process.env[m[1]]) process.env[m[1]] = m[2];
    }
})();

const app = express();
const PORT = process.env.PORT || 3000;
const QDRANT_URL = process.env.QDRANT_URL || "http://localhost:6333";
const QDRANT_COLLECTION = process.env.QDRANT_COLLECTION || "ldoc_methods";
const OPENAI_API_KEY = process.env.OPENAI_API_KEY || "";
const OPENAI_EMBED_MODEL = process.env.OPENAI_EMBED_MODEL || "text-embedding-3-small";

const pool = new Pool({
    host: process.env.PGHOST || "localhost",
    port: parseInt(process.env.PGPORT || "5432"),
    database: process.env.PGDATABASE || "ldoc",
    user: process.env.PGUSER || "ldoc",
    password: process.env.PGPASSWORD || "ldoc",
});

async function query(sql, params = []) {
    const { rows } = await pool.query(sql, params);
    return rows;
}

app.use(express.static(path.join(__dirname, "public")));

app.get("/api/packages", async (req, res) => {
    const rows = await query("SELECT DISTINCT package_name FROM methods ORDER BY package_name");
    res.json(rows.map(r => r.package_name));
});

app.get("/api/classes", async (req, res) => {
    const rows = await query(
        "SELECT DISTINCT class_name FROM methods WHERE package_name=$1 ORDER BY class_name",
        [req.query.package]);
    res.json(rows.map(r => r.class_name));
});

app.get("/api/methods", async (req, res) => {
    const rows = await query(
        "SELECT method_name, id AS global_id, signature FROM methods WHERE package_name=$1 AND class_name=$2 ORDER BY method_name",
        [req.query.package, req.query.className]);
    res.json(rows.map(r => ({ methodName: r.method_name, globalId: r.global_id, signature: r.signature })));
});

app.get("/api/graph", async (req, res) => {
    const { globalId, depth = 2, direction = "both" } = req.query;
    const hops = Math.min(parseInt(depth), 10);
    const nodes = new Map();
    const edges = [];

    function addNode(row) {
        if (!nodes.has(row.id))
            nodes.set(row.id, {
                id: row.id,
                name: row.class_name + "#" + row.method_name,
                className: row.class_name,
                methodName: row.method_name,
                package: row.package_name,
                signature: row.signature || "",
                visibility: row.visibility || "",
                existingJavadoc: row.existing_javadoc || "",
                developerDoc: row.developer_doc || "",
                capabilityCard: row.capability_card || "",
                purposeSummary: row.purpose_summary || "",
                codeHealthRating: row.code_health_rating || "",
                codeHealthNote: row.code_health_note || ""
            });
    }

    // Recursive CTE to walk call edges up to N hops
    if (direction === "callees" || direction === "both") {
        const rows = await query(`
            WITH RECURSIVE chain AS (
                SELECT caller_id, callee_id, ordinal, 1 AS depth
                FROM call_edges WHERE caller_id=$1
                UNION ALL
                SELECT e.caller_id, e.callee_id, e.ordinal, c.depth + 1
                FROM call_edges e JOIN chain c ON e.caller_id = c.callee_id
                WHERE c.depth < $2
            )
            SELECT DISTINCT c.caller_id, c.callee_id, c.ordinal,
                   ms.id, ms.class_name, ms.method_name, ms.package_name,
                   ms.signature, ms.visibility, ms.existing_javadoc,
                   ms.developer_doc, ms.capability_card, ms.purpose_summary,
                   ms.code_health_rating, ms.code_health_note,
                   mt.id AS tid, mt.class_name AS t_class_name, mt.method_name AS t_method_name,
                   mt.package_name AS t_package_name, mt.signature AS t_signature,
                   mt.visibility AS t_visibility, mt.existing_javadoc AS t_existing_javadoc,
                   mt.developer_doc AS t_developer_doc, mt.capability_card AS t_capability_card,
                   mt.purpose_summary AS t_purpose_summary,
                   mt.code_health_rating AS t_code_health_rating, mt.code_health_note AS t_code_health_note
            FROM chain c
            JOIN methods ms ON ms.id = c.caller_id
            JOIN methods mt ON mt.id = c.callee_id
        `, [globalId, hops]);
        for (const r of rows) {
            addNode(r);
            addNode({
                id: r.tid, class_name: r.t_class_name, method_name: r.t_method_name,
                package_name: r.t_package_name, signature: r.t_signature,
                visibility: r.t_visibility, existing_javadoc: r.t_existing_javadoc,
                developer_doc: r.t_developer_doc, capability_card: r.t_capability_card,
                purpose_summary: r.t_purpose_summary,
                code_health_rating: r.t_code_health_rating, code_health_note: r.t_code_health_note
            });
            edges.push({ source: r.caller_id, target: r.callee_id, order: r.ordinal });
        }
    }

    if (direction === "callers" || direction === "both") {
        const rows = await query(`
            WITH RECURSIVE chain AS (
                SELECT caller_id, callee_id, ordinal, 1 AS depth
                FROM call_edges WHERE callee_id=$1
                UNION ALL
                SELECT e.caller_id, e.callee_id, e.ordinal, c.depth + 1
                FROM call_edges e JOIN chain c ON e.callee_id = c.caller_id
                WHERE c.depth < $2
            )
            SELECT DISTINCT c.caller_id, c.callee_id, c.ordinal,
                   ms.id, ms.class_name, ms.method_name, ms.package_name,
                   ms.signature, ms.visibility, ms.existing_javadoc,
                   ms.developer_doc, ms.capability_card, ms.purpose_summary,
                   ms.code_health_rating, ms.code_health_note,
                   mt.id AS tid, mt.class_name AS t_class_name, mt.method_name AS t_method_name,
                   mt.package_name AS t_package_name, mt.signature AS t_signature,
                   mt.visibility AS t_visibility, mt.existing_javadoc AS t_existing_javadoc,
                   mt.developer_doc AS t_developer_doc, mt.capability_card AS t_capability_card,
                   mt.purpose_summary AS t_purpose_summary,
                   mt.code_health_rating AS t_code_health_rating, mt.code_health_note AS t_code_health_note
            FROM chain c
            JOIN methods ms ON ms.id = c.caller_id
            JOIN methods mt ON mt.id = c.callee_id
        `, [globalId, hops]);
        for (const r of rows) {
            addNode(r);
            addNode({
                id: r.tid, class_name: r.t_class_name, method_name: r.t_method_name,
                package_name: r.t_package_name, signature: r.t_signature,
                visibility: r.t_visibility, existing_javadoc: r.t_existing_javadoc,
                developer_doc: r.t_developer_doc, capability_card: r.t_capability_card,
                purpose_summary: r.t_purpose_summary,
                code_health_rating: r.t_code_health_rating, code_health_note: r.t_code_health_note
            });
            edges.push({ source: r.caller_id, target: r.callee_id, order: r.ordinal });
        }
    }

    // Ensure the root node is always present
    if (!nodes.has(globalId)) {
        const rows = await query("SELECT * FROM methods WHERE id=$1", [globalId]);
        if (rows.length > 0) {
            const r = rows[0];
            addNode(r);
        }
    }

    // Deduplicate edges
    const edgeSet = new Map();
    for (const e of edges) { const k = e.source + e.target; if (!edgeSet.has(k)) edgeSet.set(k, e); }
    res.json({ nodes: [...nodes.values()], edges: [...edgeSet.values()], rootId: globalId });
});

// ---------- Full-text search over summaries (ILIKE) ----------
app.get("/api/search/fulltext", async (req, res) => {
    const q = (req.query.q || "").trim();
    if (!q) return res.json({ results: [] });
    const limit = Math.min(parseInt(req.query.limit) || 30, 200);

    const tokens = q.toLowerCase().split(/\s+/).filter(t => t.length > 0);
    if (tokens.length === 0) return res.json({ results: [] });

    const conditions = tokens.map((_, i) =>
        `(COALESCE(developer_doc,'') ILIKE $${i + 1} OR COALESCE(purpose_summary,'') ILIKE $${i + 1})`
    ).join(" AND ");

    const params = tokens.map(t => `%${t}%`);
    params.push(limit);

    const rows = await query(
        `SELECT id, package_name, class_name, method_name, signature, developer_doc, purpose_summary
         FROM methods WHERE ${conditions}
         LIMIT $${params.length}`,
        params
    );
    res.json({
        results: rows.map(r => ({
            globalId: r.id,
            package: r.package_name,
            className: r.class_name,
            methodName: r.method_name,
            signature: r.signature,
            developerDoc: r.developer_doc || "",
            purposeSummary: r.purpose_summary || ""
        }))
    });
});

// ---------- RAG search (OpenAI embed → Qdrant nearest neighbors) ----------
app.get("/api/search/rag", async (req, res) => {
    const q = (req.query.q || "").trim();
    if (!q) return res.json({ results: [] });
    const limit = Math.min(parseInt(req.query.limit) || 20, 100);

    if (!OPENAI_API_KEY) {
        return res.status(400).json({ error: "OPENAI_API_KEY not set. Add it to .env and restart the UI server." });
    }

    try {
        const embedResp = await fetch("https://api.openai.com/v1/embeddings", {
            method: "POST",
            headers: {
                "Authorization": "Bearer " + OPENAI_API_KEY,
                "Content-Type": "application/json"
            },
            body: JSON.stringify({ model: OPENAI_EMBED_MODEL, input: q })
        });
        if (!embedResp.ok) {
            const txt = await embedResp.text();
            return res.status(502).json({ error: "OpenAI embed failed: " + txt });
        }
        const embedJson = await embedResp.json();
        const vector = embedJson.data[0].embedding;

        const qdrantResp = await fetch(`${QDRANT_URL}/collections/${QDRANT_COLLECTION}/points/search`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ vector, limit, with_payload: true })
        });
        if (!qdrantResp.ok) {
            const txt = await qdrantResp.text();
            return res.status(502).json({ error: "Qdrant search failed: " + txt });
        }
        const qdrantJson = await qdrantResp.json();

        const results = (qdrantJson.result || []).map(p => ({
            globalId: p.payload.globalId,
            package: p.payload.package,
            className: p.payload.className,
            methodName: p.payload.methodName,
            signature: p.payload.signature,
            developerDoc: p.payload.developerDoc || "",
            purposeSummary: p.payload.purposeSummary || "",
            score: p.score
        }));
        res.json({ results });
    } catch (err) {
        res.status(500).json({ error: String(err.message || err) });
    }
});

app.listen(PORT, () => console.log("MCP Function Registry UI running at http://localhost:" + PORT));
