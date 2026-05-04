package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
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
public class GraphRemoveNodeTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("id"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("id", Map.of("type", "string", "description", "Node id to remove."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "graph_remove_node"; }
    @Override public String description() {
        return "Remove the node with the given id from a `kind: graph` document. "
                + "All incident edges (incoming and outgoing) are dropped too.";
    }
    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("kind-graph", "eddie"); }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.requireKind(
                support.requireInline(support.loadDocument(params, ctx)), "graph");
        String id = KindToolSupport.requireString(params, "id");
        GraphDocument g = GraphCodec.parse(doc.getInlineText(), doc.getMimeType());
        boolean exists = g.nodes().stream().anyMatch(n -> n.id().equals(id));
        if (!exists) throw new ToolException("Node id '" + id + "' not found");
        List<GraphNode> nodes = new ArrayList<>(g.nodes());
        nodes.removeIf(n -> n.id().equals(id));
        List<GraphEdge> edges = new ArrayList<>(g.edges());
        int edgesBefore = edges.size();
        edges.removeIf(e -> e.source().equals(id) || e.target().equals(id));
        GraphDocument updated = new GraphDocument(g.kind(), g.graph(), nodes, edges, g.extra());
        support.writeBody(doc, GraphCodec.serialize(updated, doc.getMimeType()), ctx);
        return Map.of("documentId", doc.getId(),
                "removedNode", id,
                "removedIncidentEdges", edgesBefore - edges.size(),
                "nodeCount", nodes.size(),
                "edgeCount", edges.size());
    }
}
