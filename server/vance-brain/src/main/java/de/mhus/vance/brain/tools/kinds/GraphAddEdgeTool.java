package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.kind.GraphCodec;
import de.mhus.vance.shared.document.kind.GraphDocument;
import de.mhus.vance.shared.document.kind.GraphEdge;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GraphAddEdgeTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("source", "target"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("source", Map.of("type", "string", "description", "Source node id."));
        p.put("target", Map.of("type", "string", "description", "Target node id."));
        p.put("label", Map.of("type", "string", "description", "Optional edge label."));
        p.put("color", Map.of("type", "string", "description", "Optional HTML hex color."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "graph_add_edge"; }
    @Override public String description() {
        return "Add an edge between two nodes in a `kind: graph` document. Both source and target ids must already exist. "
                + "Duplicate edges (same source+target) are rejected.";
    }
    @Override public boolean primary() { return false; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.requireKind(
                support.requireInline(support.loadDocument(params, ctx)), "graph");
        String source = KindToolSupport.requireString(params, "source");
        String target = KindToolSupport.requireString(params, "target");
        String label = KindToolSupport.paramString(params, "label");
        String color = KindToolSupport.paramString(params, "color");
        GraphDocument g = GraphCodec.parse(doc.getInlineText(), doc.getMimeType());
        boolean srcOk = g.nodes().stream().anyMatch(n -> n.id().equals(source));
        boolean tgtOk = g.nodes().stream().anyMatch(n -> n.id().equals(target));
        if (!srcOk) throw new ToolException("Source node '" + source + "' does not exist");
        if (!tgtOk) throw new ToolException("Target node '" + target + "' does not exist");
        for (GraphEdge e : g.edges()) {
            if (e.source().equals(source) && e.target().equals(target)) {
                throw new ToolException("Edge " + source + " → " + target + " already exists");
            }
        }
        List<GraphEdge> edges = new ArrayList<>(g.edges());
        edges.add(new GraphEdge(null, source, target, label, color, new LinkedHashMap<>()));
        GraphDocument updated = new GraphDocument(g.kind(), g.graph(), g.nodes(), edges, g.extra());
        support.writeBody(doc, GraphCodec.serialize(updated, doc.getMimeType()), ctx);
        return Map.of("documentId", doc.getId(),
                "source", source,
                "target", target,
                "edgeCount", edges.size());
    }
}
