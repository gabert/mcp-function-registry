package com.github.gabert.llm.mcp.ldoc.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: mcp-function-registry <namespace> <path-to-source-root> [--with-summary] [--with-embeddings] [--lsp]");
            System.err.println();
            System.err.println("  namespace          — bucket label for this indexing (e.g. payment-service)");
            System.err.println("  path               — source root (e.g. src/main/java)");
            System.err.println("  --with-summary     — also generate LLM summaries (requires ANTHROPIC_API_KEY)");
            System.err.println("  --with-embeddings  — also store embeddings in Qdrant (implies --with-summary, requires OPENAI_API_KEY)");
            System.err.println("  --lsp              — extract via LSP server instead of JavaParser (see lsp.server.command)");
            System.err.println();
            System.err.println("Default: parse source and store call graph in PostgreSQL only (no LLM calls).");
            System.err.println();
            System.err.println("Examples:");
            System.err.println("  mcp-function-registry payment-service ./src/main/java");
            System.err.println("  mcp-function-registry payment-service ./src/main/java --with-summary");
            System.err.println("  mcp-function-registry payment-service ./src/main/java --with-summary --with-embeddings");
            System.exit(1);
        }

        String namespace = args[0];
        Path sourceDir = Path.of(args[1]);

        boolean withSummary = false;
        boolean withEmbeddings = false;
        boolean useLsp = false;
        for (int i = 2; i < args.length; i++) {
            if ("--with-summary".equals(args[i])) withSummary = true;
            else if ("--with-embeddings".equals(args[i])) withEmbeddings = true;
            else if ("--lsp".equals(args[i])) useLsp = true;
        }

        // Embeddings require a summary to embed
        if (withEmbeddings) withSummary = true;

        if (!sourceDir.toFile().isDirectory()) {
            System.err.println("Not a directory: " + sourceDir);
            System.exit(1);
        }

        log.info("Starting: namespace={}, source={}, withSummary={}, withEmbeddings={}, useLsp={}",
                namespace, sourceDir.toAbsolutePath(), withSummary, withEmbeddings, useLsp);

        AppConfig config = new AppConfig();
        AnalysisPipeline pipeline = new AnalysisPipeline(config);
        pipeline.run(sourceDir, namespace, withSummary, withEmbeddings, useLsp);

        log.info("Done.");
    }
}
