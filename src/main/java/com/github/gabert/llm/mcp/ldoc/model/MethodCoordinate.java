package com.github.gabert.llm.mcp.ldoc.model;

import java.util.UUID;

/**
 * Globally unique identifier for a method across multiple repositories
 * and multi-module Maven projects.
 *
 * Format: repository::module::qualifiedSignature
 */
public class MethodCoordinate {
    private final String repository;
    private final String module;
    private final String qualifiedSignature;

    public MethodCoordinate(String repository, String module, String qualifiedSignature) {
        this.repository = repository;
        this.module = module != null ? module : "";
        this.qualifiedSignature = qualifiedSignature;
    }

    public String getRepository() { return repository; }
    public String getModule() { return module; }
    public String getQualifiedSignature() { return qualifiedSignature; }

    /**
     * Global string ID used as MongoDB _id.
     * Format: repository::module::qualifiedSignature
     */
    public String globalId() {
        return repository + "::" + module + "::" + qualifiedSignature;
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
        // Split on first two :: occurrences
        int first = globalId.indexOf("::");
        int second = globalId.indexOf("::", first + 2);
        if (first < 0 || second < 0) {
            throw new IllegalArgumentException("Invalid coordinate: " + globalId);
        }
        return new MethodCoordinate(
                globalId.substring(0, first),
                globalId.substring(first + 2, second),
                globalId.substring(second + 2)
        );
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
