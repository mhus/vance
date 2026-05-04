package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.kind.SheetCell;
import de.mhus.vance.shared.document.kind.SheetCodec;
import de.mhus.vance.shared.document.kind.SheetDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SheetSetCellTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("field", "data"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("field", Map.of("type", "string", "description", "A1-style cell address."));
        p.put("data", Map.of("type", "string",
                "description", "Cell content. Lead with '=' for a formula (stored verbatim, not evaluated in v1)."));
        p.put("color", Map.of("type", "string",
                "description", "Optional HTML hex color for the cell text. Empty string clears."));
        p.put("background", Map.of("type", "string",
                "description", "Optional HTML hex color for the cell background. Empty string clears."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "sheet_set_cell"; }
    @Override public String description() {
        return "Set a cell's content (and optional color / background) in a `kind: sheet` document. "
                + "Replaces the cell if it already exists; creates it otherwise.";
    }
    @Override public boolean primary() { return false; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.requireKind(
                support.requireInline(support.loadDocument(params, ctx)), "sheet");
        String field = KindToolSupport.requireString(params, "field");
        SheetCodec.Address addr = SheetCodec.parseAddress(field);
        if (addr == null) throw new ToolException("Invalid A1 address: " + field);
        String key = addr.column() + addr.row();
        String data = KindToolSupport.requireRawString(params, "data");
        String color = KindToolSupport.paramRawString(params, "color");
        String bg = KindToolSupport.paramRawString(params, "background");

        SheetDocument sheet = SheetCodec.parse(doc.getInlineText(), doc.getMimeType());
        List<SheetCell> cells = new ArrayList<>(sheet.cells().size() + 1);
        boolean replaced = false;
        SheetCell newCell = new SheetCell(key, data,
                (color != null && !color.isEmpty()) ? color : null,
                (bg != null && !bg.isEmpty()) ? bg : null,
                new LinkedHashMap<>());
        for (SheetCell c : sheet.cells()) {
            if (c.field().equals(key)) {
                cells.add(newCell);
                replaced = true;
            } else {
                cells.add(c);
            }
        }
        if (!replaced) cells.add(newCell);
        SheetDocument updated = new SheetDocument(sheet.kind(), sheet.schema(),
                sheet.rows(), cells, sheet.extra());
        support.writeBody(doc, SheetCodec.serialize(updated, doc.getMimeType()), ctx);
        return Map.of("documentId", doc.getId(),
                "field", key,
                "replaced", replaced,
                "cellCount", cells.size());
    }
}
