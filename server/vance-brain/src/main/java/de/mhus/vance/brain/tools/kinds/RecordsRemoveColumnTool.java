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
public class RecordsRemoveColumnTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("field"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("field", Map.of("type", "string", "description", "Field/column to remove."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "records_remove_column"; }
    @Override public String description() {
        return "Remove a column from the schema. Each row's value for that field is dropped. "
                + "The schema must keep at least one column.";
    }
    @Override public boolean primary() { return false; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.requireKind(
                support.requireInline(support.loadDocument(params, ctx)), "records");
        String field = KindToolSupport.requireString(params, "field");

        RecordsDocument rec = RecordsCodec.parse(doc.getInlineText(), doc.getMimeType());
        if (!rec.schema().contains(field)) {
            throw new ToolException("Field '" + field + "' is not in the schema "
                    + rec.schema());
        }
        if (rec.schema().size() <= 1) {
            throw new ToolException("Cannot remove the last column — schema must be non-empty");
        }
        List<String> newSchema = new ArrayList<>(rec.schema());
        newSchema.remove(field);
        List<RecordsItem> items = new ArrayList<>(rec.items().size());
        for (RecordsItem it : rec.items()) {
            Map<String, String> values = new LinkedHashMap<>(it.values());
            values.remove(field);
            items.add(new RecordsItem(values, it.extra(), it.overflow()));
        }
        RecordsDocument updated = new RecordsDocument(rec.kind(), newSchema, items, rec.extra());
        support.writeBody(doc, RecordsCodec.serialize(updated, doc.getMimeType()), ctx);
        return Map.of("documentId", doc.getId(),
                "removedField", field,
                "schema", newSchema);
    }
}
