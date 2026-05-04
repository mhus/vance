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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SheetGetCellTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("field"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("field", Map.of("type", "string",
                "description", "A1-style cell address, e.g. 'A1', 'B5', 'AB99'."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "sheet_get_cell"; }
    @Override public String description() {
        return "Read a single cell from a `kind: sheet` document by its A1 address. "
                + "Returns the cell's data (value or formula string), color and background if set.";
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
        SheetDocument sheet = SheetCodec.parse(doc.getInlineText(), doc.getMimeType());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documentId", doc.getId());
        out.put("field", key);
        SheetCell cell = sheet.cells().stream()
                .filter(c -> c.field().equals(key))
                .findFirst()
                .orElse(null);
        if (cell == null) {
            out.put("data", "");
            out.put("empty", true);
        } else {
            out.put("data", cell.data());
            if (cell.color() != null) out.put("color", cell.color());
            if (cell.background() != null) out.put("background", cell.background());
            out.put("isFormula", cell.data().startsWith("="));
        }
        return out;
    }
}
