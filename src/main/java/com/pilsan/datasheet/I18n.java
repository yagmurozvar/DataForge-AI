package com.pilsan.datasheet;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class I18n {

    private static final String RESOURCE = "/i18n/tr_TR.json";
    private static final Map<String, String> TEXTS = load();

    private I18n() {
        throw new AssertionError();
    }

    public static String text(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        return TEXTS.getOrDefault(key, key);
    }

    public static String format(String key, Object... values) {
        String result = text(key);
        if (values == null || values.length == 0) {
            return result;
        }
        if (values.length % 2 != 0) {
            throw new IllegalArgumentException("Placeholder values must be key/value pairs.");
        }
        for (int index = 0; index < values.length; index += 2) {
            String name = String.valueOf(values[index]);
            String value = String.valueOf(values[index + 1]);
            result = result.replace("{" + name + "}", value);
        }
        return result;
    }

    public static Map<String, String> all() {
        return TEXTS;
    }

    private static Map<String, String> load() {
        try (InputStream stream = I18n.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Language resource not found: " + RESOURCE);
            }
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            JsonNode root = Json.tree(json);
            Map<String, String> flattened = new LinkedHashMap<>();
            flatten("", root, flattened);
            return Collections.unmodifiableMap(flattened);
        } catch (IOException exception) {
            throw new IllegalStateException("Language resource could not be read: " + RESOURCE, exception);
        }
    }

    private static void flatten(String prefix, JsonNode node, Map<String, String> target) {
        if (node.isValueNode()) {
            target.put(prefix, node.asText());
            return;
        }
        node.fields().forEachRemaining(entry -> {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            flatten(key, entry.getValue(), target);
        });
    }
}
