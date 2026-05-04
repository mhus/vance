package de.mhus.vance.shared.document.kind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Parser and serialiser for {@code kind: list} document bodies.
 * Mirrors the TypeScript {@code listItemsCodec.ts} so server and
 * web-UI agree on the wire format byte-for-byte.
 *
 * <p>Supported mime types: {@code text/markdown}, {@code application/json},
 * {@code application/yaml} (and the {@code text/yaml} / {@code x-} aliases).
 *
 * <p>Round-trip rules — see {@code specification/doc-kind-items.md}:
 * <ul>
 *   <li>Markdown front-matter is read as {@code kind:} + flat
 *       {@code key: value} pairs; written canonically with the
 *       {@code ---} fence and {@code kind: list} on top.</li>
 *   <li>JSON canonical form wraps {@code kind} in {@code $meta};
 *       legacy top-level {@code kind} is accepted on read.</li>
 *   <li>YAML canonical form is multi-document with the header in
 *       doc 1 and the body in doc 2; legacy single-doc with
 *       {@code kind:} at top level is accepted on read.</li>
 * </ul>
 *
 * <p>Stateless utility — all methods are static, no instance state,
 * no Spring bean.
 */
public final class ListCodec {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> JSON_MAP =
            new TypeReference<>() {};

    private ListCodec() {
        // utility class
    }

    // ── Public API ──────────────────────────────────────────────────

    /**
     * Parse a body of the given mime type into a typed
     * {@link ListDocument}.
     *
     * @throws KindCodecException for malformed JSON / YAML bodies, or
     *         when the mime type is not supported.
     */
    public static ListDocument parse(String body, @Nullable String mimeType) {
        if (isMarkdown(mimeType)) return parseMarkdown(body);
        if (isJson(mimeType)) return parseJson(body);
        if (isYaml(mimeType)) return parseYaml(body);
        throw new KindCodecException("Unsupported mime type for list: " + mimeType);
    }

    /**
     * Serialise a {@link ListDocument} into the given mime type's
     * canonical on-disk form.
     */
    public static String serialize(ListDocument doc, @Nullable String mimeType) {
        if (isMarkdown(mimeType)) return serializeMarkdown(doc);
        if (isJson(mimeType)) return serializeJson(doc);
        if (isYaml(mimeType)) return serializeYaml(doc);
        throw new KindCodecException("Unsupported mime type for list: " + mimeType);
    }

    /**
     * @return {@code true} when this codec can handle the mime type
     *         — useful for callers that want to ask before attempting
     *         to parse.
     */
    public static boolean supports(@Nullable String mimeType) {
        return isMarkdown(mimeType) || isJson(mimeType) || isYaml(mimeType);
    }

    // ── Mime helpers ────────────────────────────────────────────────

    private static boolean isMarkdown(@Nullable String mime) {
        if (mime == null) return false;
        return "text/markdown".equals(mime) || "text/x-markdown".equals(mime);
    }

    private static boolean isJson(@Nullable String mime) {
        if (mime == null) return false;
        return "application/json".equals(mime);
    }

    private static boolean isYaml(@Nullable String mime) {
        if (mime == null) return false;
        return "application/yaml".equals(mime)
                || "application/x-yaml".equals(mime)
                || "text/yaml".equals(mime)
                || "text/x-yaml".equals(mime);
    }

    // ── Markdown ────────────────────────────────────────────────────

    private static final String MD_FENCE = "---";

    /**
     * Parse a markdown body with optional {@code ---}-fenced front
     * matter and a flat bullet list. Continuation lines (≥ 2-space
     * indent that doesn't itself start a bullet) append to the
     * previous item's text with a {@code \n} separator.
     */
    private static ListDocument parseMarkdown(String body) {
        String[] lines = body.split("\\R", -1);
        int cursor = 0;

        Map<String, Object> extra = new LinkedHashMap<>();
        String kind = "";

        if (lines.length > 0 && MD_FENCE.equals(lines[0].trim())) {
            cursor = 1;
            while (cursor < lines.length && !MD_FENCE.equals(lines[cursor].trim())) {
                String trimmed = lines[cursor].trim();
                cursor++;
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int colon = trimmed.indexOf(':');
                if (colon <= 0) continue;
                String key = trimmed.substring(0, colon).trim();
                String value = trimmed.substring(colon + 1).trim();
                if ("kind".equals(key)) {
                    kind = value;
                } else {
                    extra.put(key, value);
                }
            }
            if (cursor < lines.length && MD_FENCE.equals(lines[cursor].trim())) {
                cursor++; // skip closing fence
            }
        }

        List<ListItem> items = new ArrayList<>();
        Map<String, Object> currentExtra = null;
        StringBuilder currentText = null;
        for (int i = cursor; i < lines.length; i++) {
            String raw = lines[i];
            if (raw.trim().isEmpty()) {
                items.addAll(flushItem(currentText, currentExtra));
                currentText = null;
                currentExtra = null;
                continue;
            }
            // Bullet: optional leading whitespace + '-' or '*' + space
            int idx = bulletStart(raw);
            if (idx >= 0) {
                items.addAll(flushItem(currentText, currentExtra));
                currentText = new StringBuilder(raw.substring(idx));
                currentExtra = new LinkedHashMap<>();
                continue;
            }
            // Continuation line: ≥ 2 leading spaces, not a bullet.
            if (currentText != null && raw.startsWith("  ")) {
                currentText.append('\n').append(raw.substring(2));
                continue;
            }
            // Anything else (text outside the list area) is dropped.
        }
        items.addAll(flushItem(currentText, currentExtra));

        return new ListDocument(kind.isEmpty() ? "list" : kind, items, extra);
    }

    /** Returns the byte offset right after the bullet marker if the
     *  line starts with a {@code "- "} or {@code "* "} bullet (no
     *  leading indent allowed for {@code kind: list} — nested
     *  bullets belong to {@code kind: tree}). Returns -1 otherwise. */
    private static int bulletStart(String raw) {
        if (raw.length() < 2) return -1;
        char c0 = raw.charAt(0);
        char c1 = raw.charAt(1);
        if ((c0 == '-' || c0 == '*') && c1 == ' ') {
            return 2;
        }
        return -1;
    }

    private static List<ListItem> flushItem(
            @Nullable StringBuilder text,
            @Nullable Map<String, Object> extra) {
        if (text == null) return List.of();
        return List.of(new ListItem(text.toString(),
                extra == null ? new LinkedHashMap<>() : extra));
    }

    private static String serializeMarkdown(ListDocument doc) {
        StringBuilder out = new StringBuilder();
        out.append(MD_FENCE).append('\n');
        out.append("kind: ").append(doc.kind() == null || doc.kind().isBlank() ? "list" : doc.kind()).append('\n');
        for (Map.Entry<String, Object> e : doc.extra().entrySet()) {
            out.append(e.getKey()).append(": ").append(stringifyMdExtra(e.getValue())).append('\n');
        }
        out.append(MD_FENCE).append('\n');
        for (ListItem item : doc.items()) {
            String[] textLines = item.text().split("\n", -1);
            out.append("- ").append(textLines.length > 0 ? textLines[0] : "").append('\n');
            for (int i = 1; i < textLines.length; i++) {
                out.append("  ").append(textLines[i]).append('\n');
            }
        }
        return out.toString();
    }

    private static String stringifyMdExtra(@Nullable Object value) {
        if (value == null) return "";
        if (value instanceof String s) return s;
        return String.valueOf(value);
    }

    // ── JSON ────────────────────────────────────────────────────────

    private static ListDocument parseJson(String body) {
        if (body.isBlank()) return ListDocument.empty();
        Map<String, Object> parsed;
        try {
            parsed = JSON.readValue(body, JSON_MAP);
        } catch (JacksonException e) {
            throw new KindCodecException("Invalid JSON: " + e.getOriginalMessage(), e);
        }
        if (parsed == null) {
            throw new KindCodecException("Top-level JSON must be an object");
        }
        return promoteToDocument(KindHeaderCodec.unwrapJsonMeta(parsed));
    }

    private static String serializeJson(ListDocument doc) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", itemsToList(doc.items()));
        body.putAll(doc.extra());
        Map<String, Object> wrapped = KindHeaderCodec.wrapJsonMeta(canonicalKind(doc), body);
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(wrapped) + "\n";
        } catch (JacksonException e) {
            throw new KindCodecException("Failed to write JSON: " + e.getOriginalMessage(), e);
        }
    }

    // ── YAML ────────────────────────────────────────────────────────

    private static ListDocument parseYaml(String body) {
        if (body.isBlank()) return ListDocument.empty();
        Map<String, Object> merged = KindHeaderCodec.mergeYamlMultiDoc(body);
        return promoteToDocument(merged);
    }

    private static String serializeYaml(ListDocument doc) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", itemsToList(doc.items()));
        body.putAll(doc.extra());
        return KindHeaderCodec.dumpYamlMultiDoc(canonicalKind(doc), body);
    }

    // ── Shared promotion logic (json + yaml share the object shape) ─

    /**
     * Lift a parsed JSON/YAML object into the typed
     * {@link ListDocument} shape. Accepts both the canonical
     * object-form items and the shorthand string-array form. Unknown
     * top-level fields go into {@code extra}, unknown item fields
     * into the per-item {@code extra}.
     */
    private static ListDocument promoteToDocument(Map<String, Object> obj) {
        Object kindRaw = obj.get("kind");
        String kind = (kindRaw instanceof String s) ? s : "";
        Object itemsRaw = obj.get("items");
        List<ListItem> items = new ArrayList<>();
        if (itemsRaw instanceof List<?> list) {
            for (Object raw : list) {
                if (raw instanceof String s) {
                    items.add(ListItem.of(s));
                } else if (raw instanceof Map<?, ?> map) {
                    items.add(promoteItem(map));
                }
                // Other shapes silently dropped (v1 limit).
            }
        }
        Map<String, Object> extra = new LinkedHashMap<>(obj);
        extra.remove("kind");
        extra.remove("items");
        return new ListDocument(kind, items, extra);
    }

    private static ListItem promoteItem(Map<?, ?> map) {
        Object textRaw = map.get("text");
        String text = (textRaw instanceof String s) ? s : "";
        Map<String, Object> extra = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!(e.getKey() instanceof String key)) continue;
            if ("text".equals(key)) continue;
            extra.put(key, e.getValue());
        }
        return new ListItem(text, extra);
    }

    private static List<Map<String, Object>> itemsToList(List<ListItem> items) {
        List<Map<String, Object>> out = new ArrayList<>(items.size());
        for (ListItem item : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("text", item.text());
            m.putAll(item.extra());
            out.add(m);
        }
        return out;
    }

    private static String canonicalKind(ListDocument doc) {
        return (doc.kind() == null || doc.kind().isBlank()) ? "list" : doc.kind();
    }
}
