package com.github.gabert.llm.mcp.ldoc.core;

import io.github.cdimascio.dotenv.Dotenv;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class AppConfig {

    private final Properties props = new Properties();
    private final Dotenv dotenv;

    public AppConfig() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("application.properties")) {
            if (is != null) props.load(is);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load application.properties", e);
        }
        dotenv = Dotenv.configure().ignoreIfMissing().load();
    }

    public String get(String key) {
        // priority: env var > .env file > application.properties
        String envKey = key.toUpperCase().replace('.', '_').replace('-', '_');
        String val = System.getenv(envKey);
        if (val != null) return val;
        val = dotenv.get(envKey, null);
        if (val != null) return val;
        return props.getProperty(key, "");
    }

    public String getRequired(String key) {
        String val = get(key);
        if (val == null || val.isBlank())
            throw new IllegalStateException("Required config missing: " + key + " (env: " + key.toUpperCase().replace('.', '_') + ")");
        return val;
    }

    public int getInt(String key, int defaultValue) {
        String val = get(key);
        return (val == null || val.isBlank()) ? defaultValue : Integer.parseInt(val);
    }
}
