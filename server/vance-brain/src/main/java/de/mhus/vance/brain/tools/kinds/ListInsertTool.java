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
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Insert a new item at a specific index in a {@code kind: list} document. */
@Component
@RequiredArgsConstructor
public class ListInsertTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("index", "text"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("index", Map.of("type", "integer",
                "description", "Insert position. 0 = prepend, items.size = append."));
        p.put("text", Map.of("type", "string", "description", "The new item's text."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "list_insert"; }

    @Override public String description() {
        return "Insert a new item at position `index` in a `kind: list` document. "
                + "Use 0 to prepend, items.size to append.";
    }

    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("kind-list", "eddie"); }


    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.requireKind(
                support.requireInline(support.loadDocument(params, ctx)), "list");
        int index = KindToolSupport.requireInt(params, "index");
        String text = KindToolSupport.requireRawString(params, "text");
        ListDocument list = ListCodec.parse(doc.getInlineText(), doc.getMimeType());
        List<ListItem> items = new ArrayList<>(list.items());
        if (index < 0 || index > items.size()) {
            throw new ToolException("index " + index + " out of range [0," + items.size() + "]");
        }
        items.add(index, ListItem.of(text));
        ListDocument updated = new ListDocument(list.kind(), items, list.extra());
        support.writeBody(doc, ListCodec.serialize(updated, doc.getMimeType()), ctx);
        return Map.of("documentId", doc.getId(), "insertedAt", index, "count", items.size());
    }
}
