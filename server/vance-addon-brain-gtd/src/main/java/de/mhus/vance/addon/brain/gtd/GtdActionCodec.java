package de.mhus.vance.addon.brain.gtd;

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
 * Parser and serialiser for {@code kind: action} document bodies. Markdown
 * (primary), JSON, YAML. Flat front-matter; {@code contexts} is
 * comma-separated on disk and round-trips as a list (like Kanban labels).
 * Modelled on {@code CardCodec} / {@code JournalEntryCodec}.
 */
public final class GtdActionCodec {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> JSON_MAP =
            new TypeReference<>() {};
    private static final String MD_FENCE = "---";

    private GtdActionCodec() { }

    public static GtdActionDocument parse(String body, @Nullable String mimeType) {
        if (isMarkdown(mimeType)) return parseMarkdown(body);
        if (isJson(mimeType)) return parseJson(body);
        if (isYaml(mimeType)) return parseYaml(body);
        throw new KindCodecException("Unsupported mime type for action: " + mimeType);
    }

    public static String serialize(GtdActionDocument doc, @Nullable String mimeType) {
        if (isMarkdown(mimeType)) return serializeMarkdown(doc);
        if (isJson(mimeType)) return serializeJson(doc);
        if (isYaml(mimeType)) return serializeYaml(doc);
        throw new KindCodecException("Unsupported mime type for action: " + mimeType);
    }

    public static boolean supports(@Nullable String mimeType) {
        return isMarkdown(mimeType) || isJson(mimeType) || isYaml(mimeType);
    }

    private static boolean isMarkdown(@Nullable String mime) {
        return "text/markdown".equals(mime) || "text/x-markdown".equals(mime);
    }
    private static boolean isJson(@Nullable String mime) {
        return "application/json".equals(mime);
    }
    private static boolean isYaml(@Nullable String mime) {
        return "application/yaml".equals(mime) || "application/x-yaml".equals(mime)
                || "text/yaml".equals(mime) || "text/x-yaml".equals(mime);
    }

    // ── Markdown ───────────────────────────────────────────────────

    private static GtdActionDocument parseMarkdown(String body) {
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
                values.put(trimmed.substring(0, colon).trim(), trimmed.substring(colon + 1).trim());
            }
            if (cursor < lines.length && MD_FENCE.equals(lines[cursor].trim())) cursor++;
        }
        String kind = values.remove("kind");
        if (kind == null) kind = "";
        while (cursor < lines.length && lines[cursor].trim().isEmpty()) cursor++;
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

    private static String serializeMarkdown(GtdActionDocument doc) {
        StringBuilder out = new StringBuilder();
        out.append(MD_FENCE).append('\n');
        out.append("kind: ").append(canonicalKind(doc)).append('\n');
        if (!doc.title().isEmpty()) out.append("title: ").append(doc.title()).append('\n');
        if (!doc.when().isEmpty()) out.append("when: ").append(doc.when()).append('\n');
        if (doc.deadline() != null && !doc.deadline().isBlank()) {
            out.append("deadline: ").append(doc.deadline()).append('\n');
        }
        if (!doc.contexts().isEmpty()) {
            out.append("contexts: ").append(String.join(", ", doc.contexts())).append('\n');
        }
        if (doc.done()) out.append("done: true").append('\n');
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

    private static GtdActionDocument parseJson(String body) {
        if (body.isBlank()) return GtdActionDocument.empty();
        Map<String, Object> parsed;
        try {
            parsed = JSON.readValue(body, JSON_MAP);
        } catch (JacksonException e) {
            throw new KindCodecException("Invalid JSON: " + e.getOriginalMessage(), e);
        }
        if (parsed == null) throw new KindCodecException("Top-level JSON must be an object");
        return promoteFromMap(KindHeaderCodec.unwrapJsonMeta(parsed));
    }

    private static GtdActionDocument parseYaml(String body) {
        if (body.isBlank()) return GtdActionDocument.empty();
        return promoteFromMap(KindHeaderCodec.parseYamlBody(body));
    }

    private static String serializeJson(GtdActionDocument doc) {
        Map<String, Object> wrapped =
                KindHeaderCodec.wrapJsonMeta(canonicalKind(doc), buildStructuredBody(doc));
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(wrapped) + "\n";
        } catch (JacksonException e) {
            throw new KindCodecException("Failed to write JSON: " + e.getOriginalMessage(), e);
        }
    }

    private static String serializeYaml(GtdActionDocument doc) {
        return KindHeaderCodec.dumpYamlBody(canonicalKind(doc), buildStructuredBody(doc));
    }

    // ── Promotion ──────────────────────────────────────────────────

    private static GtdActionDocument promoteFromMap(Map<String, Object> obj) {
        String kind = obj.get("kind") instanceof String s ? s : "";
        String title = coerceString(obj.get("title"));
        String when = coerceString(obj.get("when"));
        String deadline = coerceString(obj.get("deadline"));
        List<String> contexts = coerceStringList(obj.get("contexts"));
        boolean done = obj.get("done") instanceof Boolean b && b;
        String bodyText = coerceString(obj.get("body"));
        Map<String, Object> extra = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : obj.entrySet()) {
            if (isKnownField(e.getKey())) continue;
            extra.put(e.getKey(), e.getValue());
        }
        return new GtdActionDocument(
                kind.isEmpty() ? GtdActionDocument.KIND : kind,
                title == null ? "" : title,
                when == null ? "" : when,
                deadline, contexts, done,
                bodyText == null ? "" : bodyText, extra);
    }

    private static GtdActionDocument promoteFromValues(String kind,
                                                       Map<String, String> values, String mdBody) {
        String title = values.getOrDefault("title", "");
        String when = values.getOrDefault("when", "");
        String deadline = nullIfBlank(values.get("deadline"));
        List<String> contexts = splitList(values.get("contexts"));
        boolean done = "true".equalsIgnoreCase(values.get("done"));
        Map<String, Object> extra = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (isKnownField(e.getKey())) continue;
            extra.put(e.getKey(), e.getValue());
        }
        return new GtdActionDocument(
                kind.isEmpty() ? GtdActionDocument.KIND : kind,
                title, when, deadline, contexts, done, mdBody, extra);
    }

    private static boolean isKnownField(String key) {
        return switch (key) {
            case "kind", "title", "when", "deadline", "contexts", "done", "body" -> true;
            default -> false;
        };
    }

    private static Map<String, Object> buildStructuredBody(GtdActionDocument doc) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (!doc.title().isEmpty()) body.put("title", doc.title());
        if (!doc.when().isEmpty()) body.put("when", doc.when());
        if (doc.deadline() != null && !doc.deadline().isBlank()) body.put("deadline", doc.deadline());
        if (!doc.contexts().isEmpty()) body.put("contexts", new ArrayList<>(doc.contexts()));
        if (doc.done()) body.put("done", true);
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
        if (v instanceof String s) return splitList(s);
        return new ArrayList<>();
    }
    private static List<String> splitList(@Nullable String csv) {
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
    private static String canonicalKind(GtdActionDocument doc) {
        return (doc.kind() == null || doc.kind().isBlank()) ? GtdActionDocument.KIND : doc.kind();
    }
}
