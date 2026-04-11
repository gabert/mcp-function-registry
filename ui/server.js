const express = require("express");
const neo4j = require("neo4j-driver");
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
const NEO4J_URI = process.env.NEO4J_URI || "bolt://localhost:7687";
const QDRANT_URL = process.env.QDRANT_URL || "http://localhost:6333";
const QDRANT_COLLECTION = process.env.QDRANT_COLLECTION || "ldoc_methods";
const OPENAI_API_KEY = process.env.OPENAI_API_KEY || "";
const OPENAI_EMBED_MODEL = process.env.OPENAI_EMBED_MODEL || "text-embedding-3-small";
const driver = neo4j.driver(NEO4J_URI, neo4j.auth.none());

async function query(cypher, params = {}) {
    const session = driver.session();
    try { return (await session.run(cypher, params)).records; }
    finally { await session.close(); }
}

app.use(express.static(path.join(__dirname, "public")));

app.get("/api/packages", async (req, res) => {
    const records = await query("MATCH (m:Method) RETURN DISTINCT m.package AS package ORDER BY package");
    res.json(records.map(r => r.get("package")));
});

app.get("/api/classes", async (req, res) => {
    const records = await query(
        "MATCH (m:Method {package: $package}) RETURN DISTINCT m.className AS className ORDER BY className",
        { package: req.query.package });
    res.json(records.map(r => r.get("className")));
});

app.get("/api/methods", async (req, res) => {
    const records = await query(
        "MATCH (m:Method {package: $package, className: $className}) RETURN m.methodName AS methodName, m.globalId AS globalId, m.signature AS signature ORDER BY methodName",
        { package: req.query.package, className: req.query.className });
    res.json(records.map(r => ({ methodName: r.get("methodName"), globalId: r.get("globalId"), signature: r.get("signature") })));
});

app.get("/api/graph", async (req, res) => {
    const { globalId, depth = 2, direction = "both" } = req.query;
    const hops = Math.min(parseInt(depth), 10);
    const nodes = new Map();
    const edges = [];

    function addNode(r, key) {
        const p = r.get(key).properties;
        if (!nodes.has(p.globalId))
            nodes.set(p.globalId, {
                id: p.globalId, name: p.name, className: p.className,
                methodName: p.methodName, package: p.package,
                signature: p.signature || "",
                visibility: p.visibility || "",
                existingJavadoc: p.existingJavadoc || "",
                internalDocumentation: p.internalDocumentation || "",
                toolDescriptor: p.toolDescriptor || "",
                summary: p.summary || "",
                purposeSummary: p.purposeSummary || ""
            });
    }

    if (direction === "callees" || direction === "both") {
        const records = await query(
            "MATCH path = (m:Method {globalId: $globalId})-[:CALLS*1.." + hops + "]->(callee) UNWIND relationships(path) AS r RETURN startNode(r) AS src, endNode(r) AS dst, r.order AS ord",
            { globalId });
        for (const rec of records) {
            addNode(rec, "src"); addNode(rec, "dst");
            edges.push({ source: rec.get("src").properties.globalId, target: rec.get("dst").properties.globalId, order: rec.get("ord") != null ? rec.get("ord").toNumber() : null });
        }
    }

    if (direction === "callers" || direction === "both") {
        const records = await query(
            "MATCH path = (caller)-[:CALLS*1.." + hops + "]->(m:Method {globalId: $globalId}) UNWIND relationships(path) AS r RETURN startNode(r) AS src, endNode(r) AS dst, r.order AS ord",
            { globalId });
        for (const rec of records) {
            addNode(rec, "src"); addNode(rec, "dst");
            edges.push({ source: rec.get("src").properties.globalId, target: rec.get("dst").properties.globalId, order: rec.get("ord") != null ? rec.get("ord").toNumber() : null });
        }
    }

    if (!nodes.has(globalId)) {
        const records = await query("MATCH (m:Method {globalId: $globalId}) RETURN m", { globalId });
        if (records.length > 0) {
            const p = records[0].get("m").properties;
            nodes.set(globalId, {
                id: globalId, name: p.name, className: p.className, methodName: p.methodName,
                package: p.package, signature: p.signature || "",
                visibility: p.visibility || "",
                existingJavadoc: p.existingJavadoc || "",
                internalDocumentation: p.internalDocumentation || "",
                toolDescriptor: p.toolDescriptor || "",
                summary: p.summary || "", purposeSummary: p.purposeSummary || ""
            });
        }
    }

    const edgeSet = new Map();
    for (const e of edges) { const k = e.source + e.target; if (!edgeSet.has(k)) edgeSet.set(k, e); }
    res.json({ nodes: [...nodes.values()], edges: [...edgeSet.values()], rootId: globalId });
});

// ---------- Full-text search over summaries (Neo4j CONTAINS) ----------
app.get("/api/search/fulltext", async (req, res) => {
    const q = (req.query.q || "").trim();
    if (!q) return res.json({ results: [] });
    const limit = Math.min(parseInt(req.query.limit) || 30, 200);

    // Split into lowercase tokens; each token must appear somewhere in summary
    // OR purposeSummary (per-token OR between the two fields, AND across tokens).
    const tokens = q.toLowerCase().split(/\s+/).filter(t => t.length > 0);
    if (tokens.length === 0) return res.json({ results: [] });

    const whereClauses = tokens.map((_, i) =>
        `((m.summary IS NOT NULL AND toLower(m.summary) CONTAINS $t${i})
         OR (m.purposeSummary IS NOT NULL AND toLower(m.purposeSummary) CONTAINS $t${i}))`
    ).join(" AND ");

    const params = { limit: neo4j.int(limit) };
    tokens.forEach((t, i) => { params[`t${i}`] = t; });

    const records = await query(
        `MATCH (m:Method)
         WHERE ${whereClauses}
         RETURN m.globalId AS globalId, m.package AS package, m.className AS className,
                m.methodName AS methodName, m.signature AS signature,
                m.summary AS summary, m.purposeSummary AS purposeSummary
         LIMIT $limit`,
        params
    );
    res.json({
        results: records.map(r => ({
            globalId: r.get("globalId"),
            package: r.get("package"),
            className: r.get("className"),
            methodName: r.get("methodName"),
            signature: r.get("signature"),
            summary: r.get("summary") || "",
            purposeSummary: r.get("purposeSummary") || ""
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
        // 1) Embed the query
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

        // 2) Query Qdrant for nearest neighbors
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
            summary: p.payload.summary || "",
            purposeSummary: p.payload.purposeSummary || "",
            score: p.score
        }));
        res.json({ results });
    } catch (err) {
        res.status(500).json({ error: String(err.message || err) });
    }
});

app.listen(PORT, () => console.log("ldoc UI running at http://localhost:" + PORT));
