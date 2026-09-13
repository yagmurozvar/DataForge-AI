package com.pilsan.datasheet;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.Reader;

public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private Json() {
        throw new AssertionError();
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Nesne JSON biçimine dönüştürülemedi.", exception);
        }
    }

    public static String writeCompact(Object value) {
        try {
            return MAPPER.writer()
                    .without(SerializationFeature.INDENT_OUTPUT)
                    .writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Nesne JSON biçimine dönüştürülemedi.", exception);
        }
    }

    public static <T> T read(String json, Class<T> type) throws IOException {
        return MAPPER.readValue(json, type);
    }

    public static <T> T read(String json, TypeReference<T> type) throws IOException {
        return MAPPER.readValue(json, type);
    }

    public static <T> T read(Reader reader, TypeReference<T> type) throws IOException {
        return MAPPER.readValue(reader, type);
    }

    public static JsonNode tree(String json) throws IOException {
        return MAPPER.readTree(json);
    }
}
