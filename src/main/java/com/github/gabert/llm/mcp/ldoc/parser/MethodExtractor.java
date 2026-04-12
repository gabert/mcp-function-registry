package com.github.gabert.llm.mcp.ldoc.parser;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.AccessSpecifier;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.github.javaparser.utils.SourceRoot;
import com.github.gabert.llm.mcp.ldoc.model.MethodCoordinate;
import com.github.gabert.llm.mcp.ldoc.model.MethodInfo;
import com.github.gabert.llm.mcp.ldoc.model.ParameterInfo;
import com.github.gabert.llm.mcp.ldoc.model.Visibility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

public class MethodExtractor {

    private static final Logger log = LoggerFactory.getLogger(MethodExtractor.class);

    private final String namespace;
    private final String language;

    public MethodExtractor(String namespace, String language) {
        this.namespace = namespace;
        this.language = language != null ? language : "java";
    }

    public Map<String, MethodInfo> extractAll(Path sourceRoot) {
        CombinedTypeSolver typeSolver = new CombinedTypeSolver(
                new ReflectionTypeSolver(),
                new JavaParserTypeSolver(sourceRoot)
        );
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

        // Post-process: keep only project-internal callees
        Set<String> projectIds = result.keySet();
        for (MethodInfo info : result.values()) {
            info.setCalleeIds(
                    info.getCalleeIds().stream()
                            .filter(projectIds::contains)
                            .toList()
            );
        }

        log.info("Extracted {} methods total", result.size());
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
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
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
