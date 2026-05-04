package de.mhus.vance.shared.document.kind;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One cell of a {@code kind: sheet} document. {@code field} is the
 * Excel-standard A1 address (canonical uppercase), {@code data} the
 * cell content as a string. {@code data} starting with {@code "="}
 * is a formula — round-trip stable, but inert in v1 (no eval).
 *
 * <p>Spec: {@code specification/doc-kind-sheet.md} §2.1.
 */
public record SheetCell(
        String field,
        String data,
        @Nullable String color,
        @Nullable String background,
        Map<String, Object> extra) {

    public SheetCell {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(data, "data");
        if (extra == null) extra = new LinkedHashMap<>();
    }
}
