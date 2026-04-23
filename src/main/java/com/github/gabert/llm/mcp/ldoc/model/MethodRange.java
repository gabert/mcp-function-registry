package com.github.gabert.llm.mcp.ldoc.model;

/**
 * Source location of a method in its file, using LSP-style zero-based
 * line/character offsets. Persisted on Method nodes so phase 3 can
 * re-slice the body from disk without re-running the extractor.
 */
public record MethodRange(int startLine, int startChar, int endLine, int endChar) {
    public boolean isEmpty() {
        return startLine == 0 && startChar == 0 && endLine == 0 && endChar == 0;
    }
}
