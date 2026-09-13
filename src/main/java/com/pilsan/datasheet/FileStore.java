package com.pilsan.datasheet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class FileStore {

    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private FileStore() {
        throw new AssertionError();
    }

    public static void writeUtf8Atomically(Path target, String content) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent == null) {
            throw new IOException(I18n.format("storage.directoryMissing", "target", target));
        }

        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
        boolean moved = false;

        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    public static Path backup(Path source, String label) throws IOException {
        if (!Files.exists(source)) {
            return source;
        }

        String timestamp = LocalDateTime.now().format(BACKUP_TIME);
        String fileName = source.getFileName().toString();
        Path target = source.resolveSibling(fileName + "." + label + "." + timestamp);
        return Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
}
