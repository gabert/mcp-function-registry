package com.github.gabert.llm.mcp.ldoc.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.gabert.llm.mcp.ldoc.parser.MethodExtractor;
import com.github.gabert.llm.mcp.ldoc.model.MethodCoordinate;
import com.github.gabert.llm.mcp.ldoc.model.MethodInfo;
import com.github.gabert.llm.mcp.ldoc.model.MethodRange;
import com.github.gabert.llm.mcp.ldoc.model.ParameterInfo;
import com.github.gabert.llm.mcp.ldoc.model.Visibility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Quick-and-dirty LSP-driven alternative to {@link MethodExtractor}.
 * Spawns a language server via {@link LspClient}, walks the source tree, and builds
 * {@link MethodInfo} instances from documentSymbol + callHierarchy responses.
 *
 * Supports any LSP server; the command and file extension are passed in.
 * Tested against jdtls for Java.
 */
public class LspMethodExtractor {

    private static final Logger log = LoggerFactory.getLogger(LspMethodExtractor.class);

    // LSP SymbolKind
    private static final int KIND_CLASS = 5;
    private static final int KIND_METHOD = 6;
    private static final int KIND_CONSTRUCTOR = 9;
    private static final int KIND_INTERFACE = 11;
    private static final int KIND_FUNCTION = 12;

    private static final Pattern PACKAGE_PATTERN =
            Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    private final String namespace;
    private final String language;
    private final List<String> serverCommand;
    private final String languageId;
    private final String fileExtension;
    private final Path workspaceDir;
    private final long readyTimeoutMs;

    public LspMethodExtractor(String namespace, String language,
                              List<String> serverCommand, String languageId, String fileExtension,
                              Path workspaceDir, long readyTimeoutMs) {
        this.namespace = namespace;
        this.language = language != null ? language : "java";
        this.serverCommand = serverCommand;
        this.languageId = languageId;
        this.fileExtension = fileExtension.startsWith(".") ? fileExtension : "." + fileExtension;
        this.workspaceDir = workspaceDir;
        this.readyTimeoutMs = readyTimeoutMs;
    }

    /**
     * @param projectRoot directory the LSP server treats as the workspace root
     *                    (must contain {@code pom.xml} / {@code build.gradle} for
     *                    build-aware servers like jdtls).
     * @param sourceRoot  directory to walk for source files.
     */
    public Map<String, MethodInfo> extractAll(Path projectRoot, Path sourceRoot) {
        List<String> cmd = new ArrayList<>(serverCommand);
        if (workspaceDir != null) {
            Path dataDir = workspaceDir.resolve(sanitize(namespace));
            try {
                Files.createDirectories(dataDir);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create LSP workspace dir: " + dataDir, e);
            }
            cmd.add("-data");
            cmd.add(dataDir.toAbsolutePath().toString());
            log.info("Using persistent LSP workspace: {}", dataDir);
        }

        LspClient client;
        try {
            client = new LspClient(cmd, projectRoot);
        } catch (Exception e) {
            throw new RuntimeException("Failed to start LSP server: " + cmd, e);
        }

        Map<String, MethodInfo> result = new LinkedHashMap<>();
        // uri + "|" + name + "|" + detail -> globalId
        Map<String, String> callHierarchyIndex = new HashMap<>();
        // per-method: URI + selection start — used later for prepareCallHierarchy
        List<PendingCall> pendingCalls = new ArrayList<>();

        try {
            log.info("LSP initialize: rootUri={}", projectRoot.toUri());
            client.initialize(projectRoot);
            log.info("Waiting for LSP server to become ready (timeout {} ms)...", readyTimeoutMs);
            client.waitForReady(readyTimeoutMs);

            List<Path> files;
            try (Stream<Path> stream = Files.walk(sourceRoot)) {
                files = stream
                        .filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(fileExtension))
                        .toList();
            }
            log.info("Found {} source files matching {}", files.size(), fileExtension);

            for (Path file : files) {
                try {
                    String text = LspClient.readFile(file);
                    client.didOpen(file, languageId, text);
                    JsonNode symbols = client.documentSymbol(file);
                    if (symbols == null || !symbols.isArray()) continue;

                    String packageName = extractPackageName(text);
                    for (JsonNode sym : symbols) {
                        walkSymbols(client, sym, null, packageName, file, text, result, callHierarchyIndex, pendingCalls);
                    }
                } catch (Exception e) {
                    log.warn("Failed to process file {}: {}", file, e.toString());
                }
            }

            log.info("Extracted {} methods; resolving call hierarchy...", result.size());

            // Second pass: outgoing calls
            for (PendingCall pc : pendingCalls) {
                try {
                    JsonNode items = client.prepareCallHierarchy(pc.file, pc.line, pc.character);
                    if (items == null || !items.isArray() || items.isEmpty()) continue;
                    JsonNode item = items.get(0);
                    JsonNode out = client.outgoingCalls(item);
                    if (out == null || !out.isArray()) continue;

                    List<String> callees = new ArrayList<>();
                    for (JsonNode oc : out) {
                        JsonNode to = oc.get("to");
                        if (to == null) continue;
                        JsonNode toSel = to.path("selectionRange").path("start");
                        String key = buildLocationKey(
                                to.path("uri").asText(""),
                                toSel.path("line").asInt(-1),
                                toSel.path("character").asInt(-1));
                        String calleeId = callHierarchyIndex.get(key);
                        if (calleeId != null && !calleeId.equals(pc.globalId)) {
                            callees.add(calleeId);
                        }
                    }
                    MethodInfo info = result.get(pc.globalId);
                    if (info != null) {
                        info.setCalleeIds(callees.stream().distinct().toList());
                    }
                } catch (Exception e) {
                    log.warn("Call hierarchy failed for {}: {}", pc.globalId, e.toString());
                }
            }

            log.info("Extracted {} methods total via LSP", result.size());
            return result;
        } catch (Exception e) {
            throw new RuntimeException("LSP extraction failed", e);
        } finally {
            client.shutdown();
        }
    }

    private void walkSymbols(LspClient client, JsonNode sym, String enclosingClass, String packageName,
                             Path file, String fileText,
                             Map<String, MethodInfo> result,
                             Map<String, String> callHierarchyIndex,
                             List<PendingCall> pendingCalls) {
        int kind = sym.path("kind").asInt(-1);
        String name = sym.path("name").asText("");
        String detail = sym.path("detail").asText("");

        boolean isType = (kind == KIND_CLASS || kind == KIND_INTERFACE);
        String childEnclosing = enclosingClass;
        if (isType) {
            childEnclosing = (enclosingClass == null || enclosingClass.isBlank())
                    ? name
                    : enclosingClass + "." + name;
        }

        boolean isMethod = (kind == KIND_METHOD || kind == KIND_CONSTRUCTOR || kind == KIND_FUNCTION);
        if (isMethod) {
            MethodInfo info = buildMethodInfo(sym, enclosingClass, packageName, file, fileText);
            if (info != null) {
                result.put(info.getId(), info);

                JsonNode selRange = sym.path("selectionRange").path("start");
                int line = selRange.path("line").asInt(0);
                int ch = selRange.path("character").asInt(0);
                String key = buildLocationKey(file.toUri().toString(), line, ch);
                callHierarchyIndex.put(key, info.getId());
                pendingCalls.add(new PendingCall(file, line, ch, info.getId()));

                // jdtls' hover resolves inherited docs (@inheritDoc, overrides);
                // one extra round-trip per method is cheap vs. slicing comments.
                try {
                    info.setExistingJavadoc(extractJavadocFromHover(client.hover(file, line, ch)));
                } catch (Exception e) {
                    log.debug("hover failed for {}: {}", info.getId(), e.toString());
                }

                System.out.println("[lsp] " + info.getVisibility() + " " + info.getClassName()
                        + "#" + info.getMethodName());
            }
        }

        JsonNode children = sym.path("children");
        if (children.isArray()) {
            for (JsonNode child : children) {
                walkSymbols(client, child, childEnclosing, packageName, file, fileText,
                        result, callHierarchyIndex, pendingCalls);
            }
        }
    }

    /**
     * Extract the prose javadoc from a hover response. jdtls returns {@code contents}
     * in one of two shapes:
     * <ul>
     *   <li>{@code MarkupContent}: {@code {kind:"markdown", value:"```java\n<sig>\n```\n<doc>"}}</li>
     *   <li>{@code MarkedString[]}: {@code [{language:"java", value:"<sig>"}, "<doc markdown>"]}</li>
     * </ul>
     * In both forms the signature is redundant with our own {@code signature} field,
     * so we drop it and keep only the prose.
     */
    private static String extractJavadocFromHover(JsonNode hover) {
        if (hover == null || hover.isNull()) return "";
        JsonNode contents = hover.path("contents");
        String markdown;
        if (contents.isObject() && contents.has("value")) {
            markdown = stripLeadingCodeFence(contents.path("value").asText(""));
        } else if (contents.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode c : contents) {
                // MarkedString: plain string = markdown, object-with-language = code block (skip)
                if (c.isTextual()) {
                    sb.append(c.asText()).append('\n');
                } else if (c.isObject() && !c.has("language")) {
                    sb.append(stripLeadingCodeFence(c.path("value").asText(""))).append('\n');
                }
            }
            markdown = sb.toString();
        } else {
            return "";
        }
        return markdown.trim();
    }

    /** Drop a leading ```java … ``` fence if present (MarkupContent form). */
    private static String stripLeadingCodeFence(String md) {
        if (md == null) return "";
        String s = md.trim();
        if (s.startsWith("```")) {
            int endFence = s.indexOf("```", 3);
            if (endFence > 0) return s.substring(endFence + 3).trim();
        }
        return s;
    }

    private MethodInfo buildMethodInfo(JsonNode sym, String enclosingClass, String packageName,
                                       Path file, String fileText) {
        String name = sym.path("name").asText("");
        String detail = sym.path("detail").asText("");
        String className = (enclosingClass == null || enclosingClass.isBlank()) ? "Unknown" : enclosingClass;

        // qualifiedSignature: package.Class#name(detail)  — stable, unique per method
        String qualSig = (packageName == null || packageName.isBlank() ? "" : packageName + ".")
                + className + "#" + name + (detail == null ? "" : detail);

        MethodCoordinate coord = new MethodCoordinate(namespace, language, qualSig);

        MethodInfo info = new MethodInfo();
        info.setCoordinate(coord);
        info.setPackageName(packageName == null ? "" : packageName);
        info.setClassName(className);
        info.setMethodName(name);
        info.setSignature(name + (detail == null ? "" : detail));
        info.setReturnType(extractReturnType(detail));
        info.setParameters(extractParameters(detail));
        info.setSourceFile(file.toString());
        info.setVisibility(extractVisibility(sym, fileText));
        info.setCalleeIds(List.of());

        JsonNode range = sym.path("range");
        MethodRange methodRange = new MethodRange(
                range.path("start").path("line").asInt(0),
                range.path("start").path("character").asInt(0),
                range.path("end").path("line").asInt(0),
                range.path("end").path("character").asInt(0)
        );
        info.setRange(methodRange);

        String body = SourceSlicer.slice(fileText, methodRange);
        info.setBody(body);
        info.setBodyHash(sha256(body));

        return info;
    }

    private Visibility extractVisibility(JsonNode sym, String fileText) {
        JsonNode range = sym.path("selectionRange").path("start");
        int line = range.path("line").asInt(-1);
        if (line < 0) return Visibility.PACKAGE_PRIVATE;
        String[] lines = fileText.split("\n", -1);
        if (line >= lines.length) return Visibility.PACKAGE_PRIVATE;
        // Peek at declaration line and the one above (annotations can push modifier up)
        String decl = lines[line];
        String prev = line > 0 ? lines[line - 1] : "";
        String haystack = prev + " " + decl;
        if (haystack.contains("public")) return Visibility.PUBLIC;
        if (haystack.contains("private")) return Visibility.PRIVATE;
        if (haystack.contains("protected")) return Visibility.PROTECTED;
        return Visibility.PACKAGE_PRIVATE;
    }

    private String extractPackageName(String text) {
        Matcher m = PACKAGE_PATTERN.matcher(text);
        return m.find() ? m.group(1) : "";
    }

    private String extractReturnType(String detail) {
        if (detail == null) return "";
        // jdtls format: "(String, int) : void"
        int colon = detail.lastIndexOf(':');
        if (colon > 0 && detail.substring(0, colon).trim().endsWith(")")) {
            return detail.substring(colon + 1).trim();
        }
        return "";
    }

    private List<ParameterInfo> extractParameters(String detail) {
        if (detail == null) return List.of();
        int open = detail.indexOf('(');
        int close = detail.indexOf(')');
        if (open < 0 || close < 0 || close <= open + 1) return List.of();
        String inside = detail.substring(open + 1, close).trim();
        if (inside.isEmpty()) return List.of();
        List<ParameterInfo> params = new ArrayList<>();
        int i = 0;
        for (String raw : inside.split(",")) {
            String t = raw.trim();
            if (t.isEmpty()) continue;
            // If "Type name" split; else treat whole thing as type
            int sp = t.lastIndexOf(' ');
            if (sp > 0) {
                params.add(new ParameterInfo(t.substring(0, sp).trim(), t.substring(sp + 1).trim()));
            } else {
                params.add(new ParameterInfo(t, "arg" + i));
            }
            i++;
        }
        return params;
    }

    private static String sanitize(String s) {
        if (s == null || s.isBlank()) return "default";
        return s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /**
     * Stable key matching between {@code documentSymbol} (used to index methods)
     * and {@code callHierarchy} items (used to look up callees). Uses the
     * selection-range start position, which jdtls sets identically in both
     * responses — unlike the {@code detail} field, where documentSymbol returns
     * {@code "(params) : ReturnType"} but callHierarchy returns the class name.
     */
    private static String buildLocationKey(String uri, int line, int character) {
        // Normalize URI to an absolute filesystem path so that drive-letter
        // casing / URI encoding differences between our documentSymbol indexer
        // and jdtls' callHierarchy responses don't cause lookup misses on Windows.
        String normalized;
        try {
            normalized = Path.of(URI.create(uri)).toAbsolutePath().normalize().toString()
                    .toLowerCase();
        } catch (Exception e) {
            normalized = uri == null ? "" : uri.toLowerCase();
        }
        return normalized + "|" + line + ":" + character;
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(text.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }

    private record PendingCall(Path file, int line, int character, String globalId) {}
}
