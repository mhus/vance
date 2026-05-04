package de.mhus.vance.shared.document.kind;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A graph edge. {@code source} and {@code target} reference node
 * ids. {@code id} is optional — when missing the renderer
 * synthesises {@code <source>-><target>} for selection / lookup; the
 * synthetic id never gets written back to disk.
 *
 * <p>Spec: {@code specification/doc-kind-graph.md} §2.2.
 */
public record GraphEdge(
        @Nullable String id,
        String source,
        String target,
        @Nullable String label,
        @Nullable String color,
        Map<String, Object> extra) {

    public GraphEdge {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        if (extra == null) extra = new LinkedHashMap<>();
    }

    /** UI-side stable key (synthetic when {@code id} is null). */
    public String key() {
        return (id != null && !id.isEmpty()) ? id : source + "->" + target;
    }
}
