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

/** Move a subtree from one parent to another. */
@Component
@RequiredArgsConstructor
public class TreeMoveTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("path", "newParentPath"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("path", Map.of("type", "string",
                "description", "Comma-separated index path of the item to move."));
        p.put("newParentPath", Map.of("type", "string",
                "description", "Comma-separated index path of the new parent (empty string = root)."));
        p.put("position", Map.of("type", "integer",
                "description", "Position among the new parent's children; -1 = append. Default: -1."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "tree_move"; }

    @Override public String description() {
        return "Move the item at `path` (with its entire subtree) to become a child of "
                + "`newParentPath` at the given `position` (or appended). Cannot move an item "
                + "into its own subtree.";
    }

    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("kind-tree", "eddie"); }


    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.requireKind(
                support.requireInline(support.loadDocument(params, ctx)), "tree", "mindmap");
        int[] path = TreePath.parse(KindToolSupport.requireString(params, "path"));
        int[] newParent = TreePath.parse(KindToolSupport.paramString(params, "newParentPath"));
        Integer pos = KindToolSupport.paramInt(params, "position");

        if (path.length == 0) {
            throw new ToolException("path must point at an existing item, not the root");
        }
        // Self-into-subtree guard.
        if (isPrefixOrEqual(path, newParent)) {
            throw new ToolException("Cannot move an item into its own subtree");
        }

        TreeDocument tree = "mindmap".equals(doc.getKind())
                ? MindmapCodec.parse(doc.getInlineText(), doc.getMimeType())
                : TreeCodec.parse(doc.getInlineText(), doc.getMimeType());
        TreeItem moving = TreePath.at(tree, path);
        TreeDocument removed = TreePath.removeAt(tree, path);
        // Adjust newParent: when an ancestor or earlier sibling of
        // newParent was just removed, the original indices are off.
        int[] adjustedParent = adjustPathAfterRemoval(newParent, path);
        TreeDocument moved = TreePath.insertChild(removed, adjustedParent,
                pos == null ? -1 : pos, moving);

        String body = "mindmap".equals(doc.getKind())
                ? MindmapCodec.serialize(moved, doc.getMimeType())
                : TreeCodec.serialize(moved, doc.getMimeType());
        support.writeBody(doc, body, ctx);
        return Map.of("documentId", doc.getId(),
                "movedFrom", TreePath.format(path),
                "movedToParent", TreePath.format(adjustedParent));
    }

    private static boolean isPrefixOrEqual(int[] prefix, int[] candidate) {
        if (prefix.length > candidate.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (prefix[i] != candidate[i]) return false;
        }
        return true;
    }

    /** When item at {@code removed} was deleted, indices in any
     *  later-or-shared-ancestor path shift down by one at the
     *  removed depth, only if the path ran through the same parent
     *  past the removed sibling. */
    private static int[] adjustPathAfterRemoval(int[] target, int[] removed) {
        if (removed.length == 0 || target.length < removed.length) return target;
        // Same parent prefix?
        for (int i = 0; i < removed.length - 1; i++) {
            if (target[i] != removed[i]) return target;
        }
        int sharedDepth = removed.length - 1;
        if (target.length <= sharedDepth) return target;
        if (target[sharedDepth] > removed[removed.length - 1]) {
            int[] adjusted = target.clone();
            adjusted[sharedDepth] -= 1;
            return adjusted;
        }
        return target;
    }
}
