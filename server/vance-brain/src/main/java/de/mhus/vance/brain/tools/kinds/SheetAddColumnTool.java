package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
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
public class SheetAddColumnTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", KindToolSupport.documentSelectorProperties(),
            "required", List.of());

    private final KindToolSupport support;

    @Override public String name() { return "sheet_add_column"; }
    @Override public String description() {
        return "Append the next column letter (A→B→…→Z→AA→…) to the sheet's schema. "
                + "Cells in the new column are empty until set.";
    }
    @Override public boolean primary() { return false; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.requireKind(
                support.requireInline(support.loadDocument(params, ctx)), "sheet");
        SheetDocument sheet = SheetCodec.parse(doc.getInlineText(), doc.getMimeType());
        List<String> schema = sheet.schema().isEmpty()
                ? deriveSchemaFromCells(sheet)
                : new ArrayList<>(sheet.schema());
        // Find smallest free column letter not already in schema.
        String letter = SheetCodec.columnLetterFromIndex(schema.size() + 1);
        while (schema.contains(letter)) {
            int next = SheetCodec.columnIndexFromLetter(letter) + 1;
            letter = SheetCodec.columnLetterFromIndex(next);
        }
        schema.add(letter);
        SheetDocument updated = new SheetDocument(sheet.kind(), schema,
                sheet.rows(), sheet.cells(), sheet.extra());
        support.writeBody(doc, SheetCodec.serialize(updated, doc.getMimeType()), ctx);
        return Map.of("documentId", doc.getId(),
                "addedColumn", letter,
                "schema", schema);
    }

    private static List<String> deriveSchemaFromCells(SheetDocument sheet) {
        int maxIdx = 0;
        for (var c : sheet.cells()) {
            var addr = SheetCodec.parseAddress(c.field());
            if (addr == null) continue;
            int idx = SheetCodec.columnIndexFromLetter(addr.column());
            if (idx > maxIdx) maxIdx = idx;
        }
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= Math.max(maxIdx, 1); i++) out.add(SheetCodec.columnLetterFromIndex(i));
        return out;
    }
}
