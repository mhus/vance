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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RecordsAddRowTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("values"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("values", Map.of("type", "object",
                "description", "Object keyed by schema field name → string value. "
                        + "Missing fields default to empty string."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "records_add_row"; }
    @Override public String description() {
        return "Append a new row to a `kind: records` document. Values is an object keyed by field name; "
                + "schema fields not present in `values` default to empty string.";
    }
    @Override public boolean primary() { return false; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.requireKind(
                support.requireInline(support.loadDocument(params, ctx)), "records");
        Map<String, Object> values = KindToolSupport.paramMap(params, "values");
        if (values == null) throw new ToolException("Missing required object parameter 'values'");
        RecordsDocument rec = RecordsCodec.parse(doc.getInlineText(), doc.getMimeType());
        Map<String, String> rowValues = new LinkedHashMap<>();
        for (String f : rec.schema()) {
            Object v = values.get(f);
            rowValues.put(f, v == null ? "" : String.valueOf(v));
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            if (!rec.schema().contains(e.getKey())) extra.put(e.getKey(), e.getValue());
        }
        List<RecordsItem> items = new ArrayList<>(rec.items());
        items.add(new RecordsItem(rowValues, extra, new ArrayList<>()));
        RecordsDocument updated = new RecordsDocument(rec.kind(), rec.schema(), items, rec.extra());
        support.writeBody(doc, RecordsCodec.serialize(updated, doc.getMimeType()), ctx);
        return Map.of("documentId", doc.getId(),
                "newIndex", items.size() - 1,
                "rowCount", items.size());
    }
}
