package de.mhus.vance.addon.brain.canvas;

import de.mhus.vance.addon.brain.canvas.model.CanvasDocument;
import de.mhus.vance.addon.brain.canvas.model.CanvasEdge;
import de.mhus.vance.addon.brain.canvas.model.CanvasGraph;
import de.mhus.vance.addon.brain.canvas.model.CanvasNode;
import de.mhus.vance.shared.document.kind.KindCodecException;
import de.mhus.vance.shared.document.kind.KindHeaderCodec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Parser and serialiser for {@code kind: canvas} document bodies. YAML
 * is canonical, JSON is a 1:1 dual — both round-trip through the same
 * typed {@link CanvasDocument} model. Markdown is not supported (a
 * spatial graph is not prose); the codec throws {@link KindCodecException}.
 *
 * <p>Stateless utility — the {@code fromMap} / {@code toMap} node and
 * edge helpers are shared with the {@code canvas_*} tools so LLM param
 * maps and on-disk maps use one grammar.
 */
public final class CanvasCodec {

    public static final String KIND = "canvas";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> JSON_MAP =
            new TypeReference<>() {};

    private CanvasCodec() {
        // utility class
    }

    // ── Entry points ──────────────────────────────────────────────

    public static CanvasDocument parse(String body, @Nullable String mimeType) {
        if (isJson(mimeType)) return parseFlat(parseJson(body));
        if (isYaml(mimeType)) return parseFlat(KindHeaderCodec.parseYamlBody(body));
        throw new KindCodecException("Unsupported mime type for canvas: " + mimeType);
    }

    public static String serialize(CanvasDocument doc, @Nullable String mimeType) {
        if (isJson(mimeType)) return serializeJson(doc);
        if (isYaml(mimeType)) return KindHeaderCodec.dumpYamlBody(KIND, buildBody(doc));
        throw new KindCodecException("Unsupported mime type for canvas: " + mimeType);
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

    private static CanvasDocument parseFlat(Map<String, Object> top) {
        String title = str(top, "title");
        String description = str(top, "description");
        Map<String, Object> canvas = asMap(top.get("canvas"));

        List<CanvasNode> nodes = new ArrayList<>();
        for (Map<String, Object> raw : mapList(canvas.get("nodes"))) {
            nodes.add(nodeFromMap(raw));
        }
        List<CanvasEdge> edges = new ArrayList<>();
        for (Map<String, Object> raw : mapList(canvas.get("edges"))) {
            edges.add(edgeFromMap(raw));
        }
        return new CanvasDocument(title, description, new CanvasGraph(nodes, edges));
    }

    // ── Serialize ─────────────────────────────────────────────────

    private static String serializeJson(CanvasDocument doc) {
        Map<String, Object> wrapped = KindHeaderCodec.wrapJsonMeta(KIND, buildBody(doc));
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(wrapped) + "\n";
        } catch (JacksonException e) {
            throw new KindCodecException("Failed to write JSON: " + e.getOriginalMessage(), e);
        }
    }

    private static Map<String, Object> buildBody(CanvasDocument doc) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (doc.title() != null) body.put("title", doc.title());
        if (doc.description() != null) body.put("description", doc.description());

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (CanvasNode n : doc.graph().nodes()) nodes.add(nodeToMap(n));
        List<Map<String, Object>> edges = new ArrayList<>();
        for (CanvasEdge e : doc.graph().edges()) edges.add(edgeToMap(e));

        Map<String, Object> canvas = new LinkedHashMap<>();
        canvas.put("nodes", nodes);
        canvas.put("edges", edges);
        body.put("canvas", canvas);
        return body;
    }

    // ── Node map ↔ model ──────────────────────────────────────────

    public static CanvasNode nodeFromMap(Map<String, Object> raw) {
        String type = str(raw, "type");
        if (type == null) throw new KindCodecException("canvas node.type is required");
        String id = strOrEmpty(raw, "id");
        double x = dbl(raw, "x", 0);
        double y = dbl(raw, "y", 0);
        double w = dbl(raw, "w", DEFAULT_W);
        double h = dbl(raw, "h", DEFAULT_H);
        String color = str(raw, "color");
        Integer z = intOrNull(raw.get("z"));
        String parent = str(raw, "parent");
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "text" -> new CanvasNode.Text(id, x, y, w, h, color, z, parent,
                    strOrEmpty(raw, "text"),
                    boolOrNull(raw.get("bold")), boolOrNull(raw.get("italic")),
                    str(raw, "fontSize"), str(raw, "textColor"), str(raw, "author"));
            case "doc" -> {
                String ref = str(raw, "ref");
                if (ref == null) throw new KindCodecException("canvas doc-node requires `ref`");
                yield new CanvasNode.Doc(id, x, y, w, h, color, z, parent, ref);
            }
            case "link" -> {
                String href = str(raw, "href");
                if (href == null) throw new KindCodecException("canvas link-node requires `href`");
                yield new CanvasNode.Link(id, x, y, w, h, color, z, parent, href, str(raw, "title"));
            }
            case "group" -> new CanvasNode.Group(id, x, y, w, h, color, z, parent, str(raw, "label"));
            default -> throw new KindCodecException("Unknown canvas node.type='" + type + "'");
        };
    }

    public static Map<String, Object> nodeToMap(CanvasNode n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.id());
        m.put("type", n.type());
        m.put("x", n.x());
        m.put("y", n.y());
        m.put("w", n.w());
        m.put("h", n.h());
        if (n.color() != null) m.put("color", n.color());
        if (n.z() != null) m.put("z", n.z());
        if (n.parent() != null) m.put("parent", n.parent());
        switch (n) {
            case CanvasNode.Text t -> {
                m.put("text", t.text());
                if (Boolean.TRUE.equals(t.bold())) m.put("bold", true);
                if (Boolean.TRUE.equals(t.italic())) m.put("italic", true);
                if (t.fontSize() != null) m.put("fontSize", t.fontSize());
                if (t.textColor() != null) m.put("textColor", t.textColor());
                if (t.author() != null) m.put("author", t.author());
            }
            case CanvasNode.Doc d -> m.put("ref", d.ref());
            case CanvasNode.Link l -> {
                m.put("href", l.href());
                if (l.title() != null) m.put("title", l.title());
            }
            case CanvasNode.Group g -> {
                if (g.label() != null) m.put("label", g.label());
            }
        }
        return m;
    }

    // ── Edge map ↔ model ──────────────────────────────────────────

    public static CanvasEdge edgeFromMap(Map<String, Object> raw) {
        String id = strOrEmpty(raw, "id");
        String from = str(raw, "from");
        String to = str(raw, "to");
        if (from == null || to == null) {
            throw new KindCodecException("canvas edge requires `from` and `to`");
        }
        return new CanvasEdge(
                id, from, to,
                CanvasEdge.Side.parse(str(raw, "fromSide")),
                CanvasEdge.Side.parse(str(raw, "toSide")),
                CanvasEdge.End.parse(str(raw, "fromEnd"), CanvasEdge.End.NONE),
                CanvasEdge.End.parse(str(raw, "toEnd"), CanvasEdge.End.ARROW),
                str(raw, "label"),
                str(raw, "color"),
                boolOrNull(raw.get("dashed")),
                dblOrNull(raw.get("width")));
    }

    public static Map<String, Object> edgeToMap(CanvasEdge e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("from", e.from());
        m.put("to", e.to());
        if (e.fromSide() != null) m.put("fromSide", e.fromSide().wire());
        if (e.toSide() != null) m.put("toSide", e.toSide().wire());
        // Only emit ends that deviate from the default (fromEnd=none, toEnd=arrow)
        // to keep the on-disk graph compact.
        if (e.fromEnd() != CanvasEdge.End.NONE) m.put("fromEnd", e.fromEnd().wire());
        if (e.toEnd() != CanvasEdge.End.ARROW) m.put("toEnd", e.toEnd().wire());
        if (e.label() != null) m.put("label", e.label());
        if (e.color() != null) m.put("color", e.color());
        if (Boolean.TRUE.equals(e.dashed())) m.put("dashed", true);
        if (e.width() != null) m.put("width", e.width());
        return m;
    }

    // ── Coercion helpers ──────────────────────────────────────────

    private static final double DEFAULT_W = 240;
    private static final double DEFAULT_H = 120;

    private static @Nullable String str(Map<String, Object> raw, String key) {
        Object v = raw.get(key);
        if (v instanceof String s && !s.isBlank()) return s.trim();
        return null;
    }

    private static String strOrEmpty(Map<String, Object> raw, String key) {
        String s = str(raw, key);
        return s == null ? "" : s;
    }

    private static double dbl(Map<String, Object> raw, String key, double fallback) {
        Object v = raw.get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                /* fall through */
            }
        }
        return fallback;
    }

    private static @Nullable Boolean boolOrNull(@Nullable Object o) {
        if (o instanceof Boolean b) return b;
        if (o instanceof String s && !s.isBlank()) return Boolean.parseBoolean(s.trim());
        return null;
    }

    private static @Nullable Double dblOrNull(@Nullable Object o) {
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

    private static @Nullable Integer intOrNull(@Nullable Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                /* fall through */
            }
        }
        return null;
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
}
