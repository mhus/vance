package de.mhus.vance.shared.document.kind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * In-memory model of a {@code kind: sheet} document — sparse 2D
 * grid with A1 cell addresses.
 *
 * @param kind   always {@code "sheet"}.
 * @param schema ordered list of visible column letters
 *               ({@code ["A", "B", "C"]}). Optional — when empty the
 *               viewer derives columns from the cells.
 * @param rows   the explicit visible row count, or {@code null} to
 *               let the viewer derive it from the highest cell row.
 * @param cells  sparse list of cells with content or formatting.
 * @param extra  unknown top-level fields, passthrough.
 *
 * <p>Spec: {@code specification/doc-kind-sheet.md}.
 */
public record SheetDocument(
        String kind,
        List<String> schema,
        @Nullable Integer rows,
        List<SheetCell> cells,
        Map<String, Object> extra) {

    public SheetDocument {
        if (kind == null || kind.isBlank()) kind = "sheet";
        if (schema == null) schema = new ArrayList<>();
        if (cells == null) cells = new ArrayList<>();
        if (extra == null) extra = new LinkedHashMap<>();
    }

    public static SheetDocument empty() {
        return new SheetDocument("sheet", new ArrayList<>(), null, new ArrayList<>(), new LinkedHashMap<>());
    }
}
