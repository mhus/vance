package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.kind.SheetCell;
import de.mhus.vance.shared.document.kind.SheetCodec;
import de.mhus.vance.shared.document.kind.SheetDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SheetFindTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("query"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("query", Map.of("type", "string",
                "description", "Substring to look for in cell data (case-insensitive)."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "sheet_find"; }
    @Override public String description() {
        return "Find cells whose data contains the query (case-insensitive). Returns matching addresses with their data.";
    }
    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("kind-sheet", "eddie"); }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.requireKind(
                support.requireInline(support.loadDocument(params, ctx)), "sheet");
        String query = KindToolSupport.requireString(params, "query").toLowerCase();
        SheetDocument sheet = SheetCodec.parse(doc.getInlineText(), doc.getMimeType());
        List<Map<String, Object>> matches = new ArrayList<>();
        for (SheetCell c : sheet.cells()) {
            if (!c.data().toLowerCase().contains(query)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("field", c.field());
            m.put("data", c.data());
            matches.add(m);
        }
        return Map.of("documentId", doc.getId(),
                "matchCount", matches.size(),
                "matches", matches);
    }
}
