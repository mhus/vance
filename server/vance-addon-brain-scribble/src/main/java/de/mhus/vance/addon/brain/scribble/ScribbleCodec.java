package de.mhus.vance.addon.brain.scribble;

import de.mhus.vance.addon.brain.scribble.model.ScribblePoint;
import de.mhus.vance.addon.brain.scribble.model.ScribbleSheet;
import de.mhus.vance.addon.brain.scribble.model.ScribbleSize;
import de.mhus.vance.addon.brain.scribble.model.ScribbleStroke;
import de.mhus.vance.shared.document.kind.KindCodecException;
import de.mhus.vance.shared.document.kind.KindHeaderCodec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Parser and serialiser for {@code kind: scribble} document bodies. YAML is
 * canonical, JSON is a 1:1 dual — both round-trip through the same typed
 * {@link ScribbleSheet} model. Markdown is not supported (handwriting is
 * not prose); the codec throws {@link KindCodecException}.
 *
 * <p><b>Compact YAML.</b> A written sheet holds hundreds of strokes with
 * dozens of points each — block-style nested sequences would explode the
 * file into one line per number. The dumper therefore renders every stroke
 * map (and its point lists) in flow style, one stroke per line:
 *
 * <pre>
 * scribble:
 *   size: {w: 1240, h: 1754}
 *   strokes:
 *   - {tool: pen, c: "1", w: m, p: [[42, 80, 0.4], [47, 81, 0.6]]}
 * </pre>
 *
 * <p><b>Lenient reads.</b> Unusable strokes (no parseable points) are
 * dropped, never thrown — {@link ScribbleValidationService} reports them
 * with their index via a second pass over the raw body. Unknown tool or
 * width values fall back to the v1 defaults ({@code pen}/{@code m}), an
 * invalid palette index falls back to {@code "1"}.
 *
 * <p>Stateless utility — the {@code strokeFromMap}/{@code strokeToMap}
 * helpers are shared with the DTO mapper and the later {@code scribble_*}
 * tools so all input paths use one grammar.
 */
public final class ScribbleCodec {

    public static final String KIND = "scribble";

    /** Fallback pen pressure when a point carries none (mouse input, old files). */
    public static final double DEFAULT_PRESSURE = 0.5;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> JSON_MAP = new TypeReference<>() {};

    private ScribbleCodec() {
        // utility class
    }

    // ── Entry points ──────────────────────────────────────────────

    public static ScribbleSheet parse(String body, @Nullable String mimeType) {
        return parseFlat(parseTop(body, mimeType));
    }

    /**
     * The raw unwrapped body map (JSON or YAML, {@code $meta} lifted) —
     * the second-pass view {@link ScribbleValidationService} walks to
     * report dropped strokes by index.
     */
    public static Map<String, Object> parseTop(String body, @Nullable String mimeType) {
        if (isJson(mimeType)) return parseJson(body);
        if (isYaml(mimeType)) return KindHeaderCodec.parseYamlBody(body);
        throw new KindCodecException("Unsupported mime type for scribble: " + mimeType);
    }

    public static String serialize(ScribbleSheet sheet, @Nullable String mimeType) {
        if (isJson(mimeType)) return serializeJson(sheet);
        if (isYaml(mimeType)) return dumpYaml(buildBody(sheet));
        throw new KindCodecException("Unsupported mime type for scribble: " + mimeType);
    }

    public static boolean supports(@Nullable String mimeType) {
        return isJson(mimeType) || isYaml(mimeType);
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

    // ── Parse ─────────────────────────────────────────────────────

    /** Parse the already-unwrapped body map into the typed sheet. Lenient. */
    public static ScribbleSheet parseFlat(Map<String, Object> top) {
        String title = str(top, "title");
        Map<String, Object> scribble = asMap(top.get("scribble"));
        ScribbleSize size = sizeFromMap(scribble.get("size"));
        List<ScribbleStroke> strokes = new ArrayList<>();
        for (Map<String, Object> raw : rawStrokes(top)) {
            ScribbleStroke stroke = strokeFromMap(raw);
            if (stroke != null) strokes.add(stroke);
        }
        // Book-level flags, lenient like everything here: a missing or
        // non-boolean `enabled` stays true (every ordinary sheet is on), a
        // missing `default` stays false. Explicit values win.
        boolean enabled = !(top.get("enabled") instanceof Boolean b) || b;
        boolean defaultSheet = top.get("default") instanceof Boolean d && d;
        return new ScribbleSheet(title, size, strokes, enabled, defaultSheet);
    }

    /** The raw {@code scribble.strokes} entries of an unwrapped body map. */
    public static List<Map<String, Object>> rawStrokes(Map<String, Object> top) {
        Map<String, Object> scribble = asMap(top.get("scribble"));
        return mapList(scribble.get("strokes"));
    }

    private static Map<String, Object> parseJson(String body) {
        if (body.isBlank()) return new LinkedHashMap<>();
        Map<String, Object> parsed;
        try {
            parsed = JSON.readValue(body, JSON_MAP);
        } catch (JacksonException e) {
            throw new KindCodecException("Invalid JSON: " + e.getOriginalMessage(), e);
        }
        if (parsed == null) throw new KindCodecException("Top-level JSON must be an object");
        return KindHeaderCodec.unwrapJsonMeta(parsed);
    }

    // ── Serialize ─────────────────────────────────────────────────

    private static String serializeJson(ScribbleSheet sheet) {
        Map<String, Object> wrapped = KindHeaderCodec.wrapJsonMeta(KIND, buildBody(sheet));
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(wrapped) + "\n";
        } catch (JacksonException e) {
            throw new KindCodecException("Failed to write JSON: " + e.getOriginalMessage(), e);
        }
    }

    private static Map<String, Object> buildBody(ScribbleSheet sheet) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (sheet.title() != null) body.put("title", sheet.title());
        // Flags serialize only on deviation — ordinary sheets never carry them.
        if (!sheet.enabled()) body.put("enabled", false);
        if (sheet.defaultSheet()) body.put("default", true);

        FlowMap size = new FlowMap();
        size.put("w", sheet.size().w());
        size.put("h", sheet.size().h());

        List<Object> strokes = new ArrayList<>();
        for (ScribbleStroke stroke : sheet.strokes()) strokes.add(strokeToMap(stroke));

        Map<String, Object> scribble = new LinkedHashMap<>();
        scribble.put("size", size);
        scribble.put("strokes", strokes);
        body.put("scribble", scribble);
        return body;
    }

    private static String dumpYaml(Map<String, Object> body) {
        Map<String, Object> wrapped = KindHeaderCodec.wrapJsonMeta(KIND, body);
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setIndent(2);
        // Flow sequences must never split — a stroke stays one line, however long.
        opts.setWidth(100);
        opts.setSplitLines(false);
        Yaml yaml = new Yaml(new FlowRepresenter(opts), opts);
        return yaml.dump(wrapped);
    }

    // ── Stroke map ↔ model ────────────────────────────────────────

    /**
     * One stroke from its wire map. Returns {@code null} for an unusable
     * stroke (no parseable points) — the caller drops it and the validator
     * reports the index.
     */
    public static @Nullable ScribbleStroke strokeFromMap(Map<String, Object> raw) {
        List<ScribblePoint> points = new ArrayList<>();
        if (raw.get("p") instanceof List<?> list) {
            for (Object o : list) {
                ScribblePoint point = pointFromRaw(o);
                if (point != null) points.add(point);
            }
        }
        if (points.isEmpty()) return null;
        ScribbleStroke.Tool tool = ScribbleStroke.Tool.parse(str(raw, "tool"), ScribbleStroke.Tool.PEN);
        ScribbleStroke.Width width = ScribbleStroke.Width.parse(str(raw, "w"), ScribbleStroke.Width.M);
        return new ScribbleStroke(tool, paletteIndex(str(raw, "c")), width, points);
    }

    public static Map<String, Object> strokeToMap(ScribbleStroke stroke) {
        FlowMap m = new FlowMap();
        m.put("tool", stroke.tool().wire());
        m.put("c", stroke.color());
        m.put("w", stroke.width().wire());
        FlowList p = new FlowList();
        for (ScribblePoint point : stroke.points()) {
            FlowList coords = new FlowList();
            coords.add(point.x());
            coords.add(point.y());
            coords.add(roundPressure(point.pressure()));
            p.add(coords);
        }
        m.put("p", p);
        return m;
    }

    private static @Nullable ScribblePoint pointFromRaw(@Nullable Object raw) {
        if (!(raw instanceof List<?> list) || list.size() < 2) return null;
        Double x = dblCoerce(list.get(0));
        Double y = dblCoerce(list.get(1));
        if (x == null || y == null) return null;
        double pressure = DEFAULT_PRESSURE;
        if (list.size() > 2) {
            Double p = dblCoerce(list.get(2));
            if (p != null) pressure = p;
        }
        if (pressure < 0) pressure = 0;
        if (pressure > 1) pressure = 1;
        return new ScribblePoint(x.intValue(), y.intValue(), pressure);
    }

    private static ScribbleSize sizeFromMap(@Nullable Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return ScribbleSize.a4Portrait();
        ScribbleSize size = new ScribbleSize(intCoerce(map.get("w"), -1), intCoerce(map.get("h"), -1));
        return size.isPositive() ? size : ScribbleSize.a4Portrait();
    }

    /** Palette color index {@code "1"}–{@code "4"}; anything else falls back to {@code "1"}. */
    private static String paletteIndex(@Nullable String raw) {
        if (raw == null) return "1";
        String trimmed = raw.trim();
        return switch (trimmed) {
            case "1", "2", "3", "4" -> trimmed;
            default -> "1";
        };
    }

    private static double roundPressure(double pressure) {
        return Math.round(pressure * 10.0) / 10.0;
    }

    // ── Coercion helpers ──────────────────────────────────────────

    private static @Nullable String str(Map<String, Object> raw, String key) {
        Object v = raw.get(key);
        if (v instanceof String s && !s.isBlank()) return s.trim();
        return null;
    }

    private static @Nullable Double dblCoerce(@Nullable Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                /* fall through */
            }
        }
        return null;
    }

    private static int intCoerce(@Nullable Object o, int fallback) {
        Double d = dblCoerce(o);
        return d == null ? fallback : d.intValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(@Nullable Object raw) {
        if (!(raw instanceof Map<?, ?> mm)) return new LinkedHashMap<>();
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : mm.entrySet()) {
            if (e.getKey() != null) m.put(e.getKey().toString(), e.getValue());
        }
        return m;
    }

    private static List<Map<String, Object>> mapList(@Nullable Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        for (Object o : list) {
            if (o instanceof Map<?, ?>) out.add(asMap(o));
        }
        return out;
    }

    // ── Flow-style markers for the YAML dumper ────────────────────

    /**
     * Dumper marker types: plain collection subclasses that
     * {@link FlowRepresenter} renders inline (flow style), so a stroke is
     * one YAML line instead of three lines per point.
     */
    static final class FlowMap extends LinkedHashMap<String, Object> {}

    static final class FlowList extends ArrayList<Object> {}

    private static final class FlowRepresenter extends Representer {

        FlowRepresenter(DumperOptions options) {
            super(options);
            representers.put(
                    FlowMap.class, data -> representMapping(Tag.MAP, (Map<?, ?>) data, DumperOptions.FlowStyle.FLOW));
            representers.put(
                    FlowList.class,
                    data -> representSequence(Tag.SEQ, (Iterable<?>) data, DumperOptions.FlowStyle.FLOW));
        }
    }
}
