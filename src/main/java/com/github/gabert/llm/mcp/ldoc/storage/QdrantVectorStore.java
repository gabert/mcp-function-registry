package com.github.gabert.llm.mcp.ldoc.storage;

import com.github.gabert.llm.mcp.ldoc.core.AppConfig;
import com.github.gabert.llm.mcp.ldoc.model.MethodCoordinate;
import com.github.gabert.llm.mcp.ldoc.model.MethodInfo;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;

public class QdrantVectorStore {

    private static final Logger log = LoggerFactory.getLogger(QdrantVectorStore.class);

    private final QdrantClient client;
    private final String collectionName;
    private final int vectorSize;

    public QdrantVectorStore(AppConfig config) {
        String host = config.get("ldoc.qdrant.host");
        int port    = config.getInt("ldoc.qdrant.port", 6334);
        this.collectionName = config.get("ldoc.qdrant.collection");
        this.vectorSize     = config.getInt("ldoc.qdrant.vector.size", 1536);

        this.client = new QdrantClient(QdrantGrpcClient.newBuilder(host, port, false).build());
        ensureCollection();
        log.info("Connected to Qdrant: {}:{} collection={}", host, port, collectionName);
    }

    private void ensureCollection() {
        try {
            var existing = client.listCollectionsAsync().get();
            boolean exists = existing.stream().anyMatch(c -> c.equals(collectionName));
            if (!exists) {
                client.createCollectionAsync(collectionName,
                        Collections.VectorParams.newBuilder()
                                .setSize(vectorSize)
                                .setDistance(Collections.Distance.Cosine)
                                .build()).get();
                log.info("Created Qdrant collection: {}", collectionName);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to ensure Qdrant collection", e);
        }
    }

    public void upsert(MethodInfo method, List<Float> embedding) {
        try {
            MethodCoordinate coord = method.getCoordinate();

            Map<String, JsonWithInt.Value> payload = new HashMap<>();
            // Coordinate — for cross-repo filtered search
            payload.put("globalId",           value(method.getId()));
            payload.put("repository",         value(coord.getRepository()));
            payload.put("module",             value(coord.getModule()));
            payload.put("qualifiedSignature", value(coord.getQualifiedSignature()));
            // Method metadata — for result display and filtering
            payload.put("package",            value(method.getPackageName()));
            payload.put("className",          value(method.getClassName()));
            payload.put("methodName",         value(method.getMethodName()));
            payload.put("signature",          value(method.getSignature()));
            payload.put("returnType",         value(method.getReturnType()));
            payload.put("sourceFile",         value(method.getSourceFile()));
            // Visibility / tool-descriptor flag — for filtered search
            // ("only methods an agent can call from new code")
            payload.put("visibility",         value(method.getVisibility() != null ? method.getVisibility().name() : ""));
            payload.put("hasToolDescriptor",  value(method.getToolDescriptor() != null));
            payload.put("toolName",           value(method.getClassName() + "." + method.getMethodName()));
            // Content — for RAG retrieval
            payload.put("summary",            value(method.getSummary()));
            payload.put("purposeSummary",     value(method.getPurposeSummary() != null ? method.getPurposeSummary() : ""));

            var point = Points.PointStruct.newBuilder()
                    .setId(id(coord.deterministicUuid()))
                    .setVectors(vectors(embedding))
                    .putAllPayload(payload)
                    .build();

            client.upsertAsync(collectionName, List.of(point)).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("Qdrant upsert failed for: " + method.getId(), e);
        }
    }

    public void close() {
        client.close();
    }
}
