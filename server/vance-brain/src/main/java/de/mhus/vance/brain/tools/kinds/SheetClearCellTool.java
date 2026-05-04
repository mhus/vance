package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.kind.SheetCell;
import de.mhus.vance.shared.document.kind.SheetCodec;
import de.mhus.vance.shared.document.kind.SheetDocument;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SheetClearCellTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("field"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("field", Map.of("type", "string", "description", "A1-style cell address to clear."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "sheet_clear_cell"; }
    @Override public String description() {
        return "Remove a cell from a `kind: sheet` document — both data and any formatting. "
                + "No-op if the cell wasn't set.";
    }
    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("kind-sheet", "eddie"); }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.requireKind(
                support.requireInline(support.loadDocument(params, ctx)), "sheet");
        String field = KindToolSupport.requireString(params, "field");
        SheetCodec.Address addr = SheetCodec.parseAddress(field);
        if (addr == null) throw new ToolException("Invalid A1 address: " + field);
        String key = addr.column() + addr.row();

        SheetDocument sheet = SheetCodec.parse(doc.getInlineText(), doc.getMimeType());
        List<SheetCell> cells = sheet.cells().stream()
                .filter(c -> !c.field().equals(key))
                .toList();
        boolean changed = cells.size() != sheet.cells().size();
        if (changed) {
            SheetDocument updated = new SheetDocument(sheet.kind(), sheet.schema(),
                    sheet.rows(), new java.util.ArrayList<>(cells), sheet.extra());
            support.writeBody(doc, SheetCodec.serialize(updated, doc.getMimeType()), ctx);
        }
        return Map.of("documentId", doc.getId(),
                "field", key,
                "removed", changed);
    }
}
