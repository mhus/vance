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
 * Parser and serialiser for {@code kind: graph} document bodies —
 * mirrors {@code graphCodec.ts}. JSON and YAML only; markdown is
 * intentionally not supported.
 *
 * <p>Reads both the canonical top-level {@code edges} array and the
 * legacy {@code node.edges: string[]} form (which gets lifted on
 * read; {@code mergeEdges} prefers explicit top-level entries on
 * collision). Writes only the canonical form.
 */
public final class GraphCodec {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> JSON_MAP =
            new TypeReference<>() {};

    private GraphCodec() {
        // utility class
    }

    public static GraphDocument parse(String body, @Nullable String mimeType) {
        if (isJson(mimeType)) return parseJson(body);
        if (isYaml(mimeType)) return parseYaml(body);
        throw new KindCodecException("Unsupported mime type for graph: " + mimeType);
    }

    public static String serialize(GraphDocument doc, @Nullable String mimeType) {
        if (isJson(mimeType)) return serializeJson(doc);
        if (isYaml(mimeType)) return serializeYaml(doc);
        throw new KindCodecException("Unsupported mime type for graph: " + mimeType);
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

    private static GraphDocument parseJson(String body) {
        if (body.isBlank()) return GraphDocument.empty();
        Map<String, Object> parsed;
        try {
            parsed = JSON.readValue(body, JSON_MAP);
        } catch (JacksonException e) {
            throw new KindCodecException("Invalid JSON: " + e.getOriginalMessage(), e);
        }
        if (parsed == null) throw new KindCodecException("Top-level JSON must be an object");
        return promoteToDocument(KindHeaderCodec.unwrapJsonMeta(parsed));
    }

    private static GraphDocument parseYaml(String body) {
        if (body.isBlank()) return GraphDocument.empty();
        return promoteToDocument(KindHeaderCodec.mergeYamlMultiDoc(body));
    }

    private static String serializeJson(GraphDocument doc) {
        Map<String, Object> wrapped = KindHeaderCodec.wrapJsonMeta(canonicalKind(doc), buildBody(doc));
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(wrapped) + "\n";
        } catch (JacksonException e) {
            throw new KindCodecException("Failed to write JSON: " + e.getOriginalMessage(), e);
        }
    }

    private static String serializeYaml(GraphDocument doc) {
        return KindHeaderCodec.dumpYamlMultiDoc(canonicalKind(doc), buildBody(doc));
    }

    // ── Promotion ──────────────────────────────────────────────────

    private static GraphDocument promoteToDocument(Map<String, Object> obj) {
        Object kindRaw = obj.get("kind");
        String kind = (kindRaw instanceof String s) ? s : "";
        GraphConfig graph = promoteConfig(obj.get("graph"));

        // Promote nodes first so legacy node.edges migration can
        // synthesise top-level edges referencing valid node ids.
        NodesPromote nodesP = promoteNodes(obj.get("nodes"));
        List<GraphEdge> explicitEdges = promoteEdges(obj.get("edges"));
        List<GraphEdge> edges = mergeEdges(explicitEdges, nodesP.legacyEdges);

        Map<String, Object> extra = new LinkedHashMap<>(obj);
        extra.remove("kind");
        extra.remove("graph");
        extra.remove("nodes");
        extra.remove("edges");
        return new GraphDocument(kind.isEmpty() ? "graph" : kind, graph, nodesP.nodes, edges, extra);
    }

    private static GraphConfig promoteConfig(@Nullable Object raw) {
        if (raw instanceof Map<?, ?> map && map.get("directed") instanceof Boolean b) {
            return new GraphConfig(b);
        }
        return GraphConfig.defaults();
    }

    private record NodesPromote(List<GraphNode> nodes, List<GraphEdge> legacyEdges) {}

    private static NodesPromote promoteNodes(@Nullable Object raw) {
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> legacyEdges = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return new NodesPromote(nodes, legacyEdges);
        Set<String> seen = new LinkedHashSet<>();
        for (Object r : list) {
            if (!(r instanceof Map<?, ?> map)) continue;
            Object idRaw = map.get("id");
            if (!(idRaw instanceof String idS)) continue;
            String id = idS.trim();
            if (id.isEmpty()) continue;
            if (seen.contains(id)) {
                throw new KindCodecException("Duplicate node id: " + id);
            }
            seen.add(id);

            String label = (map.get("label") instanceof String ls) ? ls : null;
            String color = (map.get("color") instanceof String cs && !cs.isEmpty()) ? cs : null;
            GraphPosition position = promotePosition(map.get("position"));

            // Legacy node.edges: lift to top-level edges.
            if (map.get("edges") instanceof List<?> le) {
                for (Object e : le) {
                    if (e instanceof String es && !es.trim().isEmpty()) {
                        legacyEdges.add(new GraphEdge(null, id, es.trim(),
                                null, null, new LinkedHashMap<>()));
                    }
                }
            }

            Map<String, Object> extra = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!(e.getKey() instanceof String key)) continue;
                if ("id".equals(key) || "label".equals(key) || "color".equals(key)
                        || "position".equals(key) || "edges".equals(key)) continue;
                extra.put(key, e.getValue());
            }
            nodes.add(new GraphNode(id, label, color, position, extra));
        }
        return new NodesPromote(nodes, legacyEdges);
    }

    private static List<GraphEdge> promoteEdges(@Nullable Object raw) {
        List<GraphEdge> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        for (Object r : list) {
            if (!(r instanceof Map<?, ?> map)) continue;
            Object srcRaw = map.get("source");
            Object tgtRaw = map.get("target");
            if (!(srcRaw instanceof String ss) || !(tgtRaw instanceof String ts)) continue;
            String source = ss.trim();
            String target = ts.trim();
            if (source.isEmpty() || target.isEmpty()) continue;
            String id = (map.get("id") instanceof String is && !is.trim().isEmpty()) ? is.trim() : null;
            String label = (map.get("label") instanceof String ls) ? ls : null;
            String color = (map.get("color") instanceof String cs && !cs.isEmpty()) ? cs : null;
            Map<String, Object> extra = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!(e.getKey() instanceof String key)) continue;
                if ("id".equals(key) || "source".equals(key) || "target".equals(key)
                        || "label".equals(key) || "color".equals(key)) continue;
                extra.put(key, e.getValue());
            }
            out.add(new GraphEdge(id, source, target, label, color, extra));
        }
        return out;
    }

    private static List<GraphEdge> mergeEdges(List<GraphEdge> explicit, List<GraphEdge> legacy) {
        if (legacy.isEmpty()) return explicit;
        Set<String> seen = new LinkedHashSet<>();
        for (GraphEdge e : explicit) seen.add(e.key());
        List<GraphEdge> out = new ArrayList<>(explicit);
        for (GraphEdge e : legacy) {
            String key = e.key();
            if (seen.contains(key)) continue;
            seen.add(key);
            out.add(e);
        }
        return out;
    }

    private static @Nullable GraphPosition promotePosition(@Nullable Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return null;
        Object xR = map.get("x");
        Object yR = map.get("y");
        if (!(xR instanceof Number xn) || !(yR instanceof Number yn)) return null;
        double x = xn.doubleValue();
        double y = yn.doubleValue();
        if (!Double.isFinite(x) || !Double.isFinite(y)) return null;
        return new GraphPosition(x, y);
    }

    // ── Body builder ───────────────────────────────────────────────

    private static Map<String, Object> buildBody(GraphDocument doc) {
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("directed", doc.graph().directed());
        body.put("graph", graph);
        body.put("nodes", nodesToList(doc.nodes()));
        body.put("edges", edgesToList(doc.edges()));
        for (Map.Entry<String, Object> e : doc.extra().entrySet()) {
            if (!body.containsKey(e.getKey())) body.put(e.getKey(), e.getValue());
        }
        return body;
    }

    private static List<Map<String, Object>> nodesToList(List<GraphNode> nodes) {
        List<Map<String, Object>> out = new ArrayList<>(nodes.size());
        for (GraphNode n : nodes) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n.id());
            if (n.label() != null) m.put("label", n.label());
            if (n.color() != null) m.put("color", n.color());
            if (n.position() != null) {
                Map<String, Object> pos = new LinkedHashMap<>();
                pos.put("x", n.position().x());
                pos.put("y", n.position().y());
                m.put("position", pos);
            }
            for (Map.Entry<String, Object> e : n.extra().entrySet()) {
                if (!m.containsKey(e.getKey())) m.put(e.getKey(), e.getValue());
            }
            out.add(m);
        }
        return out;
    }

    private static List<Map<String, Object>> edgesToList(List<GraphEdge> edges) {
        List<Map<String, Object>> out = new ArrayList<>(edges.size());
        for (GraphEdge e : edges) {
            Map<String, Object> m = new LinkedHashMap<>();
            if (e.id() != null) m.put("id", e.id());
            m.put("source", e.source());
            m.put("target", e.target());
            if (e.label() != null) m.put("label", e.label());
            if (e.color() != null) m.put("color", e.color());
            for (Map.Entry<String, Object> ex : e.extra().entrySet()) {
                if (!m.containsKey(ex.getKey())) m.put(ex.getKey(), ex.getValue());
            }
            out.add(m);
        }
        return out;
    }

    private static String canonicalKind(GraphDocument doc) {
        return (doc.kind() == null || doc.kind().isBlank()) ? "graph" : doc.kind();
    }
}
