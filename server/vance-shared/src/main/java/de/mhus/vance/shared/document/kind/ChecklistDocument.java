package de.mhus.vance.shared.document.kind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory model of a {@code kind: checklist} document — a flat,
 * ordered sequence of {@link ChecklistItem}s with per-item status and
 * optional priority.
 *
 * @param kind  always {@code "checklist"}; kept generic for symmetry
 *              with other kind documents.
 * @param items the ordered item list — never {@code null}, may be
 *              empty.
 * @param extra unknown top-level fields. Re-emitted verbatim on
 *              serialise.
 *
 * <p>Spec: {@code specification/doc-kind-checklist.md}.
 */
public record ChecklistDocument(String kind, List<ChecklistItem> items, Map<String, Object> extra) {

    public ChecklistDocument {
        if (kind == null || kind.isBlank()) kind = "checklist";
        if (items == null) items = new ArrayList<>();
        if (extra == null) extra = new LinkedHashMap<>();
    }

    /** Empty document with the canonical kind set. */
    public static ChecklistDocument empty() {
        return new ChecklistDocument("checklist", new ArrayList<>(), new LinkedHashMap<>());
    }
}
