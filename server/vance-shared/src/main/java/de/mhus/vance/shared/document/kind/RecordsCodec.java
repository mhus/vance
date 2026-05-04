package de.mhus.vance.shared.document.kind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Parser and serialiser for {@code kind: records} document bodies —
 * mirrors {@code recordsCodec.ts}. Same wire format including the
 * markdown CSV-light grammar (RFC-4180-ish: quote on commas / quotes
 * / leading-trailing whitespace, double {@code ""} inside quoted
 * runs) and the schema-required rule.
 *
 * <p>Stateless utility — all methods static.
 */
public final class RecordsCodec {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> JSON_MAP =
            new TypeReference<>() {};

    private RecordsCodec() {
        // utility class
    }

    public static RecordsDocument parse(String body, @Nullable String mimeType) {
        if (isMarkdown(mimeType)) return parseMarkdown(body);
        if (isJson(mimeType)) return parseJson(body);
        if (isYaml(mimeType)) return parseYaml(body);
        throw new KindCodecException("Unsupported mime type for records: " + mimeType);
    }

    public static String serialize(RecordsDocument doc, @Nullable String mimeType) {
        if (isMarkdown(mimeType)) return serializeMarkdown(doc);
        if (isJson(mimeType)) return serializeJson(doc);
        if (isYaml(mimeType)) return serializeYaml(doc);
        throw new KindCodecException("Unsupported mime type for records: " + mimeType);
    }

    public static boolean supports(@Nullable String mimeType) {
        return isMarkdown(mimeType) || isJson(mimeType) || isYaml(mimeType);
    }

    // ── Mime ────────────────────────────────────────────────────────

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

    // ── Markdown ────────────────────────────────────────────────────

    private static final String MD_FENCE = "---";

    private static RecordsDocument parseMarkdown(String body) {
        String[] lines = body.split("\\R", -1);
        int cursor = 0;

        Map<String, Object> extra = new LinkedHashMap<>();
        String kind = "";
        String schemaRaw = "";

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
                if ("kind".equals(key)) kind = value;
                else if ("schema".equals(key)) schemaRaw = value;
                else extra.put(key, value);
            }
            if (cursor < lines.length && MD_FENCE.equals(lines[cursor].trim())) cursor++;
        }

        List<String> schema = parseSchemaCsv(schemaRaw);
        if (schema.isEmpty()) {
            throw new KindCodecException(
                    "Missing or empty schema in front-matter — `kind: records` requires `schema: field1, field2, ...`");
        }

        List<RecordsItem> items = new ArrayList<>();
        for (int i = cursor; i < lines.length; i++) {
            String raw = lines[i];
            if (raw.trim().isEmpty()) continue;
            BulletMatch bullet = matchBullet(raw);
            if (bullet == null) continue;
            items.add(rowFromCsvValues(parseCsvLine(bullet.body), schema));
        }

        return new RecordsDocument(kind.isEmpty() ? "records" : kind, schema, items, extra);
    }

    private record BulletMatch(String body) {}

    private static @Nullable BulletMatch matchBullet(String raw) {
        int i = 0;
        while (i < raw.length() && (raw.charAt(i) == ' ' || raw.charAt(i) == '\t')) i++;
        if (i + 1 >= raw.length()) return null;
        char marker = raw.charAt(i);
        if ((marker != '-' && marker != '*') || raw.charAt(i + 1) != ' ') return null;
        return new BulletMatch(raw.substring(i + 2));
    }

    private static RecordsItem rowFromCsvValues(List<String> values, List<String> schema) {
        Map<String, String> v = new LinkedHashMap<>();
        for (int j = 0; j < schema.size(); j++) {
            v.put(schema.get(j), j < values.size() ? values.get(j) : "");
        }
        List<String> overflow = values.size() > schema.size()
                ? new ArrayList<>(values.subList(schema.size(), values.size()))
                : new ArrayList<>();
        return new RecordsItem(v, new LinkedHashMap<>(), overflow);
    }

    private static String serializeMarkdown(RecordsDocument doc) {
        if (doc.schema().isEmpty()) {
            throw new KindCodecException("Cannot serialise records without a schema");
        }
        StringBuilder out = new StringBuilder();
        out.append(MD_FENCE).append('\n');
        out.append("kind: ").append(canonicalKind(doc)).append('\n');
        out.append("schema: ").append(String.join(", ", doc.schema())).append('\n');
        for (Map.Entry<String, Object> e : doc.extra().entrySet()) {
            out.append(e.getKey()).append(": ").append(stringifyMdExtra(e.getValue())).append('\n');
        }
        out.append(MD_FENCE).append('\n');
        for (RecordsItem item : doc.items()) {
            out.append("- ").append(rowToCsv(item, doc.schema())).append('\n');
        }
        return out.toString();
    }

    private static String rowToCsv(RecordsItem item, List<String> schema) {
        List<String> cells = new ArrayList<>();
        for (String field : schema) {
            cells.add(encodeCsvValue(item.values().getOrDefault(field, ""), field));
        }
        for (String v : item.overflow()) {
            cells.add(encodeCsvValue(v, "<overflow>"));
        }
        return String.join(", ", cells);
    }

    private static String encodeCsvValue(String value, String fieldHint) {
        if (value.contains("\n") || value.contains("\r")) {
            throw new KindCodecException(
                    "Value for \"" + fieldHint + "\" contains a newline — markdown form is single-line per bullet. Use json or yaml instead.");
        }
        if (value.isEmpty()) return "";
        boolean needsQuote = value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || (!value.isEmpty() && (Character.isWhitespace(value.charAt(0))
                        || Character.isWhitespace(value.charAt(value.length() - 1))));
        if (!needsQuote) return value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    /**
     * CSV-light parser for the bullet body. Quoted values keep
     * embedded commas and use {@code ""} for literal quotes;
     * whitespace around comma separators is trimmed, whitespace
     * inside quotes is preserved.
     */
    private static List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        int n = line.length();
        int i = 0;
        boolean lastWasComma = false;
        while (i < n) {
            while (i < n && line.charAt(i) == ' ') i++;
            if (i >= n) {
                out.add("");
                lastWasComma = false;
                break;
            }
            if (line.charAt(i) == '"') {
                i++;
                StringBuilder buf = new StringBuilder();
                while (i < n) {
                    char c = line.charAt(i);
                    if (c == '"') {
                        if (i + 1 < n && line.charAt(i + 1) == '"') {
                            buf.append('"');
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    buf.append(c);
                    i++;
                }
                out.add(buf.toString());
                while (i < n && line.charAt(i) == ' ') i++;
                if (i < n && line.charAt(i) == ',') { i++; lastWasComma = true; }
                else lastWasComma = false;
            } else {
                StringBuilder buf = new StringBuilder();
                while (i < n && line.charAt(i) != ',') {
                    buf.append(line.charAt(i));
                    i++;
                }
                if (i < n && line.charAt(i) == ',') { i++; lastWasComma = true; }
                else lastWasComma = false;
                out.add(stripTrailingSpaces(buf.toString()));
            }
        }
        // Trailing comma without value: append empty cell.
        if (lastWasComma) out.add("");
        return out;
    }

    private static String stripTrailingSpaces(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ' ') end--;
        return s.substring(0, end);
    }

    private static List<String> parseSchemaCsv(String raw) {
        if (raw.isBlank()) return new ArrayList<>();
        List<String> rawFields = parseCsvLine(raw);
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String f : rawFields) {
            String trimmed = f.trim();
            if (trimmed.isEmpty() || seen.contains(trimmed)) continue;
            seen.add(trimmed);
            out.add(trimmed);
        }
        return out;
    }

    private static String stringifyMdExtra(@Nullable Object value) {
        if (value == null) return "";
        if (value instanceof String s) return s;
        return String.valueOf(value);
    }

    // ── JSON ────────────────────────────────────────────────────────

    private static RecordsDocument parseJson(String body) {
        if (body.isBlank()) {
            throw new KindCodecException("Empty JSON body — `kind: records` requires a schema");
        }
        Map<String, Object> parsed;
        try {
            parsed = JSON.readValue(body, JSON_MAP);
        } catch (JacksonException e) {
            throw new KindCodecException("Invalid JSON: " + e.getOriginalMessage(), e);
        }
        if (parsed == null) throw new KindCodecException("Top-level JSON must be an object");
        return promoteToDocument(KindHeaderCodec.unwrapJsonMeta(parsed));
    }

    private static String serializeJson(RecordsDocument doc) {
        if (doc.schema().isEmpty()) {
            throw new KindCodecException("Cannot serialise records without a schema");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema", new ArrayList<>(doc.schema()));
        body.put("items", itemsToList(doc));
        body.putAll(doc.extra());
        Map<String, Object> wrapped = KindHeaderCodec.wrapJsonMeta(canonicalKind(doc), body);
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(wrapped) + "\n";
        } catch (JacksonException e) {
            throw new KindCodecException("Failed to write JSON: " + e.getOriginalMessage(), e);
        }
    }

    // ── YAML ────────────────────────────────────────────────────────

    private static RecordsDocument parseYaml(String body) {
        if (body.isBlank()) {
            throw new KindCodecException("Empty YAML body — `kind: records` requires a schema");
        }
        Map<String, Object> merged = KindHeaderCodec.mergeYamlMultiDoc(body);
        return promoteToDocument(merged);
    }

    private static String serializeYaml(RecordsDocument doc) {
        if (doc.schema().isEmpty()) {
            throw new KindCodecException("Cannot serialise records without a schema");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema", new ArrayList<>(doc.schema()));
        body.put("items", itemsToList(doc));
        body.putAll(doc.extra());
        return KindHeaderCodec.dumpYamlMultiDoc(canonicalKind(doc), body);
    }

    // ── Promotion ───────────────────────────────────────────────────

    private static RecordsDocument promoteToDocument(Map<String, Object> obj) {
        Object kindRaw = obj.get("kind");
        String kind = (kindRaw instanceof String s) ? s : "";
        List<String> schema = promoteSchema(obj.get("schema"));
        if (schema.isEmpty()) {
            throw new KindCodecException(
                    "Missing or empty schema — `kind: records` requires a `schema: [...]`");
        }
        List<RecordsItem> items = promoteItems(obj.get("items"), schema);
        Map<String, Object> extra = new LinkedHashMap<>(obj);
        extra.remove("kind");
        extra.remove("schema");
        extra.remove("items");
        return new RecordsDocument(kind.isEmpty() ? "records" : kind, schema, items, extra);
    }

    private static List<String> promoteSchema(@Nullable Object raw) {
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (Object o : list) {
                if (!(o instanceof String s)) continue;
                String t = s.trim();
                if (t.isEmpty() || seen.contains(t)) continue;
                seen.add(t);
                out.add(t);
            }
            return out;
        }
        if (raw instanceof String s) {
            return parseSchemaCsv(s);
        }
        return new ArrayList<>();
    }

    private static List<RecordsItem> promoteItems(@Nullable Object raw, List<String> schema) {
        List<RecordsItem> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        Set<String> schemaSet = new LinkedHashSet<>(schema);
        for (Object r : list) {
            if (!(r instanceof Map<?, ?> map)) continue;
            Map<String, String> values = new LinkedHashMap<>();
            for (String f : schema) values.put(f, coerceValue(map.get(f)));
            Map<String, Object> extra = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!(e.getKey() instanceof String key)) continue;
                if (schemaSet.contains(key)) continue;
                extra.put(key, e.getValue());
            }
            out.add(new RecordsItem(values, extra, new ArrayList<>()));
        }
        return out;
    }

    private static String coerceValue(@Nullable Object v) {
        if (v == null) return "";
        if (v instanceof String s) return s;
        if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
        try {
            return JSON.writeValueAsString(v);
        } catch (JacksonException e) {
            return String.valueOf(v);
        }
    }

    private static List<Map<String, Object>> itemsToList(RecordsDocument doc) {
        List<Map<String, Object>> out = new ArrayList<>(doc.items().size());
        for (RecordsItem item : doc.items()) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (String f : doc.schema()) {
                m.put(f, item.values().getOrDefault(f, ""));
            }
            for (Map.Entry<String, Object> e : item.extra().entrySet()) {
                if (!m.containsKey(e.getKey())) m.put(e.getKey(), e.getValue());
            }
            out.add(m);
        }
        return out;
    }

    private static String canonicalKind(RecordsDocument doc) {
        return (doc.kind() == null || doc.kind().isBlank()) ? "records" : doc.kind();
    }
}
