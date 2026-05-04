package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.kind.GraphCodec;
import de.mhus.vance.shared.document.kind.GraphConfig;
import de.mhus.vance.shared.document.kind.GraphDocument;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GraphSetDirectedTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("directed"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("directed", Map.of("type", "boolean",
                "description", "true to render arrows on edges, false for plain lines."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "graph_set_directed"; }
    @Override public String description() {
        return "Set the document-level `directed` flag on a `kind: graph` document. "
                + "Affects rendering only; edges are unchanged.";
    }
    @Override public boolean primary() { return false; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.requireKind(
                support.requireInline(support.loadDocument(params, ctx)), "graph");
        Boolean directed = KindToolSupport.paramBoolean(params, "directed");
        if (directed == null) throw new ToolException("Missing or non-boolean parameter 'directed'");
        GraphDocument g = GraphCodec.parse(doc.getInlineText(), doc.getMimeType());
        GraphDocument updated = new GraphDocument(g.kind(), new GraphConfig(directed),
                g.nodes(), g.edges(), g.extra());
        support.writeBody(doc, GraphCodec.serialize(updated, doc.getMimeType()), ctx);
        return Map.of("documentId", doc.getId(), "directed", directed);
    }
}
