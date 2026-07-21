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
 * Parser and serialiser for {@code kind: checklist} document bodies.
 * Mirrors the TypeScript {@code checklistCodec.ts} so server and
 * web-UI agree on the wire format byte-for-byte.
 *
 * <p>Supported mime types: {@code text/markdown}, {@code application/json},
 * {@code application/yaml} (and the {@code text/yaml} / {@code x-}
 * aliases).
 *
 * <p>Round-trip rules — see {@code specification/doc-kind-checklist.md}:
 * <ul>
 *   <li>Markdown front-matter mirrors {@link ListCodec} — flat
 *       {@code key: value} pairs in a {@code ---} fence, {@code kind:
 *       checklist} on the first body line.</li>
 *   <li>Markdown body uses the extended GFM checkbox syntax: every
 *       bullet starts with {@code - [<char>] }, where {@code <char>}
 *       maps to {@link ChecklistStatus}. Plain bullets without a
 *       checkbox are read as {@link ChecklistStatus#OPEN} and
 *       canonicalised on write to {@code - [ ] }.</li>
 *   <li>Priority lives as a trailing {@code #prio:high} or {@code #prio:low}
 *       tag in markdown, as a {@code priority} field in JSON/YAML.</li>
 *   <li>Unknown checkbox chars (e.g. {@code [Z]}) read as
 *       {@link ChecklistStatus#OPEN} plus {@code extra._statusChar}.
 *       Writes prefer {@code extra._statusChar} when present so
 *       custom chars round-trip.</li>
 * </ul>
 *
 * <p>Stateless utility — all methods are static, no instance state,
 * no Spring bean.
 *
 * <p><b>Parity harness.</b> This codec and its TS twin
 * {@code client/packages/vance-face/src/document/checklistCodec.ts} must agree on the wire
 * format. A shared fixture corpus at
 * {@code test-fixtures/kind-codecs/checklist/} pins that agreement; it
 * is read by both {@code ChecklistCodecParityTest} (Java) and
 * {@code checklistCodec.parity.test.ts} (TS). Edit the codec and the
 * corpus together.
 */
public final class ChecklistCodec {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> JSON_MAP =
            new TypeReference<>() {};

    /** Reserved per-item extra key that preserves a non-standard
     *  Markdown checkbox char across a round-trip. */
    public static final String STATUS_CHAR_EXTRA_KEY = "_statusChar";

    private ChecklistCodec() {
        // utility class
    }

    // ── Public API ──────────────────────────────────────────────────

    public static ChecklistDocument parse(String body, @Nullable String mimeType) {
        if (isMarkdown(mimeType)) return parseMarkdown(body);
        if (isJson(mimeType)) return parseJson(body);
        if (isYaml(mimeType)) return parseYaml(body);
        throw new KindCodecException("Unsupported mime type for checklist: " + mimeType);
    }

    public static String serialize(ChecklistDocument doc, @Nullable String mimeType) {
        if (isMarkdown(mimeType)) return serializeMarkdown(doc);
        if (isJson(mimeType)) return serializeJson(doc);
        if (isYaml(mimeType)) return serializeYaml(doc);
        throw new KindCodecException("Unsupported mime type for checklist: " + mimeType);
    }

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

    /** Matches a bullet with optional checkbox: capture group 1 is the
     *  status char (single character or empty); group 2 is the rest of
     *  the line after the bullet/checkbox. */
    private static final Pattern BULLET_PATTERN =
            Pattern.compile("^[-*] (?:\\[(.)?] )?(.*)$");

    /** Matches the trailing {@code #prio:high|low} tag (optionally
     *  preceded by whitespace) at the very end of the bullet text. */
    private static final Pattern PRIO_TAG_PATTERN =
            Pattern.compile("\\s*#prio:(high|low)\\s*$", Pattern.CASE_INSENSITIVE);

    private static ChecklistDocument parseMarkdown(String body) {
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
                cursor++;
            }
        }

        List<ChecklistItem> items = new ArrayList<>();
        @Nullable BuildingItem current = null;
        for (int i = cursor; i < lines.length; i++) {
            String raw = lines[i];
            if (raw.trim().isEmpty()) {
                if (current != null) {
                    items.add(current.toItem());
                    current = null;
                }
                continue;
            }
            Matcher m = BULLET_PATTERN.matcher(raw);
            if (m.matches()) {
                if (current != null) items.add(current.toItem());
                String charGroup = m.group(1);
                String text = m.group(2);
                current = new BuildingItem();
                if (charGroup == null) {
                    // No checkbox at all — plain bullet, default OPEN.
                    current.status = ChecklistStatus.OPEN;
                } else if (charGroup.isEmpty()) {
                    // Zero-width `[]` — treat as OPEN, no custom-char preserve.
                    current.status = ChecklistStatus.OPEN;
                } else {
                    char c = charGroup.charAt(0);
                    ChecklistStatus mapped = ChecklistStatus.fromMarkdownChar(c);
                    if (mapped != null) {
                        current.status = mapped;
                    } else {
                        current.status = ChecklistStatus.OPEN;
                        current.extra.put(STATUS_CHAR_EXTRA_KEY, String.valueOf(c));
                    }
                }
                // Trailing #prio tag — extract before storing text.
                Matcher prio = PRIO_TAG_PATTERN.matcher(text);
                if (prio.find()) {
                    current.priority = ChecklistPriority.fromWireName(prio.group(1));
                    text = text.substring(0, prio.start());
                }
                current.text.append(text);
                continue;
            }
            // Continuation: ≥ 2 leading spaces, append to previous text.
            if (current != null && raw.startsWith("  ")) {
                current.text.append('\n').append(raw.substring(2));
                continue;
            }
            // Anything else outside the list area is dropped (v1 limit).
        }
        if (current != null) items.add(current.toItem());

        return new ChecklistDocument(kind.isEmpty() ? "checklist" : kind, items, extra);
    }

    /** Mutable scratch buffer used during markdown parse. */
    private static final class BuildingItem {
        final StringBuilder text = new StringBuilder();
        ChecklistStatus status = ChecklistStatus.OPEN;
        @Nullable ChecklistPriority priority;
        final Map<String, Object> extra = new LinkedHashMap<>();

        ChecklistItem toItem() {
            return new ChecklistItem(text.toString(), status, priority, extra);
        }
    }

    private static String serializeMarkdown(ChecklistDocument doc) {
        StringBuilder out = new StringBuilder();
        out.append(MD_FENCE).append('\n');
        out.append("kind: ").append(canonicalKind(doc)).append('\n');
        for (Map.Entry<String, Object> e : doc.extra().entrySet()) {
            out.append(e.getKey()).append(": ").append(stringifyMdExtra(e.getValue())).append('\n');
        }
        out.append(MD_FENCE).append('\n');
        for (ChecklistItem item : doc.items()) {
            char statusChar = pickMarkdownChar(item);
            String[] textLines = item.text().split("\n", -1);
            String firstLine = textLines.length > 0 ? textLines[0] : "";
            out.append("- [").append(statusChar).append("] ").append(firstLine);
            if (item.priority() != null) {
                out.append(" #prio:").append(item.priority().wireName());
            }
            out.append('\n');
            for (int i = 1; i < textLines.length; i++) {
                out.append("  ").append(textLines[i]).append('\n');
            }
        }
        return out.toString();
    }

    private static char pickMarkdownChar(ChecklistItem item) {
        Object customRaw = item.extra().get(STATUS_CHAR_EXTRA_KEY);
        if (customRaw instanceof String s && !s.isEmpty()) {
            return s.charAt(0);
        }
        return item.status().markdownChar();
    }

    private static String stringifyMdExtra(@Nullable Object value) {
        if (value == null) return "";
        if (value instanceof String s) return s;
        return String.valueOf(value);
    }

    // ── JSON ────────────────────────────────────────────────────────

    private static ChecklistDocument parseJson(String body) {
        if (body.isBlank()) return ChecklistDocument.empty();
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

    private static String serializeJson(ChecklistDocument doc) {
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

    private static ChecklistDocument parseYaml(String body) {
        if (body.isBlank()) return ChecklistDocument.empty();
        Map<String, Object> merged = KindHeaderCodec.parseYamlBody(body);
        return promoteToDocument(merged);
    }

    private static String serializeYaml(ChecklistDocument doc) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", itemsToList(doc.items()));
        body.putAll(doc.extra());
        return KindHeaderCodec.dumpYamlBody(canonicalKind(doc), body);
    }

    // ── Shared promotion logic (json + yaml share the object shape) ─

    private static ChecklistDocument promoteToDocument(Map<String, Object> obj) {
        Object kindRaw = obj.get("kind");
        String kind = (kindRaw instanceof String s) ? s : "";
        Object itemsRaw = obj.get("items");
        List<ChecklistItem> items = new ArrayList<>();
        if (itemsRaw instanceof List<?> list) {
            for (Object raw : list) {
                if (raw instanceof String s) {
                    items.add(ChecklistItem.of(s));
                } else if (raw instanceof Map<?, ?> map) {
                    ChecklistItem promoted = promoteItem(map);
                    if (promoted != null) items.add(promoted);
                }
            }
        }
        Map<String, Object> extra = new LinkedHashMap<>(obj);
        extra.remove("kind");
        extra.remove("items");
        return new ChecklistDocument(kind, items, extra);
    }

    /** Lift one parsed item map into a {@link ChecklistItem}. Returns
     *  {@code null} when {@code text} is missing or non-string —
     *  malformed items are silently dropped (spec §2.4). */
    private static @Nullable ChecklistItem promoteItem(Map<?, ?> map) {
        Object textRaw = map.get("text");
        if (!(textRaw instanceof String text)) return null;

        ChecklistStatus status = ChecklistStatus.OPEN;
        Object statusRaw = map.get("status");
        if (statusRaw instanceof String sw) {
            ChecklistStatus mapped = ChecklistStatus.fromWireName(sw);
            if (mapped != null) status = mapped;
        }

        @Nullable ChecklistPriority priority = null;
        Object priorityRaw = map.get("priority");
        if (priorityRaw instanceof String pw) {
            priority = ChecklistPriority.fromWireName(pw);
        }

        Map<String, Object> extra = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!(e.getKey() instanceof String key)) continue;
            if ("text".equals(key) || "status".equals(key) || "priority".equals(key)) continue;
            // Permissive: preserve unknown priority strings round-trip.
            extra.put(key, e.getValue());
        }
        // If the priority value was non-standard, preserve it via extra so it round-trips.
        if (priority == null && priorityRaw instanceof String pw && !pw.isBlank()) {
            extra.put("priority", pw);
        }
        return new ChecklistItem(text, status, priority, extra);
    }

    private static List<Map<String, Object>> itemsToList(List<ChecklistItem> items) {
        List<Map<String, Object>> out = new ArrayList<>(items.size());
        for (ChecklistItem item : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("text", item.text());
            if (item.status() != ChecklistStatus.OPEN) {
                m.put("status", item.status().wireName());
            }
            if (item.priority() != null) {
                m.put("priority", item.priority().wireName());
            }
            for (Map.Entry<String, Object> e : item.extra().entrySet()) {
                // Don't overwrite the canonical priority key with a stashed string.
                if ("priority".equals(e.getKey()) && item.priority() != null) continue;
                m.put(e.getKey(), e.getValue());
            }
            out.add(m);
        }
        return out;
    }

    private static String canonicalKind(ChecklistDocument doc) {
        return (doc.kind() == null || doc.kind().isBlank()) ? "checklist" : doc.kind();
    }
}
