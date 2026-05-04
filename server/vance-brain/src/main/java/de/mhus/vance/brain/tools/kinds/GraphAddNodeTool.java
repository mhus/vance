package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.kind.GraphCodec;
import de.mhus.vance.shared.document.kind.GraphDocument;
import de.mhus.vance.shared.document.kind.GraphNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GraphAddNodeTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("id"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("id", Map.of("type", "string", "description", "Unique node id within the document."));
        p.put("label", Map.of("type", "string", "description", "Optional display label."));
        p.put("color", Map.of("type", "string", "description", "Optional HTML hex color."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "graph_add_node"; }
    @Override public String description() {
        return "Add a node with the given unique id (and optional label/color) to a `kind: graph` document.";
    }
    @Override public boolean primary() { return false; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.requireKind(
                support.requireInline(support.loadDocument(params, ctx)), "graph");
        String id = KindToolSupport.requireString(params, "id");
        String label = KindToolSupport.paramString(params, "label");
        String color = KindToolSupport.paramString(params, "color");

        GraphDocument g = GraphCodec.parse(doc.getInlineText(), doc.getMimeType());
        for (GraphNode existing : g.nodes()) {
            if (existing.id().equals(id)) {
                throw new ToolException("Node id '" + id + "' already exists");
            }
        }
        List<GraphNode> nodes = new ArrayList<>(g.nodes());
        nodes.add(new GraphNode(id, label, color, null, new LinkedHashMap<>()));
        GraphDocument updated = new GraphDocument(g.kind(), g.graph(), nodes, g.edges(), g.extra());
        support.writeBody(doc, GraphCodec.serialize(updated, doc.getMimeType()), ctx);
        return Map.of("documentId", doc.getId(),
                "addedNode", id,
                "nodeCount", nodes.size());
    }
}
