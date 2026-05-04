package de.mhus.vance.shared.document.kind;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A graph node. {@code id} is mandatory and must be unique inside
 * the document. {@code label}, {@code color}, {@code position} are
 * optional visual metadata; {@code extra} preserves unknown fields
 * across round-trip.
 *
 * <p>Spec: {@code specification/doc-kind-graph.md} §2.1.
 */
public record GraphNode(
        String id,
        @Nullable String label,
        @Nullable String color,
        @Nullable GraphPosition position,
        Map<String, Object> extra) {

    public GraphNode {
        Objects.requireNonNull(id, "id");
        if (extra == null) extra = new LinkedHashMap<>();
    }
}
