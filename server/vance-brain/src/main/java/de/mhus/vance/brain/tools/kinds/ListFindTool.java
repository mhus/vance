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

/** Find items whose text contains a query (case-insensitive). */
@Component
@RequiredArgsConstructor
public class ListFindTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("query"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("query", Map.of("type", "string",
                "description", "Substring to look for (case-insensitive)."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "list_find"; }

    @Override public String description() {
        return "Find items whose text contains the query (case-insensitive). "
                + "Returns matching indices with their text.";
    }

    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("kind-list", "eddie"); }


    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.requireKind(
                support.requireInline(support.loadDocument(params, ctx)), "list");
        String query = KindToolSupport.requireString(params, "query").toLowerCase();
        ListDocument list = ListCodec.parse(doc.getInlineText(), doc.getMimeType());
        List<Map<String, Object>> matches = new ArrayList<>();
        for (int i = 0; i < list.items().size(); i++) {
            ListItem item = list.items().get(i);
            if (item.text().toLowerCase().contains(query)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("index", i);
                m.put("text", item.text());
                matches.add(m);
            }
        }
        return Map.of("documentId", doc.getId(),
                "matchCount", matches.size(),
                "matches", matches);
    }
}
