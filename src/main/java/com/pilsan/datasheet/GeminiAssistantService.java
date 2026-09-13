package com.pilsan.datasheet;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

public final class GeminiAssistantService {

    private static final String ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions";
    private static final String MODEL = "gemini-3.5-flash-lite";
    private static final String SYSTEM_PROMPT_RESOURCE = "/ai/catalog-assistant.system.txt";
    private static final String USER_PROMPT_RESOURCE = "/ai/catalog-assistant.user.txt";
    private static final int MAX_HISTORY_TURNS = 12;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(45);

    private final DatasheetRepository repository;
    private final HttpClient httpClient;
    private final String systemPrompt;
    private final String userPromptTemplate;
    private final ArrayDeque<Turn> history = new ArrayDeque<>();

    public GeminiAssistantService(DatasheetRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
        httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        systemPrompt = ResourceText.read(SYSTEM_PROMPT_RESOURCE).trim();
        userPromptTemplate = ResourceText.read(USER_PROMPT_RESOURCE);
    }

    public CompletableFuture<String> ask(String rawMessage) {
        String userMessage = rawMessage == null ? "" : rawMessage.trim();
        if (userMessage.isEmpty()) {
            return CompletableFuture.completedFuture(I18n.text("assistant.noData"));
        }
        if (repository.all().isEmpty()) {
            return CompletableFuture.completedFuture(I18n.text("assistant.emptyCatalog"));
        }

        String cleanMessage = userMessage;
        String attachmentContext = "";

        try {
            if (userMessage.startsWith("{")) {
                JsonNode rootNode = Json.tree(userMessage);
                if (rootNode.has("text")) {
                    cleanMessage = rootNode.path("text").asText("");
                }
                if (rootNode.has("attachment") && !rootNode.path("attachment").isNull()) {
                    JsonNode att = rootNode.path("attachment");
                    String name = att.path("name").asText("Dosya");
                    String type = att.path("type").asText("file");
                    String data = att.path("data").asText("");
                    if ("link".equals(type)) {
                        String pageContent = fetchWebPageContent(data);
                        attachmentContext = "\n\n[Kullanıcı şu web linkini ekledi: " + data + "]\nSayfa İçeriği:\n" + pageContent + "\nLütfen bu içeriği inceleyerek kullanıcının sorusunu yanıtla.";
                    } else {
                        attachmentContext = "\n\n[Kullanıcı bir " + (type.equals("image") ? "fotoğraf/görsel" : "dosya") + " yükledi: " + name + "]\nİçerik Verisi: " + data;
                    }
                }
            } else if (userMessage.startsWith("http://") || userMessage.startsWith("https://")) {
                int firstSpace = userMessage.indexOf(' ');
                String urlPart = firstSpace == -1 ? userMessage : userMessage.substring(0, firstSpace);
                String restPart = firstSpace == -1 ? "" : userMessage.substring(firstSpace + 1);
                cleanMessage = restPart.isEmpty() ? "Bu web linkini inceleyip detay verir misin?" : restPart.trim();
                String pageContent = fetchWebPageContent(urlPart);
                attachmentContext = "\n\n[Kullanıcı şu web linkini paylaştı: " + urlPart + "]\nSayfa İçeriği:\n" + pageContent + "\nLütfen bu içeriği inceleyerek soruyu yanıtla.";
            } else if (userMessage.contains("http://") || userMessage.contains("https://")) {
                 int httpIdx = userMessage.indexOf("http");
                 int spaceIdx = userMessage.indexOf(' ', httpIdx);
                 String urlPart = spaceIdx == -1 ? userMessage.substring(httpIdx) : userMessage.substring(httpIdx, spaceIdx);
                 urlPart = urlPart.replaceAll("[\\]\\[)}>]", "");
                 String pageContent = fetchWebPageContent(urlPart);
                 attachmentContext = "\n\n[Kullanıcı mesajında şu web linkini paylaştı: " + urlPart + "]\nSayfa İçeriği:\n" + pageContent + "\nLütfen bu içeriği de dikkate alarak soruyu yanıtla.";
            }
        } catch (Exception ignored) {
        }

        final String finalQuery = (cleanMessage + attachmentContext).trim();

        List<Turn> historySnapshot;
        synchronized (history) {
            historySnapshot = List.copyOf(history);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                AssistantResponse response = request(finalQuery, historySnapshot);
                String answer = resolveAnswer(response);
                remember(finalQuery, answer);
                return answer;
            } catch (IOException exception) {
                AppLog.error("Asistan istek hatası: " + exception.getMessage(), exception);
                throw new CompletionException(exception);
            }
        });
    }

    private String fetchWebPageContent(String urlString) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(urlString))
                    .timeout(Duration.ofSeconds(12))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7")
                    .header("Upgrade-Insecure-Requests", "1")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 400) {
                String html = response.body();
                String text = html.replaceAll("(?is)<script.*?>.*?</script>", " ")
                            .replaceAll("(?is)<style.*?>.*?</style>", " ")
                            .replaceAll("(?is)<[^>]*>", " ")
                            .replaceAll("\\s+", " ")
                            .trim();
                if (text.length() > 14000) {
                    text = text.substring(0, 14000) + "... [İçerik kısaltıldı]";
                }
                return text;
            } else {
                return "[Erişim Reddedildi. HTTP Status: " + response.statusCode() + " - Web sitesi otomatik taramayı engelliyor olabilir.]";
            }
        } catch (Exception e) {
            AppLog.error("Web sayfası çekilemedi: " + urlString, e);
        }
        return "[Web sayfasına ulaşılamadı veya içerik okunamadı]";
    }

    private AssistantResponse request(String message, List<Turn> historySnapshot) throws IOException {
        String catalog = Json.writeCompact(catalogSnapshot());
        String historyText = historySnapshot.stream()
                .map(turn -> turn.role() + ": " + turn.text())
                .collect(Collectors.joining("\n"));
        String input = userPromptTemplate.formatted(catalog, historyText, message);

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", MODEL);
        requestBody.put("store", false);
        requestBody.put("system_instruction", systemPrompt);

        requestBody.put("generation_config", Map.of(
                "thinking_level", "minimal",
                "max_output_tokens", 8192));
        requestBody.put("input", input);
        requestBody.put("response_format", List.of(Map.of(
                "type", "text",
                "mime_type", "application/json",
                "schema", responseSchema())));

        String body = Json.writeCompact(requestBody);
        long started = System.nanoTime();
        AppLog.info("Asistan API isteği gönderildi. Model=" + MODEL + ", katalog=" + repository.all().size());

        HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", AppSecrets.GEMINI_API_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();
            AppLog.info("Asistan API yanıtı alındı. HTTP=" + response.statusCode() + ", süre=" + elapsed + " ms");
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException(apiError(response.statusCode(), response.body()));
            }
            String output = extractOutputText(response.body());
            return Json.read(output, AssistantResponse.class);
        } catch (HttpTimeoutException exception) {
            throw new IOException(I18n.text("assistant.unavailable"), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException(I18n.text("assistant.unavailable"), exception);
        }
    }

    private String resolveAnswer(AssistantResponse response) {
        if (response == null || !response.inScope()) {
            return I18n.text("assistant.refusal");
        }
        if (containsUnknownCode(response.referencedCodes())) {
            return I18n.text("assistant.noData");
        }
        String answer = response.answer() == null ? "" : response.answer().trim();
        return answer.isEmpty() ? I18n.text("assistant.invalidResponse") : answer;
    }

    private boolean containsUnknownCode(List<String> referencedCodes) {
        if (referencedCodes == null || referencedCodes.isEmpty()) {
            return false;
        }
        Set<String> knownCodes = repository.all().stream()
                .map(Datasheet::code)
                .filter(code -> code != null && !code.isBlank())
                .map(code -> code.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        return referencedCodes.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(code -> !code.isEmpty())
                .map(code -> code.toLowerCase(Locale.ROOT))
                .anyMatch(code -> !knownCodes.contains(code));
    }

    private void remember(String userMessage, String assistantMessage) {
        synchronized (history) {
            history.addLast(new Turn("user", userMessage));
            history.addLast(new Turn("assistant", assistantMessage));
            while (history.size() > MAX_HISTORY_TURNS) {
                history.removeFirst();
            }
        }
    }

    private List<CatalogItem> catalogSnapshot() {
        return repository.all().stream()
                .map(datasheet -> new CatalogItem(
                        datasheet.id(),
                        datasheet.code(),
                        datasheet.title(),
                        datasheet.category(),
                        datasheet.description(),
                        datasheet.specs(),
                        datasheet.safety(),
                        datasheet.notes()))
                .toList();
    }

    private Map<String, Object> responseSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("inScope", Map.of(
                "type", "boolean",
                "description", "Soru sektörel bilgi araştırması, web linki analizi veya katalog ürünlerini kapsıyorsa true."));
        properties.put("answer", Map.of(
                "type", "string",
                "description", "Kullanıcıya gösterilecek profesyonel HTML formatlı Türkçe cevap. Ürün önerirken <button class=\"ai-btn\" data-id=\"URUN_ID_BURAYA\">Aç</button> formatında buton ekle."));
        properties.put("referencedCodes", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "Cevapta kullanılan ve katalogda gerçekten bulunan ürün kodları."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("properties", properties);
        schema.put("required", List.of("inScope", "answer", "referencedCodes"));
        return schema;
    }

    private String extractOutputText(String responseBody) throws IOException {
        JsonNode root = Json.tree(responseBody);
        JsonNode steps = root.path("steps");
        if (!steps.isArray()) {
            throw new IOException(I18n.text("assistant.invalidResponse"));
        }
        for (int index = steps.size() - 1; index >= 0; index--) {
            JsonNode step = steps.get(index);
            if (!"model_output".equals(step.path("type").asText())) {
                continue;
            }
            JsonNode content = step.path("content");
            if (!content.isArray()) {
                continue;
            }
            for (JsonNode part : content) {
                if (!"text".equals(part.path("type").asText())) {
                    continue;
                }
                String text = part.path("text").asText("").trim();
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        throw new IOException(I18n.text("assistant.invalidResponse"));
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

    private record Turn(String role, String text) {
    }

    private record CatalogItem(
            String id,
            String code,
            String title,
            String category,
            String description,
            List<Datasheet.Spec> specs,
            List<String> safety,
            String notes) {
    }

    private record AssistantResponse(
            boolean inScope,
            String answer,
            List<String> referencedCodes) {

        private AssistantResponse {
            referencedCodes = referencedCodes == null
                    ? List.of()
                    : referencedCodes.stream()
                            .filter(Objects::nonNull)
                            .map(String::trim)
                            .filter(code -> !code.isEmpty())
                            .toList();
        }
    }
}