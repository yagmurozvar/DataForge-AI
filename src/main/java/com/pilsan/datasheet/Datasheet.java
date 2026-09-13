package com.pilsan.datasheet;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Datasheet(
        String id,
        String code,
        String title,
        String category,
        String description,
        List<Spec> specs,
        List<String> safety,
        String notes,
        String image,
        String sourcePath) {

    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");

    // 9 parametre ile çağrıldığında hata vermemesi için uyum constructor'ı
    public Datasheet(
            String id,
            String code,
            String title,
            String category,
            String description,
            List<Spec> specs,
            List<String> safety,
            String notes,
            String image) {
        this(id, code, title, category, description, specs, safety, notes, image, "");
    }

    public Datasheet(
            String id,
            String code,
            String title,
            String category,
            String description,
            List<Spec> specs,
            List<String> safety,
            String notes,
            String image,
            String sourcePath) {
        this.id = normalizeId(id);
        this.code = trim(code);
        this.title = trim(title);
        this.category = trim(category);
        this.description = trim(description);
        this.notes = notes == null ? "" : notes;
        this.image = image == null ? "" : image.trim();
        this.sourcePath = sourcePath == null ? "" : sourcePath.trim();
        this.specs = sanitizeSpecs(specs);
        this.safety = sanitizeLines(safety);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Spec(String label, String value) {

        public Spec {
            label = trim(label);
            value = trim(value);
        }

        public boolean isEmpty() {
            return label.isEmpty() && value.isEmpty();
        }
    }

    public Datasheet withId(String value) {
        return new Datasheet(value, code, title, category, description, specs, safety, notes, image, sourcePath);
    }

    public Datasheet withNotes(String value) {
        return new Datasheet(id, code, title, category, description, specs, safety, value, image, sourcePath);
    }

    public Datasheet withImage(String value) {
        return new Datasheet(id, code, title, category, description, specs, safety, notes, value, sourcePath);
    }

    public Datasheet withSourcePath(String value) {
        return new Datasheet(id, code, title, category, description, specs, safety, notes, image, value);
    }

    public boolean isValid() {
        return !code.isEmpty() || !title.isEmpty();
    }

    public String displayName() {
        if (code.isEmpty()) {
            return title;
        }
        if (title.isEmpty()) {
            return code;
        }
        return code + " - " + title;
    }

    public int searchScore(String query) {
        String normalized = normalize(query);
        if (normalized.isEmpty()) {
            return -1;
        }

        String[] tokens = normalized.split("\\s+");
        int score = 0;

        int matchedTokens = 0;
        for (String token : tokens) {
            if (token.length() < 2) {
                continue;
            }
            int tokenScore = scoreToken(token);
            if (tokenScore >= 0) {
                score += tokenScore;
                matchedTokens++;
            }
        }

        return matchedTokens == 0 ? -1 : score;
    }

    public String specValueContaining(List<String> fragments) {
        for (Spec spec : specs) {
            String label = normalize(spec.label());
            for (String fragment : fragments) {
                String needle = normalize(fragment);
                if (!needle.isEmpty() && label.contains(needle)) {
                    return spec.value();
                }
            }
        }
        return "";
    }

    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        String lowered = value
                .replace('İ', 'i')
                .replace('I', 'i')
                .replace('ı', 'i')
                .replace('Ş', 's')
                .replace('ş', 's')
                .replace('Ğ', 'g')
                .replace('ğ', 'g')
                .replace('Ü', 'u')
                .replace('ü', 'u')
                .replace('Ö', 'o')
                .replace('ö', 'o')
                .replace('Ç', 'c')
                .replace('ç', 'c')
                .toLowerCase(Locale.ROOT);

        return DIACRITICS.matcher(Normalizer.normalize(lowered, Normalizer.Form.NFKD))
                .replaceAll("")
                .trim();
    }

    private int scoreToken(String token) {
        int best = -1;
        best = Math.max(best, scoreField(normalize(code), token, 120, 80));
        best = Math.max(best, scoreField(normalize(title), token, 90, 55));
        best = Math.max(best, scoreField(normalize(category), token, 45, 30));
        best = Math.max(best, scoreField(normalize(description), token, 20, 10));

        for (Spec spec : specs) {
            best = Math.max(best, scoreField(normalize(spec.label()), token, 45, 30));
            best = Math.max(best, scoreField(normalize(spec.value()), token, 35, 22));
        }

        for (String line : safety) {
            best = Math.max(best, scoreField(normalize(line), token, 14, 8));
        }

        return best;
    }

    private static int scoreField(String field, String token, int exactScore, int containsScore) {
        if (field.isEmpty()) {
            return -1;
        }
        if (field.equals(token)) {
            return exactScore;
        }
        if (field.startsWith(token)) {
            return containsScore + 8;
        }
        if (field.contains(token)) {
            return containsScore;
        }
        return -1;
    }

    private static String normalizeId(String value) {
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value.trim();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static List<Spec> sanitizeSpecs(List<Spec> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<Spec> result = new ArrayList<>();
        for (Spec spec : source) {
            if (spec != null && !spec.isEmpty()) {
                result.add(spec);
            }
        }
        return List.copyOf(result);
    }

    private static List<String> sanitizeLines(List<String> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return source.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
    }
}