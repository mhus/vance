package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.tools.Tool;
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
public class GraphFindNodeTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("query"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("query", Map.of("type", "string",
                "description", "Substring to look for in node id or label (case-insensitive)."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "graph_find_node"; }
    @Override public String description() {
        return "Find nodes whose id or label contains the query (case-insensitive).";
    }
    @Override public boolean primary() { return false; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.requireKind(
                support.requireInline(support.loadDocument(params, ctx)), "graph");
        String query = KindToolSupport.requireString(params, "query").toLowerCase();
        GraphDocument g = GraphCodec.parse(doc.getInlineText(), doc.getMimeType());
        List<Map<String, Object>> matches = new ArrayList<>();
        for (GraphNode n : g.nodes()) {
            boolean hit = n.id().toLowerCase().contains(query)
                    || (n.label() != null && n.label().toLowerCase().contains(query));
            if (!hit) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n.id());
            if (n.label() != null) m.put("label", n.label());
            matches.add(m);
        }
        return Map.of("documentId", doc.getId(),
                "matchCount", matches.size(),
                "matches", matches);
    }
}
