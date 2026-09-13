package com.pilsan.datasheet;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class AppLog {

    private static final DateTimeFormatter ENTRY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter FILE_FORMAT = DateTimeFormatter.ofPattern("ddMMyyyy_HHmm");
    private static final String CONFIGURED_FILE = System.getenv("PILSAN_LOG_FILE");
    private static final Path FILE = resolveLogFile();
    private static final Object LOCK = new Object();
    private static final long MAX_BYTES = 5L * 1024 * 1024;

    private AppLog() {
        throw new AssertionError("Bu sınıf örneklenemez.");
    }

    public static Path file() {
        return FILE;
    }

    public static void info(String message) {
        write("INFO", message, null);
    }

    public static void warning(String message) {
        write("WARN", message, null);
    }

    public static void error(String message, Throwable throwable) {
        write("ERROR", message, throwable);
    }

    private static void write(String level, String message, Throwable throwable) {
        StringBuilder entry = new StringBuilder();
        entry.append('[').append(ENTRY_FORMAT.format(LocalDateTime.now())).append("] ")
                .append(level).append(" | ")
                .append(message == null ? "" : message)
                .append(System.lineSeparator());

        if (throwable != null) {
            StringWriter trace = new StringWriter();
            throwable.printStackTrace(new PrintWriter(trace));
            entry.append(trace).append(System.lineSeparator());
        }

        synchronized (LOCK) {
            try {
                rotateIfNeeded();
                Files.writeString(
                        FILE,
                        entry.toString(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException ignored) {
                System.err.print(entry);
            }
        }
    }

    private static void rotateIfNeeded() {
        try {
            if (Files.exists(FILE) && Files.size(FILE) > MAX_BYTES) {
                Path archived = FILE.resolveSibling(FILE.getFileName() + ".1");
                Files.move(FILE, archived, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
        }
    }

    private static Path resolveLogFile() {
        if (CONFIGURED_FILE != null && !CONFIGURED_FILE.isBlank()) {
            return Path.of(CONFIGURED_FILE).toAbsolutePath().normalize();
        }
        String fileName = FILE_FORMAT.format(LocalDateTime.now()) + "_logs.log";
        return resolveProjectDirectory().resolve(fileName);
    }

    private static Path resolveProjectDirectory() {
        String configured = System.getenv("PILSAN_PROJECT_DIR");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }
}
