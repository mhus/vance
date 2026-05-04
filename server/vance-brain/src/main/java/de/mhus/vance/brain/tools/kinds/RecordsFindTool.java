package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.kind.RecordsCodec;
import de.mhus.vance.shared.document.kind.RecordsDocument;
import de.mhus.vance.shared.document.kind.RecordsItem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RecordsFindTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("query"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("field", Map.of("type", "string",
                "description", "Restrict the search to one field. "
                        + "Omit to search all fields."));
        p.put("query", Map.of("type", "string",
                "description", "Substring to look for (case-insensitive)."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "records_find"; }
    @Override public String description() {
        return "Find rows whose values contain the query (case-insensitive). "
                + "Optional `field` restricts the search to one column.";
    }
    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("kind-records", "eddie"); }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.requireKind(
                support.requireInline(support.loadDocument(params, ctx)), "records");
        String query = KindToolSupport.requireString(params, "query").toLowerCase();
        String field = KindToolSupport.paramString(params, "field");
        RecordsDocument rec = RecordsCodec.parse(doc.getInlineText(), doc.getMimeType());
        if (field != null && !rec.schema().contains(field)) {
            throw new ToolException("Field '" + field + "' is not in the schema "
                    + rec.schema());
        }
        List<Map<String, Object>> matches = new ArrayList<>();
        for (int i = 0; i < rec.items().size(); i++) {
            RecordsItem row = rec.items().get(i);
            boolean hit = false;
            if (field != null) {
                hit = row.values().getOrDefault(field, "").toLowerCase().contains(query);
            } else {
                for (String f : rec.schema()) {
                    if (row.values().getOrDefault(f, "").toLowerCase().contains(query)) {
                        hit = true;
                        break;
                    }
                }
            }
            if (!hit) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rowIndex", i);
            m.put("values", row.values());
            matches.add(m);
        }
        return Map.of("documentId", doc.getId(),
                "matchCount", matches.size(),
                "matches", matches);
    }
}
