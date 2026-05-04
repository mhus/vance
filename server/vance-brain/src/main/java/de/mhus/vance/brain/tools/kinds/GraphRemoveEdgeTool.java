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
public class GraphRemoveEdgeTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("source", "target"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("source", Map.of("type", "string", "description", "Source node id of the edge to remove."));
        p.put("target", Map.of("type", "string", "description", "Target node id of the edge to remove."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "graph_remove_edge"; }
    @Override public String description() {
        return "Remove the edge between source and target from a `kind: graph` document. "
                + "Errors if no such edge exists.";
    }
    @Override public boolean primary() { return false; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.requireKind(
                support.requireInline(support.loadDocument(params, ctx)), "graph");
        String source = KindToolSupport.requireString(params, "source");
        String target = KindToolSupport.requireString(params, "target");
        GraphDocument g = GraphCodec.parse(doc.getInlineText(), doc.getMimeType());
        List<GraphEdge> edges = new ArrayList<>(g.edges());
        boolean removed = edges.removeIf(e -> e.source().equals(source) && e.target().equals(target));
        if (!removed) {
            throw new ToolException("No edge from '" + source + "' to '" + target + "'");
        }
        GraphDocument updated = new GraphDocument(g.kind(), g.graph(), g.nodes(), edges, g.extra());
        support.writeBody(doc, GraphCodec.serialize(updated, doc.getMimeType()), ctx);
        return Map.of("documentId", doc.getId(),
                "removedSource", source,
                "removedTarget", target,
                "edgeCount", edges.size());
    }
}
