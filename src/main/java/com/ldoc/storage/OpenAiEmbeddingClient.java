package com.ldoc.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldoc.core.AppConfig;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class OpenAiEmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiEmbeddingClient.class);
    private static final String API_URL = "https://api.openai.com/v1/embeddings";

    private final OkHttpClient http = new OkHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;
    private final String model;

    public OpenAiEmbeddingClient(AppConfig config) {
        this.apiKey = config.getRequired("OPENAI_API_KEY");
        this.model  = config.get("ldoc.openai.embedding.model");
    }

    public List<Float> embed(String text) {
        try {
            Map<String, Object> body = Map.of("model", model, "input", text);
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
                var embeddingArray = root.path("data").get(0).path("embedding");
                List<Float> result = new ArrayList<>();
                for (var node : embeddingArray) result.add((float) node.asDouble());
                return result;
            }
        } catch (Exception e) {
            log.error("OpenAI embedding call failed", e);
            throw new RuntimeException(e);
        }
    }
}
