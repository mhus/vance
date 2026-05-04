package de.mhus.vance.shared.document.kind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Parser and serialiser for {@code kind: sheet} document bodies —
 * mirrors {@code sheetCodec.ts}. JSON and YAML only; markdown is
 * intentionally not supported (spec §3.3).
 *
 * <p>Cells are validated against the A1 address pattern and
 * uppercased; invalid addresses are dropped (resilient), duplicates
 * throw.
 */
public final class SheetCodec {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> JSON_MAP =
            new TypeReference<>() {};
    private static final Pattern ADDRESS = Pattern.compile("^([A-Z]+)([1-9][0-9]*)$");
    private static final Pattern COL_LETTERS = Pattern.compile("^[A-Z]+$");

    private SheetCodec() {
        // utility class
    }

    public static SheetDocument parse(String body, @Nullable String mimeType) {
        if (isJson(mimeType)) return parseJson(body);
        if (isYaml(mimeType)) return parseYaml(body);
        throw new KindCodecException("Unsupported mime type for sheet: " + mimeType);
    }

    public static String serialize(SheetDocument doc, @Nullable String mimeType) {
        if (isJson(mimeType)) return serializeJson(doc);
        if (isYaml(mimeType)) return serializeYaml(doc);
        throw new KindCodecException("Unsupported mime type for sheet: " + mimeType);
    }

    public static boolean supports(@Nullable String mimeType) {
        return isJson(mimeType) || isYaml(mimeType);
    }

    // ── A1 helpers ─────────────────────────────────────────────────

    public record Address(String column, int row) {}

    public static @Nullable Address parseAddress(String addr) {
        if (addr == null) return null;
        Matcher m = ADDRESS.matcher(addr.trim().toUpperCase());
        if (!m.matches()) return null;
        int row;
        try {
            row = Integer.parseInt(m.group(2));
        } catch (NumberFormatException e) {
            return null;
        }
        if (row < 1) return null;
        return new Address(m.group(1), row);
    }

    public static String columnLetterFromIndex(int idx) {
        if (idx < 1) return "A";
        StringBuilder out = new StringBuilder();
        int n = idx;
        while (n > 0) {
            int rem = (n - 1) % 26;
            out.insert(0, (char) ('A' + rem));
            n = (n - 1) / 26;
        }
        return out.toString();
    }

    public static int columnIndexFromLetter(String col) {
        if (col == null || !COL_LETTERS.matcher(col).matches()) return 0;
        int n = 0;
        for (int i = 0; i < col.length(); i++) {
            n = n * 26 + (col.charAt(i) - 'A' + 1);
        }
        return n;
    }

    // ── Mime ───────────────────────────────────────────────────────

    private static boolean isJson(@Nullable String mime) {
        return "application/json".equals(mime);
    }
    private static boolean isYaml(@Nullable String mime) {
        return "application/yaml".equals(mime)
                || "application/x-yaml".equals(mime)
                || "text/yaml".equals(mime)
                || "text/x-yaml".equals(mime);
    }

    // ── JSON / YAML ────────────────────────────────────────────────

    private static SheetDocument parseJson(String body) {
        if (body.isBlank()) return SheetDocument.empty();
        Map<String, Object> parsed;
        try {
            parsed = JSON.readValue(body, JSON_MAP);
        } catch (JacksonException e) {
            throw new KindCodecException("Invalid JSON: " + e.getOriginalMessage(), e);
        }
        if (parsed == null) throw new KindCodecException("Top-level JSON must be an object");
        return promoteToDocument(KindHeaderCodec.unwrapJsonMeta(parsed));
    }

    private static SheetDocument parseYaml(String body) {
        if (body.isBlank()) return SheetDocument.empty();
        return promoteToDocument(KindHeaderCodec.mergeYamlMultiDoc(body));
    }

    private static String serializeJson(SheetDocument doc) {
        Map<String, Object> wrapped = KindHeaderCodec.wrapJsonMeta(canonicalKind(doc), buildBody(doc));
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(wrapped) + "\n";
        } catch (JacksonException e) {
            throw new KindCodecException("Failed to write JSON: " + e.getOriginalMessage(), e);
        }
    }

    private static String serializeYaml(SheetDocument doc) {
        return KindHeaderCodec.dumpYamlMultiDoc(canonicalKind(doc), buildBody(doc));
    }

    // ── Promotion ──────────────────────────────────────────────────

    private static SheetDocument promoteToDocument(Map<String, Object> obj) {
        Object kindRaw = obj.get("kind");
        String kind = (kindRaw instanceof String s) ? s : "";
        List<String> schema = promoteSchema(obj.get("schema"));
        Integer rows = promoteRows(obj.get("rows"));
        List<SheetCell> cells = promoteCells(obj.get("cells"));
        Map<String, Object> extra = new LinkedHashMap<>(obj);
        extra.remove("kind");
        extra.remove("schema");
        extra.remove("rows");
        extra.remove("cells");
        return new SheetDocument(kind.isEmpty() ? "sheet" : kind, schema, rows, cells, extra);
    }

    private static List<String> promoteSchema(@Nullable Object raw) {
        List<String> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        Set<String> seen = new LinkedHashSet<>();
        for (Object o : list) {
            if (!(o instanceof String s)) continue;
            String col = s.trim().toUpperCase();
            if (!COL_LETTERS.matcher(col).matches()) continue;
            if (seen.contains(col)) continue;
            seen.add(col);
            out.add(col);
        }
        return out;
    }

    private static @Nullable Integer promoteRows(@Nullable Object raw) {
        if (raw instanceof Number n) {
            int v = n.intValue();
            return v >= 1 ? v : null;
        }
        return null;
    }

    private static List<SheetCell> promoteCells(@Nullable Object raw) {
        List<SheetCell> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        Set<String> seen = new LinkedHashSet<>();
        for (Object r : list) {
            if (!(r instanceof Map<?, ?> map)) continue;
            Object fieldRaw = map.get("field");
            if (!(fieldRaw instanceof String fs)) continue;
            Address parsed = parseAddress(fs);
            if (parsed == null) continue; // resilient: drop invalid addresses
            String field = parsed.column() + parsed.row();
            if (seen.contains(field)) {
                throw new KindCodecException("Duplicate cell: " + field);
            }
            seen.add(field);
            String data = coerceCellValue(map.get("data"));
            String color = (map.get("color") instanceof String cs && !cs.isEmpty()) ? cs : null;
            String bg = (map.get("background") instanceof String bs && !bs.isEmpty()) ? bs : null;
            Map<String, Object> extra = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!(e.getKey() instanceof String key)) continue;
                if ("field".equals(key) || "data".equals(key)
                        || "color".equals(key) || "background".equals(key)) continue;
                extra.put(key, e.getValue());
            }
            out.add(new SheetCell(field, data, color, bg, extra));
        }
        return out;
    }

    private static String coerceCellValue(@Nullable Object v) {
        if (v == null) return "";
        if (v instanceof String s) return s;
        if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
        return String.valueOf(v);
    }

    private static Map<String, Object> buildBody(SheetDocument doc) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (!doc.schema().isEmpty()) body.put("schema", new ArrayList<>(doc.schema()));
        if (doc.rows() != null) body.put("rows", doc.rows());
        body.put("cells", cellsToList(doc.cells()));
        for (Map.Entry<String, Object> e : doc.extra().entrySet()) {
            if (!body.containsKey(e.getKey())) body.put(e.getKey(), e.getValue());
        }
        return body;
    }

    private static List<Map<String, Object>> cellsToList(List<SheetCell> cells) {
        List<Map<String, Object>> out = new ArrayList<>(cells.size());
        for (SheetCell cell : cells) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("field", cell.field());
            m.put("data", cell.data());
            if (cell.color() != null) m.put("color", cell.color());
            if (cell.background() != null) m.put("background", cell.background());
            for (Map.Entry<String, Object> e : cell.extra().entrySet()) {
                if (!m.containsKey(e.getKey())) m.put(e.getKey(), e.getValue());
            }
            out.add(m);
        }
        return out;
    }

    private static String canonicalKind(SheetDocument doc) {
        return (doc.kind() == null || doc.kind().isBlank()) ? "sheet" : doc.kind();
    }
}
