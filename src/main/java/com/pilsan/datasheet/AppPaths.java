package com.pilsan.datasheet;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class AppPaths {

    private static final Path DATA_DIRECTORY = resolveDataDirectory();
    private static final Path ASSETS_DIRECTORY = Paths.get("assets", "datasheets");

    private AppPaths() {
        throw new AssertionError();
    }

    public static Path catalogFile() {
        return DATA_DIRECTORY.resolve("datasheets.json");
    }

    public static Path settingsFile() {
        return DATA_DIRECTORY.resolve("settings.properties");
    }

    public static Path trashFile() {
        return DATA_DIRECTORY.resolve("trash.json");
    }

    public static Path filesDirectory() {
        try {
            Files.createDirectories(ASSETS_DIRECTORY);
        } catch (IOException exception) {
            throw new UncheckedIOException("Dosya saklama dizini oluşturulamadı.", exception);
        }
        return ASSETS_DIRECTORY;
    }

    private static Path resolveDataDirectory() {
        Path directory = Paths.get("src", "main", "resources", "data");
        try {
            Files.createDirectories(directory);
            return directory;
        } catch (IOException exception) {
            throw new UncheckedIOException("Veri dizini oluşturulamadı.", exception);
        }
    }
}