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
public class RecordsRemoveRowTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("rowIndex"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("rowIndex", Map.of("type", "integer", "description", "Row to remove (0-based)."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "records_remove_row"; }
    @Override public String description() {
        return "Remove the row at `rowIndex` from a `kind: records` document. "
                + "Subsequent row indices shift down by one.";
    }
    @Override public boolean primary() { return false; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.requireKind(
                support.requireInline(support.loadDocument(params, ctx)), "records");
        int rowIndex = KindToolSupport.requireInt(params, "rowIndex");
        RecordsDocument rec = RecordsCodec.parse(doc.getInlineText(), doc.getMimeType());
        if (rowIndex < 0 || rowIndex >= rec.items().size()) {
            throw new ToolException("rowIndex " + rowIndex + " out of range");
        }
        List<RecordsItem> items = new ArrayList<>(rec.items());
        RecordsItem removed = items.remove(rowIndex);
        RecordsDocument updated = new RecordsDocument(rec.kind(), rec.schema(), items, rec.extra());
        support.writeBody(doc, RecordsCodec.serialize(updated, doc.getMimeType()), ctx);
        return Map.of("documentId", doc.getId(),
                "removedRowIndex", rowIndex,
                "removedValues", removed.values(),
                "rowCount", items.size());
    }
}
