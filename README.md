# mcp-function-registry

**A queryable index of a codebase's methods, built for AI coding agents.**

`mcp-function-registry` walks a resolved call graph, generates call-chain-aware summaries
and LLM tool descriptors for every method, and stores the result as a Neo4j graph plus a
Qdrant vector index. The registry is language-agnostic by design: coding agents query it
— via MCP or a thin REST surface — to discover *which existing method to call* when
writing new code, instead of re-deriving the answer from raw source on every turn. The
current parser backend targets Java; additional language backends are on the roadmap.

---

## Why

Large codebases overwhelm agent context windows. Dumping a repository into a prompt
is wasteful and imprecise; grep-based retrieval surfaces syntax matches, not
behavioural ones. The working hypothesis here is that an agent should be able to ask
questions like:

- *"What public method in this repo creates an invoice from a cart?"*
- *"Everything downstream of `PaymentController.charge()` — which of those touch the
  database?"*
- *"Give me the tool-descriptor for `EmployeeService.hireEmployee` so I can call it
  from the code I'm writing."*

…and get a structured, ranked answer in milliseconds. That requires the index to be
**pre-computed, call-chain-aware, and stored as a graph + vector space**, not
regenerated on demand. Hence this project.

---

## What it does

```
Java sources
    │
    ▼
┌───────────────────────────────────────────────────────────────┐
│ 1. Parse (JavaParser + SymbolSolver)                          │
│    Resolve every method call to a fully qualified signature.  │
│    Build MethodInfo records with callees, visibility, body,   │
│    existing javadoc, signature, parameters.                   │
└───────────────────────────────────────────────────────────────┘
    │
    ▼
┌───────────────────────────────────────────────────────────────┐
│ 2. Topologically sort the call graph (Kahn's algorithm)       │
│    Leaves first, callers after callees. Cycles broken by      │
│    placing cycle members at the front with best-effort        │
│    context.                                                   │
└───────────────────────────────────────────────────────────────┘
    │
    ▼
┌───────────────────────────────────────────────────────────────┐
│ 3. LLM phase (Claude Sonnet 4.6)                              │
│    For each method in topological order, prompt includes the  │
│    body + already-generated summaries of its callees. Claude  │
│    returns four fields:                                       │
│      - purposeSummary        (embedded for semantic search)   │
│      - summary               (behavioural walkthrough)        │
│      - internalDocumentation (contract-style reference)       │
│      - toolDescriptor        (public methods only; LLM-tool   │
│                               shaped, for agent consumption)  │
│    Structural fields (parameter names/types, return type,     │
│    coordinate) are derived deterministically and merged — the │
│    LLM contributes only descriptions.                         │
└───────────────────────────────────────────────────────────────┘
    │
    ▼
┌───────────────────────────────────────────────────────────────┐
│ 4. Store                                                      │
│    Neo4j : (:Method)-[:CALLS {order}]->(:Method) with full    │
│            metadata on the node.                              │
│    Qdrant: one point per method, embedded via OpenAI          │
│            text-embedding-3-small. Payload carries visibility,│
│            tool name, and the canonical coordinate for        │
│            filtered search.                                   │
└───────────────────────────────────────────────────────────────┘
```

### Multi-repo identity

Every method has a globally unique coordinate:

```
repository::module::fully.qualified.Class.method(arg.Types)
```

`MethodCoordinate` gives you `globalId()` (used as the Neo4j node key) and
`deterministicUuid()` (UUID5 of the globalId, used as the Qdrant point ID). Multiple
repositories coexist in the same databases; queries filter by `repository` to scope,
or search across all.

### Tool descriptors

For **public methods only**, the LLM phase produces a descriptor shaped like an
LLM tool-use registration:

```json
{
  "coordinate": "demo-app::::com.example.demo.service.EmployeeService.hireEmployee(com.example.demo.dto.EmployeeDto)",
  "displayName": "EmployeeService.hireEmployee",
  "language": "java",
  "description": "Persists a new active employee from the supplied DTO…",
  "parameters": [
    { "name": "dto", "nativeType": "com.example.demo.dto.EmployeeDto", "description": "…" }
  ],
  "returnType": "com.example.demo.dto.EmployeeDto"
}
```

Note: this is a **code-implementation hint**, not a runtime tool registration. The
agent reads it and emits source code that calls the method directly. MCP / OpenAI /
Anthropic tool-schema projections (with their 64-char sanitized names and JSON
Schema `input_schema`) are computed on demand — there's exactly one canonical
storage form.

---

## Tech stack

| Concern        | Choice                                                     |
|----------------|------------------------------------------------------------|
| Parsing        | `javaparser-symbol-solver-core` 3.26.2                     |
| LLM            | Anthropic Claude (claude-sonnet-4-6) via OkHttp            |
| Embeddings     | OpenAI `text-embedding-3-small` via OkHttp                 |
| Graph DB       | Neo4j Community 5.21 (no auth, local dev)                  |
| Vector DB      | Qdrant 1.9.1                                               |
| Build          | Maven, Java 17                                             |
| Config         | `dotenv-java` + `application.properties`                   |
| UI             | Node.js + Express + Cytoscape.js (dagre layout)            |
| Infra          | Docker Compose                                             |

---

## Quick start

### Prerequisites

- Java 17+, Maven
- Docker Desktop (for Neo4j + Qdrant)
- Node.js (for the UI)
- API keys in a `.env` file — only needed for the full pipeline

```bash
# .env
ANTHROPIC_API_KEY=sk-ant-...
OPENAI_API_KEY=sk-...
```

### 1. Start databases

```bash
docker compose up -d
```

- Neo4j browser: http://localhost:7474 (no auth)
- Qdrant dashboard: http://localhost:6333/dashboard

### 2. Build

```bash
mvn package -DskipTests
```

### 3. Run the pipeline

Graph-only — no API keys needed, just parse + store the call graph:

```bash
java -jar target/mcp-function-registry-1.0.0-SNAPSHOT.jar \
  my-repo ./src/main/java
```

Full pipeline — parse + LLM summaries + embeddings:

```bash
java -jar target/mcp-function-registry-1.0.0-SNAPSHOT.jar \
  my-repo ./src/main/java --with-summary --with-embeddings
```

CLI shape: `<repository> <source-root> [module] [--with-summary] [--with-embeddings]`

### 4. Explore

```bash
cd ui && npm install && node server.js
```

Open http://localhost:3000 — filter by package / class / method, click **Show Graph**,
click nodes for the full detail panel (summary, internal documentation, tool
descriptor, call-graph edges).

### Example Cypher

```cypher
// Entry points — methods nobody calls
MATCH (m:Method) WHERE NOT ()-[:CALLS]->(m)
RETURN m.className, m.methodName;

// Call composition for a given method
MATCH path = (m:Method {methodName: "hireEmployee"})-[:CALLS*]->(callee)
RETURN path;

// Upstream callers of a repository method
MATCH path = (caller)-[:CALLS*]->(m:Method {methodName: "upsertMethod"})
RETURN path;
```

---

## Design decisions worth flagging

1. **No source modification.** Nothing is written back into `.java` files. The
   generated documentation and descriptors live only in Neo4j / Qdrant.
2. **Call-chain-aware summarisation.** A caller's summary is generated *after* its
   callees, with those callees' summaries as context. This produces far more accurate
   behaviour descriptions than per-method-in-isolation prompting.
3. **Hybrid tool-descriptor generation.** Structure (parameter names, native types,
   coordinate) is deterministic from the parsed AST. The LLM contributes only
   descriptions. It cannot invent, drop, or rename parameters.
4. **Public-only tool descriptors.** Private / protected / package-private methods
   still get parsed, still enter the call graph, still get summaries and embeddings —
   they just have `toolDescriptor = null`. The descriptor's purpose is "can an agent
   call this from new code?" and non-public methods aren't accessible from outside
   their scope.
5. **No fat jar.** Distributed as `jar + lib/` (maven-jar-plugin +
   maven-dependency-plugin) rather than maven-shade.
6. **Graph-only mode.** `--graph-only` (the default without the `--with-*` flags)
   skips all LLM and embedding calls. Useful for quick call-graph exploration or CI
   runs without API keys.
7. **Multi-repo aware from day one.** Coordinates are `repo::module::qualifiedSig`.
   Multiple projects share the same Neo4j + Qdrant stores.

---

## Roadmap

- [ ] **LangChain4j port** — replace the hand-rolled Anthropic / OpenAI / Qdrant
  clients with LangChain4j abstractions to cut boilerplate and gain swappable model
  backends.
- [ ] **MCP server** — expose the registry over the Model Context Protocol so
  coding agents can query it natively.
- [ ] **Language-agnostic parsing** — move from JavaParser-specific extraction to a
  thin LSP-proxy frontend, so the same registry structure works for TypeScript, Go,
  Python, etc. (LSP preferred over tree-sitter because LSP gives resolved symbols,
  not just a parse tree.)
- [ ] **Incremental re-indexing.** Bodies are already hashed (SHA-256); the delta
  pipeline isn't wired up yet.
- [ ] **Schema versioning** for Neo4j / Qdrant payloads.

---

## Project layout

```
src/main/java/com/ldoc/
  core/        Main, AppConfig, AnalysisPipeline (phase orchestration)
  parser/      MethodExtractor (JavaParser + symbol solver)
  graph/       TopologicalSorter (Kahn's algorithm, cycle-aware)
  llm/         ClaudeClient, PromptBuilder, SummaryParser, ToolDescriptorBuilder
  storage/     Neo4jGraphStore, QdrantVectorStore, OpenAiEmbeddingClient
  model/       MethodCoordinate, MethodInfo, ParameterInfo, ToolDescriptor, Visibility
ui/            Node.js Express server + Cytoscape.js frontend
docker-compose.yml
pom.xml
```

---

## Status

Early, actively developed, single-maintainer. The pipeline is working end-to-end
(parse → summarise → Neo4j + Qdrant → UI) and has been validated against a small
Spring Boot demo application (~100 methods). Interfaces are **not** stable — expect
breaking changes as the MCP and multi-language work lands.

Issues, ideas, and PRs are welcome. Good entry points for reading the code:

- `core/AnalysisPipeline.java` — end-to-end phase orchestration
- `parser/MethodExtractor.java` — how call resolution actually happens
- `llm/PromptBuilder.java` + `llm/ToolDescriptorBuilder.java` — hybrid
  deterministic/LLM descriptor assembly
- `graph/TopologicalSorter.java` — cycle handling for mutually recursive methods