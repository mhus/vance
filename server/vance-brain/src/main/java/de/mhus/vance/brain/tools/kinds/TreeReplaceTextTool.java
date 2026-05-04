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

/** Replace the text of a tree item without touching its children. */
@Component
@RequiredArgsConstructor
public class TreeReplaceTextTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("path", "text"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("path", Map.of("type", "string",
                "description", "Comma-separated index path of the item whose text is replaced."));
        p.put("text", Map.of("type", "string", "description", "New text for the item."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "tree_replace_text"; }

    @Override public String description() {
        return "Replace the text of the item at `path` without touching its children or extras.";
    }

    @Override public boolean primary() { return false; }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.requireKind(
                support.requireInline(support.loadDocument(params, ctx)), "tree", "mindmap");
        int[] path = TreePath.parse(KindToolSupport.requireString(params, "path"));
        String newText = KindToolSupport.requireRawString(params, "text");

        TreeDocument tree = "mindmap".equals(doc.getKind())
                ? MindmapCodec.parse(doc.getInlineText(), doc.getMimeType())
                : TreeCodec.parse(doc.getInlineText(), doc.getMimeType());
        TreeItem original = TreePath.at(tree, path);
        TreeDocument updated = TreePath.replaceAt(tree, path,
                (item) -> new TreeItem(newText, item.children(), item.extra()));
        String body = "mindmap".equals(doc.getKind())
                ? MindmapCodec.serialize(updated, doc.getMimeType())
                : TreeCodec.serialize(updated, doc.getMimeType());
        support.writeBody(doc, body, ctx);
        return Map.of("documentId", doc.getId(),
                "path", TreePath.format(path),
                "previousText", original.text(),
                "newText", newText);
    }
}
