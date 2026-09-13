package com.pilsan.datasheet;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class ResourceText {

    private ResourceText() {
        throw new AssertionError();
    }

    public static String read(String resource) {
        try (InputStream stream = ResourceText.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Resource not found: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Resource could not be read: " + resource, exception);
        }
    }
}
