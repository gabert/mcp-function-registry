package com.github.gabert.llm.mcp.ldoc.llm;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Deserializes a JSON value that may be either a string or an array of strings.
 * Arrays are joined with newline separators.
 *
 * LLMs sometimes return structured fields like "throws" as arrays when multiple
 * items are present, even when the prompt asks for a string.
 */
public class StringOrArrayDeserializer extends StdDeserializer<String> {

    public StringOrArrayDeserializer() {
        super(String.class);
    }

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        if (p.currentToken() == JsonToken.START_ARRAY) {
            List<String> items = new ArrayList<>();
            while (p.nextToken() != JsonToken.END_ARRAY) {
                items.add(p.getValueAsString());
            }
            return String.join("\n", items);
        }
        return p.getValueAsString();
    }
}
