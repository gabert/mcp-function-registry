# Running ldoc with an LSP backend

`ldoc` can extract methods and the call graph via a Language Server instead of the
built-in JavaParser pipeline. This is enabled with `--lsp` and is the recommended
path for real-world Maven projects because it relies on the build-tool's own symbol
resolution (correct overloads, generics, cross-module calls).

Current status: tested with **Eclipse JDT-LS** against a Spring Boot Maven app.

---

## One-time setup

### 1. JDK 21
Eclipse JDT-LS (snapshot and anything past ~Nov 2024) requires **Java 21+** to run.
You can keep your existing JDK for building `ldoc` itself — only the LSP server
needs 21.

Expected layout:
```
D:\Util\jdk-21\bin\java.exe
```

### 2. Eclipse JDT Language Server

Download the latest snapshot tarball and extract it under `%USERPROFILE%\.jdtls`:

```powershell
# PowerShell
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$dir = Join-Path $env:USERPROFILE ".jdtls"
New-Item -ItemType Directory -Force -Path $dir | Out-Null
Invoke-WebRequest -UseBasicParsing `
  -Uri "https://download.eclipse.org/jdtls/snapshots/jdt-language-server-latest.tar.gz" `
  -OutFile (Join-Path $dir "jdt-language-server-latest.tar.gz")
```

Then from bash / git-bash:
```bash
cd "$USERPROFILE/.jdtls"
tar -xzf jdt-language-server-latest.tar.gz
```

After extraction you should have:
```
%USERPROFILE%\.jdtls\
    plugins\org.eclipse.equinox.launcher_<version>.jar
    config_win\
    ...
```

**If you're on an older JDK (17):** JDT-LS 1.36.0 is the last Java-17-compatible
release. Grab it from `https://download.eclipse.org/jdtls/milestones/1.36.0/` and
expect the call-hierarchy NPE bug — it was fixed in a later snapshot.

### 3. Wrapper script

`ldoc` spawns the LSP server as a subprocess, so we need a single command that
launches jdtls with all the right JVM flags. Create
`%USERPROFILE%\.jdtls\jdtls.cmd`:

```batch
@echo off
setlocal enabledelayedexpansion
set "JDTLS_HOME=%~dp0"
set "LAUNCHER="
for %%f in ("%JDTLS_HOME%plugins\org.eclipse.equinox.launcher_*.jar") do set "LAUNCHER=%%f"
if "%LAUNCHER%"=="" (
  echo [jdtls.cmd] Could not find Equinox launcher jar under %JDTLS_HOME%plugins 1>&2
  exit /b 1
)
"D:\Util\jdk-21\bin\java.exe" ^
  -Declipse.application=org.eclipse.jdt.ls.core.id1 ^
  -Dosgi.bundles.defaultStartLevel=4 ^
  -Declipse.product=org.eclipse.jdt.ls.core.product ^
  -Dlog.level=ALL ^
  -Xmx2G ^
  --add-modules=ALL-SYSTEM ^
  --add-opens java.base/java.util=ALL-UNNAMED ^
  --add-opens java.base/java.lang=ALL-UNNAMED ^
  -jar "!LAUNCHER!" ^
  -configuration "%JDTLS_HOME%config_win" ^
  %*
endlocal
```

Notes:
- The loop picks up the launcher jar by glob so upgrades don't break the wrapper.
- Bump `-Xmx` on large codebases. 2 GB is enough for demos; 4–6 GB for real repos.
- `%*` forwards the `-data <workspace>` args that `ldoc` appends at runtime.

---

## Configuration (`application.properties`)

```properties
# Enable LSP mode
lsp.server.command=C:/Users/<you>/.jdtls/jdtls.cmd
lsp.language.id=java
lsp.file.extension=.java

# Persistent jdtls workspace cache. Strongly recommended — without it,
# jdtls re-indexes the whole project on every run.
# ldoc creates <lsp.workspace.dir>/<repository> and passes it as '-data <dir>'.
lsp.workspace.dir=C:/Users/<you>/.ldoc/jdtls-workspaces

# How long to wait for jdtls to finish indexing before querying symbols.
# First run on a big Maven project can take minutes.
lsp.ready.timeout.ms=180000
```

All keys can also be set via environment variables
(`LSP_SERVER_COMMAND`, `LSP_WORKSPACE_DIR`, …) — env takes precedence.

---

## Running against a project

```bash
# Start the stores
docker compose up -d

# Build ldoc
mvn -q -DskipTests package

# Extract + store the call graph (LSP mode)
java -jar target/ldoc-1.0.0-SNAPSHOT.jar <repo-name> <path-to-src-root> --lsp

# Example:
java -jar target/ldoc-1.0.0-SNAPSHOT.jar demo-app ../demo_app/src/main/java --lsp
```

Arguments:
- `<repo-name>` — any identifier, used as the Neo4j repo tag and as the jdtls
  workspace subdirectory.
- `<path-to-src-root>` — the source directory (e.g. `src/main/java`). `ldoc` walks
  **up** from here looking for `pom.xml` / `build.gradle[.kts]` and points jdtls at
  the detected project root, so passing either the source root or the project root
  works.

The usual optional flags still apply: `--with-summary` (LLM summaries),
`--with-embeddings` (+ Qdrant). Neither depends on `--lsp`.

### First run vs. subsequent runs

- **First run on a repo**: jdtls imports the Maven project, downloads dependencies
  into its own m2 cache, and builds the Eclipse workspace index. Expect several
  minutes on a real codebase. Watch logs for `LSP server reported ready` — nothing
  is queried before that.
- **Subsequent runs**: the persistent `lsp.workspace.dir` is reused and readiness
  arrives in seconds. This is why the workspace dir is so important for production
  use.

### What gets extracted

Per method:
- `signature`, `returnType`, `parameters`
- `packageName`, `className`, `methodName`
- `visibility` (PUBLIC / PROTECTED / PACKAGE_PRIVATE / PRIVATE)
- `bodyHash` (body itself is held in memory for LLM prompts, not stored)
- `existingJavadoc` — rendered via `textDocument/hover`, so `@inheritDoc` and
  overrides resolve properly
- `calleeIds` — via `textDocument/prepareCallHierarchy` +
  `callHierarchy/outgoingCalls`, matched back to indexed methods by
  `(normalized-file-path, selectionRange.start)`.

### Inspecting the result

Neo4j Browser is at http://localhost:7474 (no auth on the compose file).

```cypher
// How much did we extract?
MATCH (m:Method) RETURN count(m);
MATCH (:Method)-[c:CALLS]->(:Method) RETURN count(c);

// The full call graph
MATCH (a:Method)-[:CALLS]->(b:Method)
RETURN a.className + '.' + a.methodName AS caller,
       b.className + '.' + b.methodName AS callee
ORDER BY caller;

// Read a javadoc
MATCH (m:Method {className:'EmployeeService', methodName:'hireEmployee'})
RETURN m.existingJavadoc;
```

---

## Troubleshooting

**"Required config missing: lsp.server.command"** — the `--lsp` flag was passed but
`lsp.server.command` is empty. Set it in `application.properties` or via the
`LSP_SERVER_COMMAND` env var.

**jdtls never becomes ready (timeout after 180 s)** — on first run, jdtls may need
longer for a big project. Bump `lsp.ready.timeout.ms`. Also check that your
project root actually contains `pom.xml`; if `ldoc` logged
`Detected project root <some-parent-dir>` that's not what you expected, pass the
project root explicitly instead of the source root.

**All methods come back as `Unknown#...` or visibility is always `PACKAGE_PRIVATE`**
— the server is returning flat `SymbolInformation[]` instead of nested
`DocumentSymbol[]`. This happens if `hierarchicalDocumentSymbolSupport` isn't
advertised in client capabilities. `LspClient.initialize` already does this — if
you're seeing this, check that you're running a build that contains the fix.

**Zero CALLS edges despite many methods extracted** — either:
- You're on jdtls 1.36.0 (Java 17) and hitting the `CallHierarchyHandler` NPE bug
  on most methods. Upgrade to the latest snapshot + Java 21.
- URI mismatch between documentSymbol and callHierarchy responses. Should be fixed
  by `buildLocationKey` normalizing to lowercase absolute path — if not, log raw
  responses and compare.

**Per-method warnings: `LSP error: … NullPointerException … fullLocation is null`**
— same jdtls 1.36.0 bug. The error is caught and that method gets an empty callee
list, but everything else proceeds. Fix by upgrading jdtls.

**Wrapper fails with "Could not find Equinox launcher jar"** — the glob in
`jdtls.cmd` didn't match anything. Check that `%USERPROFILE%\.jdtls\plugins`
contains an `org.eclipse.equinox.launcher_*.jar`.

---

## Using a non-Java language server

Most of the code is generic LSP — only the wrapper script and the `language.id` /
file extension are Java-specific. To point at another server:

```properties
lsp.server.command=pylsp
lsp.language.id=python
lsp.file.extension=.py
```

Caveats:
- `lsp.workspace.dir` is only useful for servers that take `-data <dir>` (jdtls).
  For others, leave it blank.
- Call hierarchy support varies wildly between servers. `pylsp` doesn't implement
  `callHierarchy/outgoingCalls` at all; `rust-analyzer` does but only for items it
  can resolve statically.
- The visibility regex in `LspMethodExtractor` is Java-specific. Non-Java code will
  end up as `PACKAGE_PRIVATE` by default.
