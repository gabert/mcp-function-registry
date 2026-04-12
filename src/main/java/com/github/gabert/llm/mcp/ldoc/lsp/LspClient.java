package com.github.gabert.llm.mcp.ldoc.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Quick-and-dirty LSP JSON-RPC client over stdio.
 * No interface, no abstraction — only what {@link LspMethodExtractor} needs.
 */
public class LspClient {

    private static final Logger log = LoggerFactory.getLogger(LspClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Process process;
    private final OutputStream out;
    private final Thread readerThread;
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private volatile boolean running = true;

    public LspClient(List<String> command, Path workDir) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (workDir != null) pb.directory(workDir.toFile());
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        this.process = pb.start();
        this.out = process.getOutputStream();
        InputStream in = process.getInputStream();

        this.readerThread = new Thread(() -> readLoop(in), "lsp-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    // ----- public API used by LspMethodExtractor -----

    public JsonNode initialize(Path rootPath) throws Exception {
        ObjectNode params = JSON.createObjectNode();
        params.putNull("processId");
        params.put("rootUri", rootPath.toUri().toString());
        ObjectNode caps = JSON.createObjectNode();
        ObjectNode window = JSON.createObjectNode();
        window.put("workDoneProgress", true);
        caps.set("window", window);
        // Ask for nested DocumentSymbol[] (with children, range, selectionRange)
        // rather than the flat SymbolInformation[] fallback — we need the tree
        // to derive enclosing class and the exact position for callHierarchy.
        ObjectNode textDocument = JSON.createObjectNode();
        ObjectNode documentSymbol = JSON.createObjectNode();
        documentSymbol.put("hierarchicalDocumentSymbolSupport", true);
        textDocument.set("documentSymbol", documentSymbol);
        ObjectNode callHierarchy = JSON.createObjectNode();
        callHierarchy.put("dynamicRegistration", false);
        textDocument.set("callHierarchy", callHierarchy);
        caps.set("textDocument", textDocument);
        params.set("capabilities", caps);
        JsonNode res = request("initialize", params);
        notify("initialized", JSON.createObjectNode());
        return res;
    }

    /**
     * Block until the server signals it's ready to answer queries, or the timeout elapses.
     * jdtls sends a {@code language/status} notification with type={@code Started}
     * or {@code ServiceReady} once its workspace import completes. Other servers may
     * never send it — in that case we log and proceed so the caller isn't stuck.
     */
    public void waitForReady(long timeoutMs) {
        try {
            ready.get(timeoutMs, TimeUnit.MILLISECONDS);
            log.info("LSP server reported ready");
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("LSP server did not signal readiness within {} ms; proceeding anyway", timeoutMs);
        } catch (Exception e) {
            log.warn("waitForReady interrupted: {}", e.toString());
        }
    }

    public void didOpen(Path file, String languageId, String text) throws Exception {
        ObjectNode doc = JSON.createObjectNode();
        doc.put("uri", file.toUri().toString());
        doc.put("languageId", languageId);
        doc.put("version", 1);
        doc.put("text", text);
        ObjectNode params = JSON.createObjectNode();
        params.set("textDocument", doc);
        notify("textDocument/didOpen", params);
    }

    public JsonNode documentSymbol(Path file) throws Exception {
        ObjectNode params = JSON.createObjectNode();
        ObjectNode td = JSON.createObjectNode();
        td.put("uri", file.toUri().toString());
        params.set("textDocument", td);
        return request("textDocument/documentSymbol", params);
    }

    public JsonNode prepareCallHierarchy(Path file, int line, int character) throws Exception {
        ObjectNode params = JSON.createObjectNode();
        ObjectNode td = JSON.createObjectNode();
        td.put("uri", file.toUri().toString());
        params.set("textDocument", td);
        ObjectNode pos = JSON.createObjectNode();
        pos.put("line", line);
        pos.put("character", character);
        params.set("position", pos);
        return request("textDocument/prepareCallHierarchy", params);
    }

    public JsonNode outgoingCalls(JsonNode item) throws Exception {
        ObjectNode params = JSON.createObjectNode();
        params.set("item", item);
        return request("callHierarchy/outgoingCalls", params);
    }

    public JsonNode hover(Path file, int line, int character) throws Exception {
        ObjectNode params = JSON.createObjectNode();
        ObjectNode td = JSON.createObjectNode();
        td.put("uri", file.toUri().toString());
        params.set("textDocument", td);
        ObjectNode pos = JSON.createObjectNode();
        pos.put("line", line);
        pos.put("character", character);
        params.set("position", pos);
        return request("textDocument/hover", params);
    }

    public void shutdown() {
        try {
            try { request("shutdown", JSON.nullNode()); } catch (Exception ignored) {}
            try { notify("exit", JSON.nullNode()); } catch (Exception ignored) {}
        } finally {
            running = false;
            try { out.close(); } catch (IOException ignored) {}
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    // ----- JSON-RPC plumbing -----

    private JsonNode request(String method, JsonNode params) throws Exception {
        long id = nextId.getAndIncrement();
        ObjectNode msg = JSON.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("id", id);
        msg.put("method", method);
        if (params != null) msg.set("params", params);

        CompletableFuture<JsonNode> fut = new CompletableFuture<>();
        pending.put(id, fut);
        writeMessage(msg);
        return fut.get(60, TimeUnit.SECONDS);
    }

    private void notify(String method, JsonNode params) throws IOException {
        ObjectNode msg = JSON.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("method", method);
        if (params != null) msg.set("params", params);
        writeMessage(msg);
    }

    private synchronized void writeMessage(JsonNode msg) throws IOException {
        byte[] body = JSON.writeValueAsBytes(msg);
        String header = "Content-Length: " + body.length + "\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private void readLoop(InputStream in) {
        try {
            while (running) {
                int contentLength = -1;
                StringBuilder headerLine = new StringBuilder();
                int prev = -1, cur;
                while ((cur = in.read()) != -1) {
                    if (prev == '\r' && cur == '\n') {
                        String line = headerLine.substring(0, headerLine.length() - 1);
                        if (line.isEmpty()) break;
                        if (line.toLowerCase().startsWith("content-length:")) {
                            contentLength = Integer.parseInt(line.substring("content-length:".length()).trim());
                        }
                        headerLine.setLength(0);
                    } else {
                        headerLine.append((char) cur);
                    }
                    prev = cur;
                }
                if (cur == -1 || contentLength < 0) return;

                byte[] body = in.readNBytes(contentLength);
                JsonNode msg = JSON.readTree(body);

                if (msg.has("id") && (msg.has("result") || msg.has("error"))) {
                    long id = msg.get("id").asLong();
                    CompletableFuture<JsonNode> fut = pending.remove(id);
                    if (fut != null) {
                        if (msg.has("error")) {
                            fut.completeExceptionally(new RuntimeException("LSP error: " + msg.get("error")));
                        } else {
                            fut.complete(msg.get("result"));
                        }
                    }
                } else if (msg.has("method") && msg.has("id")) {
                    // Server-to-client request.
                    String method = msg.get("method").asText("");
                    ObjectNode reply = JSON.createObjectNode();
                    reply.put("jsonrpc", "2.0");
                    reply.set("id", msg.get("id"));
                    if ("workspace/configuration".equals(method)) {
                        // jdtls asks for settings per items[] entry; reply with nulls (use defaults).
                        ArrayNode arr = JSON.createArrayNode();
                        JsonNode items = msg.path("params").path("items");
                        int n = items.isArray() ? items.size() : 0;
                        for (int i = 0; i < n; i++) arr.addNull();
                        reply.set("result", arr);
                    } else {
                        reply.putNull("result");
                    }
                    writeMessage(reply);
                } else if (msg.has("method")) {
                    // Server → client notification.
                    String method = msg.get("method").asText("");
                    if ("language/status".equals(method)) {
                        JsonNode params = msg.path("params");
                        String type = params.path("type").asText("");
                        String message = params.path("message").asText("");
                        log.debug("language/status: type={} message={}", type, message);
                        if ("ServiceReady".equals(type) || "Started".equals(type)
                                || "Ready".equalsIgnoreCase(message)) {
                            ready.complete(null);
                        }
                    }
                }
            }
        } catch (Exception e) {
            if (running) log.warn("LSP reader loop ended: {}", e.toString());
        }
    }

    // ----- helpers -----

    public static String readFile(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }
}
