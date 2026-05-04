package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.tools.Tool;
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
public class RecordsGetRowsTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", KindToolSupport.documentSelectorProperties(),
            "required", List.of());

    private final KindToolSupport support;

    @Override public String name() { return "records_get_rows"; }
    @Override public String description() {
        return "Read all rows of a `kind: records` document. Returns the schema and an array of rows; each row is an object keyed by field name.";
    }
    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("kind-records", "eddie"); }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.requireKind(
                support.requireInline(support.loadDocument(params, ctx)), "records");
        RecordsDocument rec = RecordsCodec.parse(doc.getInlineText(), doc.getMimeType());
        List<Map<String, Object>> rows = new ArrayList<>(rec.items().size());
        for (int i = 0; i < rec.items().size(); i++) {
            RecordsItem item = rec.items().get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", i);
            for (String f : rec.schema()) row.put(f, item.values().getOrDefault(f, ""));
            if (!item.extra().isEmpty()) row.put("extra", item.extra());
            if (!item.overflow().isEmpty()) row.put("overflow", item.overflow());
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documentId", doc.getId());
        out.put("schema", rec.schema());
        out.put("rowCount", rows.size());
        out.put("rows", rows);
        return out;
    }
}
