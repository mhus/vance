package de.mhus.vance.shared.document.kind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory model of a {@code kind: list} document — a flat,
 * ordered sequence of {@link ListItem}s.
 *
 * @param kind  always {@code "list"}; kept generic for symmetry with
 *              other kind documents (so a generic codec can hold any
 *              kind in the same field).
 * @param items the ordered item list — never {@code null}, may be
 *              empty.
 * @param extra unknown top-level fields. For markdown that is the
 *              residual front-matter map (keys other than
 *              {@code kind}); for JSON/YAML it's every top-level key
 *              other than {@code $meta} and {@code items}. Re-emitted
 *              verbatim on serialise.
 *
 * <p>Spec: {@code specification/doc-kind-items.md}.
 */
public record ListDocument(String kind, List<ListItem> items, Map<String, Object> extra) {

    public ListDocument {
        if (kind == null || kind.isBlank()) kind = "list";
        if (items == null) items = new ArrayList<>();
        if (extra == null) extra = new LinkedHashMap<>();
    }

    /** Empty document with the canonical kind set. */
    public static ListDocument empty() {
        return new ListDocument("list", new ArrayList<>(), new LinkedHashMap<>());
    }
}
