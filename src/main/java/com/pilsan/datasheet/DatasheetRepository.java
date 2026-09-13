package com.pilsan.datasheet;

import com.fasterxml.jackson.core.type.TypeReference;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class DatasheetRepository {

    private static final System.Logger LOGGER = System.getLogger(DatasheetRepository.class.getName());
    private static final String SEED_RESOURCE = "/data/datasheets.json";
    private static final TypeReference<List<Datasheet>> LIST_TYPE = new TypeReference<>() {
    };

    private final Path storageFile;
    private final Path trashFile;
    private final Map<String, Datasheet> items = new LinkedHashMap<>();
    private final Map<String, Datasheet> trashItems = new LinkedHashMap<>();

    private DatasheetRepository(Path storageFile, Path trashFile) {
        this.storageFile = storageFile;
        this.trashFile = trashFile;
    }

    public static DatasheetRepository open() {
        DatasheetRepository repository = new DatasheetRepository(AppPaths.catalogFile(), AppPaths.trashFile());
        repository.initialise();
        return repository;
    }

    public synchronized List<Datasheet> all() {
        return items.values().stream()
                .sorted(Comparator.comparing(Datasheet::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public synchronized List<Datasheet> deletedAll() {
        return trashItems.values().stream()
                .sorted(Comparator.comparing(Datasheet::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public synchronized List<Datasheet> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return all();
        }

        int safeLimit = Math.max(1, Math.min(limit, 2000));
        String lowerQuery = query.toLowerCase(java.util.Locale.ROOT);

        return items.values().stream()
                .filter(item ->
                    (item.code() != null && item.code().toLowerCase(java.util.Locale.ROOT).contains(lowerQuery)) ||
                    (item.title() != null && item.title().toLowerCase(java.util.Locale.ROOT).contains(lowerQuery)) ||
                    (item.category() != null && item.category().toLowerCase(java.util.Locale.ROOT).contains(lowerQuery)) ||
                    (item.description() != null && item.description().toLowerCase(java.util.Locale.ROOT).contains(lowerQuery))
                )
                .sorted(Comparator.comparing(Datasheet::displayName, String.CASE_INSENSITIVE_ORDER))
                .limit(safeLimit)
                .toList();
    }

    public synchronized Optional<Datasheet> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(items.get(id));
    }

    public synchronized Optional<Datasheet> findDeletedById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(trashItems.get(id));
    }

    public synchronized Optional<Datasheet> findByCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String normalizedTarget = Datasheet.normalize(code);
        return items.values().stream()
                .filter(item -> item.code() != null && !item.code().isBlank())
                .filter(item -> Datasheet.normalize(item.code()).equals(normalizedTarget))
                .findFirst();
    }

    public synchronized Datasheet save(Datasheet datasheet) throws IOException {
        if (datasheet == null || !datasheet.isValid()) {
            throw new IllegalArgumentException(I18n.text("record.requiredCodeOrTitle"));
        }

        Datasheet previous = items.put(datasheet.id(), datasheet);
        try {
            persist();
            return datasheet;
        } catch (IOException exception) {
            restore(datasheet.id(), previous);
            throw exception;
        }
    }

    public synchronized SaveResult saveImported(Datasheet datasheet) throws IOException {
        if (datasheet == null || !datasheet.isValid()) {
            throw new IllegalArgumentException(I18n.text("record.requiredCodeOrTitle"));
        }

        String secureId = UUID.randomUUID().toString();

        Datasheet stored = new Datasheet(
                secureId,
                datasheet.code(),
                datasheet.title(),
                datasheet.category(),
                datasheet.description(),
                datasheet.specs(),
                datasheet.safety(),
                datasheet.notes(),
                datasheet.image(),
                datasheet.sourcePath());

        Datasheet previous = items.put(stored.id(), stored);
        try {
            persist();
            return new SaveResult(stored, false);
        } catch (IOException exception) {
            restore(stored.id(), previous);
            throw exception;
        }
    }

    public synchronized boolean delete(String id) throws IOException {
        return moveToTrash(id);
    }

    public synchronized boolean moveToTrash(String id) throws IOException {
        Datasheet removed = items.remove(id);
        if (removed == null) {
            return false;
        }

        trashItems.put(removed.id(), removed);
        try {
            persist();
            persistTrash();
            return true;
        } catch (IOException exception) {
            items.put(removed.id(), removed);
            trashItems.remove(removed.id());
            throw exception;
        }
    }

    public synchronized boolean restoreFromTrash(String id) throws IOException {
        Datasheet removed = trashItems.remove(id);
        if (removed == null) {
            return false;
        }

        items.put(removed.id(), removed);
        try {
            persist();
            persistTrash();
            return true;
        } catch (IOException exception) {
            trashItems.put(removed.id(), removed);
            items.remove(removed.id());
            throw exception;
        }
    }

    public synchronized boolean hardDelete(String id) throws IOException {
        Datasheet removed = trashItems.remove(id);
        if (removed == null) {
            return false;
        }

        try {
            persistTrash();
            return true;
        } catch (IOException exception) {
            trashItems.put(removed.id(), removed);
            throw exception;
        }
    }

    private void initialise() {
        items.clear();
        trashItems.clear();

        if (!Files.exists(storageFile)) {
            index(readSeed());
            try {
                persist();
            } catch (IOException exception) {
                throw new IllegalStateException(I18n.text("storage.catalogSeedFailed"), exception);
            }
        } else {
            try {
                List<Datasheet> loaded = readFileStrict(storageFile);
                if (loaded.isEmpty()) {
                    index(readSeed());
                } else {
                    index(loaded);
                }
            } catch (IOException exception) {
                LOGGER.log(System.Logger.Level.ERROR, "Katalog okunamadı: " + storageFile, exception);
                backupCorruptCatalog();
                items.clear();

                if (!recoverBackup()) {
                    index(readSeed());
                }

                try {
                    Files.deleteIfExists(storageFile);
                    persist();
                } catch (IOException persistFailure) {
                    throw new IllegalStateException(I18n.text("storage.catalogRecoveryFailed"), persistFailure);
                }
            }
        }

        if (Files.exists(trashFile)) {
            try {
                List<Datasheet> loadedTrash = readFileStrict(trashFile);
                indexTrash(loadedTrash);
            } catch (IOException exception) {
                LOGGER.log(System.Logger.Level.WARNING, "Çöp kutusu okunamadı.", exception);
            }
        }
    }

    private void index(List<Datasheet> source) {
        for (Datasheet datasheet : source) {
            if (datasheet != null && datasheet.isValid()) {
                items.put(datasheet.id(), datasheet);
            }
        }
    }

    private void indexTrash(List<Datasheet> source) {
        for (Datasheet datasheet : source) {
            if (datasheet != null && datasheet.isValid()) {
                trashItems.put(datasheet.id(), datasheet);
            }
        }
    }

    private List<Datasheet> readFileStrict(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            List<Datasheet> parsed = Json.read(reader, LIST_TYPE);
            return parsed == null ? List.of() : parsed;
        }
    }

    private List<Datasheet> readSeed() {
        try (InputStream stream = DatasheetRepository.class.getResourceAsStream(SEED_RESOURCE)) {
            if (stream == null) {
                LOGGER.log(System.Logger.Level.WARNING, "Başlangıç kataloğu bulunamadı: " + SEED_RESOURCE);
                return List.of();
            }
            try (Reader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                List<Datasheet> parsed = Json.read(reader, LIST_TYPE);
                return parsed == null ? List.of() : parsed;
            }
        } catch (IOException exception) {
            LOGGER.log(System.Logger.Level.ERROR, "Başlangıç kataloğu okunamadı.", exception);
            return List.of();
        }
    }

    private boolean recoverBackup() {
        Path backup = storageFile.resolveSibling(storageFile.getFileName() + ".bak");
        if (!Files.exists(backup)) {
            return false;
        }

        try {
            List<Datasheet> recovered = readFileStrict(backup);
            index(recovered);
            return true;
        } catch (IOException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "Katalog yedeği okunamadı.", exception);
            return false;
        }
    }

    private void persist() throws IOException {
        if (Files.exists(storageFile)) {
            try {
                Files.copy(
                        storageFile,
                        storageFile.resolveSibling(storageFile.getFileName() + ".bak"),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException exception) {
                LOGGER.log(System.Logger.Level.WARNING, "Katalog yedeği oluşturulamadı.", exception);
            }
        }

        String content = Json.write(new ArrayList<>(items.values())) + System.lineSeparator();
        FileStore.writeUtf8Atomically(storageFile, content);
    }

    private void persistTrash() throws IOException {
        if (Files.exists(trashFile)) {
            try {
                Files.copy(
                        trashFile,
                        trashFile.resolveSibling(trashFile.getFileName() + ".bak"),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException exception) {
                LOGGER.log(System.Logger.Level.WARNING, "Çöp kutusu yedeği oluşturulamadı.", exception);
            }
        }

        String content = Json.write(new ArrayList<>(trashItems.values())) + System.lineSeparator();
        FileStore.writeUtf8Atomically(trashFile, content);
    }

    private void backupCorruptCatalog() {
        try {
            FileStore.backup(storageFile, "corrupt");
        } catch (IOException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "Bozuk katalog yedeklenemedi.", exception);
        }
    }

    private void restore(String id, Datasheet previous) {
        if (previous == null) {
            items.remove(id);
        } else {
            items.put(id, previous);
        }
    }

    public record SaveResult(Datasheet datasheet, boolean updated) {
    }
}