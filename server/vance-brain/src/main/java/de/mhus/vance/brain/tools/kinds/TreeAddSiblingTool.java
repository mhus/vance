package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.kind.MindmapCodec;
import de.mhus.vance.shared.document.kind.TreeCodec;
import de.mhus.vance.shared.document.kind.TreeDocument;
import de.mhus.vance.shared.document.kind.TreeItem;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Add a sibling immediately after the item at the given path. */
@Component
@RequiredArgsConstructor
public class TreeAddSiblingTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("path", "text"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("path", Map.of("type", "string",
                "description", "Comma-separated index path of the reference item. "
                        + "Sibling is inserted directly after it."));
        p.put("text", Map.of("type", "string", "description", "Text of the new sibling."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "tree_add_sibling"; }

    @Override public String description() {
        return "Add a sibling immediately after the item at `path`. Returns the new node's path.";
    }

    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("kind-tree", "eddie"); }


    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.requireKind(
                support.requireInline(support.loadDocument(params, ctx)), "tree", "mindmap");
        int[] refPath = TreePath.parse(KindToolSupport.requireString(params, "path"));
        if (refPath.length == 0) {
            throw new ToolException("path must point at an existing item, not the root");
        }
        String text = KindToolSupport.requireRawString(params, "text");

        TreeDocument tree = "mindmap".equals(doc.getKind())
                ? MindmapCodec.parse(doc.getInlineText(), doc.getMimeType())
                : TreeCodec.parse(doc.getInlineText(), doc.getMimeType());
        // Make sure the reference item exists.
        TreePath.at(tree, refPath);

        int[] parentPath = new int[refPath.length - 1];
        System.arraycopy(refPath, 0, parentPath, 0, parentPath.length);
        int insertAt = refPath[refPath.length - 1] + 1;

        TreeDocument updated = TreePath.insertChild(tree, parentPath, insertAt, TreeItem.leaf(text));
        int[] newPath = new int[refPath.length];
        System.arraycopy(refPath, 0, newPath, 0, refPath.length - 1);
        newPath[refPath.length - 1] = insertAt;

        String body = "mindmap".equals(doc.getKind())
                ? MindmapCodec.serialize(updated, doc.getMimeType())
                : TreeCodec.serialize(updated, doc.getMimeType());
        support.writeBody(doc, body, ctx);

        return Map.of("documentId", doc.getId(),
                "newPath", TreePath.format(newPath));
    }
}
