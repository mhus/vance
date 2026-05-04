package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.kind.MindmapCodec;
import de.mhus.vance.shared.document.kind.TreeCodec;
import de.mhus.vance.shared.document.kind.TreeDocument;
import de.mhus.vance.shared.document.kind.TreeItem;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Remove the subtree at the given path. */
@Component
@RequiredArgsConstructor
public class TreeRemoveTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("path"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("path", Map.of("type", "string",
                "description", "Comma-separated index path of the item to remove."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "tree_remove"; }

    @Override public String description() {
        return "Remove the item at `path` together with its entire subtree. Use `path` carefully — "
                + "subsequent sibling indices shift down by one.";
    }

    @Override public boolean primary() { return false; }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.requireKind(
                support.requireInline(support.loadDocument(params, ctx)), "tree", "mindmap");
        int[] path = TreePath.parse(KindToolSupport.requireString(params, "path"));

        TreeDocument tree = "mindmap".equals(doc.getKind())
                ? MindmapCodec.parse(doc.getInlineText(), doc.getMimeType())
                : TreeCodec.parse(doc.getInlineText(), doc.getMimeType());
        TreeItem removed = TreePath.at(tree, path);
        TreeDocument updated = TreePath.removeAt(tree, path);
        String body = "mindmap".equals(doc.getKind())
                ? MindmapCodec.serialize(updated, doc.getMimeType())
                : TreeCodec.serialize(updated, doc.getMimeType());
        support.writeBody(doc, body, ctx);
        return Map.of("documentId", doc.getId(),
                "removedText", removed.text(),
                "removedChildCount", removed.children().size());
    }
}
