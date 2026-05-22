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
 * Parser and serialiser for {@code kind: chart} document bodies —
 * mirrors {@code chartCodec.ts}. JSON and YAML only; markdown is
 * intentionally not supported.
 *
 * <p>One discriminator ({@link ChartType}) covers all chart kinds; the
 * per-type data-point shape is validated on read, with malformed
 * points silently dropped and empty series elided (see
 * {@code specification/doc-kind-chart.md} §2.5 + §3.1).
 *
 * <p>Each data point survives round-trip in the form it arrived in —
 * an object-form point ({@code {x: …, y: …}}) is re-emitted as an
 * object, a tuple-form point ({@code [x, y]}) as a tuple. The codec
 * does not normalise between the two.
 */
public final class ChartCodec {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> JSON_MAP =
            new TypeReference<>() {};

    private ChartCodec() {
        // utility class
    }

    public static ChartDocument parse(String body, @Nullable String mimeType) {
        if (isJson(mimeType)) return parseJson(body);
        if (isYaml(mimeType)) return parseYaml(body);
        throw new KindCodecException("Unsupported mime type for chart: " + mimeType);
    }

    public static String serialize(ChartDocument doc, @Nullable String mimeType) {
        if (isJson(mimeType)) return serializeJson(doc);
        if (isYaml(mimeType)) return serializeYaml(doc);
        throw new KindCodecException("Unsupported mime type for chart: " + mimeType);
    }

    public static boolean supports(@Nullable String mimeType) {
        return isJson(mimeType) || isYaml(mimeType);
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

    private static ChartDocument parseJson(String body) {
        if (body.isBlank()) return ChartDocument.empty();
        Map<String, Object> parsed;
        try {
            parsed = JSON.readValue(body, JSON_MAP);
        } catch (JacksonException e) {
            throw new KindCodecException("Invalid JSON: " + e.getOriginalMessage(), e);
        }
        if (parsed == null) throw new KindCodecException("Top-level JSON must be an object");
        return promoteToDocument(KindHeaderCodec.unwrapJsonMeta(parsed));
    }

    private static ChartDocument parseYaml(String body) {
        if (body.isBlank()) return ChartDocument.empty();
        return promoteToDocument(KindHeaderCodec.parseYamlBody(body));
    }

    private static String serializeJson(ChartDocument doc) {
        Map<String, Object> wrapped = KindHeaderCodec.wrapJsonMeta(canonicalKind(doc), buildBody(doc));
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(wrapped) + "\n";
        } catch (JacksonException e) {
            throw new KindCodecException("Failed to write JSON: " + e.getOriginalMessage(), e);
        }
    }

    private static String serializeYaml(ChartDocument doc) {
        return KindHeaderCodec.dumpYamlBody(canonicalKind(doc), buildBody(doc));
    }

    // ── Promotion ──────────────────────────────────────────────────

    private static ChartDocument promoteToDocument(Map<String, Object> obj) {
        Object kindRaw = obj.get("kind");
        String kind = (kindRaw instanceof String s) ? s : "";

        ChartHeader header = promoteHeader(obj.get("chart"));
        ChartType type = header.chartType();

        // Axes are silently dropped for pie / donut (no axis semantics)
        // — keeping them around would round-trip noise the spec calls
        // out as "ignored" in §2.5.
        ChartAxis xAxis = type.isNamedValueShaped()
                ? null
                : promoteAxis(obj.get("xAxis"), AxisType.CATEGORY);
        ChartAxis yAxis = type.isNamedValueShaped()
                ? null
                : promoteAxis(obj.get("yAxis"), AxisType.VALUE);

        List<ChartSeries> series = promoteSeries(obj.get("series"), type);
        Map<String, Object> override = promoteOverride(obj.get("echartsOptionOverride"));

        Map<String, Object> extra = new LinkedHashMap<>(obj);
        extra.remove("kind");
        extra.remove("chart");
        extra.remove("xAxis");
        extra.remove("yAxis");
        extra.remove("series");
        extra.remove("echartsOptionOverride");

        return new ChartDocument(
                kind.isEmpty() ? "chart" : kind,
                header, xAxis, yAxis, series, override, extra);
    }

    private static ChartHeader promoteHeader(@Nullable Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new KindCodecException("Missing or non-object `chart` block");
        }
        Object typeRaw = map.get("chartType");
        if (!(typeRaw instanceof String typeStr)) {
            throw new KindCodecException("Missing or non-string `chart.chartType`");
        }
        ChartType type = ChartType.fromWire(typeStr);
        if (type == null) {
            throw new KindCodecException("Unknown chartType: " + typeStr);
        }
        String title = (map.get("title") instanceof String s1) ? s1 : null;
        String subtitle = (map.get("subtitle") instanceof String s2) ? s2 : null;
        boolean legend = !(map.get("legend") instanceof Boolean lb) || lb;
        boolean stacked = map.get("stacked") instanceof Boolean sb && sb;
        boolean smooth = map.get("smooth") instanceof Boolean smb && smb;
        return new ChartHeader(type, title, subtitle, legend, stacked, smooth);
    }

    private static ChartAxis promoteAxis(@Nullable Object raw, AxisType defaultType) {
        if (!(raw instanceof Map<?, ?> map)) {
            // No axis block on disk → default axis for this side.
            return new ChartAxis(defaultType, null, null, null, new ArrayList<>());
        }
        AxisType type = defaultType;
        if (map.get("type") instanceof String ts) {
            AxisType parsed = AxisType.fromWire(ts);
            if (parsed != null) type = parsed;
        }
        String label = (map.get("label") instanceof String ls) ? ls : null;
        Double min = (map.get("min") instanceof Number mn) ? mn.doubleValue() : null;
        Double max = (map.get("max") instanceof Number mx) ? mx.doubleValue() : null;
        List<String> categories = new ArrayList<>();
        if (map.get("categories") instanceof List<?> catList) {
            for (Object c : catList) {
                if (c instanceof String cs) categories.add(cs);
                else if (c instanceof Number cn) categories.add(cn.toString());
            }
        }
        return new ChartAxis(type, label, min, max, categories);
    }

    private static List<ChartSeries> promoteSeries(@Nullable Object raw, ChartType type) {
        List<ChartSeries> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        for (Object r : list) {
            if (!(r instanceof Map<?, ?> map)) continue;
            Object nameRaw = map.get("name");
            if (!(nameRaw instanceof String name) || name.isBlank()) continue;
            String color = (map.get("color") instanceof String cs && !cs.isEmpty()) ? cs : null;

            List<Object> data = new ArrayList<>();
            if (map.get("data") instanceof List<?> dataList) {
                for (Object pt : dataList) {
                    if (isValidDataPoint(pt, type)) data.add(pt);
                }
            }
            // Series with zero valid points after shape-filtering are
            // elided — they'd render as an empty legend entry with no
            // bars/lines, which is just noise.
            if (data.isEmpty()) continue;

            Map<String, Object> extra = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!(e.getKey() instanceof String key)) continue;
                if ("name".equals(key) || "color".equals(key) || "data".equals(key)) continue;
                extra.put(key, e.getValue());
            }
            out.add(new ChartSeries(name, color, data, extra));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Map<String, Object> promoteOverride(@Nullable Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getKey() instanceof String k) out.put(k, e.getValue());
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * Per-point shape validation. Tuple-form ({@code List}) requires the
     * minimum number of elements; object-form ({@code Map}) requires the
     * keys the renderer needs to make sense of the point. Wrong types
     * inside (e.g. {@code y} as a string) survive — the renderer will
     * coerce or drop them; the codec is only the structural gate.
     */
    private static boolean isValidDataPoint(@Nullable Object point, ChartType type) {
        if (point instanceof Map<?, ?> map) {
            return switch (type) {
                case LINE, BAR, AREA, SCATTER -> map.containsKey("x") && map.containsKey("y");
                case PIE, DONUT -> map.containsKey("name") && map.containsKey("value");
                case CANDLESTICK -> map.containsKey("t") && map.containsKey("o")
                        && map.containsKey("h") && map.containsKey("l") && map.containsKey("c");
                case HEATMAP -> map.containsKey("x") && map.containsKey("y") && map.containsKey("v");
            };
        }
        if (point instanceof List<?> list) {
            // pie/donut don't have a tuple form — there's no canonical
            // ordering between name and value when both could be either
            // string or number depending on the chart.
            return switch (type) {
                case LINE, BAR, AREA, SCATTER -> list.size() >= 2;
                case PIE, DONUT -> false;
                case CANDLESTICK -> list.size() >= 5;
                case HEATMAP -> list.size() >= 3;
            };
        }
        return false;
    }

    // ── Body builder ───────────────────────────────────────────────

    private static Map<String, Object> buildBody(ChartDocument doc) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chart", headerToMap(doc.chart()));
        if (doc.xAxis() != null) body.put("xAxis", axisToMap(doc.xAxis()));
        if (doc.yAxis() != null) body.put("yAxis", axisToMap(doc.yAxis()));
        body.put("series", seriesToList(doc.series()));
        if (doc.echartsOptionOverride() != null && !doc.echartsOptionOverride().isEmpty()) {
            body.put("echartsOptionOverride", doc.echartsOptionOverride());
        }
        for (Map.Entry<String, Object> e : doc.extra().entrySet()) {
            if (!body.containsKey(e.getKey())) body.put(e.getKey(), e.getValue());
        }
        return body;
    }

    private static Map<String, Object> headerToMap(ChartHeader h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("chartType", h.chartType().wire());
        if (h.title() != null) m.put("title", h.title());
        if (h.subtitle() != null) m.put("subtitle", h.subtitle());
        // legend defaults to true — emit only when explicitly false so
        // the on-disk shape stays compact.
        if (!h.legend()) m.put("legend", false);
        if (h.stacked()) m.put("stacked", true);
        if (h.smooth()) m.put("smooth", true);
        return m;
    }

    private static Map<String, Object> axisToMap(ChartAxis a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", a.type().wire());
        if (a.label() != null) m.put("label", a.label());
        if (a.min() != null) m.put("min", a.min());
        if (a.max() != null) m.put("max", a.max());
        if (!a.categories().isEmpty()) m.put("categories", new ArrayList<>(a.categories()));
        return m;
    }

    private static List<Map<String, Object>> seriesToList(List<ChartSeries> series) {
        List<Map<String, Object>> out = new ArrayList<>(series.size());
        for (ChartSeries s : series) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.name());
            if (s.color() != null) m.put("color", s.color());
            m.put("data", new ArrayList<>(s.data()));
            for (Map.Entry<String, Object> ex : s.extra().entrySet()) {
                if (!m.containsKey(ex.getKey())) m.put(ex.getKey(), ex.getValue());
            }
            out.add(m);
        }
        return out;
    }

    private static String canonicalKind(ChartDocument doc) {
        return (doc.kind() == null || doc.kind().isBlank()) ? "chart" : doc.kind();
    }
}
