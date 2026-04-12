package com.github.gabert.llm.mcp.ldoc.model;

import java.util.UUID;

/**
 * Globally unique identifier for a method.
 *
 * Format: namespace::language:pkg.Class#method(ParamType,ParamType)
 *
 * - namespace: user-supplied bucket label (e.g. "demo-app"), allows multiple
 *   indexings to coexist in one Neo4j/Qdrant without collisions.
 * - language prefix: "java:", "python:", "go:", etc. — future-proofs for
 *   multi-language support.
 * - qualifiedSignature: pkg.Class#method(ParamType,ParamType) — built from
 *   the parsed AST, not from LSP detail strings.
 */
public class MethodCoordinate {
    private final String namespace;
    private final String language;
    private final String qualifiedSignature;

    public MethodCoordinate(String namespace, String language, String qualifiedSignature) {
        this.namespace = namespace != null ? namespace : "";
        this.language = language != null ? language : "java";
        this.qualifiedSignature = qualifiedSignature;
    }

    public String getNamespace() { return namespace; }
    public String getLanguage() { return language; }
    public String getQualifiedSignature() { return qualifiedSignature; }

    /**
     * Global string ID used as Neo4j node key and Qdrant point identity.
     * Format: namespace::language:qualifiedSignature
     */
    public String globalId() {
        return namespace + "::" + language + ":" + qualifiedSignature;
    }

    /**
     * Deterministic UUID for Qdrant point ID — derived from globalId.
     */
    public UUID deterministicUuid() {
        return UUID.nameUUIDFromBytes(globalId().getBytes());
    }

    /**
     * Parses a globalId string back into a MethodCoordinate.
     */
    public static MethodCoordinate parse(String globalId) {
        int sep = globalId.indexOf("::");
        if (sep < 0) {
            throw new IllegalArgumentException("Invalid coordinate: " + globalId);
        }
        String namespace = globalId.substring(0, sep);
        String rest = globalId.substring(sep + 2);
        int colon = rest.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("Invalid coordinate (no language prefix): " + globalId);
        }
        String language = rest.substring(0, colon);
        String qualifiedSignature = rest.substring(colon + 1);
        return new MethodCoordinate(namespace, language, qualifiedSignature);
    }

    @Override
    public String toString() { return globalId(); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MethodCoordinate that)) return false;
        return globalId().equals(that.globalId());
    }

    @Override
    public int hashCode() { return globalId().hashCode(); }
}
