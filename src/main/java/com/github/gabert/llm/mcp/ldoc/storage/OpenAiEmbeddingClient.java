package com.github.gabert.llm.mcp.ldoc.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.gabert.llm.mcp.ldoc.core.AppConfig;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class OpenAiEmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiEmbeddingClient.class);
    private static final String API_URL = "https://api.openai.com/v1/embeddings";
    /** OpenAI accepts up to 2048 inputs per request; 128 is a safer memory/latency tradeoff. */
    private static final int DEFAULT_BATCH_SIZE = 128;

    private final OkHttpClient http = new OkHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;
    private final String model;
    private final int batchSize;

    public OpenAiEmbeddingClient(AppConfig config) {
        this.apiKey = config.getRequired("OPENAI_API_KEY");
        this.model  = config.get("ldoc.openai.embedding.model");
        this.batchSize = config.getInt("ldoc.openai.embedding.batch.size", DEFAULT_BATCH_SIZE);
    }

    public List<Float> embed(String text) {
        return embedBatch(List.of(text)).get(0);
    }

    /**
     * Embed many inputs, chunking into batches to stay within request limits.
     * Order is preserved: result[i] corresponds to texts[i].
     */
    public List<List<Float>> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) return Collections.emptyList();
        List<List<Float>> out = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += batchSize) {
            int end = Math.min(start + batchSize, texts.size());
            out.addAll(embedChunk(texts.subList(start, end)));
        }
        return out;
    }

    private List<List<Float>> embedChunk(List<String> chunk) {
        try {
            Map<String, Object> body = Map.of("model", model, "input", chunk);
            RequestBody requestBody = RequestBody.create(
                    mapper.writeValueAsString(body),
                    MediaType.get("application/json")
            );
            Request request = new Request.Builder()
                    .url(API_URL)
                    .post(requestBody)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .build();

            try (Response response = http.newCall(request).execute()) {
                String responseBody = response.body().string();
                if (!response.isSuccessful()) {
                    throw new RuntimeException("OpenAI embeddings error " + response.code() + ": " + responseBody);
                }
                var root = mapper.readTree(responseBody);
                var data = root.path("data");
                List<List<Float>> result = new ArrayList<>(chunk.size());
                // OpenAI returns items in the same order as input.
                for (var item : data) {
                    List<Float> vec = new ArrayList<>();
                    for (var node : item.path("embedding")) vec.add((float) node.asDouble());
                    result.add(vec);
                }
                return result;
            }
        } catch (Exception e) {
            log.error("OpenAI embedding call failed", e);
            throw new RuntimeException(e);
        }
    }
}
