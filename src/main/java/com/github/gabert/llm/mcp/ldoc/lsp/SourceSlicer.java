package com.github.gabert.llm.mcp.ldoc.lsp;

import com.github.gabert.llm.mcp.ldoc.model.MethodRange;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Extracts a substring from a source file using LSP-style line/character offsets.
 * Used by phase-1 extraction (from in-memory file text) and phase-3 body re-fetch
 * (from the file on disk) in the two-pass LSP pipeline.
 */
public final class SourceSlicer {

    private SourceSlicer() {}

    /** Read the file and slice it by {@code range}. Returns empty string on I/O failure. */
    public static String sliceFromFile(Path file, MethodRange range) {
        if (file == null || range == null) return "";
        try {
            return slice(Files.readString(file), range);
        } catch (IOException e) {
            return "";
        }
    }

    /** Slice {@code text} by LSP-style range (zero-based line/character offsets). */
    public static String slice(String text, MethodRange range) {
        if (text == null || range == null) return "";
        return slice(text, range.startLine(), range.startChar(), range.endLine(), range.endChar());
    }

    public static String slice(String text, int startLine, int startCh, int endLine, int endCh) {
        if (text == null) return "";
        String[] lines = text.split("\n", -1);
        if (startLine >= lines.length) return "";
        if (startLine == endLine) {
            String l = lines[startLine];
            int s = Math.min(startCh, l.length());
            int e = Math.min(endCh, l.length());
            if (e < s) e = s;
            return l.substring(s, e);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(lines[startLine].substring(Math.min(startCh, lines[startLine].length())));
        sb.append('\n');
        for (int i = startLine + 1; i < endLine && i < lines.length; i++) {
            sb.append(lines[i]).append('\n');
        }
        if (endLine < lines.length) {
            String l = lines[endLine];
            sb.append(l, 0, Math.min(endCh, l.length()));
        }
        return sb.toString();
    }
}
