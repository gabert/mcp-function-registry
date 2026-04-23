package com.github.gabert.llm.mcp.ldoc.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.gabert.llm.mcp.ldoc.storage.PostgresStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ApiServer {

    private static final Logger log = LoggerFactory.getLogger(ApiServer.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final PostgresStore store;
    private final Path staticDir;
    private final int port;

    public ApiServer(AppConfig config, Path staticDir) {
        this.store = new PostgresStore(config);
        this.staticDir = staticDir;
        this.port = config.getInt("ldoc.api.port", 3000);
    }

    public void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/api/packages", this::handlePackages);
        server.createContext("/api/classes", this::handleClasses);
        server.createContext("/api/methods", this::handleMethods);
        server.createContext("/api/graph", this::handleGraph);
        server.createContext("/api/search/fulltext", this::handleFulltext);
        server.createContext("/", this::handleStatic);

        server.setExecutor(null);
        server.start();
        log.info("API server running at http://localhost:{}", port);
    }

    // ── API endpoints ───────────────────────────────────────────────────

    private void handlePackages(HttpExchange ex) throws IOException {
        List<String> packages = store.queryPackages();
        sendJson(ex, packages);
    }

    private void handleClasses(HttpExchange ex) throws IOException {
        String pkg = queryParam(ex, "package");
        List<String> classes = store.queryClasses(pkg);
        sendJson(ex, classes);
    }

    private void handleMethods(HttpExchange ex) throws IOException {
        String pkg = queryParam(ex, "package");
        String className = queryParam(ex, "className");
        List<Map<String, String>> methods = store.queryMethods(pkg, className);
        sendJson(ex, methods);
    }

    private void handleGraph(HttpExchange ex) throws IOException {
        String globalId = queryParam(ex, "globalId");
        int depth = parseInt(queryParam(ex, "depth"), 2);
        String direction = queryParam(ex, "direction");
        if (direction == null || direction.isBlank()) direction = "both";
        depth = Math.min(depth, 10);

        Map<String, Object> result = store.queryGraph(globalId, depth, direction);
        result.put("rootId", globalId);
        sendJson(ex, result);
    }

    private void handleFulltext(HttpExchange ex) throws IOException {
        String q = queryParam(ex, "q");
        int limit = parseInt(queryParam(ex, "limit"), 30);
        limit = Math.min(limit, 200);

        List<Map<String, String>> results;
        if (q == null || q.isBlank()) {
            results = List.of();
        } else {
            results = store.searchFulltext(q, limit);
        }
        sendJson(ex, Map.of("results", results));
    }

    // ── Static file serving ─────────────────────────────────────────────

    private void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";

        Path file = staticDir.resolve(path.substring(1)).normalize();
        if (!file.startsWith(staticDir) || !Files.exists(file) || Files.isDirectory(file)) {
            sendError(ex, 404, "Not found");
            return;
        }

        String contentType = guessContentType(file.toString());
        ex.getResponseHeaders().set("Content-Type", contentType);
        byte[] bytes = Files.readAllBytes(file);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static String queryParam(HttpExchange ex, String name) {
        String query = ex.getRequestURI().getRawQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            if (key.equals(name)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static int parseInt(String s, int defaultVal) {
        if (s == null || s.isBlank()) return defaultVal;
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return defaultVal; }
    }

    private static void sendJson(HttpExchange ex, Object obj) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(obj);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sendError(HttpExchange ex, int code, String msg) throws IOException {
        byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String guessContentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    // ── Main ────────────────────────────────────────────────────────────

    public static void main(String[] args) throws IOException {
        Path staticDir;
        if (args.length > 0) {
            staticDir = Path.of(args[0]);
        } else {
            staticDir = Path.of("ui", "public");
        }
        if (!Files.isDirectory(staticDir)) {
            System.err.println("Static dir not found: " + staticDir.toAbsolutePath());
            System.err.println("Usage: api-server [path-to-static-dir]");
            System.exit(1);
        }

        AppConfig config = new AppConfig();
        new ApiServer(config, staticDir).start();
    }
}
