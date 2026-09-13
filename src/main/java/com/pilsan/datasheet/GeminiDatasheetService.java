package com.pilsan.datasheet;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class GeminiDatasheetService {

    private static final String INTERACTIONS_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions";
    private static final String FILES_UPLOAD_ENDPOINT = "https://generativelanguage.googleapis.com/upload/v1beta/files";
    private static final String FILES_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/";
    private static final String MODEL = "gemini-3.5-flash-lite";
    private static final long MAX_FILE_BYTES = 50L * 1024L * 1024L;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(25);
    private static final Duration UPLOAD_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration FILE_PROCESSING_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration INTERACTION_TIMEOUT = Duration.ofMinutes(4);

    private static final long MIN_REQUEST_INTERVAL_MS = 100;
    private static final int MAX_ATTEMPTS = 4;
    private static final int QUOTA_GIVEUP_THRESHOLD = 3;
    private static final long MAX_BACKOFF_MS = 60000;

    private final HttpClient httpClient;
    private final Object rateLock = new Object();
    private long nextAllowedNanos = 0;

    public GeminiDatasheetService() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public static final class QuotaExhaustedException extends IOException {
        public QuotaExhaustedException(String message) {
            super(message);
        }
    }

    public CompletableFuture<Datasheet> analyze(Path file) {
        Objects.requireNonNull(file, "file");
        return CompletableFuture.supplyAsync(() -> {
            try {
                return analyzeBlocking(file);
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        });
    }

    private Datasheet analyzeBlocking(Path file) throws IOException {
        validateFile(file);
        long size = Files.size(file);
        String fileName = file.getFileName().toString();
        String mimeType = detectMimeType(fileName);

        AppLog.info("Dosya analizi başladı. Dosya=" + fileName + ", tür=" + mimeType + ", boyut=" + size + " bayt, model=" + MODEL);

        UploadedFile uploaded = uploadFile(file, mimeType);
        try {
            Extraction extraction = extractPrimary(uploaded, fileName, mimeType);
            if (!extraction.valid()) {
                String reason = extraction.reason().isBlank()
                        ? I18n.text("import.notDatasheet")
                        : extraction.reason();
                throw new IOException(reason);
            }

            List<Datasheet.Spec> technicalSpecs = completeSpecs(extraction.specs());

            if (extraction.technicalTableRowCount() > 0 && technicalSpecs.isEmpty()) {
                throw new IOException(I18n.text("import.technicalDataFailed"));
            }

            Datasheet datasheet = extraction.toDatasheet(technicalSpecs);
            if (!datasheet.isValid()) {
                throw new IOException(I18n.text("import.identityFailed"));
            }

            AppLog.info("Analiz tamamlandı. Kod=" + datasheet.code()
                    + ", teknik veri=" + datasheet.specs().size()
                    + ", güvenlik maddesi=" + datasheet.safety().size());
            return datasheet;
        } finally {
            deleteUploadedFile(uploaded);
        }
    }

    private Extraction extractPrimary(UploadedFile uploaded, String fileName, String mimeType) throws IOException {
        String responseBody = sendInteraction(
                "birincil analiz",
                buildRequest(uploaded.uri(), mimeType, extractionPrompt(fileName), responseSchema()));
        String output = extractOutputText(responseBody);
        AppLog.info("Birincil yapılandırılmış çıktı: " + output);
        return Json.read(output, Extraction.class);
    }

    private UploadedFile uploadFile(Path file, String mimeType) throws IOException {
        long started = System.nanoTime();
        long size = Files.size(file);
        String fileName = file.getFileName().toString();
        String metadata = Json.write(Map.of("file", Map.of("display_name", fileName)));

        HttpRequest startRequest = HttpRequest.newBuilder(URI.create(FILES_UPLOAD_ENDPOINT))
                .timeout(UPLOAD_TIMEOUT)
                .header("x-goog-api-key", AppSecrets.GEMINI_API_KEY)
                .header("X-Goog-Upload-Protocol", "resumable")
                .header("X-Goog-Upload-Command", "start")
                .header("X-Goog-Upload-Header-Content-Length", Long.toString(size))
                .header("X-Goog-Upload-Header-Content-Type", mimeType)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(metadata))
                .build();

        HttpResponse<String> startResponse = sendHttp(startRequest, "Dosya yükleme oturumu");
        requireSuccess(startResponse);
        String uploadUrl = startResponse.headers()
                .firstValue("x-goog-upload-url")
                .orElseThrow(() -> new IOException("Analiz servisi dosya yükleme adresi döndürmedi."));

        HttpRequest uploadRequest = HttpRequest.newBuilder(URI.create(uploadUrl))
                .timeout(UPLOAD_TIMEOUT)
                .header("X-Goog-Upload-Offset", "0")
                .header("X-Goog-Upload-Command", "upload, finalize")
                .POST(HttpRequest.BodyPublishers.ofFile(file))
                .build();

        HttpResponse<String> uploadResponse = sendHttp(uploadRequest, "Dosya yükleme");
        requireSuccess(uploadResponse);
        UploadedFile uploaded = parseUploadedFile(uploadResponse.body());
        uploaded = waitUntilActive(uploaded);
        AppLog.info("Dosya Gemini Files API'ye yüklendi. Süre=" + elapsedMillis(started)
                + " ms, dosya=" + uploaded.name());
        return uploaded;
    }

    private String detectMimeType(String fileName) {
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        return "application/pdf";
    }

    private UploadedFile waitUntilActive(UploadedFile initial) throws IOException {
        UploadedFile current = initial;
        long deadline = System.nanoTime() + FILE_PROCESSING_TIMEOUT.toNanos();
        while ("PROCESSING".equalsIgnoreCase(current.state())) {
            if (System.nanoTime() >= deadline) {
                throw new IOException(I18n.text("import.processingTimeout"));
            }
            try {
                Thread.sleep(750);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException(I18n.text("import.processingInterrupted"), exception);
            }
            current = getUploadedFile(current.name());
        }
        if (!"ACTIVE".equalsIgnoreCase(current.state())) {
            throw new IOException(I18n.format("import.processingFailed", "state", current.state()));
        }
        return current;
    }

    private UploadedFile getUploadedFile(String name) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(FILES_ENDPOINT + name))
                .timeout(Duration.ofSeconds(30))
                .header("x-goog-api-key", AppSecrets.GEMINI_API_KEY)
                .GET()
                .build();
        HttpResponse<String> response = sendHttp(request, "Dosya durum kontrolü");
        requireSuccess(response);
        return parseUploadedFile(response.body());
    }

    private void deleteUploadedFile(UploadedFile uploaded) {
        if (uploaded == null || uploaded.name().isBlank()) {
            return;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(FILES_ENDPOINT + uploaded.name()))
                    .timeout(Duration.ofSeconds(30))
                    .header("x-goog-api-key", AppSecrets.GEMINI_API_KEY)
                    .DELETE()
                    .build();
            HttpResponse<String> response = sendHttp(request, "Geçici dosya silme");
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                AppLog.info("Geçici Gemini dosyası silindi: " + uploaded.name());
            } else {
                AppLog.warning("Geçici Gemini dosyası silinemedi. HTTP " + response.statusCode());
            }
        } catch (IOException exception) {
            AppLog.error("Geçici Gemini dosyası silinemedi.", exception);
        }
    }

    private UploadedFile parseUploadedFile(String body) throws IOException {
        JsonNode root = Json.tree(body);
        JsonNode file = root.has("file") ? root.path("file") : root;
        String name = file.path("name").asText("").trim();
        String uri = file.path("uri").asText("").trim();
        String mimeType = file.path("mimeType").asText("application/pdf").trim();
        String state = file.path("state").asText("ACTIVE").trim();
        if (name.isEmpty() || uri.isEmpty()) {
            throw new IOException(I18n.text("import.uploadInfoMissing"));
        }
        return new UploadedFile(name, uri, mimeType, state);
    }

    private void throttle() {
        long waitNanos;
        synchronized (rateLock) {
            long now = System.nanoTime();
            long start = Math.max(now, nextAllowedNanos);
            waitNanos = start - now;
            nextAllowedNanos = start + Duration.ofMillis(MIN_REQUEST_INTERVAL_MS).toNanos();
        }
        if (waitNanos > 0) {
            try {
                Thread.sleep(waitNanos / 1_000_000, (int) (waitNanos % 1_000_000));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private long retryDelayMillis(HttpResponse<String> response, int attempt) {
        long retryAfter = response.headers()
                .firstValue("Retry-After")
                .map(this::parseRetryAfter)
                .orElse(0L);
        if (retryAfter > 0) {
            return retryAfter * 1000L;
        }
        long base = 5000L * (1L << (attempt - 1));
        return Math.min(base, MAX_BACKOFF_MS);
    }

    private long parseRetryAfter(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }

    private void sleepMillis(long millis) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException(I18n.text("import.analysisInterrupted"), exception);
        }
    }

    private String sendInteraction(String phase, Map<String, Object> payload) throws IOException {
        String requestBody = Json.write(payload);
        IOException lastError = null;
        int consecutiveQuota = 0;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            throttle();
            long started = System.nanoTime();
            AppLog.info("Analiz isteği gönderiliyor. Aşama=" + phase + ", deneme=" + attempt
                    + ", istek=" + requestBody.length() + " karakter");
            HttpRequest request = HttpRequest.newBuilder(URI.create(INTERACTIONS_ENDPOINT))
                    .timeout(INTERACTION_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("x-goog-api-key", AppSecrets.GEMINI_API_KEY)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 429) {
                    consecutiveQuota++;
                    if (consecutiveQuota >= QUOTA_GIVEUP_THRESHOLD) {
                        throw new QuotaExhaustedException(I18n.text("import.rateLimited"));
                    }
                    long waitMs = retryDelayMillis(response, attempt);
                    AppLog.warning("API limiti (429). Aşama=" + phase + ", bekleme=" + waitMs + " ms");
                    sleepMillis(waitMs);
                    continue;
                }

                consecutiveQuota = 0;
                AppLog.info("Analiz yanıtı alındı. Aşama=" + phase + ", HTTP=" + response.statusCode()
                        + ", süre=" + elapsedMillis(started) + " ms");
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IOException(apiError(response.statusCode(), response.body()));
                }
                return response.body();
            } catch (HttpTimeoutException exception) {
                lastError = exception;
                AppLog.error("Analiz isteği zaman aşımına uğradı. Aşama=" + phase
                        + ", deneme=" + attempt + ", süre=" + elapsedMillis(started) + " ms", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException(I18n.text("import.analysisInterrupted"), exception);
            }
        }
        throw new IOException(I18n.text("import.analysisTimeout"), lastError);
    }

    private HttpResponse<String> sendHttp(HttpRequest request, String phase) throws IOException {
        IOException lastError = null;
        int consecutiveQuota = 0;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            throttle();
            long started = System.nanoTime();
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 429) {
                    consecutiveQuota++;
                    if (consecutiveQuota >= QUOTA_GIVEUP_THRESHOLD) {
                        throw new QuotaExhaustedException(I18n.text("import.rateLimited"));
                    }
                    long waitMs = retryDelayMillis(response, attempt);
                    AppLog.warning(phase + " için API limiti (429). Bekleme=" + waitMs + " ms");
                    sleepMillis(waitMs);
                    continue;
                }

                AppLog.info(phase + " tamamlandı. HTTP=" + response.statusCode()
                        + ", süre=" + elapsedMillis(started) + " ms");
                return response;
            } catch (HttpTimeoutException exception) {
                lastError = exception;
                AppLog.error(I18n.format("import.phaseTimeout", "phase", phase), exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException(I18n.format("import.phaseInterrupted", "phase", phase), exception);
            }
        }
        throw new IOException(I18n.format("import.phaseTimeout", "phase", phase), lastError);
    }

    private void requireSuccess(HttpResponse<String> response) throws IOException {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(apiError(response.statusCode(), response.body()));
        }
    }

    private void validateFile(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException(I18n.text("import.fileMissing"));
        }
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (!name.endsWith(".pdf") && !name.endsWith(".jpg") && !name.endsWith(".jpeg")
            && !name.endsWith(".png") && !name.endsWith(".webp")) {
            throw new IOException("Yalnızca PDF veya resim (JPG, PNG, WEBP) formatındaki dosyalar yüklenebilir.");
        }
        long size = Files.size(file);
        if (size <= 0) {
            throw new IOException(I18n.text("import.fileEmpty"));
        }
        if (size > MAX_FILE_BYTES) {
            throw new IOException(I18n.text("import.fileTooLarge"));
        }
    }

    private Map<String, Object> buildRequest(
            String fileUri,
            String mimeType,
            String prompt,
            Map<String, Object> schema) {

        String inputType = mimeType.startsWith("image/") ? "image" : "document";

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", MODEL);
        request.put("store", false);
        request.put("system_instruction", systemInstruction());
        request.put("generation_config", Map.of(
                "thinking_level", "low",
                "thinking_summaries", "none",
                "max_output_tokens", 12000));
        request.put("input", List.of(
                Map.of("type", "text", "text", prompt),
                Map.of(
                        "type", inputType,
                        "uri", fileUri,
                        "mime_type", mimeType)));
        request.put("response_format", List.of(Map.of(
                "type", "text",
                "mime_type", "application/json",
                "schema", schema)));
        return request;
    }

    private List<Datasheet.Spec> completeSpecs(List<Datasheet.Spec> specs) {
        if (specs == null) {
            return List.of();
        }
        return specs.stream()
                .filter(Objects::nonNull)
                .filter(spec -> !spec.label().isBlank())
                .filter(spec -> !spec.value().isBlank())
                .toList();
    }

    private long elapsedMillis(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    private String systemInstruction() {
        return ResourceText.read("/ai/datasheet-analysis.system.txt").trim();
    }

    private String extractionPrompt(String fileName) {
        return ResourceText.read("/ai/datasheet-extraction.user.txt")
                .replace("{fileName}", fileName);
    }

    private Map<String, Object> responseSchema() {
        Map<String, Object> specSchema = specSchema();

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("valid", Map.of(
                "type", "boolean",
                "description", "Belgenin gerçek bir ürün veya komponent teknik datasheeti olup olmadığı."));
        properties.put("reason", Map.of(
                "type", "string",
                "description", "valid false ise kısa gerekçe, aksi halde boş string."));
        properties.put("code", Map.of(
                "type", "string",
                "description", "Belgede açıkça görülen model, part number veya ürün kodu."));
        properties.put("title", Map.of(
                "type", "string",
                "description", "Ürünün belgede yazan açık adı."));
        properties.put("category", Map.of(
                "type", "string",
                "description", "Belgeden güvenle anlaşılabilen kısa ürün kategorisi."));
        properties.put("description", Map.of(
                "type", "string",
                "description", "Belgedeki bilgilerden oluşturulmuş kısa ve nesnel ürün açıklaması."));
        properties.put("technicalTableRowCount", Map.of(
                "type", "integer",
                "minimum", 0,
                "description", "Belgedeki tüm teknik veri tablolarında görsel olarak bulunan gerçek label/value satırlarının toplamı."));
        properties.put("specs", Map.of(
                "type", "array",
                "items", specSchema,
                "description", "Teknik veri tablolarındaki tüm gerçek satırlar. Her öğede aynı görsel satırdaki parametre adı ve karşısındaki değer bulunur."));
        properties.put("safety", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "Belgede açıkça bulunan güvenlik ve kullanım uyarıları."));
        properties.put("image", Map.of(
                "type", "string",
                "description", "Belge içinde ürünün varsa ana fotoğrafı/görseli (base64 veya resim verisi olarak çıkarılabiliyorsa, yoksa boş string)."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("properties", properties);
        schema.put("required", List.of(
                "valid",
                "reason",
                "code",
                "title",
                "category",
                "description",
                "technicalTableRowCount",
                "specs",
                "safety",
                "image"));
        return schema;
    }

    private Map<String, Object> specSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("label", Map.of(
                "type", "string",
                "description", "Teknik tablonun ilgili görsel satırındaki parametre veya özellik adı. Tablo başlığı değildir."));
        properties.put("value", Map.of(
                "type", "string",
                "description", "Aynı görsel satırda label karşısında bulunan teknik değer. Birim, tolerans, aralık ve koşullar dahil edilir; görünür bir değer varken boş bırakılamaz."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("properties", properties);
        schema.put("required", List.of("label", "value"));
        return schema;
    }

    private String extractOutputText(String responseBody) throws IOException {
        JsonNode root = Json.tree(responseBody);
        JsonNode steps = root.path("steps");
        if (!steps.isArray()) {
            throw new IOException(I18n.text("import.modelOutputMissing"));
        }

        for (int i = steps.size() - 1; i >= 0; i--) {
            JsonNode step = steps.get(i);
            if (!"model_output".equals(step.path("type").asText())) {
                continue;
            }
            JsonNode content = step.path("content");
            if (!content.isArray()) {
                continue;
            }
            for (JsonNode part : content) {
                if ("text".equals(part.path("type").asText())) {
                    String text = part.path("text").asText("").trim();
                    if (!text.isEmpty()) {
                        return text;
                    }
                }
            }
        }
        throw new IOException(I18n.text("import.structuredOutputMissing"));
    }

    private String apiError(int statusCode, String body) {
        String message = "";
        try {
            message = Json.tree(body).path("error").path("message").asText("").trim();
        } catch (IOException ignored) {
            message = "";
        }

        if (statusCode == 401 || statusCode == 403) {
            return I18n.text("import.authFailed");
        }
        if (statusCode == 429) {
            return I18n.text("import.rateLimited");
        }
        if (!message.isEmpty()) {
            return I18n.format("import.apiError", "message", message);
        }
        return I18n.format("import.requestFailed", "status", statusCode);
    }

    private record Extraction(
            boolean valid,
            String reason,
            String code,
            String title,
            String category,
            String description,
            int technicalTableRowCount,
            List<Datasheet.Spec> specs,
            List<String> safety,
            String image) {

        private Extraction {
            reason = clean(reason);
            code = clean(code);
            title = clean(title);
            category = clean(category);
            description = clean(description);
            technicalTableRowCount = Math.max(0, technicalTableRowCount);
            specs = specs == null
                    ? List.of()
                    : specs.stream().filter(Objects::nonNull).toList();
            safety = safety == null
                    ? List.of()
                    : safety.stream().filter(Objects::nonNull).toList();
            image = clean(image);
        }

        private Datasheet toDatasheet(List<Datasheet.Spec> technicalSpecs) {
            return new Datasheet(
                    "",
                    code,
                    title,
                    category,
                    description,
                    technicalSpecs,
                    safety,
                    "",
                    image);
        }

        private static String clean(String value) {
            return value == null ? "" : value.trim();
        }
    }

    private record UploadedFile(String name, String uri, String mimeType, String state) {

        private UploadedFile {
            name = name == null ? "" : name.trim();
            uri = uri == null ? "" : uri.trim();
            mimeType = mimeType == null || mimeType.isBlank() ? "application/pdf" : mimeType.trim();
            state = state == null || state.isBlank() ? "ACTIVE" : state.trim();
        }
    }
}
