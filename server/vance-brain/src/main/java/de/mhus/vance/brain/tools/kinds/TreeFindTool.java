package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.kind.MindmapCodec;
import de.mhus.vance.shared.document.kind.TreeCodec;
import de.mhus.vance.shared.document.kind.TreeDocument;
import de.mhus.vance.shared.document.kind.TreeItem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Find paths to items whose text contains a query string. */
@Component
@RequiredArgsConstructor
public class TreeFindTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("query"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("query", Map.of("type", "string",
                "description", "Substring to search for in item text (case-insensitive)."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "tree_find"; }

    @Override public String description() {
        return "Find paths to items whose text contains the query (case-insensitive). "
                + "Each match returns the comma-separated index path and the matching text.";
    }

    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("kind-tree", "eddie"); }


    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.requireKind(
                support.requireInline(support.loadDocument(params, ctx)), "tree", "mindmap");
        String query = KindToolSupport.requireString(params, "query");
        TreeDocument tree = "mindmap".equals(doc.getKind())
                ? MindmapCodec.parse(doc.getInlineText(), doc.getMimeType())
                : TreeCodec.parse(doc.getInlineText(), doc.getMimeType());
        List<int[]> paths = TreePath.findByText(tree, query);
        List<Map<String, Object>> matches = new ArrayList<>(paths.size());
        for (int[] path : paths) {
            TreeItem item = TreePath.at(tree, path);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", TreePath.format(path));
            m.put("text", item.text());
            matches.add(m);
        }
        return Map.of("documentId", doc.getId(),
                "matchCount", matches.size(),
                "matches", matches);
    }
}
