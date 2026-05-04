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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Read the whole tree of a {@code kind: tree} or {@code kind: mindmap} document. */
@Component
@RequiredArgsConstructor
public class TreeGetTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", KindToolSupport.documentSelectorProperties(),
            "required", List.of());

    private final KindToolSupport support;

    @Override public String name() { return "tree_get"; }

    @Override public String description() {
        return "Read the whole tree of a `kind: tree` (or `kind: mindmap`) document. Returns nested items "
                + "with their text and children as a tree-shaped JSON object.";
    }

    @Override public boolean primary() { return false; }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.requireKind(
                support.requireInline(support.loadDocument(params, ctx)), "tree", "mindmap");
        TreeDocument tree = "mindmap".equals(doc.getKind())
                ? MindmapCodec.parse(doc.getInlineText(), doc.getMimeType())
                : TreeCodec.parse(doc.getInlineText(), doc.getMimeType());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documentId", doc.getId());
        out.put("kind", tree.kind());
        out.put("items", itemsToList(tree.items()));
        return out;
    }

    static List<Map<String, Object>> itemsToList(List<TreeItem> items) {
        List<Map<String, Object>> out = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            TreeItem item = items.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", i);
            m.put("text", item.text());
            if (!item.children().isEmpty()) m.put("children", itemsToList(item.children()));
            if (!item.extra().isEmpty()) m.put("extra", item.extra());
            out.add(m);
        }
        return out;
    }
}
