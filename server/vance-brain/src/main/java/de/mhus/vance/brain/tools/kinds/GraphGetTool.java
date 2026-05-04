package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.kind.GraphCodec;
import de.mhus.vance.shared.document.kind.GraphDocument;
import de.mhus.vance.shared.document.kind.GraphEdge;
import de.mhus.vance.shared.document.kind.GraphNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GraphGetTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", KindToolSupport.documentSelectorProperties(),
            "required", List.of());

    private final KindToolSupport support;

    @Override public String name() { return "graph_get"; }
    @Override public String description() {
        return "Read a `kind: graph` document. Returns the directed flag, all nodes (id/label/color/position), and all edges (source/target/label/color).";
    }
    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("kind-graph", "eddie"); }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.requireKind(
                support.requireInline(support.loadDocument(params, ctx)), "graph");
        GraphDocument g = GraphCodec.parse(doc.getInlineText(), doc.getMimeType());
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (GraphNode n : g.nodes()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n.id());
            if (n.label() != null) m.put("label", n.label());
            if (n.color() != null) m.put("color", n.color());
            if (n.position() != null) m.put("position", Map.of("x", n.position().x(), "y", n.position().y()));
            nodes.add(m);
        }
        List<Map<String, Object>> edges = new ArrayList<>();
        for (GraphEdge e : g.edges()) {
            Map<String, Object> m = new LinkedHashMap<>();
            if (e.id() != null) m.put("id", e.id());
            m.put("source", e.source());
            m.put("target", e.target());
            if (e.label() != null) m.put("label", e.label());
            if (e.color() != null) m.put("color", e.color());
            edges.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documentId", doc.getId());
        out.put("directed", g.graph().directed());
        out.put("nodeCount", nodes.size());
        out.put("edgeCount", edges.size());
        out.put("nodes", nodes);
        out.put("edges", edges);
        return out;
    }
}
