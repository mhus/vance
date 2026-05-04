package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.kind.ListCodec;
import de.mhus.vance.shared.document.kind.ListDocument;
import de.mhus.vance.shared.document.kind.ListItem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Remove the item at the given index of a {@code kind: list} document. */
@Component
@RequiredArgsConstructor
public class ListRemoveTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("index"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("index", Map.of("type", "integer", "description", "Index of the item to remove (0-based)."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "list_remove"; }

    @Override public String description() {
        return "Remove the item at `index` from a `kind: list` document. "
                + "Returns the removed text and the new count.";
    }

    @Override public boolean primary() { return false; }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.requireKind(
                support.requireInline(support.loadDocument(params, ctx)), "list");
        int index = KindToolSupport.requireInt(params, "index");
        ListDocument list = ListCodec.parse(doc.getInlineText(), doc.getMimeType());
        List<ListItem> items = new ArrayList<>(list.items());
        if (index < 0 || index >= items.size()) {
            throw new ToolException("index " + index + " out of range [0," + (items.size() - 1) + "]");
        }
        ListItem removed = items.remove(index);
        ListDocument updated = new ListDocument(list.kind(), items, list.extra());
        support.writeBody(doc, ListCodec.serialize(updated, doc.getMimeType()), ctx);
        return Map.of("documentId", doc.getId(),
                "removedText", removed.text(),
                "count", items.size());
    }
}
