package de.mhus.vance.addon.brain.journal;

import de.mhus.vance.shared.document.kind.KindCodecException;
import de.mhus.vance.shared.document.kind.KindHeaderCodec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Parser and serialiser for {@code kind: journal-entry} document bodies.
 * Three mime types: {@code text/markdown} (primary — a diary page is
 * prose), {@code application/json}, {@code application/yaml}.
 *
 * <p>The Markdown form carries structural metadata in the YAML-style
 * front-matter ({@code ---} fenced block, flat scalar keys only —
 * {@code tags} is comma-separated on disk and round-trips as a list).
 * The rest of the body is verbatim. Modelled on {@code CardCodec}.
 *
 * <p>Stateless utility — all methods static, no Spring bean.
 */
public final class JournalEntryCodec {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> JSON_MAP =
            new TypeReference<>() {};
    private static final String MD_FENCE = "---";

    private JournalEntryCodec() {
        // utility class
    }

    public static JournalEntryDocument parse(String body, @Nullable String mimeType) {
        if (isMarkdown(mimeType)) return parseMarkdown(body);
        if (isJson(mimeType)) return parseJson(body);
        if (isYaml(mimeType)) return parseYaml(body);
        throw new KindCodecException("Unsupported mime type for journal-entry: " + mimeType);
    }

    public static String serialize(JournalEntryDocument doc, @Nullable String mimeType) {
        if (isMarkdown(mimeType)) return serializeMarkdown(doc);
        if (isJson(mimeType)) return serializeJson(doc);
        if (isYaml(mimeType)) return serializeYaml(doc);
        throw new KindCodecException("Unsupported mime type for journal-entry: " + mimeType);
    }

    public static boolean supports(@Nullable String mimeType) {
        return isMarkdown(mimeType) || isJson(mimeType) || isYaml(mimeType);
    }

    // ── Mime ───────────────────────────────────────────────────────

    private static boolean isMarkdown(@Nullable String mime) {
        return "text/markdown".equals(mime) || "text/x-markdown".equals(mime);
    }

    private static boolean isJson(@Nullable String mime) {
        return "application/json".equals(mime);
    }

    private static boolean isYaml(@Nullable String mime) {
        return "application/yaml".equals(mime)
                || "application/x-yaml".equals(mime)
                || "text/yaml".equals(mime)
                || "text/x-yaml".equals(mime);
    }

    // ── Markdown ───────────────────────────────────────────────────

    private static JournalEntryDocument parseMarkdown(String body) {
        String[] lines = body.split("\\R", -1);
        int cursor = 0;
        Map<String, String> values = new LinkedHashMap<>();

        if (lines.length > 0 && MD_FENCE.equals(lines[0].trim())) {
            cursor = 1;
            while (cursor < lines.length && !MD_FENCE.equals(lines[cursor].trim())) {
                String trimmed = lines[cursor].trim();
                cursor++;
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int colon = trimmed.indexOf(':');
                if (colon <= 0) continue;
                values.put(trimmed.substring(0, colon).trim(),
                        trimmed.substring(colon + 1).trim());
            }
            if (cursor < lines.length && MD_FENCE.equals(lines[cursor].trim())) {
                cursor++;
            }
        }

        String kind = values.remove("kind");
        if (kind == null) kind = "";

        while (cursor < lines.length && lines[cursor].trim().isEmpty()) {
            cursor++;
        }
        StringBuilder bodyBuf = new StringBuilder();
        for (int i = cursor; i < lines.length; i++) {
            bodyBuf.append(lines[i]);
            if (i < lines.length - 1) bodyBuf.append('\n');
        }
        while (bodyBuf.length() > 0 && bodyBuf.charAt(bodyBuf.length() - 1) == '\n') {
            bodyBuf.setLength(bodyBuf.length() - 1);
        }

        return promoteFromValues(kind, values, bodyBuf.toString());
    }

    private static String serializeMarkdown(JournalEntryDocument doc) {
        StringBuilder out = new StringBuilder();
        out.append(MD_FENCE).append('\n');
        out.append("kind: ").append(canonicalKind(doc)).append('\n');
        if (!doc.date().isEmpty()) {
            out.append("date: ").append(doc.date()).append('\n');
        }
        if (!doc.title().isEmpty()) {
            out.append("title: ").append(doc.title()).append('\n');
        }
        if (doc.mood() != null && !doc.mood().isBlank()) {
            out.append("mood: ").append(doc.mood()).append('\n');
        }
        if (!doc.tags().isEmpty()) {
            out.append("tags: ").append(String.join(", ", doc.tags())).append('\n');
        }
        for (Map.Entry<String, Object> e : doc.extra().entrySet()) {
            if (isKnownField(e.getKey())) continue;
            out.append(e.getKey()).append(": ").append(stringifyScalar(e.getValue())).append('\n');
        }
        out.append(MD_FENCE).append('\n');
        if (!doc.body().isEmpty()) {
            out.append('\n').append(doc.body());
            if (!doc.body().endsWith("\n")) out.append('\n');
        }
        return out.toString();
    }

    // ── JSON / YAML ────────────────────────────────────────────────

    private static JournalEntryDocument parseJson(String body) {
        if (body.isBlank()) return JournalEntryDocument.empty();
        Map<String, Object> parsed;
        try {
            parsed = JSON.readValue(body, JSON_MAP);
        } catch (JacksonException e) {
            throw new KindCodecException("Invalid JSON: " + e.getOriginalMessage(), e);
        }
        if (parsed == null) throw new KindCodecException("Top-level JSON must be an object");
        return promoteFromMap(KindHeaderCodec.unwrapJsonMeta(parsed));
    }

    private static JournalEntryDocument parseYaml(String body) {
        if (body.isBlank()) return JournalEntryDocument.empty();
        return promoteFromMap(KindHeaderCodec.parseYamlBody(body));
    }

    private static String serializeJson(JournalEntryDocument doc) {
        Map<String, Object> wrapped =
                KindHeaderCodec.wrapJsonMeta(canonicalKind(doc), buildStructuredBody(doc));
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(wrapped) + "\n";
        } catch (JacksonException e) {
            throw new KindCodecException("Failed to write JSON: " + e.getOriginalMessage(), e);
        }
    }

    private static String serializeYaml(JournalEntryDocument doc) {
        return KindHeaderCodec.dumpYamlBody(canonicalKind(doc), buildStructuredBody(doc));
    }

    // ── Promotion ──────────────────────────────────────────────────

    private static JournalEntryDocument promoteFromMap(Map<String, Object> obj) {
        String kind = obj.get("kind") instanceof String s ? s : "";
        String date = coerceString(obj.get("date"));
        String title = coerceString(obj.get("title"));
        String mood = coerceString(obj.get("mood"));
        List<String> tags = coerceStringList(obj.get("tags"));
        String bodyText = coerceString(obj.get("body"));

        Map<String, Object> extra = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : obj.entrySet()) {
            if (isKnownField(e.getKey())) continue;
            extra.put(e.getKey(), e.getValue());
        }
        return new JournalEntryDocument(
                kind.isEmpty() ? JournalEntryDocument.KIND : kind,
                date == null ? "" : date,
                title == null ? "" : title,
                mood, tags,
                bodyText == null ? "" : bodyText,
                extra);
    }

    private static JournalEntryDocument promoteFromValues(String kind,
                                                          Map<String, String> values,
                                                          String mdBody) {
        String date = nullIfBlank(values.get("date"));
        String title = values.getOrDefault("title", "");
        String mood = nullIfBlank(values.get("mood"));
        List<String> tags = splitTags(values.get("tags"));

        Map<String, Object> extra = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (isKnownField(e.getKey())) continue;
            extra.put(e.getKey(), e.getValue());
        }
        return new JournalEntryDocument(
                kind.isEmpty() ? JournalEntryDocument.KIND : kind,
                date == null ? "" : date,
                title, mood, tags, mdBody, extra);
    }

    private static boolean isKnownField(String key) {
        return switch (key) {
            case "kind", "date", "title", "mood", "tags", "body" -> true;
            default -> false;
        };
    }

    private static Map<String, Object> buildStructuredBody(JournalEntryDocument doc) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (!doc.date().isEmpty()) body.put("date", doc.date());
        if (!doc.title().isEmpty()) body.put("title", doc.title());
        if (doc.mood() != null && !doc.mood().isBlank()) body.put("mood", doc.mood());
        if (!doc.tags().isEmpty()) body.put("tags", new ArrayList<>(doc.tags()));
        if (!doc.body().isEmpty()) body.put("body", doc.body());
        for (Map.Entry<String, Object> e : doc.extra().entrySet()) {
            if (!body.containsKey(e.getKey())) body.put(e.getKey(), e.getValue());
        }
        return body;
    }

    // ── Helpers ────────────────────────────────────────────────────

    private static @Nullable String coerceString(@Nullable Object v) {
        if (v == null) return null;
        if (v instanceof String s) return s.isBlank() ? null : s;
        return v.toString();
    }

    private static List<String> coerceStringList(@Nullable Object v) {
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof String s && !s.isBlank()) out.add(s.trim());
                else if (o != null) out.add(o.toString());
            }
            return out;
        }
        if (v instanceof String s) return splitTags(s);
        return new ArrayList<>();
    }

    private static List<String> splitTags(@Nullable String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) return out;
        for (String part : csv.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static @Nullable String nullIfBlank(@Nullable String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String stringifyScalar(@Nullable Object value) {
        if (value == null) return "";
        if (value instanceof String s) return s;
        return String.valueOf(value);
    }

    private static String canonicalKind(JournalEntryDocument doc) {
        return (doc.kind() == null || doc.kind().isBlank())
                ? JournalEntryDocument.KIND : doc.kind();
    }
}
