package com.pilsan.datasheet;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class Settings {

    private static final System.Logger LOGGER = System.getLogger(Settings.class.getName());
    private static final String KEY_ANCHOR_X = "widget.anchorX";
    private static final String KEY_ANCHOR_Y = "widget.anchorY";
    private static final String KEY_THEME = "widget.theme";
    private static final String KEY_ALWAYS_ON_TOP = "widget.alwaysOnTop";

    private final Path file;
    private final Properties properties = new Properties();

    private Settings(Path file) {
        this.file = file;
    }

    public static Settings load() {
        Settings settings = new Settings(AppPaths.settingsFile());
        settings.readFromDisk();
        return settings;
    }

    public synchronized void save() throws IOException {
        String content = String.join(
                System.lineSeparator(),
                KEY_ANCHOR_X + "=" + properties.getProperty(KEY_ANCHOR_X, ""),
                KEY_ANCHOR_Y + "=" + properties.getProperty(KEY_ANCHOR_Y, ""),
                KEY_THEME + "=" + theme(),
                KEY_ALWAYS_ON_TOP + "=" + alwaysOnTop(),
                "");
        FileStore.writeUtf8Atomically(file, content);
    }

    public synchronized void saveQuietly() {
        try {
            save();
        } catch (IOException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "Ayarlar kaydedilemedi: " + file, exception);
        }
    }

    public synchronized double anchorX(double fallback) {
        return readDouble(KEY_ANCHOR_X, fallback);
    }

    public synchronized double anchorY(double fallback) {
        return readDouble(KEY_ANCHOR_Y, fallback);
    }

    public synchronized void anchor(double x, double y) {
        properties.setProperty(KEY_ANCHOR_X, Double.toString(x));
        properties.setProperty(KEY_ANCHOR_Y, Double.toString(y));
    }

    public synchronized String theme() {
        String value = properties.getProperty(KEY_THEME, "light");
        return "dark".equalsIgnoreCase(value) ? "dark" : "light";
    }

    public synchronized void theme(String value) {
        properties.setProperty(KEY_THEME, "dark".equalsIgnoreCase(value) ? "dark" : "light");
    }

    public synchronized boolean alwaysOnTop() {
        return !"false".equalsIgnoreCase(properties.getProperty(KEY_ALWAYS_ON_TOP, "true"));
    }

    public synchronized void alwaysOnTop(boolean value) {
        properties.setProperty(KEY_ALWAYS_ON_TOP, Boolean.toString(value));
    }

    private void readFromDisk() {
        if (!Files.exists(file)) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException | IllegalArgumentException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "Ayarlar okunamadı: " + file, exception);
        }
    }

    private double readDouble(String key, double fallback) {
        String raw = properties.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }

        try {
            double value = Double.parseDouble(raw);
            return Double.isFinite(value) ? value : fallback;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}
