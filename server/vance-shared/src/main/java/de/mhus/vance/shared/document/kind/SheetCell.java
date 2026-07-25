package de.mhus.vance.shared.document.kind;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One cell of a {@code kind: sheet} document. {@code field} is the
 * Excel-standard A1 address (canonical uppercase), {@code data} the
 * cell content as a string. {@code data} starting with {@code "="}
 * is a formula — round-trip stable, evaluated server-side into the
 * {@code $computed} overlay.
 *
 * <p>Optional per-cell formatting: {@code color}/{@code background}
 * (HTML hex), {@code bold}/{@code italic}, {@code align}
 * ({@code left|center|right}) and {@code numberFormat} (an Excel-style
 * format code, e.g. {@code "#,##0.00"}, {@code "0%"}, {@code "@"}). All
 * sparse — only non-default values are stored.
 *
 * <p>Spec: {@code specification/doc-kind-sheet.md} §2.1.
 */
public record SheetCell(
        String field,
        String data,
        @Nullable String color,
        @Nullable String background,
        @Nullable Boolean bold,
        @Nullable Boolean italic,
        @Nullable String align,
        @Nullable String numberFormat,
        Map<String, Object> extra) {

    public SheetCell {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(data, "data");
        if (extra == null) extra = new LinkedHashMap<>();
    }

    /** Backward-compatible constructor without the extended formatting fields. */
    public SheetCell(String field, String data, @Nullable String color,
                     @Nullable String background, Map<String, Object> extra) {
        this(field, data, color, background, null, null, null, null, extra);
    }
}
