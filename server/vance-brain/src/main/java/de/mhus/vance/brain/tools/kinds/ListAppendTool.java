package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.tools.Tool;
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

/** Append one item to the end of a {@code kind: list} document. */
@Component
@RequiredArgsConstructor
public class ListAppendTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("text"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("text", Map.of("type", "string", "description", "The new item's text."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "list_append"; }

    @Override public String description() {
        return "Append a new item with the given text to the end of a `kind: list` document.";
    }

    @Override public boolean primary() { return true; }
    @Override public Set<String> labels() { return Set.of("kind-list", "eddie"); }


    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.requireKind(
                support.requireInline(support.loadDocument(params, ctx)), "list");
        String text = KindToolSupport.requireRawString(params, "text");
        ListDocument list = ListCodec.parse(doc.getInlineText(), doc.getMimeType());
        List<ListItem> items = new ArrayList<>(list.items());
        items.add(ListItem.of(text));
        ListDocument updated = new ListDocument(list.kind(), items, list.extra());
        support.writeBody(doc, ListCodec.serialize(updated, doc.getMimeType()), ctx);
        return Map.of("documentId", doc.getId(), "newIndex", items.size() - 1, "count", items.size());
    }
}
