package de.mhus.vance.shared.document.kind;

import org.jspecify.annotations.Nullable;

/**
 * Per-column metadata for a {@code kind: sheet} document, keyed by
 * column letter in {@link SheetDocument#columns()}. Sparse — only
 * columns that deviate from the default carry an entry.
 *
 * @param width  display width in pixels ({@code null} = default/auto).
 * @param border vertical column border: {@code left | right | both}
 *               ({@code null} = none). Rendered as a CSS border on every
 *               cell of the column.
 */
public record SheetColumn(
        @Nullable Integer width,
        @Nullable String border) {

    public boolean isEmpty() {
        return width == null && (border == null || border.isBlank());
    }
}
