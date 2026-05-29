package de.mhus.vance.shared.document.kind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Parser and serialiser for {@code kind: card} document bodies. Three
 * mime types: {@code text/markdown} (primary — Kanban cards are
 * description-heavy), {@code application/json}, {@code application/yaml}.
 *
 * <p>The Markdown form carries structural metadata in the YAML-style
 * front-matter ({@code ---} fenced block). Only flat scalar keys are
 * supported in the fence — list-valued fields like {@code labels} are
 * comma-separated on disk and round-trip as a list in memory. The rest
 * of the Markdown body is verbatim.
 *
 * <p>The JSON/YAML forms keep {@code labels} as a real list and lift
 * the Markdown body into a {@code body} field. Round-trip across mime
 * types is lossy on purpose: comma-separated label strings collapse
 * commas (treat them as field separators).
 *
 * <p>Stateless utility — all methods are static, no instance state,
 * no Spring bean.
 */
public final class CardCodec {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> JSON_MAP =
            new TypeReference<>() {};

    private CardCodec() {
        // utility class
    }

    public static CardDocument parse(String body, @Nullable String mimeType) {
        if (isMarkdown(mimeType)) return parseMarkdown(body);
        if (isJson(mimeType)) return parseJson(body);
        if (isYaml(mimeType)) return parseYaml(body);
        throw new KindCodecException("Unsupported mime type for card: " + mimeType);
    }

    public static String serialize(CardDocument doc, @Nullable String mimeType) {
        if (isMarkdown(mimeType)) return serializeMarkdown(doc);
        if (isJson(mimeType)) return serializeJson(doc);
        if (isYaml(mimeType)) return serializeYaml(doc);
        throw new KindCodecException("Unsupported mime type for card: " + mimeType);
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

    private static final String MD_FENCE = "---";

    /** Counts GFM checkbox lines in a Markdown body. Group 1 = char. */
    private static final Pattern CHECKBOX_LINE =
            Pattern.compile("^\\s*[-*] \\[([ xX])] ", Pattern.MULTILINE);

    private static CardDocument parseMarkdown(String body) {
        String[] lines = body.split("\\R", -1);
        int cursor = 0;
        Map<String, String> values = new LinkedHashMap<>();
        String kind = "";

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

        kind = values.remove("kind");
        if (kind == null) kind = "";

        // Skip a blank line directly after the closing fence so the
        // body starts at the first meaningful line.
        while (cursor < lines.length && lines[cursor].trim().isEmpty()) {
            cursor++;
        }

        StringBuilder bodyBuf = new StringBuilder();
        for (int i = cursor; i < lines.length; i++) {
            bodyBuf.append(lines[i]);
            if (i < lines.length - 1) bodyBuf.append('\n');
        }
        // Drop a single trailing newline coming from the split — round-
        // trip cleaner that way.
        while (bodyBuf.length() > 0
                && bodyBuf.charAt(bodyBuf.length() - 1) == '\n') {
            bodyBuf.setLength(bodyBuf.length() - 1);
        }

        return promoteFromValues(kind, values, bodyBuf.toString());
    }

    private static String serializeMarkdown(CardDocument doc) {
        StringBuilder out = new StringBuilder();
        out.append(MD_FENCE).append('\n');
        out.append("kind: ").append(canonicalKind(doc)).append('\n');
        if (!doc.title().isEmpty()) {
            out.append("title: ").append(doc.title()).append('\n');
        }
        if (doc.priority() != null) {
            out.append("priority: ").append(doc.priority()).append('\n');
        }
        if (doc.assignee() != null) {
            out.append("assignee: ").append(doc.assignee()).append('\n');
        }
        if (!doc.labels().isEmpty()) {
            out.append("labels: ").append(String.join(", ", doc.labels())).append('\n');
        }
        if (doc.dueDate() != null) {
            out.append("dueDate: ").append(doc.dueDate()).append('\n');
        }
        if (doc.estimate() != null) {
            double e = doc.estimate();
            if (e == Math.floor(e) && !Double.isInfinite(e)) {
                out.append("estimate: ").append((long) e).append('\n');
            } else {
                out.append("estimate: ").append(e).append('\n');
            }
        }
        if (doc.blocked()) {
            out.append("blocked: true").append('\n');
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

    private static CardDocument parseJson(String body) {
        if (body.isBlank()) return CardDocument.empty();
        Map<String, Object> parsed;
        try {
            parsed = JSON.readValue(body, JSON_MAP);
        } catch (JacksonException e) {
            throw new KindCodecException("Invalid JSON: " + e.getOriginalMessage(), e);
        }
        if (parsed == null) throw new KindCodecException("Top-level JSON must be an object");
        return promoteFromMap(KindHeaderCodec.unwrapJsonMeta(parsed));
    }

    private static CardDocument parseYaml(String body) {
        if (body.isBlank()) return CardDocument.empty();
        return promoteFromMap(KindHeaderCodec.parseYamlBody(body));
    }

    private static String serializeJson(CardDocument doc) {
        Map<String, Object> wrapped = KindHeaderCodec.wrapJsonMeta(canonicalKind(doc), buildStructuredBody(doc));
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(wrapped) + "\n";
        } catch (JacksonException e) {
            throw new KindCodecException("Failed to write JSON: " + e.getOriginalMessage(), e);
        }
    }

    private static String serializeYaml(CardDocument doc) {
        return KindHeaderCodec.dumpYamlBody(canonicalKind(doc), buildStructuredBody(doc));
    }

    // ── Promotion ──────────────────────────────────────────────────

    private static CardDocument promoteFromMap(Map<String, Object> obj) {
        String kind = obj.get("kind") instanceof String s ? s : "";
        String title = coerceString(obj.get("title"));
        if (title == null) title = "";
        String priority = coerceString(obj.get("priority"));
        String assignee = coerceString(obj.get("assignee"));
        List<String> labels = coerceStringList(obj.get("labels"));
        String dueDate = coerceString(obj.get("dueDate"));
        Double estimate = coerceDouble(obj.get("estimate"));
        boolean blocked = obj.get("blocked") instanceof Boolean b && b;
        String bodyText = coerceString(obj.get("body"));
        if (bodyText == null) bodyText = "";

        Map<String, Object> extra = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : obj.entrySet()) {
            if (isKnownField(e.getKey())) continue;
            extra.put(e.getKey(), e.getValue());
        }
        return new CardDocument(
                kind.isEmpty() ? "card" : kind,
                title, priority, assignee, labels, dueDate,
                estimate, blocked, bodyText, extra);
    }

    /** Markdown-specific lift: front-matter is flat strings, body is verbatim. */
    private static CardDocument promoteFromValues(String kind,
                                                  Map<String, String> values,
                                                  String mdBody) {
        String title = values.getOrDefault("title", "");
        String priority = nullIfBlank(values.get("priority"));
        String assignee = nullIfBlank(values.get("assignee"));
        List<String> labels = splitLabels(values.get("labels"));
        String dueDate = nullIfBlank(values.get("dueDate"));
        Double estimate = null;
        String estimateRaw = values.get("estimate");
        if (estimateRaw != null && !estimateRaw.isBlank()) {
            try {
                estimate = Double.parseDouble(estimateRaw.trim());
            } catch (NumberFormatException ignored) {
                // ignore; preserved in extra below
            }
        }
        boolean blocked = "true".equalsIgnoreCase(values.get("blocked"));

        Map<String, Object> extra = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            String k = e.getKey();
            if (isKnownField(k)) continue;
            extra.put(k, e.getValue());
        }
        // Preserve a non-numeric estimate value so it round-trips.
        if (estimate == null && estimateRaw != null && !estimateRaw.isBlank()) {
            extra.put("estimate", estimateRaw);
        }
        return new CardDocument(
                kind.isEmpty() ? "card" : kind,
                title, priority, assignee, labels, dueDate,
                estimate, blocked, mdBody, extra);
    }

    private static boolean isKnownField(String key) {
        return switch (key) {
            case "kind", "title", "priority", "assignee", "labels",
                 "dueDate", "estimate", "blocked", "body" -> true;
            default -> false;
        };
    }

    // ── JSON/YAML body builder ─────────────────────────────────────

    private static Map<String, Object> buildStructuredBody(CardDocument doc) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (!doc.title().isEmpty()) body.put("title", doc.title());
        if (doc.priority() != null) body.put("priority", doc.priority());
        if (doc.assignee() != null) body.put("assignee", doc.assignee());
        if (!doc.labels().isEmpty()) body.put("labels", new ArrayList<>(doc.labels()));
        if (doc.dueDate() != null) body.put("dueDate", doc.dueDate());
        if (doc.estimate() != null) {
            double e = doc.estimate();
            if (e == Math.floor(e) && !Double.isInfinite(e)) {
                body.put("estimate", (long) e);
            } else {
                body.put("estimate", e);
            }
        }
        if (doc.blocked()) body.put("blocked", true);
        if (!doc.body().isEmpty()) body.put("body", doc.body());
        for (Map.Entry<String, Object> e : doc.extra().entrySet()) {
            if (!body.containsKey(e.getKey())) body.put(e.getKey(), e.getValue());
        }
        return body;
    }

    // ── Body-level helpers ─────────────────────────────────────────

    /** Count GFM checkboxes in a card body. Returns {@code [total, done]}. */
    public static int[] countCheckboxes(String body) {
        if (body == null || body.isEmpty()) return new int[]{0, 0};
        int total = 0;
        int done = 0;
        Matcher m = CHECKBOX_LINE.matcher(body);
        while (m.find()) {
            total++;
            char c = m.group(1).charAt(0);
            if (c == 'x' || c == 'X') done++;
        }
        return new int[]{total, done};
    }

    // ── Helpers ───────────────────────────────────────────────────

    private static @Nullable String coerceString(@Nullable Object v) {
        if (v == null) return null;
        if (v instanceof String s) return s.isBlank() ? null : s;
        return v.toString();
    }

    private static @Nullable Double coerceDouble(@Nullable Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Double.parseDouble(s.trim()); }
            catch (NumberFormatException e) { return null; }
        }
        return null;
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
        if (v instanceof String s) return splitLabels(s);
        return new ArrayList<>();
    }

    private static List<String> splitLabels(@Nullable String csv) {
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

    private static String canonicalKind(CardDocument doc) {
        return (doc.kind() == null || doc.kind().isBlank()) ? "card" : doc.kind();
    }
}
