package com.github.gabert.llm.mcp.ldoc.parser;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.AccessSpecifier;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.github.javaparser.utils.SourceRoot;
import com.github.gabert.llm.mcp.ldoc.model.MethodCoordinate;
import com.github.gabert.llm.mcp.ldoc.model.MethodInfo;
import com.github.gabert.llm.mcp.ldoc.model.ParameterInfo;
import com.github.gabert.llm.mcp.ldoc.model.Visibility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

public class MethodExtractor {

    private static final Logger log = LoggerFactory.getLogger(MethodExtractor.class);

    private final String namespace;
    private final String language;
    private final String classpath;
    private final List<String> excludePackages;

    public MethodExtractor(String namespace, String language) {
        this(namespace, language, null, List.of());
    }

    public MethodExtractor(String namespace, String language, String classpath, List<String> excludePackages) {
        this.namespace = namespace;
        this.language = language != null ? language : "java";
        this.classpath = classpath;
        this.excludePackages = excludePackages != null ? excludePackages : List.of();
    }

    public Map<String, MethodInfo> extractAll(Path sourceRoot) {
        CombinedTypeSolver typeSolver = new CombinedTypeSolver(
                new ReflectionTypeSolver(),
                new JavaParserTypeSolver(sourceRoot)
        );
        if (classpath != null && !classpath.isBlank()) {
            int added = 0;
            for (String entry : classpath.split(File.pathSeparator)) {
                String trimmed = entry.trim();
                if (trimmed.isEmpty()) continue;
                File f = new File(trimmed);
                if (!f.exists()) continue;
                if (trimmed.endsWith(".jar")) {
                    try {
                        typeSolver.add(new JarTypeSolver(f.toPath()));
                        added++;
                    } catch (IOException e) {
                        log.warn("Cannot add jar to type solver: {}: {}", trimmed, e.getMessage());
                    }
                } else if (f.isDirectory()) {
                    typeSolver.add(new JavaParserTypeSolver(f.toPath()));
                    added++;
                }
            }
            log.info("Added {} classpath entries to type solver", added);
        }
        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(typeSolver);

        SourceRoot root = new SourceRoot(sourceRoot);
        root.getParserConfiguration()
                .setSymbolResolver(symbolSolver)
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);

        List<CompilationUnit> cus;
        try {
            cus = root.tryToParse()
                    .stream()
                    .filter(r -> r.isSuccessful() && r.getResult().isPresent())
                    .map(r -> r.getResult().get())
                    .toList();
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse source root: " + sourceRoot, e);
        }
        log.info("Parsed {} compilation units from {}", cus.size(), sourceRoot);

        Map<String, MethodInfo> result = new LinkedHashMap<>();

        for (CompilationUnit cu : cus) {
            for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
                try {
                    MethodInfo info = buildMethodInfo(method, cu);
                    result.put(info.getId(), info);
                    System.out.println("[parse] " + info.getVisibility() + " " + info.getClassName() + "#" + info.getMethodName() + "  (" + info.getCalleeIds().size() + " callees)");
                } catch (Exception e) {
                    log.warn("Skipping unresolvable method {}: {}",
                            method.getNameAsString(), e.getMessage());
                }
            }
        }

        // Create stub entries for external callees so they appear in the graph.
        // Exclude packages matching the configured prefixes (JDK, etc.).
        Set<String> projectIds = new HashSet<>(result.keySet());
        Map<String, MethodInfo> externalStubs = new LinkedHashMap<>();
        int excluded = 0;
        for (MethodInfo info : result.values()) {
            List<String> filtered = new ArrayList<>();
            for (String calleeId : info.getCalleeIds()) {
                if (projectIds.contains(calleeId)) {
                    filtered.add(calleeId);
                } else if (isExcludedPackage(calleeId)) {
                    excluded++;
                } else if (externalStubs.containsKey(calleeId)) {
                    filtered.add(calleeId);
                } else {
                    MethodInfo stub = buildExternalStub(calleeId);
                    if (stub != null) {
                        externalStubs.put(calleeId, stub);
                        filtered.add(calleeId);
                    }
                }
            }
            info.setCalleeIds(filtered);
        }
        if (!externalStubs.isEmpty()) {
            result.putAll(externalStubs);
            log.info("Created {} stub entries for external callees ({} excluded by package filter)",
                    externalStubs.size(), excluded);
        }

        log.info("Extracted {} methods total ({} project, {} external)",
                result.size(), projectIds.size(), externalStubs.size());
        return result;
    }

    private MethodInfo buildMethodInfo(MethodDeclaration method, CompilationUnit cu) {
        ResolvedMethodDeclaration resolved = method.resolve();

        MethodCoordinate coordinate = new MethodCoordinate(
                namespace, language, resolved.getQualifiedSignature());

        MethodInfo info = new MethodInfo();
        info.setCoordinate(coordinate);
        info.setPackageName(resolved.getPackageName());
        info.setClassName(resolved.getClassName());
        info.setMethodName(resolved.getName());
        info.setReturnType(method.getTypeAsString());
        info.setSourceFile(cu.getStorage()
                .map(s -> s.getPath().toString())
                .orElse("unknown"));
        info.setSignature(method.getDeclarationAsString(true, true, true));
        info.setVisibility(mapVisibility(method.getAccessSpecifier()));

        List<ParameterInfo> params = method.getParameters().stream()
                .map(p -> new ParameterInfo(p.getTypeAsString(), p.getNameAsString()))
                .toList();
        info.setParameters(params);

        String body = method.getBody().map(Object::toString).orElse("");
        info.setBody(body);
        info.setBodyHash(sha256(body));

        method.getJavadocComment().ifPresent(jd ->
                info.setExistingJavadoc(jd.getContent()));

        List<String> calleeIds = new ArrayList<>();
        String selfId = info.getId();
        for (MethodCallExpr call : collectCallsInDataflowOrder(method)) {
            try {
                String qualSig = call.resolve().getQualifiedSignature();
                String calleeGlobalId = new MethodCoordinate(namespace, language, qualSig).globalId();
                if (!calleeGlobalId.equals(selfId)) {
                    calleeIds.add(calleeGlobalId);
                }
            } catch (Exception ignored) {
                // Unresolvable — external library or JDK
            }
        }
        info.setCalleeIds(calleeIds.stream().distinct().toList());

        return info;
    }

    /**
     * Post-order AST traversal: children before parent. This matches Java's evaluation
     * order — for {@code foo(bar(x))}, {@code bar} is collected before {@code foo}
     * because the argument must execute first. Sequential statements naturally appear
     * in source order since sibling nodes are visited left-to-right.
     */
    private List<MethodCallExpr> collectCallsInDataflowOrder(Node node) {
        List<MethodCallExpr> result = new ArrayList<>();
        for (Node child : node.getChildNodes()) {
            result.addAll(collectCallsInDataflowOrder(child));
        }
        if (node instanceof MethodCallExpr mce) {
            result.add(mce);
        }
        return result;
    }

    private boolean isExcludedPackage(String globalId) {
        if (excludePackages.isEmpty()) return false;
        try {
            MethodCoordinate coord = MethodCoordinate.parse(globalId);
            String qualSig = coord.getQualifiedSignature();
            for (String prefix : excludePackages) {
                if (qualSig.startsWith(prefix)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * Creates a minimal MethodInfo for an external callee (library/JDK method)
     * by parsing its globalId back into package, class, and method name.
     */
    private MethodInfo buildExternalStub(String globalId) {
        try {
            MethodCoordinate coord = MethodCoordinate.parse(globalId);
            String qualSig = coord.getQualifiedSignature();

            // qualSig format: pkg.Class.method(params)
            int parenIdx = qualSig.indexOf('(');
            String beforeParams = parenIdx > 0 ? qualSig.substring(0, parenIdx) : qualSig;
            int lastDot = beforeParams.lastIndexOf('.');
            if (lastDot < 0) return null;
            String methodName = beforeParams.substring(lastDot + 1);
            String fqClass = beforeParams.substring(0, lastDot);
            int classDot = fqClass.lastIndexOf('.');
            String packageName = classDot > 0 ? fqClass.substring(0, classDot) : "";
            String className = classDot > 0 ? fqClass.substring(classDot + 1) : fqClass;

            MethodInfo stub = new MethodInfo();
            stub.setCoordinate(coord);
            stub.setPackageName(packageName);
            stub.setClassName(className);
            stub.setMethodName(methodName);
            stub.setSignature(qualSig);
            stub.setVisibility(Visibility.PUBLIC);
            stub.setCalleeIds(List.of());
            stub.setParameters(List.of());
            stub.setSourceFile("external");
            stub.setBodyHash("");
            return stub;
        } catch (Exception e) {
            log.debug("Cannot build stub for {}: {}", globalId, e.getMessage());
            return null;
        }
    }

    private Visibility mapVisibility(AccessSpecifier spec) {
        if (spec == null) return Visibility.PACKAGE_PRIVATE;
        return switch (spec) {
            case PUBLIC    -> Visibility.PUBLIC;
            case PROTECTED -> Visibility.PROTECTED;
            case PRIVATE   -> Visibility.PRIVATE;
            case NONE      -> Visibility.PACKAGE_PRIVATE;
        };
    }

    private String sha256(String text) {
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
}
