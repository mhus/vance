package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.sheet.SheetEvalService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.kind.SheetCell;
import de.mhus.vance.shared.document.kind.SheetCodec;
import de.mhus.vance.shared.document.kind.SheetComputed;
import de.mhus.vance.shared.document.kind.SheetDocument;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SheetGetRangeTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("range"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("range", Map.of("type", "string",
                "description", "Excel-style range like 'A1:C3'. Returns every cell in the rectangle "
                        + "(empty entries omitted)."));
        return p;
    }

    private final KindToolSupport support;
    private final SheetEvalService evalService;

    @Override public String name() { return "sheet_get_range"; }
    @Override public String description() {
        return "Read every populated cell in an Excel-style A1:C3 range from a `kind: sheet` "
                + "document. Formula cells also carry the server-evaluated `computedValue`.";
    }
    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("kind-sheet", "eddie", "read-only"); }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.requireKind(
                support.requireInline(support.loadDocument(params, ctx)), "sheet");
        String range = KindToolSupport.requireString(params, "range");
        int colon = range.indexOf(':');
        if (colon <= 0 || colon == range.length() - 1) {
            throw new ToolException("Range must be 'A1:C3' style; got '" + range + "'");
        }
        SheetCodec.Address topLeft = SheetCodec.parseAddress(range.substring(0, colon));
        SheetCodec.Address bottomRight = SheetCodec.parseAddress(range.substring(colon + 1));
        if (topLeft == null || bottomRight == null) {
            throw new ToolException("Invalid range endpoints in '" + range + "'");
        }
        int colMin = SheetCodec.columnIndexFromLetter(topLeft.column());
        int colMax = SheetCodec.columnIndexFromLetter(bottomRight.column());
        if (colMin > colMax) { int t = colMin; colMin = colMax; colMax = t; }
        int rowMin = Math.min(topLeft.row(), bottomRight.row());
        int rowMax = Math.max(topLeft.row(), bottomRight.row());

        SheetDocument sheet = SheetCodec.parse(support.readBody(doc, ctx), doc.getMimeType());
        Map<String, SheetCell> byField = new HashMap<>();
        for (SheetCell c : sheet.cells()) byField.put(c.field(), c);

        boolean anyFormula = sheet.cells().stream().anyMatch(c -> c.data().startsWith("="));
        Map<String, SheetComputed.Value> computed = new HashMap<>();
        if (anyFormula) {
            for (SheetComputed.Value v : evalService.evaluate(sheet).values()) {
                computed.put(v.field(), v);
            }
        }

        List<Map<String, Object>> hits = new ArrayList<>();
        for (int r = rowMin; r <= rowMax; r++) {
            for (int c = colMin; c <= colMax; c++) {
                String key = SheetCodec.columnLetterFromIndex(c) + r;
                SheetCell cell = byField.get(key);
                if (cell == null) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("field", key);
                m.put("data", cell.data());
                if (cell.color() != null) m.put("color", cell.color());
                if (cell.background() != null) m.put("background", cell.background());
                SheetComputed.Value v = computed.get(key);
                if (v != null) {
                    m.put("computedValue", v.value());
                    m.put("computedType", v.type());
                    if (v.error() != null) m.put("computedError", v.error());
                }
                hits.add(m);
            }
        }
        return Map.of("documentId", doc.getId(),
                "range", range,
                "cellCount", hits.size(),
                "cells", hits);
    }
}
