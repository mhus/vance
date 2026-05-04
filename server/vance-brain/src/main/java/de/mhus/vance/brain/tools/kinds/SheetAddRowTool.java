package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.kind.SheetCodec;
import de.mhus.vance.shared.document.kind.SheetDocument;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SheetAddRowTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", KindToolSupport.documentSelectorProperties(),
            "required", List.of());

    private final KindToolSupport support;

    @Override public String name() { return "sheet_add_row"; }
    @Override public String description() {
        return "Append one row to the sheet's visible row count. Cells in the new row are empty until set via sheet_set_cell.";
    }
    @Override public boolean primary() { return false; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.requireKind(
                support.requireInline(support.loadDocument(params, ctx)), "sheet");
        SheetDocument sheet = SheetCodec.parse(doc.getInlineText(), doc.getMimeType());
        int currentRows = sheet.rows() == null
                ? Math.max(highestRow(sheet), 1)
                : sheet.rows();
        int newRows = currentRows + 1;
        SheetDocument updated = new SheetDocument(sheet.kind(), sheet.schema(),
                newRows, sheet.cells(), sheet.extra());
        support.writeBody(doc, SheetCodec.serialize(updated, doc.getMimeType()), ctx);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documentId", doc.getId());
        out.put("rows", newRows);
        return out;
    }

    private static int highestRow(SheetDocument sheet) {
        int max = 0;
        for (var c : sheet.cells()) {
            var addr = SheetCodec.parseAddress(c.field());
            if (addr != null && addr.row() > max) max = addr.row();
        }
        return max;
    }
}
