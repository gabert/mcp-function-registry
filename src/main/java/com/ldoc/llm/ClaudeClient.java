package com.ldoc.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldoc.core.AppConfig;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public class ClaudeClient {

    private static final Logger log = LoggerFactory.getLogger(ClaudeClient.class);
    private static final String API_URL = "https://api.anthropic.com/v1/messages";

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(30))
            .readTimeout(Duration.ofMinutes(5))
            .writeTimeout(Duration.ofSeconds(60))
            .callTimeout(Duration.ofMinutes(5))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;
    private final String model;
    private final int maxTokens;

    public ClaudeClient(AppConfig config) {
        this.apiKey = config.getRequired("ANTHROPIC_API_KEY");
        this.model = config.get("ldoc.claude.model");
        this.maxTokens = config.getInt("ldoc.claude.max.tokens", 4096);
    }

    private static final int MAX_ATTEMPTS = 5;

    public String complete(String prompt) {
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return callOnce(prompt);
            } catch (RetryableException e) {
                lastError = e;
                if (attempt == MAX_ATTEMPTS) break;
                long backoffMs = (long) (Math.pow(2, attempt - 1) * 1000) + (long) (Math.random() * 500);
                log.warn("Claude API transient error (attempt {}/{}): {}. Retrying in {}ms", attempt, MAX_ATTEMPTS, e.getMessage(), backoffMs);
                try { Thread.sleep(backoffMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw new RuntimeException(ie); }
            } catch (Exception e) {
                log.error("Claude API call failed (non-retryable)", e);
                throw new RuntimeException(e);
            }
        }
        log.error("Claude API call failed after {} attempts", MAX_ATTEMPTS, lastError);
        throw new RuntimeException("Claude API failed after " + MAX_ATTEMPTS + " retries: " + lastError.getMessage(), lastError);
    }

    private String callOnce(String prompt) throws Exception {
        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", maxTokens,
                "messages", List.of(Map.of("role", "user", "content", prompt))
        );

        RequestBody requestBody = RequestBody.create(
                mapper.writeValueAsString(body),
                MediaType.get("application/json")
        );

        Request request = new Request.Builder()
                .url(API_URL)
                .post(requestBody)
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("content-type", "application/json")
                .build();

        try (Response response = http.newCall(request).execute()) {
            String responseBody = response.body().string();
            int code = response.code();
            if (!response.isSuccessful()) {
                String msg = "Claude API error " + code + ": " + responseBody;
                if (isRetryable(code, responseBody)) throw new RetryableException(msg);
                throw new RuntimeException(msg);
            }
            var root = mapper.readTree(responseBody);
            return root.path("content").get(0).path("text").asText();
        } catch (java.io.IOException e) {
            // Network-level failures (timeouts, connection drops) are retryable
            throw new RetryableException("Network error: " + e.getMessage());
        }
    }

    private boolean isRetryable(int code, String body) {
        // 429 rate limit, 5xx server errors, 424 "Could not serve request" (transient Anthropic)
        if (code == 429 || code >= 500) return true;
        if (code == 424 && body != null && body.contains("Could not serve request")) return true;
        return false;
    }

    private static class RetryableException extends Exception {
        RetryableException(String msg) { super(msg); }
    }
}
