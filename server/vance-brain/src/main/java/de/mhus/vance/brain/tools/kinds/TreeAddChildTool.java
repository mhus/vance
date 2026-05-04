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

/** Add a child to the item at the given path (or to the root if path is empty). */
@Component
@RequiredArgsConstructor
public class TreeAddChildTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("text"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("parentPath", Map.of("type", "string",
                "description", "Comma-separated index path of the parent item, or empty string "
                        + "to append to the tree root. Example: '0,2' = third child of first item."));
        p.put("position", Map.of("type", "integer",
                "description", "Insert position among siblings; -1 appends. Default: -1."));
        p.put("text", Map.of("type", "string", "description", "Text of the new node."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "tree_add_child"; }

    @Override public String description() {
        return "Add a child node under the item at `parentPath` (empty string for root level). "
                + "Returns the new node's path.";
    }

    @Override public boolean primary() { return false; }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.requireKind(
                support.requireInline(support.loadDocument(params, ctx)), "tree", "mindmap");
        int[] parentPath = TreePath.parse(KindToolSupport.paramString(params, "parentPath"));
        Integer pos = KindToolSupport.paramInt(params, "position");
        String text = KindToolSupport.requireRawString(params, "text");

        TreeDocument tree = "mindmap".equals(doc.getKind())
                ? MindmapCodec.parse(doc.getInlineText(), doc.getMimeType())
                : TreeCodec.parse(doc.getInlineText(), doc.getMimeType());
        TreeItem newItem = TreeItem.leaf(text);
        TreeDocument updated = TreePath.insertChild(tree, parentPath, pos == null ? -1 : pos, newItem);

        // Compute the new path: parentPath + the actual index it
        // landed at. Recompute the index from the updated children
        // count to keep this honest with insertChild's clamp logic.
        List<TreeItem> finalParent = parentPath.length == 0
                ? updated.items()
                : TreePath.at(updated, parentPath).children();
        int landedIdx = (pos == null || pos < 0 || pos >= finalParent.size())
                ? finalParent.size() - 1
                : pos;
        int[] newPath = new int[parentPath.length + 1];
        System.arraycopy(parentPath, 0, newPath, 0, parentPath.length);
        newPath[parentPath.length] = landedIdx;

        String body = "mindmap".equals(doc.getKind())
                ? MindmapCodec.serialize(updated, doc.getMimeType())
                : TreeCodec.serialize(updated, doc.getMimeType());
        support.writeBody(doc, body, ctx);

        return Map.of("documentId", doc.getId(),
                "newPath", TreePath.format(newPath),
                "parentPath", TreePath.format(parentPath));
    }
}
