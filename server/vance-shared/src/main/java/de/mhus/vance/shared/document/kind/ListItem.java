package de.mhus.vance.shared.document.kind;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One item in a {@code kind: list} document. The text is mandatory
 * and may span multiple lines (continuation indent in markdown).
 *
 * <p>{@code extra} carries any per-item fields the codec doesn't
 * know — they round-trip losslessly through JSON and YAML, but get
 * dropped in markdown (which can only express the bullet text).
 *
 * <p>Spec: {@code specification/doc-kind-items.md} §2.
 */
public record ListItem(String text, Map<String, Object> extra) {

    public ListItem {
        Objects.requireNonNull(text, "text");
        if (extra == null) extra = new LinkedHashMap<>();
    }

    /** Convenience: an item with the given text and no extras. */
    public static ListItem of(String text) {
        return new ListItem(text, new LinkedHashMap<>());
    }
}
