package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.sheet.SheetEvalService;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.kind.SheetCodec;
import de.mhus.vance.shared.document.kind.SheetComputed;
import de.mhus.vance.shared.document.kind.SheetDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Recalculate a {@code kind: sheet} document: evaluate every formula
 * server-side (Apache POI) and persist the {@code $computed} overlay.
 * The finance-style "Reload" for sheets — source ({@code cell.data})
 * stays untouched, the computed values are written back authoritatively.
 */
@Component
@RequiredArgsConstructor
public class SheetCalcTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", KindToolSupport.documentSelectorProperties(),
            "required", List.of());

    private final KindToolSupport support;
    private final SheetEvalService evalService;

    @Override public String name() { return "sheet_calc"; }

    @Override
    public String description() {
        return "Recalculate all formulas in a `kind: sheet` document (server-side, "
                + "Apache POI) and persist the computed values. Formula cells (data "
                + "starting with '=') get their evaluated result; source cells are "
                + "unchanged. Returns the computed values.";
    }

    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("kind-sheet", "eddie", "write", "document"); }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.requireKind(
                support.requireInline(support.loadDocument(params, ctx)), "sheet");
        SheetDocument sheet = SheetCodec.parse(support.readBody(doc, ctx), doc.getMimeType());
        SheetComputed computed = evalService.evaluate(sheet);
        support.writeBody(doc, SheetCodec.serialize(sheet, computed, doc.getMimeType()), ctx);

        List<Map<String, Object>> values = new ArrayList<>(computed.values().size());
        for (SheetComputed.Value v : computed.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("field", v.field());
            m.put("value", v.value());
            m.put("type", v.type());
            if (v.error() != null) m.put("error", v.error());
            values.add(m);
        }
        return Map.of("documentId", doc.getId(),
                "computedCount", computed.values().size(),
                "values", values);
    }
}
