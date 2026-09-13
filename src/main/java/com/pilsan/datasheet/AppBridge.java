package com.pilsan.datasheet;

import com.fasterxml.jackson.core.type.TypeReference;
import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javafx.application.Platform;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

public final class AppBridge {

    private static final System.Logger LOGGER = System.getLogger(AppBridge.class.getName());
    private static final int SEARCH_LIMIT = 1000;
    private static final int MAX_CONCURRENCY = 4;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final WidgetWindow window;
    private final DatasheetRepository repository;
    private final GeminiAssistantService assistant;
    private final PdfExportService pdfExport;
    private final GeminiDatasheetService gemini;
    private final Settings settings;
    private final AtomicBoolean datasheetImportInProgress = new AtomicBoolean();
    private final AtomicBoolean assistantRequestInProgress = new AtomicBoolean();

    AppBridge(
            WidgetWindow window,
            DatasheetRepository repository,
            GeminiAssistantService assistant,
            PdfExportService pdfExport,
            GeminiDatasheetService gemini,
            Settings settings) {

        this.window = Objects.requireNonNull(window, "window");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.assistant = Objects.requireNonNull(assistant, "assistant");
        this.pdfExport = Objects.requireNonNull(pdfExport, "pdfExport");
        this.gemini = Objects.requireNonNull(gemini, "gemini");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    Map<String, Object> bootstrapPayload() {
        return Map.of(
                "theme", settings.theme(),
                "alwaysOnTop", settings.alwaysOnTop(),
                "catalogSize", repository.all().size(),
                "texts", I18n.all());
    }

    public void resize(String payload) {
        Map<String, Object> values = decode(payload);
        window.applyMeasuredSize(
                number(values, "width"),
                number(values, "height"),
                number(values, "barLeft"),
                number(values, "barTop"));
    }

    public String search(String query) {
        return Json.write(repository.search(query, SEARCH_LIMIT));
    }

    public String datasheet(String id) {
        Optional<Datasheet> found = repository.findById(id);
        return found.map(Json::write).orElseGet(() -> failure(I18n.text("record.notFound")));
    }

    public String saveDatasheet(String payload) {
        try {
            Datasheet parsed = Json.read(payload, Datasheet.class);
            if (!parsed.isValid()) {
                return failure(I18n.text("record.requiredCodeOrTitle"));
            }

            Optional<Datasheet> existing = repository.findById(parsed.id());
            Datasheet toSave = existing.map(d -> parsed.withSourcePath(d.sourcePath())).orElse(parsed);

            Datasheet saved = repository.save(toSave);
            return success(I18n.text("record.saved"), Map.of("datasheet", saved));
        } catch (IOException | IllegalArgumentException exception) {
            LOGGER.log(System.Logger.Level.WARNING, I18n.text("record.saveFailed"), exception);
            return failure(I18n.text("record.saveFailed"));
        }
    }

    public String saveNotes(String payload) {
        Map<String, Object> values = decode(payload);
        String id = text(values, "id");
        String notes = text(values, "notes");

        Optional<Datasheet> found = repository.findById(id);
        if (found.isEmpty()) {
            return failure(I18n.text("record.notFound"));
        }

        try {
            repository.save(found.get().withNotes(notes));
            return success(I18n.text("record.notesSaved"), Map.of());
        } catch (IOException exception) {
            LOGGER.log(System.Logger.Level.WARNING, I18n.text("record.notesSaveFailed"), exception);
            return failure(I18n.text("record.notesSaveFailed"));
        }
    }

    public void openOriginalFile(String id) {
        Optional<Datasheet> found = repository.findById(id);
        if (found.isEmpty() || found.get().sourcePath() == null || found.get().sourcePath().isBlank()) {
            window.notifyWidget("Bu kayıt için kayıtlı orijinal dosya yolu bulunamadı.", false);
            return;
        }

        File file = AppPaths.filesDirectory().resolve(found.get().sourcePath()).toFile();
        if (!file.exists()) {
            window.notifyWidget("Orijinal dosya diskte bulunamadı: " + file.getName(), false);
            return;
        }

        try {
            Desktop.getDesktop().open(file);
        } catch (IOException e) {
            LOGGER.log(System.Logger.Level.WARNING, "Dosya açılırken hata oluştu", e);
            window.notifyWidget("Dosya açılamadı.", false);
        }
    }

    public void openFile(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return;
        }
        try {
            File file = new File(filePath);
            if (file.exists() && Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(file);
            }
        } catch (Exception e) {
            LOGGER.log(System.Logger.Level.WARNING, "Dosya açılamadı", e);
            window.notifyWidget("Dosya açılamadı.", false);
        }
    }

    public String deleteDatasheet(String id) {
        try {
            return repository.delete(id)
                    ? success("Çöp kutusuna taşındı.", Map.of())
                    : failure(I18n.text("record.notFound"));
        } catch (IOException exception) {
            LOGGER.log(System.Logger.Level.WARNING, I18n.text("record.deleteFailed"), exception);
            return failure(I18n.text("record.deleteFailed"));
        }
    }

    public String getDeletedDatasheets() {
        try {
            return Json.write(repository.deletedAll());
        } catch (Exception e) {
            return Json.write(List.of());
        }
    }

    public String restoreDatasheet(String id) {
        try {
            return repository.restoreFromTrash(id)
                    ? success("Dosya başarıyla geri yüklendi.", Map.of())
                    : failure(I18n.text("record.notFound"));
        } catch (IOException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "Geri yükleme hatası", exception);
            return failure("Geri yüklenirken hata oluştu.");
        }
    }

    public String hardDeleteDatasheet(String id) {
        try {
            return repository.hardDelete(id)
                    ? success("Dosya kalıcı olarak silindi.", Map.of())
                    : failure(I18n.text("record.notFound"));
        } catch (IOException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "Kalıcı silme hatası", exception);
            return failure("Silinirken hata oluştu.");
        }
    }

    private String stripExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
    }

    private boolean isSupportedFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (name.startsWith("~$") || name.startsWith(".")) {
            return false;
        }
        return name.endsWith(".pdf") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".png") || name.endsWith(".webp");
    }

    private Datasheet forceCodeAndPath(Datasheet original, String fileName) {
        return new Datasheet(
                original.id(),
                stripExtension(fileName),
                original.title(),
                original.category(),
                original.description(),
                original.specs(),
                original.safety(),
                original.notes(),
                original.image(),
                fileName
        );
    }

    public void uploadDatasheet() {
        if (!datasheetImportInProgress.compareAndSet(false, true)) {
            Platform.runLater(() -> window.notifyWidget(I18n.text("import.busy"), false));
            return;
        }

        Platform.runLater(() -> {
            try {
                FileChooser chooser = new FileChooser();
                chooser.setTitle("Dosya Seçin");

                File selected = chooser.showOpenDialog(window.owner());
                if (selected == null) {
                    datasheetImportInProgress.set(false);
                    return;
                }

                String fileName = selected.getName();
                Path targetDir = AppPaths.filesDirectory();
                Path destination = targetDir.resolve(fileName);
                Files.copy(selected.toPath(), destination, StandardCopyOption.REPLACE_EXISTING);

                AppLog.info("Dosya kopyalandı ve analiz başlatılıyor: " + fileName);
                window.callWidget("datasheetImportStarted", "Analiz ediliyor: " + fileName);

                gemini.analyze(destination)
                        .handle((datasheet, failure) -> {
                            Platform.runLater(() -> {
                                if (failure != null || datasheet == null) {
                                    datasheetImportInProgress.set(false);
                                    String errorMsg = "Yükleme Başarısız: Dosya yapay zeka tarafından işlenemedi.";
                                    if (failure != null) {
                                        Throwable cause = unwrap(failure);
                                        if (cause instanceof GeminiDatasheetService.QuotaExhaustedException) {
                                            errorMsg = "Günlük API kotası doldu. Lütfen yarın tekrar deneyin.";
                                        } else {
                                            errorMsg = "Yükleme Başarısız: " + (cause.getMessage() != null ? cause.getMessage() : "Bilinmeyen hata");
                                        }
                                        LOGGER.log(System.Logger.Level.WARNING, errorMsg, cause);
                                        AppLog.error(errorMsg, cause);
                                    }
                                    window.notifyWidget(errorMsg, false);
                                    window.callWidget("datasheetImportFailed", errorMsg);
                                    return;
                                }

                                Datasheet configuredDatasheet = forceCodeAndPath(datasheet, fileName);
                                String code = configuredDatasheet.code();

                                Optional<Datasheet> existing = code != null && !code.isBlank()
                                        ? repository.findByCode(code)
                                        : Optional.empty();

                                if (existing.isPresent()) {
                                    datasheetImportInProgress.set(false);
                                    try {
                                        String jsonDatasheet = Json.write(configuredDatasheet);
                                        Map<String, Object> conflictData = Map.of(
                                                "existingId", existing.get().id(),
                                                "existingTitle", existing.get().title(),
                                                "code", code,
                                                "newDatasheetJson", jsonDatasheet
                                        );
                                        window.callWidget("datasheetConflict", Json.write(conflictData));
                                    } catch (Exception e) {
                                        window.notifyWidget("Mükerrer kayıt işlenirken hata oluştu.", false);
                                    }
                                    return;
                                }

                                finalizeImport(configuredDatasheet);
                            });
                            return null;
                        });

            } catch (Exception exception) {
                datasheetImportInProgress.set(false);
                String errorMsg = "Dosya seçilemedi veya analiz başlatılamadı: " + exception.getMessage();
                LOGGER.log(System.Logger.Level.ERROR, errorMsg, exception);
                AppLog.error(errorMsg, exception);
                window.notifyWidget(errorMsg, false);
            }
        });
    }

    public void resolveDatasheetConflict(String payload) {
        try {
            Map<String, Object> values = decode(payload);
            String action = text(values, "action");
            String existingId = text(values, "existingId");
            String datasheetJson = text(values, "datasheetJson");

            if ("replace".equals(action)) {
                if (!existingId.isBlank()) {
                    repository.delete(existingId);
                }
                Datasheet parsed = Json.read(datasheetJson, Datasheet.class);
                var result = repository.saveImported(parsed);
                AppLog.info("Mükerrer datasheet değiştirildi. Kod=" + parsed.code());
                window.notifyWidget("Eski kayıt silindi ve yeni datasheet başarıyla yüklendi!", true);
                window.callWidget("datasheetImported", result.datasheet(), result.updated());
            } else {
                window.notifyWidget("Yükleme işlemi iptal edildi.", false);
            }
        } catch (Exception e) {
            LOGGER.log(System.Logger.Level.WARNING, "Çakışma çözümlenemedi.", e);
            window.notifyWidget("İşlem tamamlanamadı.", false);
        }
    }

    private void finalizeImport(Datasheet configuredDatasheet) {
        try {
            var result = repository.saveImported(configuredDatasheet);
            datasheetImportInProgress.set(false);

            AppLog.info("Datasheet kütüphaneye kaydedildi. Kod=" + configuredDatasheet.code());
            window.notifyWidget("Dosya başarıyla eklendi!", true);
            window.callWidget("datasheetImported", result.datasheet(), result.updated());
        } catch (IOException exception) {
            datasheetImportInProgress.set(false);
            String saveErr = "Kayıt hatası: Dosya veritabanına yazılamadı.";
            LOGGER.log(System.Logger.Level.WARNING, saveErr, exception);
            window.notifyWidget(saveErr, false);
            window.callWidget("datasheetImportFailed", saveErr);
        }
    }

    public void uploadFolder() {
        if (!datasheetImportInProgress.compareAndSet(false, true)) {
            Platform.runLater(() -> window.notifyWidget(I18n.text("import.busy"), false));
            return;
        }

        Platform.runLater(() -> {
            try {
                DirectoryChooser chooser = new DirectoryChooser();
                chooser.setTitle("Taranacak Klasörü Seçin");

                File selectedDir = chooser.showDialog(null);
                if (selectedDir == null) {
                    datasheetImportInProgress.set(false);
                    return;
                }

                List<Path> supported;
                try (Stream<Path> stream = Files.walk(selectedDir.toPath())) {
                    supported = stream
                            .filter(Files::isRegularFile)
                            .filter(this::isSupportedFile)
                            .toList();
                }

                AppLog.info("Toplu klasör tarama tamamlandı. Klasör=" + selectedDir.getAbsolutePath() + ", uygun dosya=" + supported.size());
                startBatch(supported);

            } catch (Exception exception) {
                datasheetImportInProgress.set(false);
                LOGGER.log(System.Logger.Level.ERROR, "Klasör taranırken hata oluştu.", exception);
                AppLog.error("Klasör tarama hatası.", exception);
                window.notifyWidget("Klasör taranamadı (Erişim engellendi veya hatalı klasör).", false);
            }
        });
    }

    public String orphanCount() {
        try {
            Path directory = AppPaths.filesDirectory();
            if (!Files.isDirectory(directory)) {
                return "0";
            }

            Set<String> knownCodes = repository.all().stream()
                    .map(Datasheet::code)
                    .filter(code -> code != null && !code.isBlank())
                    .map(Datasheet::normalize)
                    .collect(Collectors.toSet());

            long count;
            try (Stream<Path> stream = Files.list(directory)) {
                count = stream
                        .filter(Files::isRegularFile)
                        .filter(this::isSupportedFile)
                        .filter(path -> {
                            String code = Datasheet.normalize(stripExtension(path.getFileName().toString()));
                            return code.isBlank() || !knownCodes.contains(code);
                        })
                        .count();
            }
            return String.valueOf(count);
        } catch (IOException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "Yetim dosya sayımı başarısız.", exception);
            return "0";
        }
    }

    public void retryFailedDatasheets() {
        if (!datasheetImportInProgress.compareAndSet(false, true)) {
            Platform.runLater(() -> window.notifyWidget(I18n.text("import.busy"), false));
            return;
        }

        Platform.runLater(() -> {
            try {
                Path directory = AppPaths.filesDirectory();
                List<Path> supported;
                try (Stream<Path> stream = Files.list(directory)) {
                    supported = stream
                            .filter(Files::isRegularFile)
                            .filter(this::isSupportedFile)
                            .toList();
                }
                startBatch(supported);
            } catch (Exception exception) {
                datasheetImportInProgress.set(false);
                LOGGER.log(System.Logger.Level.ERROR, "Yetim dosya taraması başarısız.", exception);
                AppLog.error("Yetim dosya taraması başarısız.", exception);
                window.notifyWidget("Eksik dosya taraması yapılamadı.", false);
            }
        });
    }

    private void startBatch(List<Path> supportedFiles) {
        if (supportedFiles.isEmpty()) {
            datasheetImportInProgress.set(false);
            window.notifyWidget("İşlenecek uygun dosya bulunamadı.", true);
            return;
        }

        Set<String> knownCodes = repository.all().stream()
                .map(Datasheet::code)
                .filter(code -> code != null && !code.isBlank())
                .map(Datasheet::normalize)
                .collect(Collectors.toSet());

        List<Path> pending = supportedFiles.stream()
                .filter(path -> {
                    String code = Datasheet.normalize(stripExtension(path.getFileName().toString()));
                    return code.isBlank() || !knownCodes.contains(code);
                })
                .toList();

        int skipCount = supportedFiles.size() - pending.size();

        if (pending.isEmpty()) {
            datasheetImportInProgress.set(false);
            window.notifyWidget("İşlenecek yeni dosya yok. Zaten kayıtlı: " + skipCount, true);
            return;
        }

        AppLog.info("Paralel toplu işlem başladı. İşlenecek=" + pending.size()
                + ", atlanan=" + skipCount + ", eşzamanlılık=" + MAX_CONCURRENCY);
        window.callWidget("datasheetImportStarted", "İşleniyor: 0/" + pending.size());

        BatchState state = new BatchState(pending, skipCount);
        int starters = Math.min(MAX_CONCURRENCY, pending.size());
        for (int i = 0; i < starters; i++) {
            dispatchNext(state);
        }
    }

    private void dispatchNext(BatchState state) {
        if (state.quotaHit.get()) {
            checkBatchDone(state);
            return;
        }

        int index = state.nextIndex.getAndIncrement();
        if (index >= state.total) {
            checkBatchDone(state);
            return;
        }

        state.active.incrementAndGet();
        Path targetFile = state.pending.get(index);
        String fileName = targetFile.getFileName().toString();
        Path destination = AppPaths.filesDirectory().resolve(fileName);

        try {
            if (!targetFile.toAbsolutePath().normalize().equals(destination.toAbsolutePath().normalize())) {
                Files.createDirectories(destination.getParent());
                Files.copy(targetFile, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            AppLog.error("Dosya kopyalanamadı: " + fileName, exception);
            state.failCount.incrementAndGet();
            state.active.decrementAndGet();
            reportProgress(state, fileName);
            dispatchNext(state);
            return;
        }

        gemini.analyze(destination).whenComplete((datasheet, failure) -> {
            boolean quota = false;

            if (failure != null || datasheet == null) {
                Throwable cause = failure == null ? null : unwrap(failure);
                if (cause instanceof GeminiDatasheetService.QuotaExhaustedException) {
                    quota = true;
                    state.quotaHit.set(true);
                    AppLog.warning("Günlük API kotası doldu. Yeni dosya başlatılmayacak.");
                } else {
                    AppLog.error("Dosya işlenemedi: " + fileName, cause);
                    state.failCount.incrementAndGet();
                }
            } else {
                try {
                    repository.saveImported(forceCodeAndPath(datasheet, fileName));
                    state.successCount.incrementAndGet();
                } catch (IOException exception) {
                    AppLog.error("Kayıt yazılamadı: " + fileName, exception);
                    state.failCount.incrementAndGet();
                }
            }

            state.active.decrementAndGet();
            reportProgress(state, fileName);

            if (quota) {
                checkBatchDone(state);
            } else {
                dispatchNext(state);
            }
        });
    }

    private void reportProgress(BatchState state, String fileName) {
        int done = state.successCount.get() + state.failCount.get();
        Platform.runLater(() -> window.callWidget("datasheetImportStarted",
                "İşleniyor: " + done + "/" + state.total + " (" + fileName + ")"));
    }

    private void checkBatchDone(BatchState state) {
        boolean noMoreToStart = state.quotaHit.get() || state.nextIndex.get() >= state.total;
        if (!noMoreToStart || state.active.get() > 0) {
            return;
        }
        if (!state.finished.compareAndSet(false, true)) {
            return;
        }

        datasheetImportInProgress.set(false);
        int success = state.successCount.get();
        int fail = state.failCount.get();
        boolean quota = state.quotaHit.get();

        AppLog.info("Toplu işlem bitti. Başarılı=" + success + ", Atlandı=" + state.skipCount
                + ", Hatalı=" + fail + ", kota=" + quota);

        Platform.runLater(() -> {
            if (quota) {
                window.notifyWidget("Günlük API kotası doldu. Başarılı: " + success
                        + ", Atlandı: " + state.skipCount + ", Hatalı: " + fail
                        + ". Yarın kaldığınız yerden devam edebilirsiniz.", false);
            } else {
                window.notifyWidget("Toplu işlem tamamlandı. Başarılı: " + success
                        + ", Atlandı: " + state.skipCount + ", Hatalı: " + fail, fail == 0);
            }
        });
    }

    public void askAssistant(String message) {
        if (!assistantRequestInProgress.compareAndSet(false, true)) {
            window.callWidget("assistantError", I18n.text("assistant.busy"));
            return;
        }

        AppLog.info("Asistan sorgusu: " + (message == null ? "" : message.trim()));
        assistant.ask(message)
                .whenComplete((answer, failure) -> Platform.runLater(() -> {
                    assistantRequestInProgress.set(false);
                    if (failure != null) {
                        Throwable cause = unwrap(failure);
                        AppLog.error("Asistan yanıtı üretilemedi.", cause);
                        window.callWidget("assistantError", I18n.text("assistant.unavailable"));
                        return;
                    }
                    AppLog.info("Asistan yanıtı: " + answer);
                    window.callWidget("assistantReply", answer);
                }));
    }

    public void exportPdf(String payload) {
        Map<String, Object> values = decode(payload);
        String title = text(values, "title");
        String code = text(values, "code");
        String html = text(values, "html");

        if (html.isBlank()) {
            window.notifyWidget(I18n.text("pdf.emptyContent"), false);
            return;
        }

        String rawFileName = !code.isBlank() ? code : (!title.isBlank() ? title : I18n.text("common.datasheet"));
        String safeName = rawFileName.replaceAll("[^a-zA-Z0-9-_ğüşıöçĞÜŞİÖÇ ]", "").trim();
        if (safeName.isEmpty()) {
            safeName = "Datasheet";
        }

        File downloadsDir = new File(System.getProperty("user.home"), "Downloads");
        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs();
        }

        File pdfFile = new File(downloadsDir, safeName + ".pdf");
        int counter = 1;
        while (pdfFile.exists()) {
            pdfFile = new File(downloadsDir, safeName + " (" + counter++ + ").pdf");
        }

        final File destination = pdfFile;

        pdfExport.export(
                html,
                safeName,
                destination,
                result -> Platform.runLater(() -> {
                    if (result.success()) {
                        window.callWidget("pdfExportCompleted", Json.write(Map.of(
                                "message", destination.getName() + " İndirilenler klasörüne indirildi.",
                                "path", destination.getAbsolutePath()
                        )));
                    } else {
                        window.notifyWidget(result.message(), false);
                    }
                }));
    }

    public String setTheme(String theme) {
        settings.theme(theme);
        try {
            settings.save();
            return success(I18n.text("settings.themeSaved"), Map.of());
        } catch (IOException exception) {
            LOGGER.log(System.Logger.Level.WARNING, I18n.text("settings.themeSaveFailed"), exception);
            return failure(I18n.text("settings.themeSaveFailed"));
        }
    }

    public String setAlwaysOnTop(String value) {
        boolean enabled = Boolean.parseBoolean(value);
        settings.alwaysOnTop(enabled);
        window.alwaysOnTop(enabled);

        try {
            settings.save();
            return success(I18n.text("settings.windowSaved"), Map.of());
        } catch (IOException exception) {
            LOGGER.log(System.Logger.Level.WARNING, I18n.text("settings.windowSaveFailed"), exception);
            return failure(I18n.text("settings.windowSaveFailed"));
        }
    }

    public void quit() {
        window.persistState();
        Platform.exit();
    }

    private Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    Map<String, Object> decode(String payload) {
        if (payload == null || payload.isBlank()) {
            return Map.of();
        }

        try {
            Map<String, Object> parsed = Json.read(payload, MAP_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (IOException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "Geçersiz köprü verisi.", exception);
            return Map.of();
        }
    }

    private double number(Map<String, Object> values, String key) {
        Object raw = values.get(key);
        if (!(raw instanceof Number value)) {
            return 0d;
        }

        double number = value.doubleValue();
        return Double.isFinite(number) ? number : 0d;
    }

    private String text(Map<String, Object> values, String key) {
        Object raw = values.get(key);
        return raw == null ? "" : raw.toString();
    }

    private String success(String message, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        payload.put("message", message);
        payload.putAll(extra);
        return Json.write(payload);
    }

    private String failure(String message) {
        return Json.write(Map.of("ok", false, "message", message));
    }

    private static final class BatchState {
        private final List<Path> pending;
        private final int total;
        private final int skipCount;
        private final AtomicInteger nextIndex = new AtomicInteger(0);
        private final AtomicInteger active = new AtomicInteger(0);
        private final AtomicInteger successCount = new AtomicInteger(0);
        private final AtomicInteger failCount = new AtomicInteger(0);
        private final AtomicBoolean quotaHit = new AtomicBoolean(false);
        private final AtomicBoolean finished = new AtomicBoolean(false);

        private BatchState(List<Path> pending, int skipCount) {
            this.pending = pending;
            this.total = pending.size();
            this.skipCount = skipCount;
        }
    }
}