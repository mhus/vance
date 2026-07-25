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
 *
 * <p><b>Parity harness.</b> This codec and its TS twin
 * {@code client/packages/vance-face/src/document/sheetCodec.ts} must agree on the wire
 * format. A shared fixture corpus at
 * {@code test-fixtures/kind-codecs/sheet/} pins that agreement; it
 * is read by both {@code SheetCodecParityTest} (Java) and
 * {@code sheetCodec.parity.test.ts} (TS). Edit the codec and the
 * corpus together.
 */
public final class SheetCodec {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> JSON_MAP =
            new TypeReference<>() {};
    private static final Pattern ADDRESS = Pattern.compile("^([A-Z]+)([1-9][0-9]*)$");
    private static final Pattern COL_LETTERS = Pattern.compile("^[A-Z]+$");
    private static final Set<String> CELL_FIELDS = Set.of(
            "field", "data", "color", "background", "bold", "italic", "align",
            "numberFormat", "borders");

    private SheetCodec() {
        // utility class
    }

    public static SheetDocument parse(String body, @Nullable String mimeType) {
        if (isJson(mimeType)) return parseJson(body);
        if (isYaml(mimeType)) return parseYaml(body);
        throw new KindCodecException("Unsupported mime type for sheet: " + mimeType);
    }

    public static String serialize(SheetDocument doc, @Nullable String mimeType) {
        return serialize(doc, null, mimeType);
    }

    /**
     * Serialise with an optional computed overlay written under
     * {@code $computed}. The overlay is derived data — dropped on the
     * next {@link #parse}; only the brain-side eval service passes it.
     */
    public static String serialize(SheetDocument doc, @Nullable SheetComputed computed,
                                   @Nullable String mimeType) {
        if (isJson(mimeType)) return serializeJson(doc, computed);
        if (isYaml(mimeType)) return serializeYaml(doc, computed);
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
        return promoteToDocument(KindHeaderCodec.parseYamlBody(body));
    }

    private static String serializeJson(SheetDocument doc, @Nullable SheetComputed computed) {
        Map<String, Object> wrapped =
                KindHeaderCodec.wrapJsonMeta(canonicalKind(doc), buildBody(doc, computed));
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(wrapped) + "\n";
        } catch (JacksonException e) {
            throw new KindCodecException("Failed to write JSON: " + e.getOriginalMessage(), e);
        }
    }

    private static String serializeYaml(SheetDocument doc, @Nullable SheetComputed computed) {
        return KindHeaderCodec.dumpYamlBody(canonicalKind(doc), buildBody(doc, computed));
    }

    // ── Promotion ──────────────────────────────────────────────────

    private static SheetDocument promoteToDocument(Map<String, Object> obj) {
        Object kindRaw = obj.get("kind");
        String kind = (kindRaw instanceof String s) ? s : "";
        List<String> schema = promoteSchema(obj.get("schema"));
        Integer rows = promoteRows(obj.get("rows"));
        List<SheetCell> cells = promoteCells(obj.get("cells"));
        Map<String, SheetColumn> columns = promoteColumns(obj.get("columns"));
        Map<String, Integer> rowHeights = promoteRowHeights(obj.get("rowHeights"));
        Map<String, Object> extra = new LinkedHashMap<>(obj);
        extra.remove("kind");
        extra.remove("schema");
        extra.remove("rows");
        extra.remove("cells");
        extra.remove("columns");
        extra.remove("rowHeights");
        extra.remove("$computed"); // derived overlay — never part of the input model
        return new SheetDocument(kind.isEmpty() ? "sheet" : kind, schema, rows, cells,
                columns, rowHeights, extra);
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

    private static Map<String, SheetColumn> promoteColumns(@Nullable Object raw) {
        Map<String, SheetColumn> out = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> map)) return out;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!(e.getKey() instanceof String k)) continue;
            String col = k.trim().toUpperCase();
            if (!COL_LETTERS.matcher(col).matches()) continue;
            if (!(e.getValue() instanceof Map<?, ?> m)) continue;
            Integer width = null;
            if (m.get("width") instanceof Number n && n.intValue() > 0) width = n.intValue();
            String border = null;
            if (m.get("border") instanceof String bs && isBorder(bs)) {
                border = bs.trim().toLowerCase();
            }
            SheetColumn c = new SheetColumn(width, border);
            if (!c.isEmpty()) out.put(col, c);
        }
        return out;
    }

    private static boolean isBorder(String s) {
        String t = s.trim().toLowerCase();
        return t.equals("left") || t.equals("right") || t.equals("both");
    }

    private static boolean isAlign(String s) {
        String t = s.trim().toLowerCase();
        return t.equals("left") || t.equals("center") || t.equals("right");
    }

    /** Normalise a cell-border spec to the canonical subset of {@code "trbl"}
     *  in fixed order; returns {@code null} when no valid side remains. */
    private static @Nullable String normalizeBorders(String s) {
        String in = s.toLowerCase();
        StringBuilder out = new StringBuilder(4);
        for (char c : new char[]{'t', 'r', 'b', 'l'}) {
            if (in.indexOf(c) >= 0) out.append(c);
        }
        return out.length() == 0 ? null : out.toString();
    }

    private static Map<String, Integer> promoteRowHeights(@Nullable Object raw) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> map)) return out;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!(e.getKey() instanceof String k)) continue;
            String row = k.trim();
            int rowNum;
            try {
                rowNum = Integer.parseInt(row);
            } catch (NumberFormatException ex) {
                continue;
            }
            if (rowNum < 1) continue;
            if (e.getValue() instanceof Number n && n.intValue() > 0) {
                out.put(Integer.toString(rowNum), n.intValue());
            }
        }
        return out;
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
            Boolean bold = Boolean.TRUE.equals(map.get("bold")) ? Boolean.TRUE : null;
            Boolean italic = Boolean.TRUE.equals(map.get("italic")) ? Boolean.TRUE : null;
            String align = (map.get("align") instanceof String as && isAlign(as))
                    ? as.trim().toLowerCase() : null;
            String numberFormat = (map.get("numberFormat") instanceof String nf && !nf.isBlank())
                    ? nf.trim() : null;
            String borders = (map.get("borders") instanceof String bd)
                    ? normalizeBorders(bd) : null;
            Map<String, Object> extra = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!(e.getKey() instanceof String key)) continue;
                if (CELL_FIELDS.contains(key)) continue;
                extra.put(key, e.getValue());
            }
            out.add(new SheetCell(field, data, color, bg, bold, italic, align,
                    numberFormat, borders, extra));
        }
        return out;
    }

    private static String coerceCellValue(@Nullable Object v) {
        if (v == null) return "";
        if (v instanceof String s) return s;
        if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
        return String.valueOf(v);
    }

    private static Map<String, Object> buildBody(SheetDocument doc, @Nullable SheetComputed computed) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (!doc.schema().isEmpty()) body.put("schema", new ArrayList<>(doc.schema()));
        if (doc.rows() != null) body.put("rows", doc.rows());
        Map<String, Object> cols = columnsToMap(doc.columns());
        if (!cols.isEmpty()) body.put("columns", cols);
        if (!doc.rowHeights().isEmpty()) {
            body.put("rowHeights", new LinkedHashMap<String, Object>(doc.rowHeights()));
        }
        body.put("cells", cellsToList(doc.cells()));
        for (Map.Entry<String, Object> e : doc.extra().entrySet()) {
            if (!body.containsKey(e.getKey()) && !"$computed".equals(e.getKey())) {
                body.put(e.getKey(), e.getValue());
            }
        }
        if (computed != null && !computed.values().isEmpty()) {
            body.put("$computed", computedToMap(computed));
        }
        return body;
    }

    private static Map<String, Object> computedToMap(SheetComputed computed) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (computed.computedAt() != null) m.put("computedAt", computed.computedAt());
        List<Map<String, Object>> values = new ArrayList<>(computed.values().size());
        for (SheetComputed.Value v : computed.values()) {
            Map<String, Object> vm = new LinkedHashMap<>();
            vm.put("field", v.field());
            vm.put("value", v.value());
            vm.put("type", v.type());
            if (v.error() != null) vm.put("error", v.error());
            values.add(vm);
        }
        m.put("values", values);
        return m;
    }

    private static Map<String, Object> columnsToMap(Map<String, SheetColumn> columns) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, SheetColumn> e : columns.entrySet()) {
            SheetColumn c = e.getValue();
            if (c == null || c.isEmpty()) continue;
            Map<String, Object> cm = new LinkedHashMap<>();
            if (c.width() != null) cm.put("width", c.width());
            if (c.border() != null && !c.border().isBlank()) cm.put("border", c.border());
            out.put(e.getKey(), cm);
        }
        return out;
    }

    private static List<Map<String, Object>> cellsToList(List<SheetCell> cells) {
        List<Map<String, Object>> out = new ArrayList<>(cells.size());
        for (SheetCell cell : cells) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("field", cell.field());
            m.put("data", cell.data());
            if (cell.color() != null) m.put("color", cell.color());
            if (cell.background() != null) m.put("background", cell.background());
            if (Boolean.TRUE.equals(cell.bold())) m.put("bold", true);
            if (Boolean.TRUE.equals(cell.italic())) m.put("italic", true);
            if (cell.align() != null) m.put("align", cell.align());
            if (cell.numberFormat() != null) m.put("numberFormat", cell.numberFormat());
            if (cell.borders() != null) m.put("borders", cell.borders());
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
