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
public class RecordsUpdateFieldTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("rowIndex", "field", "value"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("rowIndex", Map.of("type", "integer", "description", "0-based index of the target row."));
        p.put("field", Map.of("type", "string", "description", "Schema field name."));
        p.put("value", Map.of("type", "string", "description", "New value for the field."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "records_update_field"; }
    @Override public String description() {
        return "Update one cell in a `kind: records` document — set `field` of `rowIndex` to `value`.";
    }
    @Override public boolean primary() { return false; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.requireKind(
                support.requireInline(support.loadDocument(params, ctx)), "records");
        int rowIndex = KindToolSupport.requireInt(params, "rowIndex");
        String field = KindToolSupport.requireString(params, "field");
        String value = KindToolSupport.requireRawString(params, "value");

        RecordsDocument rec = RecordsCodec.parse(doc.getInlineText(), doc.getMimeType());
        if (rowIndex < 0 || rowIndex >= rec.items().size()) {
            throw new ToolException("rowIndex " + rowIndex + " out of range");
        }
        if (!rec.schema().contains(field)) {
            throw new ToolException("Field '" + field + "' is not in the schema "
                    + rec.schema());
        }
        List<RecordsItem> items = new ArrayList<>(rec.items());
        RecordsItem original = items.get(rowIndex);
        Map<String, String> newValues = new LinkedHashMap<>(original.values());
        String previous = newValues.getOrDefault(field, "");
        newValues.put(field, value);
        items.set(rowIndex, new RecordsItem(newValues, original.extra(), original.overflow()));
        RecordsDocument updated = new RecordsDocument(rec.kind(), rec.schema(), items, rec.extra());
        support.writeBody(doc, RecordsCodec.serialize(updated, doc.getMimeType()), ctx);
        return Map.of("documentId", doc.getId(),
                "rowIndex", rowIndex,
                "field", field,
                "previousValue", previous,
                "newValue", value);
    }
}
