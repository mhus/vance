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

/** Read all items of a {@code kind: list} document. */
@Component
@RequiredArgsConstructor
public class ListGetTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", KindToolSupport.documentSelectorProperties(),
            "required", List.of());

    private final KindToolSupport support;

    @Override public String name() { return "list_get"; }

    @Override public String description() {
        return "Read all items from a `kind: list` document. Identify the document by id or by (projectId, path).";
    }

    @Override public boolean primary() { return true; }
    @Override public Set<String> labels() { return Set.of("kind-list", "eddie"); }


    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.requireKind(
                support.requireInline(support.loadDocument(params, ctx)), "list");
        ListDocument list = ListCodec.parse(doc.getInlineText(), doc.getMimeType());
        List<Map<String, Object>> items = new ArrayList<>(list.items().size());
        for (int i = 0; i < list.items().size(); i++) {
            ListItem item = list.items().get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", i);
            m.put("text", item.text());
            if (!item.extra().isEmpty()) m.put("extra", item.extra());
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documentId", doc.getId());
        out.put("count", items.size());
        out.put("items", items);
        return out;
    }
}
